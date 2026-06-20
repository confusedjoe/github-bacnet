/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.bacnet.internal.discovery;

import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bacnet.internal.BACnetBindingConstants;
import org.openhab.binding.bacnet.internal.handler.BACnetBridgeHandler;
import org.openhab.binding.bacnet.internal.protocol.BACnetIpClient;
import org.openhab.binding.bacnet.internal.protocol.BACnetServices;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Universal BACnet device discovery. Combines several mechanisms so that even
 * devices that never answer a broadcast Who-Is are found:
 *
 * <ol>
 *   <li><b>Who-Is / I-Am</b> — the standard broadcast discovery.</li>
 *   <li><b>Passive</b> — every IP that has sent the bridge any BACnet frame
 *       (e.g. a controller that broadcasts its own Who-Is) is probed.</li>
 *   <li><b>Active sweep</b> — every host in the bridge's subnet is probed with a
 *       unicast {@code ReadProperty(device:4194303, object-identifier)}, the
 *       wildcard-instance trick that silent devices still answer.</li>
 *   <li><b>Event-driven</b> — the moment a previously-unseen IP sends any BACnet
 *       frame, it is probed immediately (see {@link #onNewSource}).</li>
 * </ol>
 *
 * Runs as <b>background discovery</b>: an initial scan starts shortly after the
 * bridge comes online and repeats on a configurable interval, so devices land in
 * the inbox without any manual scan. Probed devices are reported with their real
 * device instance and IP, so the Thing works over unicast without Who-Is.
 *
 * @author Edin - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = BACnetDiscoveryService.class)
@NonNullByDefault
public class BACnetDiscoveryService extends AbstractThingHandlerDiscoveryService<BACnetBridgeHandler> {

    private static final int SWEEP_THREADS = 32;
    private static final int PROBE_TIMEOUT_MS = 1200;
    private static final long INITIAL_DELAY_S = 15; // let the bridge finish coming online

    private final Logger logger = LoggerFactory.getLogger(BACnetDiscoveryService.class);
    private final Set<Integer> discoveredInstances = ConcurrentHashMap.newKeySet();
    private final Consumer<String> newSourceListener = this::onNewSource;

    private @Nullable ScheduledFuture<?> backgroundJob;
    private volatile boolean listenerRegistered = false;

    public BACnetDiscoveryService() {
        // last arg = background discovery enabled by default
        super(BACnetBridgeHandler.class, Set.of(BACnetBindingConstants.THING_TYPE_DEVICE), 90, true);
    }

    // ---------- lifecycle ----------
    // AbstractThingHandlerDiscoveryService does NOT auto-invoke startBackgroundDiscovery()
    // on activation, so we hook it into initialize() (called once the bridge handler is set)
    // and tear it down in dispose().

    @Override
    public void initialize() {
        super.initialize();
        startBackgroundDiscovery();
    }

    @Override
    public void dispose() {
        stopBackgroundDiscovery();
        super.dispose();
    }

    // ---------- background discovery ----------

    @Override
    protected void startBackgroundDiscovery() {
        // NOTE: this is invoked by both activate() and initialize() of the base
        // class. activate() may run before the thing handler is injected, so guard
        // against a null handler — otherwise the NPE would abort service activation
        // and silently kill all discovery.
        try {
            BACnetBridgeHandler handler = thingHandler;
            if (!handler.isBackgroundDiscovery()) {
                return;
            }
            stopBackgroundDiscovery();
            int minutes = Math.max(1, handler.getDiscoveryInterval());
            backgroundJob = scheduler.scheduleWithFixedDelay(this::backgroundScan, INITIAL_DELAY_S, minutes * 60L,
                    TimeUnit.SECONDS);
            logger.info("BACnet background discovery active (first run in {}s, then every {} min)", INITIAL_DELAY_S,
                    minutes);
        } catch (RuntimeException e) {
            // handler not ready yet — initialize() will call us again once it is
            logger.debug("startBackgroundDiscovery deferred: {}", e.toString());
        }
    }

    @Override
    protected void stopBackgroundDiscovery() {
        ScheduledFuture<?> job = backgroundJob;
        if (job != null) {
            job.cancel(true);
            backgroundJob = null;
        }
        if (listenerRegistered) {
            try {
                BACnetIpClient client = thingHandler.getClient();
                if (client != null) {
                    client.removeNewSourceListener(newSourceListener);
                }
            } catch (RuntimeException ignored) {
                // handler already gone
            }
            listenerRegistered = false;
        }
    }

    /** Periodic background task: ensure the new-source listener is wired, then scan. */
    private void backgroundScan() {
        // Must not throw: scheduleWithFixedDelay silently cancels all future runs
        // if a task throws, so swallow + log instead.
        try {
            BACnetIpClient client = thingHandler.getClient();
            if (client == null) {
                logger.debug("Background scan skipped — bridge not ready yet");
                return; // bridge not ready yet — try again next interval
            }
            if (!listenerRegistered) {
                client.addNewSourceListener(newSourceListener);
                listenerRegistered = true;
            }
            startScan();
        } catch (RuntimeException e) {
            logger.warn("BACnet background scan failed: {}", e.toString());
        }
    }

    /** A previously-unseen IP just sent us a frame — probe it right away. */
    private void onNewSource(String ip) {
        BACnetBridgeHandler handler = thingHandler;
        BACnetIpClient client = handler.getClient();
        if (client == null) {
            return;
        }
        BACnetServices svc = client.getServices();
        if (svc == null) {
            return;
        }
        scheduler.execute(() -> probe(handler, svc, ip));
    }

    // ---------- full scan (manual + periodic) ----------

    @Override
    protected void startScan() {
        BACnetBridgeHandler handler = thingHandler;
        BACnetIpClient client = handler.getClient();
        if (client == null) {
            logger.warn("Cannot scan: BACnet client not available (bridge offline?)");
            return;
        }
        BACnetServices svc = client.getServices();
        if (svc == null) {
            logger.warn("Cannot scan: BACnet services not available");
            return;
        }
        discoveredInstances.clear();
        Set<String> probedAddresses = ConcurrentHashMap.newKeySet();

        // 1) Standard Who-Is / I-Am.
        try {
            client.sendWhoIs();
            client.receiveIAm(handler.getDiscoveryTimeout(), dev -> {
                probedAddresses.add(dev.address);
                emit(handler, dev.instance, dev.address, null);
            });
        } catch (IOException e) {
            logger.warn("Who-Is broadcast failed: {}", e.getMessage());
        }

        // 2) + 3) Candidate IPs: everything we have heard from, plus the subnet sweep.
        Set<String> candidates = new LinkedHashSet<>();
        candidates.addAll(client.getSeenSources());
        candidates.addAll(sweepHosts(handler.getBroadcastAddress()));
        candidates.removeAll(probedAddresses);

        if (candidates.isEmpty()) {
            return;
        }
        logger.info("BACnet scan: probing {} candidate address(es) via unicast wildcard ReadProperty",
                candidates.size());

        ExecutorService pool = Executors.newFixedThreadPool(SWEEP_THREADS);
        try {
            for (String ip : candidates) {
                pool.submit(() -> probe(handler, svc, ip));
            }
            pool.shutdown();
            pool.awaitTermination(80, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    /** Probe one IP for a BACnet device via the wildcard-instance ReadProperty. */
    private void probe(BACnetBridgeHandler handler, BACnetServices svc, String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            int[] oid = svc.readDeviceObjectId(addr, PROBE_TIMEOUT_MS);
            if (oid == null || oid[0] != 8) {
                return;
            }
            int instance = oid[1];
            String name = svc.readObjectName(addr, 8, instance, PROBE_TIMEOUT_MS);
            emit(handler, instance, ip, name);
        } catch (Exception e) {
            logger.trace("Probe of {} failed: {}", ip, e.getMessage());
        }
    }

    /** Report a discovered device, de-duplicated by device instance. */
    private synchronized void emit(BACnetBridgeHandler handler, int instance, String address, @Nullable String name) {
        if (!discoveredInstances.add(instance)) {
            return; // already reported in this cycle
        }
        ThingUID bridgeUID = handler.getThing().getUID();
        ThingUID thingUID = new ThingUID(BACnetBindingConstants.THING_TYPE_DEVICE, bridgeUID,
                String.valueOf(instance));
        String label = (name != null && !name.isBlank()) ? name + " (BACnet " + instance + ")"
                : "BACnet Device " + instance + " (" + address + ")";
        thingDiscovered(DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID).withLabel(label)
                .withProperty(BACnetBindingConstants.CONFIG_DEVICE_INSTANCE, instance)
                .withProperty(BACnetBindingConstants.PROPERTY_ADDRESS, address)
                .withRepresentationProperty(BACnetBindingConstants.CONFIG_DEVICE_INSTANCE).build());
        logger.info("Discovered BACnet device {} ({}) at {}", instance, name != null ? name : "?", address);
    }

    /**
     * Expand a subnet broadcast address into the list of host addresses to sweep.
     * Only the common /24 case (x.y.z.255) is expanded into x.y.z.1 .. x.y.z.254;
     * other masks fall back to passive discovery only.
     */
    private Set<String> sweepHosts(@Nullable String broadcast) {
        Set<String> hosts = new LinkedHashSet<>();
        if (broadcast == null) {
            return hosts;
        }
        String[] p = broadcast.trim().split("\\.");
        if (p.length == 4 && "255".equals(p[3])) {
            String base = p[0] + "." + p[1] + "." + p[2] + ".";
            for (int i = 1; i <= 254; i++) {
                hosts.add(base + i);
            }
        }
        return hosts;
    }
}
