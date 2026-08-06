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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Thread-safe registry for managing device-to-connection mappings in TCP Server mode.
 *
 * <p>This registry maintains the association between device identifiers and their
 * corresponding {@link TcpServerTransportInstance} connections, supporting various
 * duplicate handling policies and providing lifecycle management for connected devices.</p>
 *
 * <h3>Thread Safety:</h3>
 * <p>All methods are thread-safe and can be called concurrently from multiple
 * connection threads without external synchronization.</p>
 *
 * <h3>Duplicate Handling Policies:</h3>
 * <ul>
 *   <li>{@code REPLACE} - Close existing connection and replace with new one</li>
 *   <li>{@code REJECT} - Reject new connection if device already connected</li>
 *   <li>{@code ALLOW} - Allow multiple connections per device (use first registered)</li>
 * </ul>
 */
public class TcpServerChannelRegistry {

    private static final Logger logger = LoggerFactory.getLogger(TcpServerChannelRegistry.class);

    /**
     * Policy for handling duplicate device registrations.
     */
    public enum DuplicatePolicy {
        /** Close existing connection, register new one */
        REPLACE,
        /** Reject new connection if device already exists */
        REJECT,
        /** Allow multiple connections per device */
        ALLOW
    }

    /**
     * Metadata about a registered device connection.
     */
    public static class DeviceInfo {
        private final String deviceId;
        private final TcpServerTransportInstance connection;
        private final Instant registeredAt;
        private final InetSocketAddress remoteAddress;

        DeviceInfo(String deviceId, TcpServerTransportInstance connection) {
            this.deviceId = deviceId;
            this.connection = connection;
            this.registeredAt = Instant.now();
            this.remoteAddress = connection.getRemoteAddress();
        }

        public String getDeviceId() {
            return deviceId;
        }

        public TcpServerTransportInstance getConnection() {
            return connection;
        }

        public Instant getRegisteredAt() {
            return registeredAt;
        }

        public InetSocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        public boolean isActive() {
            return connection.isOpen();
        }

        public long getBytesReceived() {
            return connection.getBytesReceived();
        }

        public long getBytesSent() {
            return connection.getBytesSent();
        }

        @Override
        public String toString() {
            return String.format("DeviceInfo{id='%s', remote=%s, active=%s, since=%s}",
                deviceId, remoteAddress, isActive(), registeredAt);
        }
    }

    /**
     * Result of a registration attempt.
     */
    public enum RegistrationResult {
        /** Successfully registered new device */
        SUCCESS,
        /** Replaced existing device connection */
        REPLACED,
        /** Rejected due to existing connection (REJECT policy) */
        REJECTED,
        /** Failed due to max connections limit */
        LIMIT_EXCEEDED
    }

    private final ConcurrentHashMap<String, DeviceInfo> deviceConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TcpServerTransportInstance, String> connectionToDevice = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCount = new AtomicInteger(0);

    private final DuplicatePolicy duplicatePolicy;
    private final int maxConnections;
    private final List<Consumer<DeviceInfo>> registrationListeners = new ArrayList<>();
    private final List<Consumer<DeviceInfo>> disconnectionListeners = new ArrayList<>();

    /**
     * Creates a registry with default settings (REPLACE policy, 1000 max connections).
     */
    public TcpServerChannelRegistry() {
        this(DuplicatePolicy.REPLACE, 1000);
    }

    /**
     * Creates a registry with the specified configuration.
     *
     * @param duplicatePolicy how to handle duplicate registrations
     * @param maxConnections maximum allowed connections (0 for unlimited)
     */
    public TcpServerChannelRegistry(DuplicatePolicy duplicatePolicy, int maxConnections) {
        this.duplicatePolicy = duplicatePolicy;
        this.maxConnections = maxConnections;
    }

    /**
     * Registers a listener for device registration events.
     *
     * @param listener the listener to add
     */
    public void addRegistrationListener(Consumer<DeviceInfo> listener) {
        registrationListeners.add(listener);
    }

    /**
     * Registers a listener for device disconnection events.
     *
     * @param listener the listener to add
     */
    public void addDisconnectionListener(Consumer<DeviceInfo> listener) {
        disconnectionListeners.add(listener);
    }

    /**
     * Attempts to register a device with its connection.
     *
     * @param deviceId the device identifier
     * @param connection the server-side connection for the device
     * @return the result of the registration attempt
     */
    public RegistrationResult register(String deviceId, TcpServerTransportInstance connection) {
        Objects.requireNonNull(deviceId, "Device ID must not be null");
        Objects.requireNonNull(connection, "Connection must not be null");

        // Check connection limit first (approximate check, exact enforcement in compute)
        if (maxConnections > 0 && connectionCount.get() >= maxConnections) {
            logger.warn("Registration rejected for device '{}': max connections ({}) reached",
                deviceId, maxConnections);
            return RegistrationResult.LIMIT_EXCEEDED;
        }

        DeviceInfo newInfo = new DeviceInfo(deviceId, connection);
        RegistrationResult[] result = new RegistrationResult[1];
        DeviceInfo[] oldInfo = new DeviceInfo[1];

        // Use compute for atomic registration
        deviceConnections.compute(deviceId, (key, existing) -> {
            if (existing == null) {
                // New device registration
                // Double-check connection limit atomically
                if (maxConnections > 0 && connectionCount.get() >= maxConnections) {
                    result[0] = RegistrationResult.LIMIT_EXCEEDED;
                    return null;
                }
                connectionCount.incrementAndGet();
                result[0] = RegistrationResult.SUCCESS;
                return newInfo;
            } else {
                // Handle duplicate
                oldInfo[0] = existing;
                return handleDuplicateInCompute(existing, newInfo, result);
            }
        });

        // Handle post-registration actions outside the compute block
        if (result[0] == RegistrationResult.SUCCESS || result[0] == RegistrationResult.REPLACED) {
            connectionToDevice.put(connection, deviceId);
            setupCloseHandler(connection, deviceId);

            if (oldInfo[0] != null) {
                // Clean up old connection mapping
                connectionToDevice.remove(oldInfo[0].getConnection());
                if (oldInfo[0].getConnection().isOpen()) {
                    try {
                        oldInfo[0].getConnection().close();
                    } catch (Exception e) {
                        logger.warn("Failed to close replaced connection for device '{}'", deviceId, e);
                    }
                }
            }

            logger.info("Device '{}' registered from {}", deviceId, connection.getRemoteAddress());
            notifyRegistrationListeners(newInfo);
        }

        return result[0];
    }

    /**
     * Handles duplicate registration inside compute block.
     * Returns the DeviceInfo to store (or null to remove).
     */
    private DeviceInfo handleDuplicateInCompute(DeviceInfo existing, DeviceInfo newInfo, RegistrationResult[] result) {
        switch (duplicatePolicy) {
            case REPLACE:
                result[0] = RegistrationResult.REPLACED;
                return newInfo;

            case REJECT:
                if (existing.isActive()) {
                    logger.warn("Registration rejected for device '{}': already connected from {}",
                        existing.getDeviceId(), existing.getRemoteAddress());
                    result[0] = RegistrationResult.REJECTED;
                    return existing; // Keep existing
                }
                // Existing connection is dead, allow replacement
                result[0] = RegistrationResult.REPLACED;
                return newInfo;

            case ALLOW:
                logger.debug("Additional connection from device '{}' ignored (ALLOW policy)", existing.getDeviceId());
                result[0] = RegistrationResult.SUCCESS;
                return existing; // Keep existing

            default:
                throw new IllegalStateException("Unknown duplicate policy: " + duplicatePolicy);
        }
    }

    /**
     * Sets up a connection close handler to clean up on disconnection.
     */
    private void setupCloseHandler(TcpServerTransportInstance connection, String deviceId) {
        connection.registerDisconnectListener(cause -> {
            DeviceInfo info = deviceConnections.get(deviceId);
            if (info != null && info.getConnection() == connection) {
                deviceConnections.remove(deviceId);
                connectionToDevice.remove(connection);
                connectionCount.decrementAndGet();
                logger.info("Device '{}' disconnected", deviceId);
                notifyDisconnectionListeners(info);
            } else {
                // Just clean up the connection mapping
                connectionToDevice.remove(connection);
            }
        });
    }

    /**
     * Gets the connection for a registered device.
     *
     * @param deviceId the device identifier
     * @return the connection, or null if not registered
     */
    public TcpServerTransportInstance getConnection(String deviceId) {
        DeviceInfo info = deviceConnections.get(deviceId);
        return info != null ? info.getConnection() : null;
    }

    /**
     * Gets the device ID for a connection.
     *
     * @param connection the connection
     * @return the device ID, or null if not registered
     */
    public String getDeviceId(TcpServerTransportInstance connection) {
        return connectionToDevice.get(connection);
    }

    /**
     * Gets device info by ID.
     *
     * @param deviceId the device identifier
     * @return the device info, or null if not found
     */
    public DeviceInfo getDeviceInfo(String deviceId) {
        return deviceConnections.get(deviceId);
    }

    /**
     * Checks if a device is registered.
     *
     * @param deviceId the device identifier
     * @return true if registered
     */
    public boolean isRegistered(String deviceId) {
        return deviceConnections.containsKey(deviceId);
    }

    /**
     * Unregisters a device, closing its connection.
     *
     * @param deviceId the device identifier
     * @return true if the device was unregistered
     */
    public boolean unregister(String deviceId) {
        DeviceInfo info = deviceConnections.remove(deviceId);
        if (info != null) {
            connectionToDevice.remove(info.getConnection());
            connectionCount.decrementAndGet();
            if (info.getConnection().isOpen()) {
                try {
                    info.getConnection().close();
                } catch (Exception e) {
                    logger.warn("Failed to close connection for device '{}'", deviceId, e);
                }
            }
            logger.info("Device '{}' unregistered", deviceId);
            notifyDisconnectionListeners(info);
            return true;
        }
        return false;
    }

    /**
     * Gets the current number of registered devices.
     *
     * @return the device count
     */
    public int getDeviceCount() {
        return connectionCount.get();
    }

    /**
     * Gets all registered device IDs.
     *
     * @return an unmodifiable set of device IDs
     */
    public Set<String> getRegisteredDevices() {
        return Collections.unmodifiableSet(deviceConnections.keySet());
    }

    /**
     * Gets all device info entries.
     *
     * @return an unmodifiable collection of device info
     */
    public Collection<DeviceInfo> getAllDeviceInfo() {
        return Collections.unmodifiableCollection(deviceConnections.values());
    }

    /**
     * Closes all connections and clears the registry.
     */
    public void shutdown() {
        logger.info("Shutting down device registry with {} active connections", connectionCount.get());

        for (DeviceInfo info : deviceConnections.values()) {
            if (info.getConnection().isOpen()) {
                try {
                    info.getConnection().close();
                } catch (Exception e) {
                    logger.warn("Failed to close connection for device '{}'", info.getDeviceId(), e);
                }
            }
        }

        deviceConnections.clear();
        connectionToDevice.clear();
        connectionCount.set(0);
    }

    private void notifyRegistrationListeners(DeviceInfo info) {
        for (Consumer<DeviceInfo> listener : registrationListeners) {
            try {
                listener.accept(info);
            } catch (Exception e) {
                logger.warn("Registration listener threw exception", e);
            }
        }
    }

    private void notifyDisconnectionListeners(DeviceInfo info) {
        for (Consumer<DeviceInfo> listener : disconnectionListeners) {
            try {
                listener.accept(info);
            } catch (Exception e) {
                logger.warn("Disconnection listener threw exception", e);
            }
        }
    }

    @Override
    public String toString() {
        return String.format("TcpServerChannelRegistry{devices=%d, max=%d, policy=%s}",
            connectionCount.get(), maxConnections, duplicatePolicy);
    }
}
