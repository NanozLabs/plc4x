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

import org.apache.plc4x.java.spi.transports.api.AsyncTransportInstance;
import org.apache.plc4x.java.spi.transports.api.BaseTransportInstance;
import org.apache.plc4x.java.spi.transports.api.exceptions.TransportException;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Virtual transport instance handed to a driver in TCP Server mode.
 *
 * <p>Because the SPI3 driver ↔ transport-instance relationship is 1:1 while a TCP server
 * manages <em>many</em> registered device connections, this class multiplexes a single
 * logical connection over the {@link TcpServerChannelRegistry}:</p>
 *
 * <ul>
 *   <li>{@link #write(byte[])} forwards to the <em>currently targeted</em> device connection.</li>
 *   <li>{@link #read(int)}/{@link #peekReadableBytes(int)}/{@link #getNumBytesAvailable()}
 *       read from the targeted device's ring buffer.</li>
 *   <li>{@link #registerDataListener(Runnable)} registers on the currently targeted device.</li>
 *   <li>If no device is targeted (or the targeted one disconnects), all I/O throws a clear
 *       {@link TransportException} telling the caller to set {@code device-id}.</li>
 * </ul>
 *
 * <p>The driver sets the routing target between protocol transactions via
 * {@link #setTargetDevice(String)} — e.g. a modbus server driver parses {@code device-id}
 * from each tag and targets the device before issuing the request. If no target is set, the
 * configured {@code target-device-id} is used; if that is also unset, a single connected
 * device (exactly one) is used as the fallback.</p>
 */
public class RoutingTransportInstance extends BaseTransportInstance<TcpServerTransportConfiguration>
        implements AsyncTransportInstance<TcpServerTransportConfiguration> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoutingTransportInstance.class);

    private final TcpServerChannelRegistry registry;

    private volatile String targetDeviceId;
    private volatile int boundPort;

    public RoutingTransportInstance(TcpServerTransportConfiguration configuration,
                                    AuditLog auditLog,
                                    TcpServerChannelRegistry registry) {
        super(configuration, auditLog);
        this.registry = registry;
        // Default routing target from the connection URL, if configured.
        this.targetDeviceId = configuration.getTargetDeviceId();
    }

    /**
     * Sets the device that subsequent read/write operations are routed to.
     *
     * @param deviceId the device identifier
     */
    public void setTargetDevice(String deviceId) {
        this.targetDeviceId = deviceId;
        LOGGER.debug("Routing target set to '{}'", deviceId);
    }

    /**
     * Returns the currently targeted device ID.
     *
     * @return the device ID, or null if not set
     */
    public String getTargetDevice() {
        return targetDeviceId;
    }

    /**
     * Returns the underlying registry (for device management APIs).
     *
     * @return the registry
     */
    public TcpServerChannelRegistry getRegistry() {
        return registry;
    }

    /**
     * The local port the server socket is bound to (0 until bound).
     *
     * @return the bound port
     */
    public int getLocalPort() {
        return boundPort;
    }

    /** Set by the server accept loop once the socket is bound. */
    void setBoundPort(int port) {
        this.boundPort = port;
    }

    @Override
    public boolean isOpen() {
        // The routing instance is a logical view: it stays "open" as long as the server
        // listener is alive. getConnection() holds this instance until close().
        return true;
    }

    @Override
    public int getNumBytesAvailable() throws TransportException {
        TcpServerTransportInstance connection = resolveTarget();
        return connection.getNumBytesAvailable();
    }

    @Override
    public byte[] peekReadableBytes(int numBytes) throws TransportException {
        TcpServerTransportInstance connection = resolveTarget();
        return connection.peekReadableBytes(numBytes);
    }

    @Override
    public byte[] read(int numBytes) throws TransportException {
        TcpServerTransportInstance connection = resolveTarget();
        return connection.read(numBytes);
    }

    @Override
    public void write(byte[] bytes) throws TransportException {
        TcpServerTransportInstance connection = resolveTarget();
        connection.write(bytes);
    }

    @Override
    public void close() throws TransportException {
        // This instance owns the server. Closing it shuts down all device connections.
        // (The accept-loop virtual thread is interrupted by the server channel close.)
        registry.shutdown();
        getAuditLog().write(AuditLogEventType.CLOSE, "Closed");
        LOGGER.debug("Routing transport closed");
    }

    // ========== AsyncTransportInstance Implementation ==========

    @Override
    public void registerDataListener(Runnable listener) {
        // The driver's data listener is routed to the currently targeted device. In a
        // multi-device server this is inherently ambiguous; the driver is expected to
        // setTargetDevice() before (re)registering, or to rely on the default target.
        TcpServerTransportInstance connection = resolveTargetOrNull();
        if (connection == null) {
            // No device available yet: remember the listener and attach it to the first
            // device that registers, so a driver waiting for unsolicited messages still
            // works once a device is online.
            pendingDataListener = listener;
            return;
        }
        connection.registerDataListener(listener);
    }

    @Override
    public void removeDataListener() {
        TcpServerTransportInstance connection = resolveTargetOrNull();
        if (connection != null) {
            connection.removeDataListener();
        }
    }

    @Override
    public void registerDisconnectListener(Consumer<Throwable> listener) {
        TcpServerTransportInstance connection = resolveTargetOrNull();
        if (connection != null) {
            connection.registerDisconnectListener(listener);
        }
    }

    @Override
    public void removeDisconnectListener() {
        TcpServerTransportInstance connection = resolveTargetOrNull();
        if (connection != null) {
            connection.removeDisconnectListener();
        }
    }

    private volatile Runnable pendingDataListener;

    /**
     * Called by the server transport when a device finishes registration, so a pending
     * data listener can be attached to the newly online device.
     */
    void onDeviceRegistered(TcpServerTransportInstance connection) {
        Runnable listener = pendingDataListener;
        if (listener != null) {
            connection.registerDataListener(listener);
        }
    }

    private TcpServerTransportInstance resolveTarget() throws TransportException {
        TcpServerTransportInstance connection = resolveTargetOrNull();
        if (connection == null) {
            throw new TransportException(
                "No device connection available. Specify a target device via the tag 'device-id' " +
                "or the connection parameter 'target-device-id'.");
        }
        return connection;
    }

    private TcpServerTransportInstance resolveTargetOrNull() {
        // 1. Explicit target (set by the driver or configured via target-device-id).
        if (targetDeviceId != null && !targetDeviceId.isBlank()) {
            TcpServerTransportInstance connection = registry.getConnection(targetDeviceId);
            if (connection != null && connection.isOpen()) {
                return connection;
            }
            LOGGER.debug("Target device '{}' is not (yet) connected", targetDeviceId);
            return null;
        }

        // 2. Single-device convenience: if exactly one device is connected, route to it.
        var deviceIds = registry.getRegisteredDevices();
        if (deviceIds.size() == 1) {
            String deviceId = deviceIds.iterator().next();
            TcpServerTransportInstance connection = registry.getConnection(deviceId);
            if (connection != null && connection.isOpen()) {
                return connection;
            }
        }

        return null;
    }
}
