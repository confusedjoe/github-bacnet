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
package org.openhab.binding.bacnet.internal.protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal BACnet/IP (Annex J) client.
 *
 * Implements only what the binding needs:
 *  - sends an unconfirmed Who-Is as a global broadcast and collects I-Am replies
 *  - sends confirmed services (ReadProperty/WriteProperty/SubscribeCOV) and
 *    awaits their ACK
 *  - listens for pushed COV / event notifications
 *
 * <h2>Single-reader design</h2>
 * Exactly one thread ({@link #dispatchLoop()}) ever calls {@code socket.receive()}.
 * It classifies every inbound frame and routes it:
 * <ul>
 *   <li>COV / event notifications -&gt; registered listeners</li>
 *   <li>I-Am replies             -&gt; {@link #iamQueue} (drained by {@link #receiveIAm})</li>
 *   <li>confirmed-service replies -&gt; the per-invoke-id queue the waiting caller
 *       registered in {@link #pending}</li>
 * </ul>
 * Previously discovery and {@link BACnetServices} each called {@code receive()}
 * on the same socket concurrently with this loop, so the loop stole (and
 * discarded) their I-Am and ACK packets. Routing through a single reader fixes
 * that race for good.
 *
 * This is a clean-room, dependency-free implementation so the binding stays
 * EPL-compatible (no bacnet4j / GPL code).
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public class BACnetIpClient {

    private static final int BACNET_PORT = 0xBAC0; // 47808
    // BVLC
    private static final byte BVLC_TYPE = (byte) 0x81;
    private static final byte BVLC_ORIGINAL_UNICAST_NPDU = 0x0A;
    private static final byte BVLC_ORIGINAL_BROADCAST_NPDU = 0x0B;
    // NPDU
    private static final byte NPDU_VERSION = 0x01;
    // APDU
    private static final byte APDU_UNCONFIRMED_REQUEST = 0x10;
    private static final byte SERVICE_WHO_IS = 0x08;
    private static final byte SERVICE_I_AM = 0x00;
    // Reply PDU types (high nibble)
    private static final int PDU_SIMPLE_ACK = 0x20;
    private static final int PDU_COMPLEX_ACK = 0x30;
    private static final int PDU_ERROR = 0x50;
    private static final int PDU_REJECT = 0x60;
    private static final int PDU_ABORT = 0x70;

    private final Logger logger = LoggerFactory.getLogger(BACnetIpClient.class);

    private final InetAddress broadcastAddress;
    private final int localPort;
    private @Nullable DatagramSocket socket;
    private @Nullable BACnetServices services;

    // Notification listeners (COV / events) — the "live socket" fan-out.
    private final List<Consumer<BACnetCovNotification.Notification>> covListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<BACnetEventNotification.Event>> eventListeners = new CopyOnWriteArrayList<>();
    // Fired once for each previously-unseen source IP (for event-driven discovery).
    private final List<Consumer<String>> newSourceListeners = new CopyOnWriteArrayList<>();

    // Single-reader routing: replies waiting to be picked up by their callers.
    private final ConcurrentHashMap<Integer, BlockingQueue<byte[]>> pending = new ConcurrentHashMap<>();
    // I-Am frames waiting to be drained by a discovery scan (bounded; oldest dropped).
    private final BlockingQueue<IamFrame> iamQueue = new LinkedBlockingQueue<>(512);
    // Every source IP we have received any BACnet frame from — lets discovery
    // probe "silent" devices that broadcast (e.g. Who-Is) but never answer Who-Is.
    private final Set<String> seenSources = ConcurrentHashMap.newKeySet();

    private volatile boolean dispatching = false;
    private @Nullable Thread dispatchThread;

    public BACnetIpClient(InetAddress broadcastAddress, int localPort) {
        this.broadcastAddress = broadcastAddress;
        this.localPort = localPort;
    }

    /** Access the confirmed-service helper (ReadProperty/WriteProperty/SubscribeCOV). */
    public @Nullable BACnetServices getServices() {
        return services;
    }

    /** Snapshot of every source IP seen on the socket so far (for discovery). */
    public Set<String> getSeenSources() {
        return new HashSet<>(seenSources);
    }

    public void addCovListener(Consumer<BACnetCovNotification.Notification> l) {
        covListeners.add(l);
    }

    public void addEventListener(Consumer<BACnetEventNotification.Event> l) {
        eventListeners.add(l);
    }

    public void removeCovListener(Consumer<BACnetCovNotification.Notification> l) {
        covListeners.remove(l);
    }

    public void removeEventListener(Consumer<BACnetEventNotification.Event> l) {
        eventListeners.remove(l);
    }

    public void addNewSourceListener(Consumer<String> l) {
        newSourceListeners.add(l);
    }

    public void removeNewSourceListener(Consumer<String> l) {
        newSourceListeners.remove(l);
    }

    public void open() throws IOException {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setBroadcast(true);
        s.bind(new InetSocketAddress(localPort));
        this.socket = s;
        this.services = new BACnetServices(this);
        logger.debug("BACnet/IP socket bound to port {}", localPort);
        // The single reader must run for any reply (I-Am, ACK) to be delivered.
        startDispatch();
    }

    public void close() {
        stopDispatch();
        DatagramSocket s = socket;
        if (s != null) {
            s.close();
            socket = null;
        }
        services = null;
        pending.clear();
        iamQueue.clear();
    }

    /**
     * Start the single background reader. Idempotent.
     */
    public synchronized void startDispatch() {
        if (dispatching) {
            return;
        }
        DatagramSocket s = socket;
        if (s == null) {
            return;
        }
        dispatching = true;
        Thread t = new Thread(this::dispatchLoop, "bacnet-socket-reader");
        t.setDaemon(true);
        dispatchThread = t;
        t.start();
    }

    public synchronized void stopDispatch() {
        dispatching = false;
        Thread t = dispatchThread;
        if (t != null) {
            t.interrupt();
            dispatchThread = null;
        }
    }

    /**
     * The one and only socket reader. Receives every frame, classifies it and
     * routes it to notification listeners, the discovery queue, or the waiting
     * confirmed-request caller.
     */
    private void dispatchLoop() {
        byte[] buf = new byte[1500];
        while (dispatching) {
            DatagramSocket s = socket;
            if (s == null) {
                break;
            }
            try {
                s.setSoTimeout(500);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                InetAddress src = p.getAddress();
                if (src == null) {
                    continue; // no source address — nothing we can route or attribute
                }
                if (seenSources.add(src.getHostAddress())) {
                    // first time we hear from this IP — notify event-driven discovery
                    for (Consumer<String> l : newSourceListeners) {
                        l.accept(src.getHostAddress());
                    }
                }
                byte[] apdu = unwrap(p.getData(), p.getLength());
                if (apdu == null || apdu.length < 2) {
                    continue;
                }
                // 1. COV notification (confirmed or unconfirmed)?
                BACnetCovNotification.Notification cov = BACnetCovNotification.parse(apdu);
                if (cov != null) {
                    for (Consumer<BACnetCovNotification.Notification> l : covListeners) {
                        l.accept(cov);
                    }
                    continue;
                }
                // 2. Event / alarm notification?
                BACnetEventNotification.Event ev = BACnetEventNotification.parse(apdu);
                if (ev != null) {
                    for (Consumer<BACnetEventNotification.Event> l : eventListeners) {
                        l.accept(ev);
                    }
                    continue;
                }
                // 3. Classify by PDU type.
                int high = apdu[0] & 0xF0;
                if (high == (APDU_UNCONFIRMED_REQUEST & 0xFF)) {
                    if ((apdu[1] & 0xFF) == (SERVICE_I_AM & 0xFF)) {
                        iamQueue.offer(new IamFrame(src, apdu));
                    }
                } else if (high == PDU_SIMPLE_ACK || high == PDU_COMPLEX_ACK || high == PDU_ERROR
                        || high == PDU_REJECT || high == PDU_ABORT) {
                    // Reply PDUs carry the invoke id at offset 1.
                    int invoke = apdu[1] & 0xFF;
                    BlockingQueue<byte[]> q = pending.get(invoke);
                    if (q != null) {
                        q.offer(apdu);
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                // normal — loop again
            } catch (IOException e) {
                if (dispatching) {
                    logger.debug("socket reader error: {}", e.getMessage());
                }
            }
        }
    }

    /** Strip BVLC + NPDU from a received frame, returning the APDU. */
    private byte @Nullable [] unwrap(byte[] data, int length) {
        if (length < 6 || (data[0] & 0xFF) != 0x81) {
            return null;
        }
        int idx = 4;
        idx++; // npdu version
        int control = data[idx++] & 0xFF;
        if ((control & 0x20) != 0) {
            idx += 2;
            int dlen = data[idx++] & 0xFF;
            idx += dlen;
        }
        if ((control & 0x08) != 0) {
            idx += 2;
            int slen = data[idx++] & 0xFF;
            idx += slen;
        }
        if ((control & 0x20) != 0) {
            idx++;
        }
        int len = length - idx;
        if (len <= 0) {
            return null;
        }
        byte[] apdu = new byte[len];
        System.arraycopy(data, idx, apdu, 0, len);
        return apdu;
    }

    /**
     * Broadcast a global Who-Is (no device range = all devices answer). Stale
     * I-Am frames from any previous scan are discarded first so the following
     * {@link #receiveIAm} only sees responses to this request.
     */
    public void sendWhoIs() throws IOException {
        DatagramSocket s = socket;
        if (s == null) {
            throw new IOException("Socket not open");
        }
        iamQueue.clear();
        byte[] apdu = new byte[] { APDU_UNCONFIRMED_REQUEST, SERVICE_WHO_IS };
        byte[] frame = wrapBroadcast(apdu);
        DatagramPacket p = new DatagramPacket(frame, frame.length, broadcastAddress, BACNET_PORT);
        s.send(p);
        logger.debug("Sent Who-Is broadcast to {}:{}", broadcastAddress.getHostAddress(), BACNET_PORT);
    }

    /**
     * Collect I-Am replies for the given duration by draining the queue that the
     * single reader fills.
     *
     * @param timeoutMs how long to listen
     * @param onDevice  called for each device discovered
     */
    public void receiveIAm(int timeoutMs, Consumer<DiscoveredDevice> onDevice) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return;
            }
            try {
                IamFrame f = iamQueue.poll(remaining, TimeUnit.MILLISECONDS);
                if (f == null) {
                    return;
                }
                DiscoveredDevice dev = parseIAm(f.apdu, f.source);
                if (dev != null) {
                    onDevice.accept(dev);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Send a confirmed-service APDU and wait for the matching reply (matched by
     * invoke id via the single reader). Returns the reply APDU (BVLC/NPDU already
     * stripped) or {@code null} on timeout.
     */
    public byte @Nullable [] sendConfirmedAndAwait(InetAddress target, byte[] apdu, int invokeId, int timeoutMs) {
        DatagramSocket s = socket;
        if (s == null) {
            return null;
        }
        BlockingQueue<byte[]> q = new ArrayBlockingQueue<>(1);
        pending.put(invokeId, q);
        try {
            byte[] frame = wrapUnicast(apdu);
            s.send(new DatagramPacket(frame, frame.length, target, BACNET_PORT));
            long remaining = timeoutMs;
            if (remaining <= 0) {
                return null;
            }
            return q.poll(remaining, TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            logger.debug("send/await failed: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pending.remove(invokeId);
        }
    }

    private byte[] wrapBroadcast(byte[] apdu) {
        // NPDU: version, control 0x00 (no destination specifier)
        byte[] npdu = new byte[] { NPDU_VERSION, 0x00 };
        int len = 4 + npdu.length + apdu.length; // BVLC header is 4 bytes
        byte[] frame = new byte[len];
        frame[0] = BVLC_TYPE;
        frame[1] = BVLC_ORIGINAL_BROADCAST_NPDU;
        frame[2] = (byte) ((len >> 8) & 0xFF);
        frame[3] = (byte) (len & 0xFF);
        System.arraycopy(npdu, 0, frame, 4, npdu.length);
        System.arraycopy(apdu, 0, frame, 4 + npdu.length, apdu.length);
        return frame;
    }

    private byte[] wrapUnicast(byte[] apdu) {
        byte[] npdu = new byte[] { NPDU_VERSION, 0x04 }; // control = expecting reply
        int len = 4 + npdu.length + apdu.length;
        byte[] frame = new byte[len];
        frame[0] = BVLC_TYPE;
        frame[1] = BVLC_ORIGINAL_UNICAST_NPDU;
        frame[2] = (byte) ((len >> 8) & 0xFF);
        frame[3] = (byte) (len & 0xFF);
        System.arraycopy(npdu, 0, frame, 4, npdu.length);
        System.arraycopy(apdu, 0, frame, 4 + npdu.length, apdu.length);
        return frame;
    }

    /**
     * Decode an I-Am APDU (BVLC/NPDU already stripped). Returns null if it is not
     * a valid device I-Am.
     *
     * I-Am payload after the 2-byte APDU header contains application-tagged values,
     * the first being the device Object Identifier (tag 0xC4).
     */
    private @Nullable DiscoveredDevice parseIAm(byte[] apdu, InetAddress source) {
        try {
            int idx = 0;
            if ((apdu[idx++] & 0xF0) != (APDU_UNCONFIRMED_REQUEST & 0xFF)) {
                return null;
            }
            if ((apdu[idx++] & 0xFF) != (SERVICE_I_AM & 0xFF)) {
                return null;
            }
            int tag = apdu[idx++] & 0xFF;
            if (tag != 0xC4) {
                return null;
            }
            long objId = ((long) (apdu[idx] & 0xFF) << 24) | ((apdu[idx + 1] & 0xFF) << 16)
                    | ((apdu[idx + 2] & 0xFF) << 8) | (apdu[idx + 3] & 0xFF);
            int objectType = (int) (objId >> 22) & 0x3FF;
            int instance = (int) (objId & 0x3FFFFF);
            if (objectType != 8) { // 8 = device object
                return null;
            }
            return new DiscoveredDevice(instance, source.getHostAddress());
        } catch (RuntimeException e) {
            logger.debug("Failed to decode I-Am from {}: {}", source, e.getMessage());
            return null;
        }
    }

    /** An I-Am frame waiting to be decoded by a discovery scan. */
    private static class IamFrame {
        final InetAddress source;
        final byte[] apdu;

        IamFrame(InetAddress source, byte[] apdu) {
            this.source = source;
            this.apdu = apdu;
        }
    }

    /** Result of a discovery: device instance number + source IP. */
    public static class DiscoveredDevice {
        public final int instance;
        public final String address;

        public DiscoveredDevice(int instance, String address) {
            this.instance = instance;
            this.address = address;
        }
    }
}
