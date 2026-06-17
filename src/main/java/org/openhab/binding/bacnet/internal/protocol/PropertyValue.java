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

/**
 * A typed BACnet property value decoded from a service response.
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public class PropertyValue {

    public enum Type {
        REAL,
        UNSIGNED,
        ENUMERATED,
        BOOLEAN,
        STRING
    }

    public final Type type;
    public final double number;
    public final boolean bool;
    public final @Nullable String text;

    private PropertyValue(Type type, double number, boolean bool, @Nullable String text) {
        this.type = type;
        this.number = number;
        this.bool = bool;
        this.text = text;
    }

    public static PropertyValue ofReal(float v) {
        return new PropertyValue(Type.REAL, v, false, null);
    }

    public static PropertyValue ofUnsigned(long v) {
        return new PropertyValue(Type.UNSIGNED, v, false, null);
    }

    public static PropertyValue ofEnumerated(long v) {
        return new PropertyValue(Type.ENUMERATED, v, v != 0, null);
    }

    public static PropertyValue ofBoolean(boolean v) {
        return new PropertyValue(Type.BOOLEAN, v ? 1 : 0, v, null);
    }

    public static PropertyValue ofString(String v) {
        return new PropertyValue(Type.STRING, 0, false, v);
    }

    @Override
    public String toString() {
        switch (type) {
            case STRING:
                return String.valueOf(text);
            case BOOLEAN:
                return String.valueOf(bool);
            case REAL:
                return String.valueOf(number);
            default:
                return String.valueOf((long) number);
        }
    }
}
