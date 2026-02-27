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
package org.apache.plc4x.java.dlt645.server;

import io.netty.channel.Channel;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.dlt645.server.protocol.Dlt645ServerProtocolLogic;
import org.apache.plc4x.java.spi.connection.AbstractPlcConnection;
import org.apache.plc4x.java.spi.connection.DefaultNettyPlcConnection;
import org.apache.plc4x.java.transport.tcp.server.DeviceChannelRegistry;
import org.apache.plc4x.java.transport.tcp.server.DeviceConversationHandler;
import org.apache.plc4x.java.transport.tcp.server.TcpServerChannelFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * DL/T 645-2007 Server Connection facade.
 * <p>
 * Wraps a server-mode {@link PlcConnection} and provides:
 * <ul>
 *   <li>Device management: discovery, events, status queries</li>
 *   <li>Sub-connection factory: {@link #getDeviceConnection(String)} returns a
 *       {@link Dlt645DeviceConnection} that is a standard {@link PlcConnection}
 *       bound to one specific device — clean tag format, no device-id in tags</li>
 * </ul>
 *
 * <pre>{@code
 * PlcConnection conn = manager.getConnection("dlt645-server:tcpserver://0.0.0.0:8899?...");
 * Dlt645ServerConnection server = Dlt645ServerConnection.of(conn);
 *
 * // Event-driven device discovery
 * server.onDeviceConnected(event -> {
 *     PlcConnection device = server.getDeviceConnection(event.getDeviceId());
 *     device.readRequestBuilder()
 *         .addTagAddress("energy", "00010000")
 *         .build().execute();
 * });
 * }</pre>
 */
public class Dlt645ServerConnection {

    private final PlcConnection serverConnection;
    private final DeviceChannelRegistry deviceRegistry;
    private final byte[] password;
    private final byte[] operatorCode;
    private final Duration requestTimeout;
    private final ConcurrentHashMap<String, Dlt645DeviceConnection> deviceConnections = new ConcurrentHashMap<>();

    /**
     * Create a Dlt645ServerConnection from a server-mode PlcConnection.
     *
     * @param connection a dlt645-server PlcConnection (must be connected)
     * @return the server connection facade
     * @throws PlcRuntimeException if the connection is not a DL/T 645 server connection
     */
    public static Dlt645ServerConnection of(PlcConnection connection) {
        return new Dlt645ServerConnection(connection);
    }

    private Dlt645ServerConnection(PlcConnection connection) {
        this.serverConnection = connection;

        if (!(connection instanceof DefaultNettyPlcConnection)) {
            throw new PlcRuntimeException(
                "Connection is not a DefaultNettyPlcConnection. " +
                "Use a dlt645-server:tcpserver:// connection URL.");
        }

        var nettyConnection = (DefaultNettyPlcConnection) connection;

        if (!(nettyConnection.getChannelFactory() instanceof TcpServerChannelFactory)) {
            throw new PlcRuntimeException(
                "Connection is not using TcpServerChannelFactory. " +
                "Use a dlt645-server:tcpserver:// connection URL.");
        }

        var channelFactory = (TcpServerChannelFactory) nettyConnection.getChannelFactory();
        this.deviceRegistry = channelFactory.getDeviceRegistry();
        if (this.deviceRegistry == null) {
            throw new PlcRuntimeException("Device registry not initialized. Connection may not be fully established.");
        }

        // Extract auth config from protocol logic
        var protocol = ((AbstractPlcConnection) connection).getProtocol();
        if (protocol instanceof Dlt645ServerProtocolLogic) {
            var serverLogic = (Dlt645ServerProtocolLogic) protocol;
            this.password = serverLogic.getPassword();
            this.operatorCode = serverLogic.getOperatorCode();
            this.requestTimeout = serverLogic.getRequestTimeout();
        } else {
            this.password = new byte[4];
            this.operatorCode = new byte[4];
            this.requestTimeout = Duration.ofMillis(10_000);
        }
    }

    // ========================================
    // Sub-connection factory (core of Option C)
    // ========================================

    /**
     * Get a device-scoped sub-connection.
     * <p>
     * The returned {@link Dlt645DeviceConnection} implements {@link PlcConnection},
     * so it can be used exactly like a standard PLC4X connection — with clean tag format,
     * no device-id needed in tags.
     * <p>
     * Connections are cached: calling this method twice with the same deviceId returns the same instance.
     *
     * @param deviceId the device identifier (from registration packet)
     * @return a PlcConnection bound to the specific device
     * @throws PlcRuntimeException if the device is not connected
     */
    public Dlt645DeviceConnection getDeviceConnection(String deviceId) {
        return deviceConnections.compute(deviceId, (id, existing) -> {
            // Reuse existing if still connected
            if (existing != null && existing.isConnected()) {
                return existing;
            }

            // Create new device connection
            Channel channel = deviceRegistry.getChannel(id);
            if (channel == null || !channel.isActive()) {
                throw new PlcRuntimeException("Device not connected: " + id);
            }

            @SuppressWarnings("unchecked")
            DeviceConversationHandler<org.apache.plc4x.java.dlt645.readwrite.Dlt645Frame> handler =
                DeviceConversationHandler.getFromChannel(channel);
            if (handler == null) {
                throw new PlcRuntimeException("Device handler not found for: " + id);
            }

            return new Dlt645DeviceConnection(id, channel, handler,
                password, operatorCode, requestTimeout);
        });
    }

    // ========================================
    // Device Event Subscription
    // ========================================

    /**
     * Subscribe to device connection events.
     */
    public Dlt645ServerConnection onDeviceConnected(Consumer<DeviceEvent> listener) {
        deviceRegistry.addRegistrationListener(info ->
            listener.accept(new DeviceEvent(
                info.getDeviceId(),
                info.getRemoteAddress(),
                info.getRegisteredAt(),
                DeviceEvent.Type.CONNECTED
            ))
        );
        return this;
    }

    /**
     * Subscribe to device disconnection events.
     */
    public Dlt645ServerConnection onDeviceDisconnected(Consumer<DeviceEvent> listener) {
        deviceRegistry.addDisconnectionListener(info -> {
            // Clean up cached connection
            deviceConnections.remove(info.getDeviceId());
            listener.accept(new DeviceEvent(
                info.getDeviceId(),
                info.getRemoteAddress(),
                info.getRegisteredAt(),
                DeviceEvent.Type.DISCONNECTED
            ));
        });
        return this;
    }

    // ========================================
    // Device Status Queries
    // ========================================

    public PlcConnection getConnection() {
        return serverConnection;
    }

    public DeviceChannelRegistry getDeviceRegistry() {
        return deviceRegistry;
    }

    public Set<String> getConnectedDevices() {
        return deviceRegistry.getRegisteredDevices();
    }

    public int getConnectedDeviceCount() {
        return deviceRegistry.getDeviceCount();
    }

    public boolean isDeviceConnected(String deviceId) {
        return deviceRegistry.isRegistered(deviceId);
    }

    public DeviceInfo getDeviceInfo(String deviceId) {
        DeviceChannelRegistry.DeviceInfo info = deviceRegistry.getDeviceInfo(deviceId);
        if (info == null) {
            return null;
        }
        return new DeviceInfo(info.getDeviceId(), info.getRemoteAddress(),
            info.getRegisteredAt(), info.isActive());
    }

    public boolean disconnectDevice(String deviceId) {
        deviceConnections.remove(deviceId);
        return deviceRegistry.unregister(deviceId);
    }

    // ========================================
    // Inner Classes
    // ========================================

    public static class DeviceEvent {
        public enum Type { CONNECTED, DISCONNECTED }

        private final String deviceId;
        private final InetSocketAddress remoteAddress;
        private final Instant timestamp;
        private final Type type;

        DeviceEvent(String deviceId, InetSocketAddress remoteAddress, Instant timestamp, Type type) {
            this.deviceId = deviceId;
            this.remoteAddress = remoteAddress;
            this.timestamp = timestamp;
            this.type = type;
        }

        public String getDeviceId() { return deviceId; }
        public InetSocketAddress getRemoteAddress() { return remoteAddress; }
        public String getRemoteIp() { return remoteAddress.getAddress().getHostAddress(); }
        public int getRemotePort() { return remoteAddress.getPort(); }
        public Instant getTimestamp() { return timestamp; }
        public Type getType() { return type; }

        @Override
        public String toString() {
            return String.format("DeviceEvent{type=%s, deviceId='%s', remote=%s}", type, deviceId, remoteAddress);
        }
    }

    public static class DeviceInfo {
        private final String deviceId;
        private final InetSocketAddress remoteAddress;
        private final Instant connectedAt;
        private final boolean active;

        DeviceInfo(String deviceId, InetSocketAddress remoteAddress, Instant connectedAt, boolean active) {
            this.deviceId = deviceId;
            this.remoteAddress = remoteAddress;
            this.connectedAt = connectedAt;
            this.active = active;
        }

        public String getDeviceId() { return deviceId; }
        public InetSocketAddress getRemoteAddress() { return remoteAddress; }
        public String getRemoteIp() { return remoteAddress.getAddress().getHostAddress(); }
        public int getRemotePort() { return remoteAddress.getPort(); }
        public Instant getConnectedAt() { return connectedAt; }
        public boolean isActive() { return active; }

        @Override
        public String toString() {
            return String.format("DeviceInfo{deviceId='%s', remote=%s, active=%s}", deviceId, remoteAddress, active);
        }
    }
}
