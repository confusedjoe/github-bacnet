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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bacnet.internal.protocol.BACnetCodec.Reader;
import org.openhab.binding.bacnet.internal.protocol.BACnetCodec.Tag;

/**
 * Parses pushed COV notifications. This is the BACnet equivalent of the RCO
 * "live socket": instead of polling, the device sends the new value when it
 * changes (after a SubscribeCOV).
 *
 * Unconfirmed-COV-Notification (service 2) and Confirmed-COV-Notification
 * (service 1) carry the same content:
 *   [0] subscriber process id
 *   [1] initiating device id
 *   [2] monitored object id
 *   [3] time remaining
 *   [4] list of property values (opening/closing) -- each has
 *        [0] property identifier, [2] value (opening/closing)
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public final class BACnetCovNotification {

    private BACnetCovNotification() {
    }

    /** Result of a parsed COV notification. */
    public static class Notification {
        public int initiatingDevice;
        public int objectType;
        public int objectInstance;
        public int propertyId = -1;
        public @Nullable PropertyValue value;
    }

    /**
     * Parse an APDU (already stripped of BVLC/NPDU) if it is a COV notification.
     * Returns null otherwise.
     */
    public static @Nullable Notification parse(byte[] apdu) {
        Reader r = new Reader(apdu, 0, apdu.length);
        int pduType = r.data[r.pos++] & 0xFF;
        int service;
        if ((pduType & 0xF0) == 0x10) {
            // unconfirmed request: next byte is service choice
            service = r.data[r.pos++] & 0xFF;
        } else if ((pduType & 0xF0) == 0x00) {
            // confirmed request: skip max-apdu, invoke id, then service
            r.pos++; // max segments/apdu
            r.pos++; // invoke id
            service = r.data[r.pos++] & 0xFF;
        } else {
            return null;
        }
        if (service != BACnetServices.SVC_UNCONF_COV_NOTIFICATION
                && service != BACnetServices.SVC_CONF_COV_NOTIFICATION) {
            return null;
        }
        Notification n = new Notification();
        try {
            while (r.hasMore()) {
                Tag t = BACnetCodec.readTag(r);
                if (t.context && t.number == 1 && !t.opening) {
                    int[] dev = BACnetCodec.readObjectId(r);
                    n.initiatingDevice = dev[1];
                } else if (t.context && t.number == 2 && !t.opening) {
                    int[] obj = BACnetCodec.readObjectId(r);
                    n.objectType = obj[0];
                    n.objectInstance = obj[1];
                } else if (t.context && t.number == 4 && t.opening) {
                    parseValueList(r, n);
                    break;
                } else if (!t.opening && !t.closing) {
                    BACnetCodec.skip(r, t.length);
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return n.propertyId >= 0 ? n : null;
    }

    private static void parseValueList(Reader r, Notification n) {
        // We only need the present-value (property 85) from the list.
        while (r.hasMore()) {
            if (r.peek() == 0x4F) { // closing tag 4
                r.pos++;
                return;
            }
            Tag t = BACnetCodec.readTag(r);
            if (t.context && t.number == 0 && !t.opening) {
                int pid = (int) BACnetCodec.readUnsigned(r, (int) t.length);
                if (n.propertyId < 0) {
                    n.propertyId = pid;
                }
            } else if (t.context && t.number == 2 && t.opening) {
                // value
                Tag vt = BACnetCodec.readTag(r);
                if (!vt.context) {
                    switch (vt.number) {
                        case BACnetCodec.TAG_REAL:
                            n.value = PropertyValue.ofReal(BACnetCodec.readReal(r));
                            break;
                        case BACnetCodec.TAG_UNSIGNED:
                            n.value = PropertyValue.ofUnsigned(BACnetCodec.readUnsigned(r, (int) vt.length));
                            break;
                        case BACnetCodec.TAG_ENUMERATED:
                            n.value = PropertyValue.ofEnumerated(BACnetCodec.readUnsigned(r, (int) vt.length));
                            break;
                        case BACnetCodec.TAG_BOOLEAN:
                            n.value = PropertyValue.ofBoolean(vt.length == 1);
                            break;
                        default:
                            BACnetCodec.skip(r, vt.length);
                            break;
                    }
                }
                // consume closing tag 2 if present
                if (r.hasMore() && r.peek() == 0x2F) {
                    r.pos++;
                }
            } else if (!t.opening && !t.closing) {
                BACnetCodec.skip(r, t.length);
            }
        }
    }
}
