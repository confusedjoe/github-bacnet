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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bacnet.internal.protocol.BACnetCodec.Reader;
import org.openhab.binding.bacnet.internal.protocol.BACnetCodec.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and parses confirmed BACnet/IP service requests.
 *
 * Supported services:
 *  - ReadProperty (choice 12)
 *  - WriteProperty (choice 15)
 *  - SubscribeCOV (choice 5)
 *  - ReadProperty on object-list (point discovery)
 *
 * Service choices per ANSI/ASHRAE 135, BACnetConfirmedServiceChoice.
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public class BACnetServices {

    private static final int BACNET_PORT = 0xBAC0;
    private static final byte BVLC_TYPE = (byte) 0x81;
    private static final byte BVLC_ORIGINAL_UNICAST_NPDU = 0x0A;

    // PDU types
    private static final int PDU_CONFIRMED_REQUEST = 0x00;
    private static final int PDU_COMPLEX_ACK = 0x30;
    private static final int PDU_SIMPLE_ACK = 0x20;
    private static final int PDU_ERROR = 0x50;

    // Service choices
    public static final int SVC_SUBSCRIBE_COV = 5;
    public static final int SVC_READ_PROPERTY = 12;
    public static final int SVC_READ_PROPERTY_MULTIPLE = 14;
    public static final int SVC_WRITE_PROPERTY = 15;
    public static final int SVC_CONF_COV_NOTIFICATION = 1;
    public static final int SVC_UNCONF_COV_NOTIFICATION = 2;

    private final Logger logger = LoggerFactory.getLogger(BACnetServices.class);
    private final DatagramSocket socket;
    private int invokeId = 0;

    public BACnetServices(DatagramSocket socket) {
        this.socket = socket;
    }

    private synchronized int nextInvokeId() {
        invokeId = (invokeId + 1) & 0xFF;
        return invokeId;
    }

    // ---------- ReadProperty ----------

    /**
     * Send a ReadProperty and wait for the ComplexACK, returning the decoded
     * present value as a {@link PropertyValue}, or null on timeout/error.
     */
    public @Nullable PropertyValue readProperty(InetAddress target, int objectType, int instance, int propertyId,
            int timeoutMs) {
        int id = nextInvokeId();
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(PDU_CONFIRMED_REQUEST);
        apdu.write(0x05); // max segments/apdu accepted (1476)
        apdu.write(id);
        apdu.write(SVC_READ_PROPERTY);
        BACnetCodec.encodeContextObjectId(apdu, 0, objectType, instance);
        BACnetCodec.encodeContextUnsigned(apdu, 1, propertyId);
        byte[] reply = sendAndReceive(target, apdu.toByteArray(), id, timeoutMs);
        if (reply == null) {
            return null;
        }
        return parseReadPropertyAck(reply);
    }

    private @Nullable PropertyValue parseReadPropertyAck(byte[] apdu) {
        Reader r = new Reader(apdu, 0, apdu.length);
        int pduType = r.data[r.pos++] & 0xFF;
        if ((pduType & 0xF0) == PDU_ERROR) {
            logger.debug("ReadProperty returned an Error PDU");
            return null;
        }
        if ((pduType & 0xF0) != PDU_COMPLEX_ACK) {
            return null;
        }
        r.pos++; // invoke id
        int service = r.data[r.pos++] & 0xFF;
        if (service != SVC_READ_PROPERTY) {
            return null;
        }
        // context 0: object id, context 1: property id, optional context 2 array index,
        // context 3: opening tag, value, closing tag.
        // The object-id and property-id are read only to advance the cursor.
        while (r.hasMore()) {
            Tag t = BACnetCodec.readTag(r);
            if (t.context && t.number == 0 && !t.opening) {
                BACnetCodec.readObjectId(r);
            } else if (t.context && t.number == 1 && !t.opening) {
                BACnetCodec.readUnsigned(r, (int) t.length);
            } else if (t.context && t.number == 3 && t.opening) {
                return decodeValue(r);
            } else if (!t.opening && !t.closing) {
                BACnetCodec.skip(r, t.length);
            }
        }
        return null;
    }

    /** Decode a single application-tagged value starting at the reader. */
    private @Nullable PropertyValue decodeValue(Reader r) {
        Tag t = BACnetCodec.readTag(r);
        if (t.context) {
            return null;
        }
        switch (t.number) {
            case BACnetCodec.TAG_REAL:
                return PropertyValue.ofReal(BACnetCodec.readReal(r));
            case BACnetCodec.TAG_UNSIGNED:
                return PropertyValue.ofUnsigned(BACnetCodec.readUnsigned(r, (int) t.length));
            case BACnetCodec.TAG_ENUMERATED:
                return PropertyValue.ofEnumerated(BACnetCodec.readUnsigned(r, (int) t.length));
            case BACnetCodec.TAG_BOOLEAN:
                return PropertyValue.ofBoolean(t.length == 1);
            case BACnetCodec.TAG_CHARACTER_STRING:
                return PropertyValue.ofString(BACnetCodec.readCharacterString(r, (int) t.length));
            case BACnetCodec.TAG_SIGNED:
                return PropertyValue.ofUnsigned(BACnetCodec.readUnsigned(r, (int) t.length));
            default:
                BACnetCodec.skip(r, t.length);
                return null;
        }
    }

    // ---------- WriteProperty ----------

    /** Write a REAL present value (e.g. analog output/value). */
    public boolean writeReal(InetAddress target, int objectType, int instance, int propertyId, float value,
            int priority, int timeoutMs) {
        int id = nextInvokeId();
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(PDU_CONFIRMED_REQUEST);
        apdu.write(0x05);
        apdu.write(id);
        apdu.write(SVC_WRITE_PROPERTY);
        BACnetCodec.encodeContextObjectId(apdu, 0, objectType, instance);
        BACnetCodec.encodeContextUnsigned(apdu, 1, propertyId);
        BACnetCodec.encodeOpeningTag(apdu, 3);
        BACnetCodec.encodeReal(apdu, value);
        BACnetCodec.encodeClosingTag(apdu, 3);
        if (priority > 0) {
            BACnetCodec.encodeContextUnsigned(apdu, 4, priority);
        }
        byte[] reply = sendAndReceive(target, apdu.toByteArray(), id, timeoutMs);
        return reply != null && (reply[0] & 0xF0) == PDU_SIMPLE_ACK;
    }

    /** Write an enumerated present value (e.g. binary output, 0/1). */
    public boolean writeEnumerated(InetAddress target, int objectType, int instance, int propertyId, long value,
            int priority, int timeoutMs) {
        int id = nextInvokeId();
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(PDU_CONFIRMED_REQUEST);
        apdu.write(0x05);
        apdu.write(id);
        apdu.write(SVC_WRITE_PROPERTY);
        BACnetCodec.encodeContextObjectId(apdu, 0, objectType, instance);
        BACnetCodec.encodeContextUnsigned(apdu, 1, propertyId);
        BACnetCodec.encodeOpeningTag(apdu, 3);
        BACnetCodec.encodeEnumerated(apdu, value);
        BACnetCodec.encodeClosingTag(apdu, 3);
        if (priority > 0) {
            BACnetCodec.encodeContextUnsigned(apdu, 4, priority);
        }
        byte[] reply = sendAndReceive(target, apdu.toByteArray(), id, timeoutMs);
        return reply != null && (reply[0] & 0xF0) == PDU_SIMPLE_ACK;
    }

    // ---------- SubscribeCOV (the "live socket" equivalent) ----------

    /**
     * Subscribe to change-of-value notifications for an object. The device will
     * then push (confirmed or unconfirmed) COV notifications when the value
     * changes, until the lifetime expires.
     */
    public boolean subscribeCov(InetAddress target, int subscriberProcessId, int objectType, int instance,
            boolean confirmed, int lifetimeSeconds, int timeoutMs) {
        int id = nextInvokeId();
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(PDU_CONFIRMED_REQUEST);
        apdu.write(0x05);
        apdu.write(id);
        apdu.write(SVC_SUBSCRIBE_COV);
        BACnetCodec.encodeContextUnsigned(apdu, 0, subscriberProcessId);
        BACnetCodec.encodeContextObjectId(apdu, 1, objectType, instance);
        // context 2: issueConfirmedNotifications (boolean)
        apdu.write(0x29); // context tag 2, length 1
        apdu.write(confirmed ? 1 : 0);
        // context 3: lifetime (unsigned, 0 = indefinite)
        BACnetCodec.encodeContextUnsigned(apdu, 3, lifetimeSeconds);
        byte[] reply = sendAndReceive(target, apdu.toByteArray(), id, timeoutMs);
        return reply != null && (reply[0] & 0xF0) == PDU_SIMPLE_ACK;
    }

    // ---------- Object list (discovery of points within a device) ----------

    /**
     * Read the device's object-list property and return all object identifiers.
     * Uses ReadProperty on the device object's object-list (property 76).
     */
    public List<int[]> readObjectList(InetAddress target, int deviceInstance, int timeoutMs) {
        List<int[]> result = new ArrayList<>();
        int id = nextInvokeId();
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(PDU_CONFIRMED_REQUEST);
        apdu.write(0x05);
        apdu.write(id);
        apdu.write(SVC_READ_PROPERTY);
        BACnetCodec.encodeContextObjectId(apdu, 0, BACnetEnums.ObjectType.DEVICE, deviceInstance);
        BACnetCodec.encodeContextUnsigned(apdu, 1, BACnetEnums.Property.OBJECT_LIST);
        byte[] reply = sendAndReceive(target, apdu.toByteArray(), id, timeoutMs);
        if (reply == null) {
            return result;
        }
        Reader r = new Reader(reply, 0, reply.length);
        int pduType = r.data[r.pos++] & 0xFF;
        if ((pduType & 0xF0) != PDU_COMPLEX_ACK) {
            return result;
        }
        r.pos++; // invoke id
        r.pos++; // service
        while (r.hasMore()) {
            Tag t = BACnetCodec.readTag(r);
            if (t.context && t.number == 3 && t.opening) {
                // values until closing tag 3
                while (r.hasMore()) {
                    if (r.peek() == 0x3F) { // closing tag 3
                        r.pos++;
                        break;
                    }
                    Tag vt = BACnetCodec.readTag(r);
                    if (!vt.context && vt.number == BACnetCodec.TAG_OBJECT_ID) {
                        result.add(BACnetCodec.readObjectId(r));
                    } else {
                        BACnetCodec.skip(r, vt.length);
                    }
                }
                break;
            } else if (!t.opening && !t.closing) {
                BACnetCodec.skip(r, t.length);
            }
        }
        return result;
    }

    // ---------- transport ----------

    private byte @Nullable [] sendAndReceive(InetAddress target, byte[] apdu, int expectedInvokeId, int timeoutMs) {
        try {
            byte[] frame = wrapUnicast(apdu);
            DatagramPacket p = new DatagramPacket(frame, frame.length, target, BACNET_PORT);
            socket.send(p);
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[1500];
            while (System.currentTimeMillis() < deadline) {
                int remaining = (int) (deadline - System.currentTimeMillis());
                socket.setSoTimeout(Math.max(1, remaining));
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(in);
                } catch (java.net.SocketTimeoutException e) {
                    return null;
                }
                byte[] inner = unwrap(in.getData(), in.getLength());
                if (inner != null && inner.length >= 3) {
                    int pduType = inner[0] & 0xFF;
                    // simple/complex ack and error carry invoke id at offset 1
                    int idOffset = ((pduType & 0xF0) == PDU_COMPLEX_ACK || (pduType & 0xF0) == PDU_SIMPLE_ACK
                            || (pduType & 0xF0) == PDU_ERROR) ? 1 : -1;
                    if (idOffset >= 0 && (inner[idOffset] & 0xFF) == expectedInvokeId) {
                        return inner;
                    }
                }
            }
            return null;
        } catch (IOException e) {
            logger.debug("send/receive failed: {}", e.getMessage());
            return null;
        }
    }

    private byte[] wrapUnicast(byte[] apdu) {
        byte[] npdu = new byte[] { 0x01, 0x04 }; // version, control=expecting reply
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

    /** Strip BVLC + NPDU, return the APDU bytes. */
    private byte @Nullable [] unwrap(byte[] data, int length) {
        if (length < 6 || (data[0] & 0xFF) != 0x81) {
            return null;
        }
        int idx = 4; // skip BVLC
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
            idx++; // hop count
        }
        int len = length - idx;
        if (len <= 0) {
            return null;
        }
        byte[] apdu = new byte[len];
        System.arraycopy(data, idx, apdu, 0, len);
        return apdu;
    }
}
                                                  