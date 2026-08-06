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
package org.apache.plc4x.java.transport.tcpserver;

import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.spi.transports.api.Transport;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.transports.api.config.TransportConfiguration;
import org.apache.plc4x.java.spi.transports.api.exceptions.TransportException;
import org.apache.plc4x.java.transport.tcpserver.registration.RegistrationPacketParser;
import org.apache.plc4x.java.transport.tcpserver.registration.RegistrationParserFactory;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TCP Server Transport for accepting inbound connections from DTU devices.
 *
 * <p>This transport operates in passive/server mode, listening on a specified address and
 * port for incoming connections from DTU (Data Transfer Unit) devices. Each incoming
 * connection goes through a registration process where the device sends a registration
 * packet containing its identifier.</p>
 *
 * <h3>Connection URL Format:</h3>
 * <pre>
 * protocol:tcp-server://[bind-address]:port[?options]
 * </pre>
 *
 * <h3>Examples:</h3>
 * <pre>{@code
 * // Listen on all interfaces, port 502
 * modbus-tcp-server:tcp-server://0.0.0.0:502
 *
 * // Listen on a specific interface
 * modbus-tcp-server:tcp-server://192.168.1.100:502
 *
 * // With registration configuration
 * modbus-tcp-server:tcp-server://0.0.0.0:502?registration-type=fixed&registration-length=16
 * }</pre>
 *
 * <h3>Architecture (multi-connection pool):</h3>
 * <p>Because the SPI3 driver ↔ transport-instance relationship is 1:1, this transport
 * returns a single {@link RoutingTransportInstance} to the driver. Internally it starts a
 * {@link ServerSocketChannel} accept loop on a virtual thread; each accepted socket is
 * wrapped in a {@link TcpServerTransportInstance} and registered by device ID in a
 * {@link TcpServerChannelRegistry} after the registration handshake completes.</p>
 *
 * @see RoutingTransportInstance
 * @see TcpServerChannelRegistry
 * @see TcpServerTransportConfiguration
 */
public class TcpServerTransport implements Transport<TcpServerTransportConfiguration> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TcpServerTransport.class);

    /**
     * Pattern for parsing TCP server transport configuration.
     * Format: [bind-address]:port
     * Examples: 0.0.0.0:502, 192.168.1.1:8502, :502 (default bind to all)
     */
    private static final Pattern TRANSPORT_PATTERN = Pattern.compile(
        "^(?:(?<ip>[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3})|(?<hostname>[a-zA-Z0-9.\\-]*))?:(?<port>[0-9]{1,5})$");

    private static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

    @Override
    public String getTransportCode() {
        return "tcp-server";
    }

    @Override
    public String getTransportName() {
        return "TCP Server Transport";
    }

    @Override
    public Class<TcpServerTransportConfiguration> getTransportConfigType() {
        return TcpServerTransportConfiguration.class;
    }

    @Override
    public TransportInstance<TcpServerTransportConfiguration> createTransportInstance(
            String transportUrl,
            TransportConfiguration configuration,
            AuditLog auditLog) throws TransportException {
        if (!(configuration instanceof TcpServerTransportConfiguration serverConfiguration)) {
            throw new IllegalArgumentException(String.format("Expected configuration of type %s but got %s",
                TcpServerTransportConfiguration.class.getSimpleName(), configuration.getClass().getSimpleName()));
        }

        SocketAddressHolder address = parseTransportConfig(transportUrl, serverConfiguration);

        // Build the registration parser from the configuration.
        RegistrationPacketParser registrationParser = createRegistrationParser(serverConfiguration);

        // Device registry with the configured duplicate policy.
        TcpServerChannelRegistry.DuplicatePolicy policy = parseDuplicatePolicy(
            serverConfiguration.getDuplicateHandling());
        TcpServerChannelRegistry registry = new TcpServerChannelRegistry(
            policy, serverConfiguration.getMaxConnections());

        // The routing instance handed to the driver.
        RoutingTransportInstance routingInstance = new RoutingTransportInstance(
            serverConfiguration, auditLog, registry);

        // Start the server accept loop.
        ServerSocketChannel serverChannel = startServer(address, serverConfiguration, auditLog);
        if (serverChannel == null) {
            throw new TransportException("Failed to start TCP server on " + address);
        }

        // Expose the actual bound port (relevant when the URL requested an ephemeral port).
        routingInstance.setBoundPort(serverChannel.socket().getLocalPort());

        startAcceptLoop(serverChannel, serverConfiguration, registry, routingInstance, auditLog);

        return routingInstance;
    }

    // ========== Server lifecycle ==========

    /**
     * Binds the server socket.
     *
     * @param address the bind address/port
     * @param configuration the transport configuration
     * @param auditLog the audit log
     * @return the bound server channel, or null on failure
     */
    private ServerSocketChannel startServer(SocketAddressHolder address,
                                            TcpServerTransportConfiguration configuration,
                                            AuditLog auditLog) {
        try {
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            ServerSocket socket = serverChannel.socket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(address.bindAddress, address.port), configuration.getBacklog());
            // Blocking accept loop on a virtual thread.
            serverChannel.configureBlocking(true);
            LOGGER.info("TCP Server started on {}:{}", address.bindAddress, address.port);
            return serverChannel;
        } catch (IOException e) {
            LOGGER.error("Failed to bind TCP server on {}:{}", address.bindAddress, address.port, e);
            return null;
        }
    }

    /**
     * Runs the accept loop on a virtual thread.
     */
    private void startAcceptLoop(ServerSocketChannel serverChannel,
                                 TcpServerTransportConfiguration configuration,
                                 TcpServerChannelRegistry registry,
                                 RoutingTransportInstance routingInstance,
                                 AuditLog auditLog) {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger accepted = new AtomicInteger();

        Thread acceptThread = Thread.ofVirtual()
            .name("TCP-Server-Accept-" + configuration.getBindAddress())
            .start(() -> {
                LOGGER.info("Accept loop started");
                while (running.get()) {
                    try {
                        SocketChannel socketChannel = serverChannel.accept();
                        if (socketChannel == null) {
                            continue;
                        }
                        socketChannel.configureBlocking(true);
                        accepted.incrementAndGet();

                        // Wrap the accepted socket: registration handshake + device registry.
                        DeviceConnectionHandler.create(socketChannel, configuration, registry,
                            createRegistrationParser(configuration), auditLog,
                            onRegistered(routingInstance));
                    } catch (IOException e) {
                        if (running.get()) {
                            LOGGER.error("Error in accept loop", e);
                        }
                        // Server socket closed → stop accepting.
                        break;
                    } catch (Exception e) {
                        LOGGER.error("Failed to initialize accepted connection", e);
                    }
                }
                LOGGER.info("Accept loop stopped");
            });
    }

    private Consumer<TcpServerTransportInstance> onRegistered(RoutingTransportInstance routingInstance) {
        return connection -> routingInstance.onDeviceRegistered(connection);
    }

    // ========== Configuration helpers ==========

    /**
     * Parses the transport configuration string to extract bind address and port.
     *
     * @param transportConfig the configuration string (e.g., "0.0.0.0:502")
     * @param configuration the transport configuration (for defaults)
     * @return the parsed bind address and port
     * @throws PlcRuntimeException if the configuration is invalid
     */
    private SocketAddressHolder parseTransportConfig(String transportConfig,
                                                     TcpServerTransportConfiguration configuration) {
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

        // Determine bind address: explicit value wins, else configured default, else wildcard.
        String bindAddress;
        if (ip != null && !ip.isBlank()) {
            bindAddress = ip;
        } else if (hostname != null && !hostname.isBlank()) {
            bindAddress = hostname;
        } else if (configuration.getBindAddress() != null && !configuration.getBindAddress().isBlank()) {
            bindAddress = configuration.getBindAddress();
        } else {
            bindAddress = DEFAULT_BIND_ADDRESS;
        }

        // The URL port is authoritative. 0 binds an ephemeral port (useful for tests).
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            throw new PlcRuntimeException("Invalid port number: " + portStr);
        }

        if (port == 0) {
            // Ephemeral port: keep 0, the kernel assigns a free port.
        } else if (port < 1 || port > 65535) {
            throw new PlcRuntimeException("Port must be between 1 and 65535: " + port);
        }

        return new SocketAddressHolder(bindAddress, port);
    }

    /**
     * Creates a registration parser based on configuration.
     */
    private RegistrationPacketParser createRegistrationParser(TcpServerTransportConfiguration configuration) {
        Map<String, String> parserConfig = new HashMap<>();
        parserConfig.put(RegistrationParserFactory.KEY_TYPE, configuration.getRegistrationType());
        parserConfig.put(RegistrationParserFactory.KEY_LENGTH,
            String.valueOf(configuration.getRegistrationLength()));
        parserConfig.put(RegistrationParserFactory.KEY_CHARSET, configuration.getRegistrationCharset());
        parserConfig.put(RegistrationParserFactory.KEY_MAX_LENGTH,
            String.valueOf(configuration.getRegistrationMaxLength()));

        if (configuration.getRegistrationPattern() != null) {
            parserConfig.put(RegistrationParserFactory.KEY_PATTERN, configuration.getRegistrationPattern());
        }

        parserConfig.put(RegistrationParserFactory.KEY_PREFIX_BYTES,
            String.valueOf(configuration.getRegistrationPrefixBytes()));
        parserConfig.put(RegistrationParserFactory.KEY_BYTE_ORDER, configuration.getRegistrationByteOrder());
        parserConfig.put(RegistrationParserFactory.KEY_DELIMITER, configuration.getRegistrationDelimiter());

        return RegistrationParserFactory.create(parserConfig);
    }

    /**
     * Parses the duplicate handling policy from string.
     */
    private TcpServerChannelRegistry.DuplicatePolicy parseDuplicatePolicy(String policy) {
        return switch (policy.toLowerCase()) {
            case "replace" -> TcpServerChannelRegistry.DuplicatePolicy.REPLACE;
            case "reject" -> TcpServerChannelRegistry.DuplicatePolicy.REJECT;
            case "allow" -> TcpServerChannelRegistry.DuplicatePolicy.ALLOW;
            default -> TcpServerChannelRegistry.DuplicatePolicy.REPLACE;
        };
    }

    /**
     * Holds the parsed bind address and port.
     */
    private static final class SocketAddressHolder {
        private final String bindAddress;
        private final int port;

        SocketAddressHolder(String bindAddress, int port) {
            this.bindAddress = Objects.requireNonNull(bindAddress);
            this.port = port;
        }

        @Override
        public String toString() {
            return bindAddress + ":" + port;
        }
    }
}
