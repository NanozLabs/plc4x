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

import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.dlt645.server.config.Dlt645ServerConfiguration;
import org.apache.plc4x.java.dlt645.tag.Dlt645TagHandler;
import org.apache.plc4x.java.spi.drivers.tags.PlcTagHandler;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.values.DefaultPlcValueHandler;
import org.apache.plc4x.java.spi.values.PlcValueHandler;
import org.apache.plc4x.java.transport.tcpserver.RoutingTransportInstance;
import org.apache.plc4x.java.transport.tcpserver.TcpServerChannelRegistry;
import org.apache.plc4x.java.transport.tcpserver.TcpServerTransportInstance;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.subscriptionemulation.PollingSubscriptionConnectionBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * DL/T 645-2007 Server connection (reverse TCP mode).
 * <p>
 * Listens (via the {@code tcp-server} transport) for inbound connections from smart meters
 * or DTU devices, each of which registers its device-id. This connection is the server-side
 * management handle and provides:
 * <ul>
 *   <li>Device management: discovery, events, status queries</li>
 *   <li>Sub-connection factory: {@link #getDeviceConnection(String)} returns a
 *       {@link Dlt645DeviceConnection} that is a standard {@code PlcConnection} bound to one
 *       specific device — clean tag format, no device-id in tags</li>
 * </ul>
 *
 * <pre>{@code
 * PlcConnection conn = manager.getConnection("dlt645-server:tcp-server://0.0.0.0:8899?...");
 * Dlt645ServerConnection server = (Dlt645ServerConnection) conn;
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
public class Dlt645ServerConnection extends PollingSubscriptionConnectionBase<Dlt645ServerConfiguration> {

    private static final Logger logger = LoggerFactory.getLogger(Dlt645ServerConnection.class);

    private final ConcurrentHashMap<String, Dlt645DeviceConnection> deviceConnections = new ConcurrentHashMap<>();
    private TcpServerChannelRegistry deviceRegistry;

    public Dlt645ServerConnection(Dlt645ServerConfiguration configuration,
                                  TransportInstance<?> transportInstance,
                                  AuditLog auditLog) {
        super(configuration, transportInstance, auditLog);
    }

    @Override
    protected PlcTagHandler getTagHandler() {
        return new Dlt645TagHandler();
    }

    @Override
    protected PlcValueHandler getValueHandler() {
        return new DefaultPlcValueHandler();
    }

    @Override
    protected void onConnect() throws PlcConnectionException {
        // The tcp-server transport hands the driver a RoutingTransportInstance that
        // multiplexes all registered device connections and exposes the shared registry.
        if (!(getTransportInstance() instanceof RoutingTransportInstance routing)) {
            throw new PlcConnectionException(
                "DL/T 645 server mode requires the 'tcp-server' transport");
        }
        this.deviceRegistry = routing.getRegistry();
        if (this.deviceRegistry == null) {
            throw new PlcConnectionException("Device registry not initialized");
        }

        // Device events: a fresh Dlt645DeviceConnection is created lazily by getDeviceConnection().
        this.deviceRegistry.addRegistrationListener(info -> {
            logger.info("DL/T 645 device connected: {}", info.getDeviceId());
        });
        this.deviceRegistry.addDisconnectionListener(info -> {
            deviceConnections.remove(info.getDeviceId());
            logger.info("DL/T 645 device disconnected: {}", info.getDeviceId());
        });
        logger.info("DL/T 645-2007 server connection established");
    }

    @Override
    public boolean isConnected() {
        // The server connection is a logical view: it stays connected as long as the
        // server socket is alive (the routing instance reports itself open).
        return deviceRegistry != null && getTransportInstance().isOpen();
    }

    // ========================================
    // Sub-connection factory (core of Option C)
    // ========================================

    /**
     * Get a device-scoped sub-connection.
     * <p>
     * The returned {@link Dlt645DeviceConnection} implements {@code PlcConnection}, so it can
     * be used exactly like a standard PLC4X connection — with clean tag format, no device-id
     * needed in tags.
     * <p>
     * Connections are cached: calling this method twice with the same deviceId returns the same instance.
     *
     * @param deviceId the device identifier (from registration packet)
     * @return a PlcConnection bound to the specific device
     * @throws PlcRuntimeException if the device is not connected
     */
    public Dlt645DeviceConnection getDeviceConnection(String deviceId) {
        return deviceConnections.compute(deviceId, (id, existing) -> {
            // Reuse existing if still online.
            if (existing != null && existing.isDeviceOnline()) {
                return existing;
            }
            TcpServerTransportInstance connection = resolveDeviceTransport(id);
            return new Dlt645DeviceConnection(id, connection, getConfiguration(), auditLog);
        });
    }

    private TcpServerTransportInstance resolveDeviceTransport(String deviceId) {
        if (deviceRegistry == null) {
            throw new PlcRuntimeException("Device registry not available");
        }
        TcpServerTransportInstance connection = deviceRegistry.getConnection(deviceId);
        if (connection == null || !connection.isOpen()) {
            throw new PlcRuntimeException("Device not connected: " + deviceId);
        }
        return connection;
    }

    // ========================================
    // Device Event Subscription
    // ========================================

    /**
     * Subscribe to device connection events.
     */
    public Dlt645ServerConnection onDeviceConnected(Consumer<DeviceEvent> listener) {
        if (deviceRegistry == null) {
            throw new IllegalStateException("Connection must be connected before subscribing to device events");
        }
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
        if (deviceRegistry == null) {
            throw new IllegalStateException("Connection must be connected before subscribing to device events");
        }
        deviceRegistry.addDisconnectionListener(info -> {
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

    public TcpServerChannelRegistry getDeviceRegistry() {
        return deviceRegistry;
    }

    public Set<String> getConnectedDevices() {
        return deviceRegistry != null ? deviceRegistry.getRegisteredDevices() : Set.of();
    }

    public int getConnectedDeviceCount() {
        return deviceRegistry != null ? deviceRegistry.getDeviceCount() : 0;
    }

    public boolean isDeviceConnected(String deviceId) {
        return deviceRegistry != null && deviceRegistry.isRegistered(deviceId);
    }

    public DeviceInfo getDeviceInfo(String deviceId) {
        TcpServerChannelRegistry.DeviceInfo info = deviceRegistry != null ? deviceRegistry.getDeviceInfo(deviceId) : null;
        if (info == null) {
            return null;
        }
        return new DeviceInfo(info.getDeviceId(), info.getRemoteAddress(),
            info.getRegisteredAt(), info.isActive());
    }

    public boolean disconnectDevice(String deviceId) {
        deviceConnections.remove(deviceId);
        return deviceRegistry != null && deviceRegistry.unregister(deviceId);
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
