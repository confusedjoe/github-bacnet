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
package org.openhab.binding.bacnet.internal.handler;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bacnet.internal.BACnetBindingConstants;
import org.openhab.binding.bacnet.internal.protocol.BACnetCovNotification;
import org.openhab.binding.bacnet.internal.protocol.BACnetEnums;
import org.openhab.binding.bacnet.internal.protocol.BACnetEventNotification;
import org.openhab.binding.bacnet.internal.protocol.BACnetIpClient;
import org.openhab.binding.bacnet.internal.protocol.BACnetServices;
import org.openhab.binding.bacnet.internal.protocol.PropertyValue;
import org.openhab.core.items.GenericItem;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.ManagedItemProvider;
import org.openhab.core.library.items.ContactItem;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles a single discovered BACnet device.
 *
 * Provides RCO-equivalent features mapped to BACnet:
 *  - present-value read/write  (datapoints)
 *  - event-state               (alarms via intrinsic reporting)
 *  - schedule / calendar present-value
 *  - COV subscription          (live updates / "live socket")
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public class BACnetDeviceHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(BACnetDeviceHandler.class);

    private int deviceInstance;
    private @Nullable InetAddress address;
    private @Nullable BACnetServices services;
    private @Nullable ScheduledFuture<?> pollJob;
    private int timeoutMs = 3000;
    private boolean autoCreateItems = true;

    private final ManagedItemProvider itemProvider;
    private final ItemChannelLinkRegistry linkRegistry;
    private final ItemRegistry itemRegistry;

    private final Consumer<BACnetCovNotification.Notification> covListener = this::onCov;
    private final Consumer<BACnetEventNotification.Event> eventListener = this::onEvent;

    public BACnetDeviceHandler(Thing thing, ManagedItemProvider itemProvider, ItemChannelLinkRegistry linkRegistry,
            ItemRegistry itemRegistry) {
        super(thing);
        this.itemProvider = itemProvider;
        this.linkRegistry = linkRegistry;
        this.itemRegistry = itemRegistry;
    }

    @Override
    public void initialize() {
        Object inst = getConfig().get(BACnetBindingConstants.CONFIG_DEVICE_INSTANCE);
        String addr = (String) getConfig().get(BACnetBindingConstants.PROPERTY_ADDRESS);
        Object autoObj = getConfig().get(BACnetBindingConstants.CONFIG_AUTO_CREATE_ITEMS);
        if (autoObj instanceof Boolean) {
            autoCreateItems = (Boolean) autoObj;
        }
        deviceInstance = inst instanceof Number ? ((Number) inst).intValue() : -1;
        if (deviceInstance < 0 || addr == null || addr.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Device instance and address are required");
            return;
        }
        Bridge bridge = getBridge();
        if (bridge == null || !(bridge.getHandler() instanceof BACnetBridgeHandler bh)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
            return;
        }
        BACnetIpClient client = bh.getClient();
        if (client == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            return;
        }
        try {
            this.address = InetAddress.getByName(addr);
        } catch (Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Bad address: " + addr);
            return;
        }
        this.services = client.getServices();
        client.addCovListener(covListener);
        client.addEventListener(eventListener);

        updateStatus(ThingStatus.ONLINE);

        // Discover the device's objects, build channels, and subscribe to COV.
        scheduler.execute(this::buildChannelsAndSubscribe);

        // Poll every 30s as a fallback; COV provides faster live updates.
        pollJob = scheduler.scheduleWithFixedDelay(this::pollAll, 5, 30, TimeUnit.SECONDS);
    }

    private void buildChannelsAndSubscribe() {
        BACnetServices svc = services;
        InetAddress addr = address;
        if (svc == null || addr == null) {
            return;
        }
        java.util.List<int[]> objects = svc.readObjectList(addr, deviceInstance, timeoutMs);
        if (objects.isEmpty()) {
            logger.debug("No objects returned for device {} (object-list read failed or empty)", deviceInstance);
            return;
        }
        org.openhab.core.thing.binding.builder.ThingBuilder builder = editThing();
        for (int[] obj : objects) {
            int type = obj[0];
            int inst = obj[1];
            if (type == BACnetEnums.ObjectType.DEVICE) {
                continue;
            }
            String channelId = BACnetEnums.ObjectType.name(type) + "_" + inst;
            if (getThing().getChannel(channelId) != null) {
                continue;
            }
            String channelTypeId = channelTypeFor(type);
            if (channelTypeId == null) {
                continue;
            }
            org.openhab.core.thing.ChannelUID cuid = new org.openhab.core.thing.ChannelUID(getThing().getUID(),
                    channelId);
            org.openhab.core.thing.type.ChannelTypeUID ctuid = new org.openhab.core.thing.type.ChannelTypeUID(
                    BACnetBindingConstants.BINDING_ID, channelTypeId);
            // Human-readable label from the object's name + engineering unit (like YABE),
            // e.g. "AU_Temp_H00 [°C]". Falls back to the channel id if unavailable.
            String objName = svc.readObjectName(addr, type, inst, timeoutMs);
            String label = (objName != null && !objName.isBlank()) ? objName : channelId;
            boolean isTemperature = false;
            if (BACnetEnums.ObjectType.isAnalog(type) || type == BACnetEnums.ObjectType.SCHEDULE) {
                PropertyValue u = svc.readProperty(addr, type, inst, BACnetEnums.Property.UNITS, timeoutMs);
                int unitCode = u != null ? (int) u.number : -1;
                String unit = BACnetEnums.Units.symbol(unitCode);
                if (!unit.isEmpty()) {
                    label = label + " [" + unit + "]";
                }
                isTemperature = unitCode == BACnetEnums.Units.DEGREES_CELSIUS
                        || unitCode == BACnetEnums.Units.DEGREES_FAHRENHEIT
                        || unitCode == BACnetEnums.Units.DEGREES_KELVIN;
            }
            org.openhab.core.thing.Channel ch = org.openhab.core.thing.binding.builder.ChannelBuilder
                    .create(cuid).withType(ctuid).withLabel(label).build();
            builder.withChannel(ch);
            if (autoCreateItems) {
                createItemAndLink(type, channelTypeId, cuid, channelId, label, isTemperature);
            }

            // Companion alarm trigger channel for this object.
            String alarmId = "alarm_" + channelId;
            if (getThing().getChannel(alarmId) == null) {
                org.openhab.core.thing.ChannelUID acuid = new org.openhab.core.thing.ChannelUID(
                        getThing().getUID(), alarmId);
                org.openhab.core.thing.type.ChannelTypeUID actuid = new org.openhab.core.thing.type.ChannelTypeUID(
                        BACnetBindingConstants.BINDING_ID, "alarm");
                org.openhab.core.thing.Channel alarmCh = org.openhab.core.thing.binding.builder.ChannelBuilder
                        .create(acuid).withKind(org.openhab.core.thing.type.ChannelKind.TRIGGER).withType(actuid)
                        .build();
                builder.withChannel(alarmCh);
            }
            // Subscribe to COV for live updates on this object.
            Bridge bridge = getBridge();
            if (bridge != null && bridge.getHandler() instanceof BACnetBridgeHandler) {
                svc.subscribeCov(addr, deviceInstance, type, inst, false, 0, timeoutMs);
            }
        }
        updateThing(builder.build());
        pollAll();
    }

    private @Nullable String channelTypeFor(int objectType) {
        if (BACnetEnums.ObjectType.isAnalog(objectType)) {
            return BACnetEnums.ObjectType.isWritable(objectType) ? "analogValue" : "analogReadonly";
        }
        if (BACnetEnums.ObjectType.isBinary(objectType)) {
            return BACnetEnums.ObjectType.isWritable(objectType) ? "switchValue" : "contactReadonly";
        }
        if (BACnetEnums.ObjectType.isMultiState(objectType)) {
            return "multiStateValue";
        }
        if (objectType == BACnetEnums.ObjectType.SCHEDULE) {
            // Schedule present-value is writable (override the active value).
            return "analogValue";
        }
        if (objectType == BACnetEnums.ObjectType.CALENDAR) {
            // Calendar present-value is read-only per ASHRAE 135 (derived from
            // the date-list), so it stays a read-only contact channel.
            return "contactReadonly";
        }
        return null;
    }

    // ---------- automatic item creation (semantic, BACnet-derived) ----------

    private void createItemAndLink(int objectType, String channelTypeId, ChannelUID channelUID, String channelId,
            String label, boolean isTemperature) {
        String itemName = itemNameFor(channelId);
        try {
            if (!itemExists(itemName)) {
                GenericItem item = newItem(channelTypeId, itemName);
                if (item == null) {
                    return;
                }
                item.setLabel(label);
                item.addTags(tagsFor(objectType, isTemperature));
                itemProvider.add(item);
            }
            // add() throws if the link already exists — harmless, keeps it idempotent
            linkRegistry.add(new ItemChannelLink(itemName, channelUID));
        } catch (RuntimeException e) {
            logger.debug("Auto item/link for {}: {}", itemName, e.toString());
        }
    }

    private boolean itemExists(String name) {
        try {
            itemRegistry.getItem(name);
            return true;
        } catch (ItemNotFoundException e) {
            return false;
        }
    }

    private @Nullable GenericItem newItem(String channelTypeId, String name) {
        switch (channelTypeId) {
            case "analogReadonly":
            case "analogValue":
            case "multiStateValue":
                return new NumberItem(name);
            case "switchValue":
                return new SwitchItem(name);
            case "contactReadonly":
                return new ContactItem(name);
            default:
                return null;
        }
    }

    /** Semantic tags derived from the BACnet object type (and unit for temperature). */
    private Set<String> tagsFor(int objectType, boolean isTemperature) {
        Set<String> tags = new HashSet<>();
        if (BACnetEnums.ObjectType.isAnalog(objectType)) {
            tags.add(BACnetEnums.ObjectType.isWritable(objectType) ? "Setpoint" : "Measurement");
            if (isTemperature) {
                tags.add("Temperature");
            }
        } else if (BACnetEnums.ObjectType.isBinary(objectType)) {
            tags.add(BACnetEnums.ObjectType.isWritable(objectType) ? "Switch" : "Status");
        } else if (objectType == BACnetEnums.ObjectType.SCHEDULE) {
            tags.add("Setpoint");
        } else {
            tags.add("Status"); // multi-state, calendar, ...
        }
        return tags;
    }

    private String itemNameFor(String channelId) {
        return "bacnet_" + deviceInstance + "_" + channelId;
    }

    @Override
    public void handleRemoval() {
        // Remove the items + links we auto-created, so deleting the Thing cleans up.
        try {
            linkRegistry.removeLinksForThing(getThing().getUID());
        } catch (RuntimeException e) {
            logger.debug("removeLinksForThing failed: {}", e.toString());
        }
        for (org.openhab.core.thing.Channel ch : getThing().getChannels()) {
            if (ch.getKind() == org.openhab.core.thing.type.ChannelKind.TRIGGER) {
                continue;
            }
            try {
                itemProvider.remove(itemNameFor(ch.getUID().getId()));
            } catch (RuntimeException ignored) {
                // not created by us / already gone
            }
        }
        updateStatus(ThingStatus.REMOVED);
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> job = pollJob;
        if (job != null) {
            job.cancel(true);
            pollJob = null;
        }
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof BACnetBridgeHandler bh) {
            BACnetIpClient client = bh.getClient();
            if (client != null) {
                client.removeCovListener(covListener);
                client.removeEventListener(eventListener);
            }
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        BACnetServices svc = services;
        InetAddress addr = address;
        if (svc == null || addr == null) {
            return;
        }
        String id = channelUID.getId();
        if (command instanceof RefreshType) {
            pollAll();
            return;
        }
        // Channel ids follow the pattern <objectType>_<instance>, e.g. "ao_3".
        ParsedChannel pc = ParsedChannel.parse(id);
        if (pc == null) {
            return;
        }
        if (!BACnetEnums.ObjectType.isWritable(pc.objectType)) {
            logger.debug("Channel {} is not writable", id);
            return;
        }
        if (BACnetEnums.ObjectType.isAnalog(pc.objectType) && command instanceof DecimalType dt) {
            svc.writeReal(addr, pc.objectType, pc.instance, BACnetEnums.Property.PRESENT_VALUE,
                    dt.floatValue(), 8, timeoutMs);
        } else if (BACnetEnums.ObjectType.isBinary(pc.objectType) && command instanceof OnOffType onOff) {
            svc.writeEnumerated(addr, pc.objectType, pc.instance, BACnetEnums.Property.PRESENT_VALUE,
                    onOff == OnOffType.ON ? 1 : 0, 8, timeoutMs);
        } else if (BACnetEnums.ObjectType.isMultiState(pc.objectType) && command instanceof DecimalType dt) {
            svc.writeEnumerated(addr, pc.objectType, pc.instance, BACnetEnums.Property.PRESENT_VALUE,
                    dt.longValue(), 8, timeoutMs);
        } else if (pc.objectType == BACnetEnums.ObjectType.SCHEDULE && command instanceof DecimalType dt) {
            // Schedule present-value is written without a priority (schedules
            // have no priority array); numeric schedules only.
            svc.writeReal(addr, pc.objectType, pc.instance, BACnetEnums.Property.PRESENT_VALUE,
                    dt.floatValue(), 0, timeoutMs);
        }
    }

    private void pollAll() {
        BACnetServices svc = services;
        InetAddress addr = address;
        if (svc == null || addr == null) {
            return;
        }
        for (org.openhab.core.thing.Channel ch : getThing().getChannels()) {
            ParsedChannel pc = ParsedChannel.parse(ch.getUID().getId());
            if (pc == null) {
                continue;
            }
            PropertyValue v = svc.readProperty(addr, pc.objectType, pc.instance,
                    BACnetEnums.Property.PRESENT_VALUE, timeoutMs);
            if (v != null) {
                updateState(ch.getUID(), toState(pc.objectType, v));
            }
        }
    }

    private void onCov(BACnetCovNotification.Notification n) {
        if (n.initiatingDevice != deviceInstance) {
            return;
        }
        PropertyValue v = n.value;
        if (v == null) {
            return;
        }
        String channelId = BACnetEnums.ObjectType.name(n.objectType) + "_" + n.objectInstance;
        updateState(channelId, toState(n.objectType, v));
        logger.debug("COV update {}#{} -> {}", BACnetEnums.ObjectType.name(n.objectType), n.objectInstance, v);
    }

    private void onEvent(BACnetEventNotification.Event e) {
        if (e.initiatingDevice != deviceInstance) {
            return;
        }
        String channelId = "alarm_" + BACnetEnums.ObjectType.name(e.objectType) + "_" + e.objectInstance;
        triggerChannel(channelId, BACnetEnums.EventState.name(e.toState));
        logger.info("BACnet alarm: device {} object {}#{} state {} -> {}", e.initiatingDevice,
                BACnetEnums.ObjectType.name(e.objectType), e.objectInstance,
                BACnetEnums.EventState.name(e.fromState), BACnetEnums.EventState.name(e.toState));
    }

    private State toState(int objectType, PropertyValue v) {
        if (BACnetEnums.ObjectType.isBinary(objectType)) {
            return v.number != 0 ? OnOffType.ON : OnOffType.OFF;
        }
        switch (v.type) {
            case REAL:
            case UNSIGNED:
            case ENUMERATED:
                return new DecimalType(v.number);
            case BOOLEAN:
                return v.bool ? OnOffType.ON : OnOffType.OFF;
            case STRING:
                String t = v.text;
                return new StringType(t != null ? t : "");
            default:
                return new DecimalType(v.number);
        }
    }

    /** Parses a channel id of the form "ao_3" into object type + instance. */
    private static class ParsedChannel {
        final int objectType;
        final int instance;

        ParsedChannel(int objectType, int instance) {
            this.objectType = objectType;
            this.instance = instance;
        }

        static @Nullable ParsedChannel parse(String channelId) {
            int us = channelId.lastIndexOf('_');
            if (us < 0) {
                return null;
            }
            String typeName = channelId.substring(0, us);
            int type = typeNameToCode(typeName);
            if (type < 0) {
                return null;
            }
            try {
                int inst = Integer.parseInt(channelId.substring(us + 1));
                return new ParsedChannel(type, inst);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        static int typeNameToCode(String n) {
            switch (n) {
                case "ai": return BACnetEnums.ObjectType.ANALOG_INPUT;
                case "ao": return BACnetEnums.ObjectType.ANALOG_OUTPUT;
                case "av": return BACnetEnums.ObjectType.ANALOG_VALUE;
                case "bi": return BACnetEnums.ObjectType.BINARY_INPUT;
                case "bo": return BACnetEnums.ObjectType.BINARY_OUTPUT;
                case "bv": return BACnetEnums.ObjectType.BINARY_VALUE;
                case "msi": return BACnetEnums.ObjectType.MULTI_STATE_INPUT;
                case "mso": return BACnetEnums.ObjectType.MULTI_STATE_OUTPUT;
                case "msv": return BACnetEnums.ObjectType.MULTI_STATE_VALUE;
                case "schedule": return BACnetEnums.ObjectType.SCHEDULE;
                case "calendar": return BACnetEnums.ObjectType.CALENDAR;
                default: return -1;
            }
        }
    }
}
