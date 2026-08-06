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
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.function.Consumer;

/**
 * Handles the lifecycle of a single accepted device connection.
 *
 * <p>Wraps a raw accepted {@link SocketChannel} into a {@link TcpServerTransportInstance}
 * together with a {@link RegistrationHandler}. The two share a cyclic reference (the
 * handler needs the instance to register it, the instance needs the handler while
 * registration is in progress), which is resolved by wiring them after construction via
 * {@link RegistrationHandler#setConnection(TcpServerTransportInstance)}.</p>
 *
 * <p>When registration completes, the given callback is invoked with the registered
 * instance so the routing layer can attach any pending data listener. On failure or
 * timeout the connection is closed.</p>
 */
public class DeviceConnectionHandler {

    private static final Logger logger = LoggerFactory.getLogger(DeviceConnectionHandler.class);

    private final TcpServerTransportInstance transportInstance;
    private final RegistrationHandler registrationHandler;

    private DeviceConnectionHandler(TcpServerTransportInstance transportInstance,
                                    RegistrationHandler registrationHandler) {
        this.transportInstance = transportInstance;
        this.registrationHandler = registrationHandler;
    }

    /**
     * Factory method: builds a fully-wired handler for an accepted socket.
     *
     * @param socketChannel the accepted socket channel (already blocking mode)
     * @param configuration the transport configuration
     * @param registry the device registry
     * @param registrationParser the configured registration parser
     * @param auditLog the audit log
     * @param onRegistered callback invoked when registration completes
     * @return the wired handler
     */
    public static DeviceConnectionHandler create(SocketChannel socketChannel,
                                                 TcpServerTransportConfiguration configuration,
                                                 TcpServerChannelRegistry registry,
                                                 RegistrationPacketParser registrationParser,
                                                 AuditLog auditLog,
                                                 Consumer<TcpServerTransportInstance> onRegistered) throws Exception {
        // Mutable reference so the callbacks below can reach the instance that is only
        // created after the handler (the handler↔instance cycle).
        final TcpServerTransportInstance[] instanceRef = new TcpServerTransportInstance[1];

        RegistrationHandler handler = new RegistrationHandler(configuration.getRegistrationTimeout(),
            registrationParser, registry,
            // onRegistrationComplete: notify the routing layer that a device is online.
            () -> {
                if (instanceRef[0] != null && onRegistered != null) {
                    onRegistered.accept(instanceRef[0]);
                }
            },
            // onFailure: registration rejected/failed/timed out — close the connection.
            () -> {
                TcpServerTransportInstance instance = instanceRef[0];
                if (instance != null) {
                    try {
                        instance.close();
                    } catch (Exception e) {
                        logger.warn("Failed to close connection after registration failure", e);
                    }
                }
            },
            // remoteAddressSupplier: derive straight from the socket, no instance needed.
            () -> (InetSocketAddress) socketChannel.socket().getRemoteSocketAddress());

        TcpServerTransportInstance instance = new TcpServerTransportInstance(socketChannel, configuration,
            auditLog, handler);

        instanceRef[0] = instance;
        handler.setConnection(instance);

        return new DeviceConnectionHandler(instance, handler);
    }

    /**
     * The transport instance for this device connection.
     *
     * @return the transport instance
     */
    public TcpServerTransportInstance getTransportInstance() {
        return transportInstance;
    }

    /**
     * Whether registration has completed.
     *
     * @return true if complete
     */
    public boolean isRegistrationComplete() {
        return registrationHandler.isComplete();
    }

    /**
     * Closes the connection.
     */
    public void close() {
        try {
            transportInstance.close();
        } catch (Exception e) {
            logger.warn("Failed to close device connection", e);
        }
    }
}
