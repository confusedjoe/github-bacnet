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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal BACnet/IP (Annex J) client.
 *
 * Implements only what discovery needs:
 *  - sends an unconfirmed Who-Is as a global broadcast
 *  - listens for I-Am replies and decodes the device instance + address
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
    private static final byte BVLC_ORIGINAL_BROADCAST_NPDU = 0x0B;
    // NPDU
    private static final byte NPDU_VERSION = 0x01;
    // APDU
    private static final byte APDU_UNCONFIRMED_REQUEST = 0x10;
    private static final byte SERVICE_WHO_IS = 0x08;
    private static final byte SERVICE_I_AM = 0x00;

    private final Logger logger = LoggerFactory.getLogger(BACnetIpClient.class);

    private final InetAddress broadcastAddress;
    private final int localPort;
    private @Nullable DatagramSocket socket;
    private @Nullable BACnetServices services;

    // Notification listeners (COV / events) — the "live socket" fan-out.
    private final List<Consumer<BACnetCovNotification.Notification>> covListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<BACnetEventNotification.Event>> eventListeners = new CopyOnWriteArrayList<>();
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

    public void open() throws IOException {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setBroadcast(true);
        s.bind(new InetSocketAddress(localPort));
        this.socket = s;
        this.services = new BACnetServices(s);
        logger.debug("BACnet/IP socket bound to port {}", localPort);
    }

    public void close() {
        stopDispatch();
        DatagramSocket s = socket;
        if (s != null) {
            s.close();
            socket = null;
        }
        services = null;
    }

    /**
     * Start the background loop that listens for pushed COV and event
     * notifications and fans them out to registered listeners. This is the
     * "live socket" of the binding.
     *
     * Note: this must only run while no synchronous request/response is in
     * flight on the same socket, so the bridge starts it after initial setup
     * and the services briefly pause it during confirmed requests. For
     * simplicity here it uses a short socket timeout and yields the socket
     * when a confirmed request needs it.
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
        Thread t = new Thread(this::dispatchLoop, "bacnet-notification-dispatch");
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
                byte[] apdu = unwrap(p.getData(), p.getLength());
                if (apdu == null) {
                    continue;
                }
                BACnetCovNotification.Notification cov = BACnetCovNotification.parse(apdu);
                if (cov != null) {
                    for (Consumer<BACnetCovNotification.Notification> l : covListeners) {
                        l.accept(cov);
                    }
                    continue;
                }
                BACnetEventNotification.Event ev = BACnetEventNotification.parse(apdu);
                if (ev != null) {
                    for (Consumer<BACnetEventNotification.Event> l : eventListeners) {
                        l.accept(ev);
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                // normal — loop again
            } catch (IOException e) {
                if (dispatching) {
                    logger.debug("dispatch loop error: {}", e.getMessage());
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
     * Broadcast a global Who-Is (no device range = all devices answer).
     */
    public void sendWhoIs() throws IOException {
        DatagramSocket s = socket;
        if (s == null) {
            throw new IOException("Socket not open");
        }
        byte[] apdu = new byte[] { APDU_UNCONFIRMED_REQUEST, SERVICE_WHO_IS };
        byte[] frame = wrapBroadcast(apdu);
        DatagramPacket p = new DatagramPacket(frame, frame.length, broadcastAddress, BACNET_PORT);
        s.send(p);
        logger.debug("Sent Who-Is broadcast to {}:{}", broadcastAddress.getHostAddress(), BACNET_PORT);
    }

    /**
     * Block and collect I-Am replies for the given duration.
     *
     * @param timeoutMs how long to listen
     * @param onDevice  called for each device discovered
     */
    public void receiveIAm(int timeoutMs, Consumer<DiscoveredDevice> onDevice) {
        DatagramSocket s = socket;
        if (s == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[1500];
        try {
            while (System.currentTimeMillis() < deadline) {
                int remaining = (int) (deadline - System.currentTimeMillis());
                if (remaining <= 0) {
                    break;
                }
                s.setSoTimeout(remaining);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
                DiscoveredDevice dev = decodeIAm(p.getData(), p.getLength(), p.getAddress());
                if (dev != null) {
                    onDevice.accept(dev);
                }
            }
        } catch (IOException e) {
            logger.warn("Error while receiving I-Am: {}", e.getMessage());
        }
    }

    private byte[] wrapBroadcast(byte[] apdu) {
        // NPDU: version, control (0x20 = expecting no reply, has destination? keep simple: 0x00)
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

    /**
     * Decode an I-Am APDU. Returns null if the packet is not a valid I-Am.
     *
     * I-Am payload after APDU header contains 4 application-tagged values:
     *   1. Object Identifier (device, instance)  -- tag 0xC4
     *   2. Max APDU length accepted               -- unsigned int
     *   3. Segmentation supported                 -- enumerated
     *   4. Vendor ID                              -- unsigned int
     */
    private @Nullable DiscoveredDevice decodeIAm(byte[] data, int length, InetAddress source) {
        try {
            if (length < 6 || (data[0] & 0xFF) != 0x81) {
                return null;
            }
            int idx = 4; // skip BVLC
            // NPDU
            // version
            idx++; // npdu version
            int control = data[idx++] & 0xFF;
            // If destination present (bit 5), skip DNET(2)+DLEN(1)+DADR + hopcount handling.
            if ((control & 0x20) != 0) {
                idx += 2; // DNET
                int dlen = data[idx++] & 0xFF;
                idx += dlen;
            }
            if ((control & 0x08) != 0) {
                // source present: SNET(2)+SLEN(1)+SADR
                idx += 2;
                int slen = data[idx++] & 0xFF;
                idx += slen;
            }
            if ((control & 0x20) != 0) {
                idx++; // hop count
            }
            // APDU
            int apduType = data[idx++] & 0xFF;
            if (apduType != (APDU_UNCONFIRMED_REQUEST & 0xFF)) {
                return null;
            }
            int service = data[idx++] & 0xFF;
            if (service != (SERVICE_I_AM & 0xFF)) {
                return null;
            }
            // First value: object identifier, application tag 0xC4 (tag 12, length 4)
            int tag = data[idx++] & 0xFF;
            if (tag != 0xC4) {
                return null;
            }
            long objId = ((long) (data[idx] & 0xFF) << 24) | ((data[idx + 1] & 0xFF) << 16)
                    | ((data[idx + 2] & 0xFF) << 8) | (data[idx + 3] & 0xFF);
            idx += 4;
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
                                                                                                                                        