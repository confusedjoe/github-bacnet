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
        // Additional value-bearing data-point object types
        public static final int LOOP = 12;
        public static final int AVERAGING = 18;
        public static final int ACCUMULATOR = 23;
        public static final int PULSE_CONVERTER = 24;
        public static final int CHARACTERSTRING_VALUE = 40;
        public static final int INTEGER_VALUE = 45;
        public static final int LARGE_ANALOG_VALUE = 46;
        public static final int POSITIVE_INTEGER_VALUE = 48;
        public static final int LIGHTING_OUTPUT = 54;
        public static final int BINARY_LIGHTING_OUTPUT = 55;

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
                case LOOP: return "loop";
                case AVERAGING: return "avg";
                case ACCUMULATOR: return "acc";
                case PULSE_CONVERTER: return "pconv";
                case CHARACTERSTRING_VALUE: return "csv";
                case INTEGER_VALUE: return "iv";
                case LARGE_ANALOG_VALUE: return "lav";
                case POSITIVE_INTEGER_VALUE: return "piv";
                case LIGHTING_OUTPUT: return "lo";
                case BINARY_LIGHTING_OUTPUT: return "blo";
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
                    || t == MULTI_STATE_OUTPUT || t == MULTI_STATE_VALUE || t == SCHEDULE
                    || t == LARGE_ANALOG_VALUE || t == INTEGER_VALUE || t == POSITIVE_INTEGER_VALUE
                    || t == LIGHTING_OUTPUT || t == BINARY_LIGHTING_OUTPUT;
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

    /** Engineering units (BACnetEngineeringUnits, ASHRAE 135 Clause 21). */
    public static final class Units {
        public static final int DEGREES_CELSIUS = 62;
        public static final int DEGREES_KELVIN = 63;
        public static final int DEGREES_FAHRENHEIT = 64;
        public static final int PERCENT = 98;

        private Units() {
        }

        /** Short symbol for a unit code, or "" if not mapped. */
        public static String symbol(int u) {
            switch (u) {
                case 62: return "°C";
                case 63: return "K";
                case 64: return "°F";
                case 53: return "Pa";
                case 54: return "kPa";
                case 55: return "bar";
                case 98: return "%";
                case 29: return "%rH";
                case 5: return "V";
                case 6: return "kV";
                case 124: return "mV";
                case 3: return "A";
                case 2: return "mA";
                case 167: return "A/m";
                case 47: return "W";
                case 48: return "kW";
                case 18: return "Wh";
                case 19: return "kWh";
                case 17: return "kJ";
                case 126: return "MJ";
                case 27: return "Hz";
                case 37: return "lx";
                case 96: return "ppm";
                case 87: return "l/s";
                case 136: return "l/h";
                case 135: return "m³/h";
                case 80: return "m³";
                case 82: return "l";
                case 74: return "m/s";
                case 90: return "°";
                case 103: return "rad";
                case 71: return "h";
                case 72: return "min";
                case 73: return "s";
                case 253: return "Pa·s";
                default: return "";
            }
        }

        /** Physical-quantity tag for a unit symbol (for sorting), or "" if none. */
        public static String quantity(String symbol) {
            switch (symbol) {
                case "°C": case "K": case "°F": return "Temperature";
                case "Pa": case "kPa": case "bar": return "Pressure";
                case "%rH": return "Humidity";
                case "%": return "Percent";
                case "V": case "kV": case "mV": return "Voltage";
                case "A": case "mA": return "Current";
                case "W": case "kW": return "Power";
                case "Wh": case "kWh": case "kJ": case "MJ": return "Energy";
                case "Hz": return "Frequency";
                case "lx": return "Illuminance";
                case "ppm": return "Concentration";
                case "l/s": case "l/h": case "m³/h": return "Flow";
                case "m³": case "l": return "Volume";
                case "m/s": return "Speed";
                case "°": case "rad": return "Angle";
                case "h": case "min": case "s": return "Duration";
                default: return "";
            }
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
