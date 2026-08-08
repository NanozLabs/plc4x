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
package org.apache.plc4x.java.transport.socket;

import org.apache.plc4x.java.spi.transports.api.Transport;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.transports.api.config.TransportConfiguration;
import org.apache.plc4x.java.spi.transports.api.exceptions.TransportException;
import org.apache.plc4x.java.transport.socket.config.SocketTransportConfiguration;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Socket transport: accepts externally-injected {@link SocketChannel}s from DTU devices.
 *
 * <p>Unlike a connecting transport, this transport does <b>not</b> open any socket itself.
 * The user owns listen/accept/registration (e.g. via Netty, a {@code ServerSocketChannel},
 * or any custom gateway) and hands each established, handshake-complete channel to this
 * transport via {@link #injectConnection(String, SocketChannel)}. The channel is keyed by a
 * user-chosen device id ({@code did}). A later {@code getConnection(url)} whose URL carries
 * {@code ?did=<id>} resolves the matching injected channel and wraps it in a
 * {@link SocketTransportInstance} for the driver.</p>
 *
 * <h3>Connection URL format:</h3>
 * <pre>{@code
 * modbus-rtu:socket://?did=DEVICE001
 * dlt645:socket://?did=123456789012
 * }</pre>
 *
 * <p>The transport is a JVM-wide singleton (via the {@code ServiceLoader} registration in
 * {@code META-INF/services}), so the device registry is shared across all drivers that use
 * the {@code socket} transport code.</p>
 *
 * @see SocketTransportInstance
 */
public class SocketTransport implements Transport<SocketTransportConfiguration> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketTransport.class);

    /** did → injected connection. JVM-wide, shared by every driver using this transport. */
    private final Map<String, SocketTransportInstance> deviceConnections = new ConcurrentHashMap<>();

    @Override
    public String getTransportCode() {
        return "socket";
    }

    @Override
    public String getTransportName() {
        return "Socket (injected DTU connection)";
    }

    @Override
    public Class<SocketTransportConfiguration> getTransportConfigType() {
        return SocketTransportConfiguration.class;
    }

    /**
     * Registers an established, handshake-complete channel under the given device id.
     *
     * <p>The user is responsible for listen/accept and any registration handshake, and must
     * consume the handshake bytes <em>before</em> injecting — the channel handed to plc4x
     * should only carry protocol bytes from here on.</p>
     *
     * @param deviceId the device identifier, used to address this connection via {@code ?did=}
     * @param socketChannel the accepted, established channel
     * @throws IllegalArgumentException if a connection is already registered for the device id
     */
    public void injectConnection(String deviceId, SocketChannel socketChannel) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        SocketTransportInstance instance;
        try {
            instance = new SocketTransportInstance(socketChannel, new SocketTransportConfiguration(), auditLog());
        } catch (TransportException e) {
            throw new IllegalArgumentException("Failed to wrap injected socket for device '" + deviceId + "'", e);
        }
        SocketTransportInstance existing = deviceConnections.putIfAbsent(deviceId, instance);
        if (existing != null) {
            throw new IllegalArgumentException(
                "A connection for device '" + deviceId + "' is already injected. Close it first or use a distinct id.");
        }
        LOGGER.info("Injected connection for device '{}' ({})", deviceId, socketChannel);
    }

    /**
     * Removes a previously injected connection without closing the underlying channel.
     *
     * <p>Intended for the user to deregister a device whose channel they are taking back
     * (or that has been closed externally). Disconnects detected by the transport itself
     * are handled internally and also remove the entry.</p>
     *
     * @param deviceId the device identifier
     * @return true if a connection was removed
     */
    public boolean removeConnection(String deviceId) {
        return deviceConnections.remove(deviceId) != null;
    }

    /**
     * The device ids currently registered.
     *
     * @return unmodifiable snapshot of registered device ids
     */
    public Collection<String> getRegisteredDevices() {
        return Collections.unmodifiableCollection(deviceConnections.keySet());
    }

    /**
     * Whether a connection is registered for the device id.
     *
     * @param deviceId the device identifier
     * @return true if registered
     */
    public boolean isDeviceRegistered(String deviceId) {
        return deviceConnections.containsKey(deviceId);
    }

    @Override
    public TransportInstance<SocketTransportConfiguration> createTransportInstance(String transportUrl, TransportConfiguration configuration, AuditLog auditLog) throws TransportException {
        if (!(configuration instanceof SocketTransportConfiguration socketTransportConfiguration)) {
            throw new IllegalArgumentException(String.format("Expected configuration of type %s but got %s",
                SocketTransportConfiguration.class.getSimpleName(), configuration.getClass().getSimpleName()));
        }

        String did = socketTransportConfiguration.did;
        if (did == null || did.isBlank()) {
            throw new TransportException(
                "The 'socket' transport requires a 'did' parameter in the connection URL, e.g. " +
                "protocol:socket://?did=DEVICE001");
        }

        SocketTransportInstance instance = deviceConnections.get(did);
        if (instance == null) {
            throw new TransportException(
                "No injected connection registered for device '" + did + "'. " +
                "Call SocketTransport.injectConnection(did, channel) first. Registered devices: " + deviceConnections.keySet());
        }
        if (!instance.isOpen()) {
            deviceConnections.remove(did);
            throw new TransportException(
                "Connection for device '" + did + "' is closed. Inject it again.");
        }

        LOGGER.debug("Resolved injected connection for device '{}'", did);
        return instance;
    }

    /**
     * The audit log used for injected connections created before a {@code getConnection} call.
     * These instances get their own config-free audit log; resolved instances are not re-audited.
     */
    private AuditLog auditLog() {
        return AuditLog.builder().withSource(getTransportCode()).build();
    }

    Optional<SocketTransportInstance> peek(String deviceId) {
        return Optional.ofNullable(deviceConnections.get(deviceId));
    }

}
