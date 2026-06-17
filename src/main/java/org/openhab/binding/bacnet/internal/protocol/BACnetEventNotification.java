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
 * Parses BACnet Event Notifications (alarms). These arrive via
 * ConfirmedEventNotification (service 2) or UnconfirmedEventNotification
 * (service 3) when a device with intrinsic reporting detects an off-normal or
 * fault condition.
 *
 * Relevant fields:
 *   [1] initiating device id
 *   [2] event object id
 *   [6] notify type
 *   [9] from-state (BACnetEventState)
 *   [10] to-state  (BACnetEventState)
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public final class BACnetEventNotification {

    private static final int SVC_CONFIRMED_EVENT = 2;
    private static final int SVC_UNCONFIRMED_EVENT = 3;

    private BACnetEventNotification() {
    }

    /** Parsed alarm event. */
    public static class Event {
        public int initiatingDevice;
        public int objectType;
        public int objectInstance;
        public int fromState = -1;
        public int toState = -1;
        public int priority;

        public boolean isAlarm() {
            // anything that is not NORMAL is an active alarm condition
            return toState != BACnetEnums.EventState.NORMAL && toState >= 0;
        }
    }

    public static @Nullable Event parse(byte[] apdu) {
        Reader r = new Reader(apdu, 0, apdu.length);
        int pduType = r.data[r.pos++] & 0xFF;
        int service;
        if ((pduType & 0xF0) == 0x10) {
            service = r.data[r.pos++] & 0xFF;
        } else if ((pduType & 0xF0) == 0x00) {
            r.pos++; // max apdu
            r.pos++; // invoke id
            service = r.data[r.pos++] & 0xFF;
        } else {
            return null;
        }
        if (service != SVC_CONFIRMED_EVENT && service != SVC_UNCONFIRMED_EVENT) {
            return null;
        }
        Event e = new Event();
        try {
            while (r.hasMore()) {
                Tag t = BACnetCodec.readTag(r);
                if (t.opening || t.closing) {
                    continue;
                }
                if (t.context) {
                    switch (t.number) {
                        case 1: // initiating device
                            e.initiatingDevice = BACnetCodec.readObjectId(r)[1];
                            break;
                        case 2: // event object
                            int[] obj = BACnetCodec.readObjectId(r);
                            e.objectType = obj[0];
                            e.objectInstance = obj[1];
                            break;
                        case 7: // priority
                            e.priority = (int) BACnetCodec.readUnsigned(r, (int) t.length);
                            break;
                        case 11: // from state
                            e.fromState = (int) BACnetCodec.readUnsigned(r, (int) t.length);
                            break;
                        case 12: // to state
                            e.toState = (int) BACnetCodec.readUnsigned(r, (int) t.length);
                            break;
                        default:
                            BACnetCodec.skip(r, t.length);
                            break;
                    }
                } else {
                    BACnetCodec.skip(r, t.length);
                }
            }
        } catch (RuntimeException ex) {
            return null;
        }
        return e.toState >= 0 ? e : null;
    }
}
