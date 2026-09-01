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
 * transport via {@link #injectChannel(String, SocketChannel)}. The channel is keyed by a
 * user-chosen device id ({@code did}). A later {@code getConnection(url)} whose URL carries
 * {@code ?socket.did=<id>} resolves the matching injected channel and wraps it in a
 * {@link SocketTransportInstance} for the driver.</p>
 *
 * <h3>Connection URL format:</h3>
 * <pre>{@code
 * modbus-rtu:socket://?socket.did=DEVICE001
 * dlt645:socket://?socket.did=123456789012
 * }</pre>
 *
 * <p>The channel registry is JVM-wide (static), so any {@link SocketTransport} instance
 * constructed via {@code ServiceLoader} sees the same injected channels. A channel is
 * unregistered when {@link #removeChannel(String)} / {@link #closeAndRemoveChannel(String)}
 * is called, or when the remote side disconnects / the instance is closed.</p>
 *
 * @see SocketTransportInstance
 */
public class SocketTransport implements Transport<SocketTransportConfiguration> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketTransport.class);

    /**
     * did → injected channel.
     * <p>Static on purpose: each {@code DriverBase} constructs its own {@link SocketTransport}
     * via {@code ServiceLoader}, so an instance field would not be visible to
     * {@code getConnection("modbus-rtu:socket://?socket.did=...")}.
     */
    private static final Map<String, SocketTransportInstance> DEVICE_CHANNELS = new ConcurrentHashMap<>();

    @Override
    public String getTransportCode() {
        return "socket";
    }

    @Override
    public String getTransportName() {
        return "Socket (injected DTU channel)";
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
     * @param deviceId the device identifier, used to address this channel via {@code socket.did}
     * @param socketChannel the accepted, established channel
     * @throws IllegalArgumentException if a channel is already registered for the device id
     */
    public static void injectChannel(String deviceId, SocketChannel socketChannel) {
        injectChannel(deviceId, socketChannel, false);
    }

    /**
     * Registers an established, handshake-complete channel under the given device id.
     *
     * @param deviceId the device identifier, used to address this channel via {@code socket.did}
     * @param socketChannel the accepted, established channel
     * @param replace if true, close and drop any existing injection for this id first
     * @throws IllegalArgumentException if {@code replace} is false and the id is already injected
     */
    public static void injectChannel(String deviceId, SocketChannel socketChannel, boolean replace) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        if (replace) {
            closeAndRemoveChannel(deviceId);
        }
        SocketTransportInstance instance;
        try {
            instance = new SocketTransportInstance(deviceId, socketChannel, new SocketTransportConfiguration(), auditLog());
        } catch (TransportException e) {
            throw new IllegalArgumentException("Failed to wrap injected socket for device '" + deviceId + "'", e);
        }
        SocketTransportInstance existing = DEVICE_CHANNELS.putIfAbsent(deviceId, instance);
        if (existing != null) {
            try {
                instance.close();
            } catch (Exception ignored) {
                // the new wrapper is discarded; the live injection stays
            }
            throw new IllegalArgumentException(
                "A channel for device '" + deviceId + "' is already injected. Close it first or use a distinct id.");
        }
        LOGGER.info("Injected channel for device '{}' ({})", deviceId, socketChannel);
    }

    /**
     * Unregisters a previously injected channel without closing the underlying socket.
     *
     * <p>Intended for the user to take the channel back, or for the instance to detach
     * itself on close / remote disconnect.</p>
     *
     * @param deviceId the device identifier
     * @return true if a channel was removed
     */
    public static boolean removeChannel(String deviceId) {
        return DEVICE_CHANNELS.remove(deviceId) != null;
    }

    /**
     * Closes the injected instance (if any) and drops it from the registry.
     *
     * @param deviceId the device identifier
     * @return true if an entry was present
     */
    public static boolean closeAndRemoveChannel(String deviceId) {
        SocketTransportInstance existing = DEVICE_CHANNELS.remove(deviceId);
        if (existing == null) {
            return false;
        }
        try {
            existing.close();
        } catch (Exception e) {
            LOGGER.warn("Failed to close previous injection for device '{}': {}", deviceId, e.getMessage());
        }
        return true;
    }

    /**
     * The device ids currently registered.
     *
     * @return unmodifiable snapshot of registered device ids
     */
    public static Collection<String> getRegisteredChannelIds() {
        return Collections.unmodifiableCollection(DEVICE_CHANNELS.keySet());
    }

    /**
     * Whether a channel is registered for the device id.
     *
     * @param deviceId the device identifier
     * @return true if registered
     */
    public static boolean isChannelRegistered(String deviceId) {
        return DEVICE_CHANNELS.containsKey(deviceId);
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
                "The 'socket' transport requires a 'socket.did' parameter in the connection URL, e.g. " +
                "protocol:socket://?socket.did=DEVICE001");
        }

        SocketTransportInstance instance = DEVICE_CHANNELS.get(did);
        if (instance == null) {
            throw new TransportException(
                "No injected channel registered for device '" + did + "'. " +
                "Call SocketTransport.injectChannel(did, channel) first. Registered: " + DEVICE_CHANNELS.keySet());
        }
        if (!instance.isOpen()) {
            DEVICE_CHANNELS.remove(did);
            throw new TransportException(
                "Channel for device '" + did + "' is closed. Inject it again.");
        }

        LOGGER.debug("Resolved injected channel for device '{}'", did);
        return instance;
    }

    private static AuditLog auditLog() {
        return AuditLog.builder().withSource("socket").build();
    }

    static Optional<SocketTransportInstance> peek(String deviceId) {
        return Optional.ofNullable(DEVICE_CHANNELS.get(deviceId));
    }

}
