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
import org.apache.plc4x.java.dlt645.protocol.Dlt645Connection;
import org.apache.plc4x.java.dlt645.server.config.Dlt645ServerConfiguration;
import org.apache.plc4x.java.transport.tcpserver.TcpServerTransportInstance;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A virtual {@link org.apache.plc4x.java.api.PlcConnection} bound to a single DL/T 645-2007 device.
 * <p>
 * Created via {@link Dlt645ServerConnection#getDeviceConnection(String)}.
 * Reuses the full DL/T 645-2007 read/write/command logic of {@link Dlt645Connection}, the only
 * difference being the transport: it is wired directly to the target device's
 * {@link TcpServerTransportInstance} (the accepted DTU socket), so every request is delivered
 * to that specific meter — no device-id needed in tags.
 *
 * <pre>{@code
 * Dlt645DeviceConnection device = server.getDeviceConnection("123456789012");
 * PlcReadResponse resp = device.readRequestBuilder()
 *     .addTagAddress("energy", "00010000")
 *     .build().execute().get();
 * float energy = resp.getFloat("energy");
 * }</pre>
 */
public class Dlt645DeviceConnection extends Dlt645Connection {

    private static final Logger logger = LoggerFactory.getLogger(Dlt645DeviceConnection.class);

    private final String deviceId;
    private final TcpServerTransportInstance deviceTransport;

    Dlt645DeviceConnection(String deviceId, TcpServerTransportInstance deviceTransport,
                           Dlt645ServerConfiguration configuration, AuditLog auditLog) {
        super(configuration, deviceTransport, auditLog);
        this.deviceId = deviceId;
        this.deviceTransport = deviceTransport;
        // The device connection is a virtual sub-connection: it is bound to an already
        // registered DTU socket, so connecting is immediate (virtual-thread I/O, no blocking).
        // This initializes the Dlt645MessageCodec that all read/write/command operations use.
        try {
            connect();
        } catch (PlcConnectionException e) {
            logger.warn("Failed to initialize device connection for '{}': {}", deviceId, e.getMessage());
        }
    }

    /**
     * Get the device identifier (from registration packet).
     */
    public String getDeviceId() {
        return deviceId;
    }

    /**
     * Whether the underlying DTU socket is still open.
     */
    public boolean isDeviceOnline() {
        return deviceTransport.isOpen();
    }

    @Override
    public boolean isConnected() {
        return deviceTransport.isOpen();
    }
}
