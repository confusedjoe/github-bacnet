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

/**
 * BACnet enumeration constants used by the binding.
 *
 * Values are taken from ANSI/ASHRAE 135. Only the subset the binding needs is
 * listed here.
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public final class BACnetEnums {

    private BACnetEnums() {
    }

    /** Object types (Clause 21, BACnetObjectType). */
    public static final class ObjectType {
        public static final int ANALOG_INPUT = 0;
        public static final int ANALOG_OUTPUT = 1;
        public static final int ANALOG_VALUE = 2;
        public static final int BINARY_INPUT = 3;
        public static final int BINARY_OUTPUT = 4;
        public static final int BINARY_VALUE = 5;
        public static final int CALENDAR = 6;
        public static final int DEVICE = 8;
        public static final int MULTI_STATE_INPUT = 13;
        public static final int MULTI_STATE_OUTPUT = 14;
        public static final int MULTI_STATE_VALUE = 19;
        public static final int NOTIFICATION_CLASS = 15;
        public static final int SCHEDULE = 17;

        private ObjectType() {
        }

        public static String name(int t) {
            switch (t) {
                case ANALOG_INPUT: return "ai";
                case ANALOG_OUTPUT: return "ao";
                case ANALOG_VALUE: return "av";
                case BINARY_INPUT: return "bi";
                case BINARY_OUTPUT: return "bo";
                case BINARY_VALUE: return "bv";
                case CALENDAR: return "calendar";
                case DEVICE: return "device";
                case MULTI_STATE_INPUT: return "msi";
                case MULTI_STATE_OUTPUT: return "mso";
                case MULTI_STATE_VALUE: return "msv";
                case NOTIFICATION_CLASS: return "nc";
                case SCHEDULE: return "schedule";
                default: return "type" + t;
            }
        }

        public static boolean isAnalog(int t) {
            return t == ANALOG_INPUT || t == ANALOG_OUTPUT || t == ANALOG_VALUE;
        }

        public static boolean isBinary(int t) {
            return t == BINARY_INPUT || t == BINARY_OUTPUT || t == BINARY_VALUE;
        }

        public static boolean isMultiState(int t) {
            return t == MULTI_STATE_INPUT || t == MULTI_STATE_OUTPUT || t == MULTI_STATE_VALUE;
        }

        public static boolean isWritable(int t) {
            return t == ANALOG_OUTPUT || t == ANALOG_VALUE || t == BINARY_OUTPUT || t == BINARY_VALUE
                    || t == MULTI_STATE_OUTPUT || t == MULTI_STATE_VALUE;
        }
    }

    /** Property identifiers (Clause 21, BACnetPropertyIdentifier). */
    public static final class Property {
        public static final int ACK_REQUIRED = 1;
        public static final int EVENT_STATE = 36;
        public static final int OBJECT_IDENTIFIER = 75;
        public static final int OBJECT_LIST = 76;
        public static final int OBJECT_NAME = 77;
        public static final int OBJECT_TYPE = 79;
        public static final int PRESENT_VALUE = 85;
        public static final int PRIORITY_ARRAY = 87;
        public static final int RELIABILITY = 103;
        public static final int STATUS_FLAGS = 111;
        public static final int UNITS = 117;
        public static final int DESCRIPTION = 28;
        public static final int NOTIFICATION_CLASS = 17;
        public static final int PRESENT_VALUE_SCHEDULE = 85;
        public static final int EFFECTIVE_PERIOD = 32;
        public static final int WEEKLY_SCHEDULE = 123;
        public static final int EXCEPTION_SCHEDULE = 38;
        public static final int DATE_LIST = 23;
        public static final int RECIPIENT_LIST = 102;

        private Property() {
        }
    }

    /** Event states (BACnetEventState). */
    public static final class EventState {
        public static final int NORMAL = 0;
        public static final int FAULT = 1;
        public static final int OFFNORMAL = 2;
        public static final int HIGH_LIMIT = 3;
        public static final int LOW_LIMIT = 4;

        private EventState() {
        }

        public static String name(int s) {
            switch (s) {
                case NORMAL: return "NORMAL";
                case FAULT: return "FAULT";
                case OFFNORMAL: return "OFFNORMAL";
                case HIGH_LIMIT: return "HIGH_LIMIT";
                case LOW_LIMIT: return "LOW_LIMIT";
                default: return "STATE_" + s;
            }
        }
    }
}
