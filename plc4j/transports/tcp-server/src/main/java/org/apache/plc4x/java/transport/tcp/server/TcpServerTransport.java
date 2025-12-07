/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.plc4x.java.transport.tcp.server;

import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.spi.configuration.HasConfiguration;
import org.apache.plc4x.java.spi.configuration.PlcTransportConfiguration;
import org.apache.plc4x.java.spi.connection.ChannelFactory;
import org.apache.plc4x.java.spi.transport.Transport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TCP Server Transport for accepting inbound connections from DTU devices.
 *
 * <p>This transport operates in passive/server mode, listening on a specified
 * port for incoming connections from DTU (Data Transfer Unit) devices. Each
 * incoming connection goes through a registration process where the device
 * sends a registration packet containing its identifier.</p>
 *
 * <h3>Connection URL Format:</h3>
 * <pre>
 * protocol:tcpserver://[bind-address]:port[?options]
 * </pre>
 *
 * <h3>Examples:</h3>
 * <pre>{@code
 * // Listen on all interfaces, port 502
 * modbus-tcp-server:tcpserver://0.0.0.0:502
 *
 * // Listen on specific interface
 * modbus-tcp-server:tcpserver://192.168.1.100:502
 *
 * // With registration configuration
 * modbus-tcp-server:tcpserver://0.0.0.0:502?registration-type=fixed&registration-length=16
 * }</pre>
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Multi-client connection management</li>
 *   <li>Flexible registration packet parsing</li>
 *   <li>Device ID to channel mapping</li>
 *   <li>Connection lifecycle management</li>
 * </ul>
 *
 * @see TcpServerChannelFactory
 * @see TcpServerTransportConfiguration
 * @since 0.14.0
 */
public class TcpServerTransport implements Transport, HasConfiguration<TcpServerTransportConfiguration> {

    /**
     * Pattern for parsing TCP server transport configuration.
     * Format: [bind-address]:port
     * Examples: 0.0.0.0:502, 192.168.1.1:8502, :502 (default bind to all)
     */
    private static final Pattern TRANSPORT_PATTERN = Pattern.compile(
        "^(?:(?<ip>[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3})|(?<hostname>[a-zA-Z0-9.\\-]*))?:(?<port>[0-9]{1,5})$");

    private static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

    private TcpServerTransportConfiguration configuration;

    @Override
    public String getTransportCode() {
        return "tcpserver";
    }

    @Override
    public String getTransportName() {
        return "TCP Server Transport";
    }

    @Override
    public void setConfiguration(TcpServerTransportConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public ChannelFactory createChannelFactory(String transportConfig) {
        SocketAddress bindAddress = parseTransportConfig(transportConfig);

        TcpServerChannelFactory channelFactory = new TcpServerChannelFactory(bindAddress);
        if (configuration != null) {
            channelFactory.setConfiguration(configuration);
        }
        return channelFactory;
    }

    @Override
    public Class<? extends PlcTransportConfiguration> getTransportConfigType() {
        return TcpServerTransportConfiguration.class;
    }

    /**
     * Parses the transport configuration string to extract bind address and port.
     *
     * @param transportConfig the configuration string (e.g., "0.0.0.0:502")
     * @return the parsed socket address
     * @throws PlcRuntimeException if the configuration is invalid
     */
    private SocketAddress parseTransportConfig(String transportConfig) {
        if (transportConfig == null || transportConfig.isBlank()) {
            throw new PlcRuntimeException("Transport configuration must not be empty");
        }

        Matcher matcher = TRANSPORT_PATTERN.matcher(transportConfig.trim());
        if (!matcher.matches()) {
            throw new PlcRuntimeException(
                "Invalid TCP server transport configuration: " + transportConfig +
                ". Expected format: [bind-address]:port (e.g., 0.0.0.0:502)");
        }

        String ip = matcher.group("ip");
        String hostname = matcher.group("hostname");
        String portStr = matcher.group("port");

        // Determine bind address
        String bindAddress;
        if (ip != null && !ip.isBlank()) {
            bindAddress = ip;
        } else if (hostname != null && !hostname.isBlank()) {
            bindAddress = hostname;
        } else {
            bindAddress = (configuration != null && configuration.getBindAddress() != null)
                ? configuration.getBindAddress()
                : DEFAULT_BIND_ADDRESS;
        }

        // Parse port
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            throw new PlcRuntimeException("Invalid port number: " + portStr);
        }

        if (port < 1 || port > 65535) {
            throw new PlcRuntimeException("Port must be between 1 and 65535: " + port);
        }

        return new InetSocketAddress(bindAddress, port);
    }
}
