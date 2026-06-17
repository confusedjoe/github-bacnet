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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigOptionProvider;
import org.openhab.core.config.core.ParameterOption;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supplies the bridge's {@code broadcastAddress} parameter with a drop-down of
 * the local machine's network interfaces — like YABE's interface picker — so the
 * user just selects the network the BACnet devices are on instead of typing a
 * broadcast address by hand.
 *
 * Each option's value is the subnet broadcast address (what the bridge actually
 * sends Who-Is to); the label also shows the interface name and local IP/prefix.
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
@Component(service = ConfigOptionProvider.class)
public class BACnetConfigOptionProvider implements ConfigOptionProvider {

    private final Logger logger = LoggerFactory.getLogger(BACnetConfigOptionProvider.class);

    @Override
    public @Nullable Collection<ParameterOption> getParameterOptions(URI uri, String param, @Nullable String context,
            @Nullable Locale locale) {
        if (!"broadcastAddress".equals(param) || !uri.toString().contains("bacnet:bridge")) {
            return null;
        }
        // value -> label, de-duplicated and insertion-ordered
        Map<String, String> options = new LinkedHashMap<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    InetAddress bc = ia.getBroadcast();
                    if (!(addr instanceof Inet4Address) || bc == null) {
                        continue;
                    }
                    // skip link-local (169.254.x.x) — never a real BACnet network
                    if (addr.getHostAddress().startsWith("169.254.")) {
                        continue;
                    }
                    String value = bc.getHostAddress();
                    String label = value + "  (" + ni.getDisplayName() + " — " + addr.getHostAddress() + "/"
                            + ia.getNetworkPrefixLength() + ")";
                    options.putIfAbsent(value, label);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not enumerate network interfaces: {}", e.getMessage());
            return null;
        }
        List<ParameterOption> result = new ArrayList<>();
        for (Map.Entry<String, String> e : options.entrySet()) {
            result.add(new ParameterOption(e.getKey(), e.getValue()));
        }
        return result;
    }
}
