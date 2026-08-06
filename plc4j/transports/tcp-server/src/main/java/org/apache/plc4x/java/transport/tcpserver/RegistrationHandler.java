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

import org.apache.plc4x.java.transport.tcpserver.registration.RegistrationPacketParser;
import org.apache.plc4x.java.transport.tcpserver.registration.RegistrationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Manages the device registration handshake for an accepted connection.
 *
 * <p>This class sits in front of a {@link TcpServerTransportInstance} while the device
 * registers. It accumulates incoming bytes until the configured
 * {@link RegistrationPacketParser} can extract a device identifier, then registers the
 * device in the {@link TcpServerChannelRegistry}.</p>
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>Connection established → RegistrationHandler created with a deadline</li>
 *   <li>Data received → Accumulate and parse registration packet</li>
 *   <li>Registration success → Register device, mark complete, keep remaining bytes</li>
 *   <li>Timeout or invalid data → Close connection</li>
 * </ol>
 *
 * <p>The parser operates on byte arrays (not Netty buffers); the read loop hands over
 * each chunk via {@link #onData(byte[])}.</p>
 */
public class RegistrationHandler {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationHandler.class);

    private final long timeoutMillis;
    private final RegistrationPacketParser parser;
    private final TcpServerChannelRegistry registry;
    private final Runnable onRegistrationComplete;
    private final Runnable onFailure;
    private final Supplier<InetSocketAddress> remoteAddressSupplier;

    private final ByteArrayOutputStream accumulator = new ByteArrayOutputStream(64);
    private final long startedAt = System.currentTimeMillis();
    private volatile boolean complete = false;
    private volatile String deviceId;
    private volatile byte[] remainingBytes = new byte[0];

    /**
     * Creates a new registration handler.
     *
     * @param timeoutMillis the registration timeout in milliseconds
     * @param parser the parser for extracting device ID
     * @param registry the device channel registry
     * @param onRegistrationComplete callback invoked after a successful registration
     * @param onFailure callback invoked when registration fails or times out
     * @param remoteAddressSupplier supplier of the remote address (for logging)
     */
    public RegistrationHandler(long timeoutMillis,
                               RegistrationPacketParser parser,
                               TcpServerChannelRegistry registry,
                               Runnable onRegistrationComplete,
                               Runnable onFailure,
                               Supplier<InetSocketAddress> remoteAddressSupplier) {
        this.timeoutMillis = timeoutMillis;
        this.parser = parser;
        this.registry = registry;
        this.onRegistrationComplete = onRegistrationComplete;
        this.onFailure = onFailure;
        this.remoteAddressSupplier = remoteAddressSupplier;
    }

    /**
     * Handles a chunk of bytes received from the device before registration completes.
     *
     * @param data the bytes received
     */
    public void onData(byte[] data) {
        if (complete) {
            return;
        }

        // Enforce the registration timeout.
        if (timeoutMillis > 0 && System.currentTimeMillis() - startedAt > timeoutMillis) {
            logger.warn("Registration timeout for connection from {}", remoteAddressSupplier.get());
            onFailure.run();
            return;
        }

        synchronized (accumulator) {
            if (complete) {
                return;
            }
            try {
                accumulator.write(data);
            } catch (Exception e) {
                logger.error("Failed to accumulate registration data", e);
                onFailure.run();
                return;
            }
            attemptRegistration();
        }
    }

    /**
     * Attempts to parse the accumulated buffer for a registration packet.
     */
    private void attemptRegistration() {
        byte[] accumulated = accumulator.toByteArray();

        RegistrationResult result = parser.parse(accumulated);

        switch (result.getStatus()) {
            case COMPLETE:
                handleRegistrationSuccess(result);
                break;

            case NEED_MORE_DATA:
                // Wait for more data
                logger.trace("Registration parser needs more data, accumulated {} bytes", accumulated.length);
                break;

            case INVALID:
                handleRegistrationFailure(result);
                break;
        }
    }

    /**
     * Handles successful registration.
     */
    private void handleRegistrationSuccess(RegistrationResult result) {
        String newDeviceId = result.getDeviceId();
        logger.info("Device '{}' registration successful from {}", newDeviceId, remoteAddressSupplier.get());

        // Register in device registry
        TcpServerChannelRegistry.RegistrationResult regResult = registry.register(newDeviceId, getConnection());

        switch (regResult) {
            case SUCCESS:
            case REPLACED:
                completeRegistration(newDeviceId, result.getConsumedBytes());
                break;

            case REJECTED:
                logger.warn("Device '{}' registration rejected (duplicate)", newDeviceId);
                onFailure.run();
                break;

            case LIMIT_EXCEEDED:
                logger.warn("Device '{}' registration rejected (limit exceeded)", newDeviceId);
                onFailure.run();
                break;
        }
    }

    /**
     * Completes the registration process.
     */
    private void completeRegistration(String registeredDeviceId, int consumedBytes) {
        synchronized (accumulator) {
            if (complete) {
                return;
            }
            complete = true;
            deviceId = registeredDeviceId;

            byte[] accumulated = accumulator.toByteArray();
            if (consumedBytes < accumulated.length) {
                remainingBytes = Arrays.copyOfRange(accumulated, consumedBytes, accumulated.length);
            } else {
                remainingBytes = new byte[0];
            }
            accumulator.reset();
        }

        if (onRegistrationComplete != null) {
            onRegistrationComplete.run();
        }
    }

    /**
     * Handles registration failure.
     */
    private void handleRegistrationFailure(RegistrationResult result) {
        logger.warn("Registration failed for connection from {}: {}",
            remoteAddressSupplier.get(), result.getFailureReason().orElse("Invalid registration packet"));
        onFailure.run();
    }

    /**
     * Returns the connection being registered.
     *
     * <p>The registry requires the actual {@link TcpServerTransportInstance}. This is wired
     * by {@link DeviceConnectionHandler} through the callback, so it is fetched lazily via
     * {@link TcpServerTransportInstance#getRegistrationHandler()}. To avoid a reference cycle
     * the handler does not hold the instance directly; {@link DeviceConnectionHandler} sets it
     * once both are constructed.</p>
     */
    private TcpServerTransportInstance getConnection() {
        return connectionRef;
    }

    /** The transport instance this handler guards; wired by {@link DeviceConnectionHandler}. */
    private volatile TcpServerTransportInstance connectionRef;

    /**
     * Wires the transport instance after construction (breaks the handler↔instance cycle).
     *
     * @param connection the transport instance
     */
    public void setConnection(TcpServerTransportInstance connection) {
        this.connectionRef = connection;
    }

    /**
     * Whether registration has completed successfully.
     *
     * @return true if complete
     */
    public boolean isComplete() {
        return complete;
    }

    /**
     * The registered device ID.
     *
     * @return the device ID, or null if not yet registered
     */
    public String getDeviceId() {
        return deviceId;
    }

    /**
     * Bytes that arrived after the registration packet and belong to the protocol stream.
     *
     * @return the remaining bytes
     */
    public byte[] getRemainingBytes() {
        return remainingBytes;
    }
}
