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
 *  - ReadProperty (choice 12), including by array index
 *  - WriteProperty (choice 15)
 *  - SubscribeCOV (choice 5)
 *  - ReadProperty on object-list (point discovery), with an indexed fallback
 *    for devices that cannot return the whole list in one (unsegmented) APDU
 *
 * Service choices per ANSI/ASHRAE 135, BACnetConfirmedServiceChoice.
 *
 * All transport (send + await reply) is delegated to {@link BACnetIpClient} so
 * that a single thread owns the socket; see that class for the rationale.
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public class BACnetServices {

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

    // Guard rail for the indexed object-list fallback.
    private static final int MAX_OBJECT_LIST = 10000;

    private final Logger logger = LoggerFactory.getLogger(BACnetServices.class);
    private final BACnetIpClient client;
    private int invokeId = 0;

    public BACnetServices(BACnetIpClient client) {
        this.client = client;
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
        return readPropertyInternal(target, objectType, instance, propertyId, -1, timeoutMs);
    }

    private @Nullable PropertyValue readPropertyInternal(InetAddress target, int objectType, int instance,
            int propertyId, int arrayIndex, int timeoutMs) {
        int id = nextInvokeId();
        byte[] apdu = buildReadProperty(id, objectType, instance, propertyId, arrayIndex);
        byte[] reply = sendAndReceive(target, apdu, id, timeoutMs);
        if (reply == null) {
            return null;
        }
        return parseReadPropertyAck(reply);
    }

    private byte[] buildReadProperty(int id, int objectType, int instance, int propertyId, int arrayIndex) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(PDU_CONFIRMED_REQUEST);
        apdu.write(0x05); // max segments/apdu accepted (1476)
        apdu.write(id);
        apdu.write(SVC_READ_PROPERTY);
        BACnetCodec.encodeContextObjectId(apdu, 0, objectType, instance);
        BACnetCodec.encodeContextUnsigned(apdu, 1, propertyId);
        if (arrayIndex >= 0) {
            BACnetCodec.encodeContextUnsigned(apdu, 2, arrayIndex);
        }
        return apdu.toByteArray();
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
     * Read the device's object-list and return all object identifiers.
     *
     * Fast path: a single ReadProperty of the whole object-list (property 76).
     * That fails on devices whose list does not fit in one APDU and which need
     * segmentation (not implemented). In that case we fall back to reading the
     * list element by element via the array index, which never needs
     * segmentation.
     */
    public List<int[]> readObjectList(InetAddress target, int deviceInstance, int timeoutMs) {
        List<int[]> bulk = readObjectListBulk(target, deviceInstance, timeoutMs);
        if (!bulk.isEmpty()) {
            return bulk;
        }
        logger.debug("Bulk object-list read empty/failed for device {} — falling back to indexed read",
                deviceInstance);
        return readObjectListIndexed(target, deviceInstance, timeoutMs);
    }

    private List<int[]> readObjectListBulk(InetAddress target, int deviceInstance, int timeoutMs) {
        List<int[]> result = new ArrayList<>();
        int id = nextInvokeId();
        byte[] apdu = buildReadProperty(id, BACnetEnums.ObjectType.DEVICE, deviceInstance,
                BACnetEnums.Property.OBJECT_LIST, -1);
        byte[] reply = sendAndReceive(target, apdu, id, timeoutMs);
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

    /**
     * Read the object-list one element at a time using the array index.
     * Index 0 yields the element count; indices 1..count each yield one object
     * identifier. Each reply is tiny, so no segmentation is ever required.
     */
    private List<int[]> readObjectListIndexed(InetAddress target, int deviceInstance, int timeoutMs) {
        List<int[]> result = new ArrayList<>();
        PropertyValue countVal = readPropertyInternal(target, BACnetEnums.ObjectType.DEVICE, deviceInstance,
                BACnetEnums.Property.OBJECT_LIST, 0, timeoutMs);
        if (countVal == null) {
            return result;
        }
        int count = (int) countVal.number;
        if (count <= 0) {
            return result;
        }
        if (count > MAX_OBJECT_LIST) {
            logger.warn("Device {} reports {} objects; capping indexed read at {}", deviceInstance, count,
                    MAX_OBJECT_LIST);
            count = MAX_OBJECT_LIST;
        }
        for (int i = 1; i <= count; i++) {
            int id = nextInvokeId();
            byte[] apdu = buildReadProperty(id, BACnetEnums.ObjectType.DEVICE, deviceInstance,
                    BACnetEnums.Property.OBJECT_LIST, i);
            byte[] reply = sendAndReceive(target, apdu, id, timeoutMs);
            if (reply == null) {
                continue;
            }
            int[] oid = parseObjectIdValue(reply);
            if (oid != null) {
                result.add(oid);
            }
        }
        return result;
    }

    /** Parse a ReadProperty ACK whose value is a single object identifier. */
    private int @Nullable [] parseObjectIdValue(byte[] apdu) {
        Reader r = new Reader(apdu, 0, apdu.length);
        int pduType = r.data[r.pos++] & 0xFF;
        if ((pduType & 0xF0) != PDU_COMPLEX_ACK) {
            return null;
        }
        r.pos++; // invoke id
        int service = r.data[r.pos++] & 0xFF;
        if (service != SVC_READ_PROPERTY) {
            return null;
        }
        while (r.hasMore()) {
            Tag t = BACnetCodec.readTag(r);
            if (t.context && t.number == 3 && t.opening) {
                Tag vt = BACnetCodec.readTag(r);
                if (!vt.context && vt.number == BACnetCodec.TAG_OBJECT_ID) {
                    return BACnetCodec.readObjectId(r);
                }
                return null;
            } else if (!t.opening && !t.closing) {
                BACnetCodec.skip(r, t.length);
            }
        }
        return null;
    }

    // ---------- transport ----------

    private byte @Nullable [] sendAndReceive(InetAddress target, byte[] apdu, int expectedInvokeId, int timeoutMs) {
        return client.sendConfirmedAndAwait(target, apdu, expectedInvokeId, timeoutMs);
    }
}
