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
package org.apache.plc4x.java.modbus.tcpserver;

import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.spi.connection.DefaultNettyPlcConnection;
import org.apache.plc4x.java.transport.tcp.server.DeviceChannelRegistry;
import org.apache.plc4x.java.transport.tcp.server.TcpServerChannelFactory;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Helper class for accessing Modbus Server specific functionality.
 *
 * <p>Supports both Modbus TCP Server and Modbus RTU Server connections.</p>
 *
 * @since 0.14.0
 */
public class ModbusTcpServerConnection {

    private final PlcConnection connection;
    private final DeviceChannelRegistry deviceRegistry;

    /**
     * Creates a ModbusTcpServerConnection wrapper from a PlcConnection.
     *
     * @param connection the PlcConnection (must be a Modbus TCP Server connection)
     * @return the wrapper instance
     * @throws PlcRuntimeException if the connection is not a Modbus TCP Server connection
     */
    public static ModbusTcpServerConnection of(PlcConnection connection) {
        return new ModbusTcpServerConnection(connection);
    }

    private ModbusTcpServerConnection(PlcConnection connection) {
        this.connection = connection;

        if (!(connection instanceof DefaultNettyPlcConnection)) {
            throw new PlcRuntimeException(
                "Connection is not a DefaultNettyPlcConnection. " +
                "Make sure you're using a modbus-tcp-server:// or modbus-rtu-server:// connection URL.");
        }

        DefaultNettyPlcConnection nettyConnection = (DefaultNettyPlcConnection) connection;

        if (!(nettyConnection.getChannelFactory() instanceof TcpServerChannelFactory)) {
            throw new PlcRuntimeException(
                "Connection is not using TcpServerChannelFactory. " +
                "Make sure you're using a modbus-tcp-server:// or modbus-rtu-server:// connection URL.");
        }

        TcpServerChannelFactory channelFactory = (TcpServerChannelFactory) nettyConnection.getChannelFactory();
        this.deviceRegistry = channelFactory.getDeviceRegistry();

        if (this.deviceRegistry == null) {
            throw new PlcRuntimeException("Device registry not initialized. Connection may not be fully established.");
        }
    }

    /**
     * Gets the underlying PlcConnection.
     *
     * @return the PlcConnection
     */
    public PlcConnection getConnection() {
        return connection;
    }

    /**
     * Gets the device channel registry.
     *
     * @return the device registry
     */
    public DeviceChannelRegistry getDeviceRegistry() {
        return deviceRegistry;
    }

    // ========================================
    // Device Event Subscription
    // ========================================

    /**
     * Subscribes to device connection events.
     *
     * <p>The callback is invoked whenever a new device successfully
     * completes registration and connects to the server.</p>
     *
     * @param listener the event listener
     * @return this instance for method chaining
     */
    public ModbusTcpServerConnection onDeviceConnected(Consumer<DeviceEvent> listener) {
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
     * Subscribes to device disconnection events.
     *
     * <p>The callback is invoked whenever a device disconnects
     * from the server (either gracefully or due to connection loss).</p>
     *
     * @param listener the event listener
     * @return this instance for method chaining
     */
    public ModbusTcpServerConnection onDeviceDisconnected(Consumer<DeviceEvent> listener) {
        deviceRegistry.addDisconnectionListener(info ->
            listener.accept(new DeviceEvent(
                info.getDeviceId(),
                info.getRemoteAddress(),
                info.getRegisteredAt(),
                DeviceEvent.Type.DISCONNECTED
            ))
        );
        return this;
    }

    // ========================================
    // Device Status Queries
    // ========================================

    /**
     * Gets all currently connected device IDs.
     *
     * @return an unmodifiable set of device IDs
     */
    public Set<String> getConnectedDevices() {
        return deviceRegistry.getRegisteredDevices();
    }

    /**
     * Gets the number of currently connected devices.
     *
     * @return the device count
     */
    public int getConnectedDeviceCount() {
        return deviceRegistry.getDeviceCount();
    }

    /**
     * Checks if a specific device is currently connected.
     *
     * @param deviceId the device identifier
     * @return true if the device is connected
     */
    public boolean isDeviceConnected(String deviceId) {
        return deviceRegistry.isRegistered(deviceId);
    }

    /**
     * Gets detailed information about a connected device.
     *
     * @param deviceId the device identifier
     * @return the device info, or null if not connected
     */
    public DeviceInfo getDeviceInfo(String deviceId) {
        DeviceChannelRegistry.DeviceInfo info = deviceRegistry.getDeviceInfo(deviceId);
        if (info == null) {
            return null;
        }
        return new DeviceInfo(
            info.getDeviceId(),
            info.getRemoteAddress(),
            info.getRegisteredAt(),
            info.isActive()
        );
    }

    /**
     * Disconnects a specific device from the server.
     *
     * @param deviceId the device identifier
     * @return true if the device was disconnected, false if it wasn't connected
     */
    public boolean disconnectDevice(String deviceId) {
        return deviceRegistry.unregister(deviceId);
    }

    // ========================================
    // Inner Classes
    // ========================================

    /**
     * Event object for device connection/disconnection events.
     */
    public static class DeviceEvent {

        public enum Type {
            CONNECTED,
            DISCONNECTED
        }

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

        /**
         * Gets the device identifier (from registration packet).
         */
        public String getDeviceId() {
            return deviceId;
        }

        /**
         * Gets the remote address (IP and port) of the device.
         */
        public InetSocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        /**
         * Gets the remote IP address as a string.
         */
        public String getRemoteIp() {
            return remoteAddress.getAddress().getHostAddress();
        }

        /**
         * Gets the remote port.
         */
        public int getRemotePort() {
            return remoteAddress.getPort();
        }

        /**
         * Gets the timestamp when this event occurred.
         * For CONNECTED events, this is the registration time.
         * For DISCONNECTED events, this is the original registration time.
         */
        public Instant getTimestamp() {
            return timestamp;
        }

        /**
         * Gets the event type.
         */
        public Type getType() {
            return type;
        }

        @Override
        public String toString() {
            return String.format("DeviceEvent{type=%s, deviceId='%s', remote=%s, time=%s}",
                type, deviceId, remoteAddress, timestamp);
        }
    }

    /**
     * Information about a connected device.
     */
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

        /**
         * Gets the device identifier.
         */
        public String getDeviceId() {
            return deviceId;
        }

        /**
         * Gets the remote address of the device.
         */
        public InetSocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        /**
         * Gets the remote IP address as a string.
         */
        public String getRemoteIp() {
            return remoteAddress.getAddress().getHostAddress();
        }

        /**
         * Gets the remote port.
         */
        public int getRemotePort() {
            return remoteAddress.getPort();
        }

        /**
         * Gets the time when the device connected.
         */
        public Instant getConnectedAt() {
            return connectedAt;
        }

        /**
         * Checks if the device connection is still active.
         */
        public boolean isActive() {
            return active;
        }

        @Override
        public String toString() {
            return String.format("DeviceInfo{deviceId='%s', remote=%s, connectedAt=%s, active=%s}",
                deviceId, remoteAddress, connectedAt, active);
        }
    }
}
