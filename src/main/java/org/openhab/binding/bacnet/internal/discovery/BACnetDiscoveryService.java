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
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Universal BACnet device discovery, registered per bridge by
 * {@link org.openhab.binding.bacnet.internal.BACnetHandlerFactory}. Combines
 * several mechanisms so that even devices that never answer a broadcast Who-Is
 * are found:
 *
 * <ol>
 *   <li><b>Who-Is / I-Am</b> — the standard broadcast discovery.</li>
 *   <li><b>Passive</b> — every IP that has sent the bridge any BACnet frame is probed.</li>
 *   <li><b>Active sweep</b> — every host in the bridge's /24 subnet is probed with a
 *       unicast {@code ReadProperty(device:4194303, object-identifier)}.</li>
 *   <li><b>Event-driven</b> — a previously-unseen IP is probed the moment it sends a frame.</li>
 * </ol>
 *
 * Runs as background discovery: an initial scan starts shortly after the bridge
 * is up and repeats on a configurable interval.
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public class BACnetDiscoveryService extends AbstractDiscoveryService {

    private static final int SWEEP_THREADS = 32;
    private static final int PROBE_TIMEOUT_MS = 1200;
    private static final long INITIAL_DELAY_S = 15; // let the bridge finish coming online

    private final Logger logger = LoggerFactory.getLogger(BACnetDiscoveryService.class);
    private final BACnetBridgeHandler handler;
    private final Set<Integer> discoveredInstances = ConcurrentHashMap.newKeySet();
    private final Consumer<String> newSourceListener = this::onNewSource;

    private @Nullable ScheduledFuture<?> backgroundJob;
    private volatile boolean listenerRegistered = false;

    public BACnetDiscoveryService(BACnetBridgeHandler handler) {
        super(Set.of(BACnetBindingConstants.THING_TYPE_DEVICE), 90, true);
        this.handler = handler;
    }

    /** Called by the handler factory right after the bridge handler is created. */
    public void start() {
        startBackgroundDiscovery();
    }

    /** Called by the handler factory when the bridge handler is removed. */
    public void stop() {
        stopBackgroundDiscovery();
    }

    // ---------- background discovery ----------

    @Override
    protected void startBackgroundDiscovery() {
        if (!handler.isBackgroundDiscovery()) {
            return;
        }
        cancelJob();
        int minutes = Math.max(1, handler.getDiscoveryInterval());
        backgroundJob = scheduler.scheduleWithFixedDelay(this::backgroundScan, INITIAL_DELAY_S, minutes * 60L,
                TimeUnit.SECONDS);
        logger.info("BACnet background discovery active (first run in {}s, then every {} min)", INITIAL_DELAY_S,
                minutes);
    }

    @Override
    protected void stopBackgroundDiscovery() {
        cancelJob();
        if (listenerRegistered) {
            BACnetIpClient client = handler.getClient();
            if (client != null) {
                client.removeNewSourceListener(newSourceListener);
            }
            listenerRegistered = false;
        }
    }

    private void cancelJob() {
        ScheduledFuture<?> job = backgroundJob;
        if (job != null) {
            job.cancel(true);
            backgroundJob = null;
        }
    }

    /** Periodic task: wire the new-source listener (once the bridge is up), then scan. */
    private void backgroundScan() {
        try {
            BACnetIpClient client = handler.getClient();
            if (client == null) {
                logger.debug("Background scan skipped — bridge not ready yet");
                return;
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
        BACnetIpClient client = handler.getClient();
        if (client == null) {
            return;
        }
        BACnetServices svc = client.getServices();
        if (svc == null) {
            return;
        }
        scheduler.execute(() -> probe(svc, ip));
    }

    // ---------- full scan (manual + periodic) ----------

    @Override
    protected void startScan() {
        BACnetIpClient client = handler.getClient();
        if (client == null) {
            logger.warn("Cannot scan: BACnet bridge offline");
            return;
        }
        BACnetServices svc = client.getServices();
        if (svc == null) {
            logger.warn("Cannot scan: BACnet services unavailable");
            return;
        }
        discoveredInstances.clear();
        Set<String> probedAddresses = ConcurrentHashMap.newKeySet();

        try {
            client.sendWhoIs();
            client.receiveIAm(handler.getDiscoveryTimeout(), dev -> {
                probedAddresses.add(dev.address);
                emit(dev.instance, dev.address, null);
            });
        } catch (IOException e) {
            logger.warn("Who-Is broadcast failed: {}", e.getMessage());
        }

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
                pool.submit(() -> probe(svc, ip));
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
    private void probe(BACnetServices svc, String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            int[] oid = svc.readDeviceObjectId(addr, PROBE_TIMEOUT_MS);
            if (oid == null || oid[0] != 8) {
                return;
            }
            int instance = oid[1];
            String name = svc.readObjectName(addr, 8, instance, PROBE_TIMEOUT_MS);
            emit(instance, ip, name);
        } catch (Exception e) {
            logger.trace("Probe of {} failed: {}", ip, e.getMessage());
        }
    }

    /** Report a discovered device, de-duplicated by device instance. */
    private synchronized void emit(int instance, String address, @Nullable String name) {
        if (!discoveredInstances.add(instance)) {
            return;
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
     * Only the common /24 case (x.y.z.255) is expanded into x.y.z.1 .. x.y.z.254.
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
