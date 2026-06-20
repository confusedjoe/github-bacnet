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
package org.openhab.binding.bacnet.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Constants used across the BACnet binding.
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public class BACnetBindingConstants {

    public static final String BINDING_ID = "bacnet";

    // Bridge that owns the BACnet/IP socket and runs discovery
    public static final ThingTypeUID THING_TYPE_BRIDGE = new ThingTypeUID(BINDING_ID, "bridge");
    // A discovered BACnet device
    public static final ThingTypeUID THING_TYPE_DEVICE = new ThingTypeUID(BINDING_ID, "device");

    // Bridge config
    public static final String CONFIG_BROADCAST = "broadcastAddress";
    public static final String CONFIG_LOCAL_PORT = "localPort";
    public static final String CONFIG_DISCOVERY_TIMEOUT = "discoveryTimeout";
    public static final String CONFIG_BACKGROUND_DISCOVERY = "backgroundDiscovery";
    public static final String CONFIG_DISCOVERY_INTERVAL = "discoveryInterval";

    // Device config / properties
    public static final String CONFIG_DEVICE_INSTANCE = "deviceInstance";
    public static final String PROPERTY_ADDRESS = "address";
}
