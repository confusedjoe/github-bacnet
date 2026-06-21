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

import java.util.Hashtable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bacnet.internal.discovery.BACnetDiscoveryService;
import org.openhab.binding.bacnet.internal.handler.BACnetBridgeHandler;
import org.openhab.binding.bacnet.internal.handler.BACnetDeviceHandler;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.ManagedItemProvider;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The {@link BACnetHandlerFactory} creates the bridge and device handlers and
 * registers a {@link BACnetDiscoveryService} against each bridge (classic
 * factory-registered discovery, so the service lifecycle is deterministic).
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.bacnet", service = ThingHandlerFactory.class)
public class BACnetHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED = Set.of(BACnetBindingConstants.THING_TYPE_BRIDGE,
            BACnetBindingConstants.THING_TYPE_DEVICE);

    private final Map<ThingUID, BACnetDiscoveryService> discoveryServices = new ConcurrentHashMap<>();
    private final Map<ThingUID, ServiceRegistration<?>> discoveryRegs = new ConcurrentHashMap<>();

    private @Reference @NonNullByDefault({}) ManagedItemProvider itemProvider;
    private @Reference @NonNullByDefault({}) ItemChannelLinkRegistry linkRegistry;
    private @Reference @NonNullByDefault({}) ItemRegistry itemRegistry;

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID typeUID = thing.getThingTypeUID();
        if (BACnetBindingConstants.THING_TYPE_BRIDGE.equals(typeUID)) {
            BACnetBridgeHandler handler = new BACnetBridgeHandler((Bridge) thing);
            registerDiscoveryService(handler);
            return handler;
        } else if (BACnetBindingConstants.THING_TYPE_DEVICE.equals(typeUID)) {
            return new BACnetDeviceHandler(thing, itemProvider, linkRegistry, itemRegistry);
        }
        return null;
    }

    @Override
    protected synchronized void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof BACnetBridgeHandler) {
            ThingUID uid = thingHandler.getThing().getUID();
            BACnetDiscoveryService discovery = discoveryServices.remove(uid);
            if (discovery != null) {
                discovery.stop();
            }
            ServiceRegistration<?> reg = discoveryRegs.remove(uid);
            if (reg != null) {
                reg.unregister();
            }
        }
    }

    private synchronized void registerDiscoveryService(BACnetBridgeHandler handler) {
        ThingUID uid = handler.getThing().getUID();
        BACnetDiscoveryService discovery = new BACnetDiscoveryService(handler);
        discoveryServices.put(uid, discovery);
        discoveryRegs.put(uid,
                bundleContext.registerService(DiscoveryService.class.getName(), discovery, new Hashtable<>()));
        discovery.start();
    }
}
