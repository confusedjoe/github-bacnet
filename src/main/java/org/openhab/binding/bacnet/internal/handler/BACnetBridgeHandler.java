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

import java.io.IOException;
import java.net.InetAddress;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bacnet.internal.BACnetBindingConstants;
import org.openhab.binding.bacnet.internal.protocol.BACnetIpClient;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BACnetBridgeHandler} owns the UDP socket and exposes the client
 * to the discovery service.
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public class BACnetBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(BACnetBridgeHandler.class);

    private @Nullable BACnetIpClient client;
    private int discoveryTimeout = 5000;
    private @Nullable String broadcastAddress;
    private boolean backgroundDiscovery = true;
    private int discoveryInterval = 15; // minutes

    public BACnetBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        String broadcast = (String) getConfig().get(BACnetBindingConstants.CONFIG_BROADCAST);
        Object portObj = getConfig().get(BACnetBindingConstants.CONFIG_LOCAL_PORT);
        Object timeoutObj = getConfig().get(BACnetBindingConstants.CONFIG_DISCOVERY_TIMEOUT);
        int port = portObj instanceof Number ? ((Number) portObj).intValue() : 47808;
        if (timeoutObj instanceof Number) {
            discoveryTimeout = ((Number) timeoutObj).intValue() * 1000;
        }
        Object bgObj = getConfig().get(BACnetBindingConstants.CONFIG_BACKGROUND_DISCOVERY);
        if (bgObj instanceof Boolean) {
            backgroundDiscovery = (Boolean) bgObj;
        }
        Object intervalObj = getConfig().get(BACnetBindingConstants.CONFIG_DISCOVERY_INTERVAL);
        if (intervalObj instanceof Number) {
            discoveryInterval = ((Number) intervalObj).intValue();
        }
        if (broadcast == null || broadcast.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Broadcast address must be set, e.g. 192.168.134.255");
            return;
        }
        try {
            InetAddress addr = InetAddress.getByName(broadcast);
            BACnetIpClient c = new BACnetIpClient(addr, port);
            c.open();
            c.startDispatch();
            this.client = c;
            this.broadcastAddress = broadcast;
            updateStatus(ThingStatus.ONLINE);
            logger.info("BACnet bridge online (broadcast {}, port {})", broadcast, port);
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    @Override
    public void dispose() {
        BACnetIpClient c = client;
        if (c != null) {
            c.close();
            client = null;
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Bridge itself has no channels.
    }

    public @Nullable BACnetIpClient getClient() {
        return client;
    }

    public @Nullable String getBroadcastAddress() {
        return broadcastAddress;
    }

    public int getDiscoveryTimeout() {
        return discoveryTimeout;
    }

    public boolean isBackgroundDiscovery() {
        return backgroundDiscovery;
    }

    /** Background re-scan interval in minutes. */
    public int getDiscoveryInterval() {
        return discoveryInterval;
    }
}
