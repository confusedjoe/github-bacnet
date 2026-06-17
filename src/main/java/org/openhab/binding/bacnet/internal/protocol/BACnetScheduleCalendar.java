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

import java.net.InetAddress;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Convenience readers for Schedule (17) and Calendar (6) objects.
 *
 * Both are read through ReadProperty:
 *  - Schedule present-value (85) yields the currently scheduled value.
 *  - Calendar present-value (85) yields a boolean (is today in the calendar?).
 *
 * The full weekly-schedule / date-list structures are large constructed types;
 * for the binding we expose the present-value, which is what most integrations
 * actually need (what is active right now).
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public final class BACnetScheduleCalendar {

    private BACnetScheduleCalendar() {
    }

    /** Read the currently active scheduled value of a Schedule object. */
    public static @Nullable PropertyValue readScheduleValue(BACnetServices services, InetAddress target,
            int instance, int timeoutMs) {
        return services.readProperty(target, BACnetEnums.ObjectType.SCHEDULE, instance,
                BACnetEnums.Property.PRESENT_VALUE, timeoutMs);
    }

    /** Read whether the current date is active in a Calendar object. */
    public static @Nullable PropertyValue readCalendarActive(BACnetServices services, InetAddress target,
            int instance, int timeoutMs) {
        return services.readProperty(target, BACnetEnums.ObjectType.CALENDAR, instance,
                BACnetEnums.Property.PRESENT_VALUE, timeoutMs);
    }
}
