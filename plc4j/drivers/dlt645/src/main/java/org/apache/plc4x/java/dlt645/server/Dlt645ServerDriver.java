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

import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.dlt645.server.config.Dlt645ServerConfiguration;
import org.apache.plc4x.java.dlt645.tag.Dlt645CommandTag;
import org.apache.plc4x.java.dlt645.tag.Dlt645Tag;
import org.apache.plc4x.java.spi.config.Configuration;
import org.apache.plc4x.java.spi.drivers.ConnectionBase;
import org.apache.plc4x.java.spi.drivers.DriverBase;
import org.apache.plc4x.java.spi.transports.api.Transport;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.transports.api.config.TransportConfiguration;
import org.apache.plc4x.java.transport.tcpserver.TcpServerTransportConfiguration;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;

import java.util.List;
import java.util.Optional;

/**
 * PLC4X Server Driver for DL/T 645-2007 (reverse connection mode).
 * <p>
 * Listens for inbound TCP connections from smart meters or DTU devices.
 * <p>
 * Connection URI format:
 * <pre>
 * dlt645-server:tcp-server://0.0.0.0:8899
 * dlt645-server:tcp-server://0.0.0.0:8899?target-device-id=123456789012
 * </pre>
 */
public class Dlt645ServerDriver extends DriverBase {

    @Override
    public String getProtocolCode() {
        return "dlt645-server";
    }

    @Override
    public String getProtocolName() {
        return "DL/T 645-2007 Server";
    }

    @Override
    protected Class<? extends Configuration> getConfigurationClass() {
        return Dlt645ServerConfiguration.class;
    }

    @Override
    protected Class<? extends TransportConfiguration> getTransportConfigurationClass(Transport<?> transport) {
        if ("tcp-server".equals(transport.getTransportCode())) {
            return TcpServerTransportConfiguration.class;
        }
        return super.getTransportConfigurationClass(transport);
    }

    @Override
    public Optional<String> getDefaultTransportCode() {
        return Optional.of("tcp-server");
    }

    @Override
    public List<String> getSupportedTransportCodes() {
        return List.of("tcp-server");
    }

    @Override
    protected boolean canPing() {
        return true;
    }

    @Override
    protected boolean canRead() {
        return true;
    }

    @Override
    protected boolean canWrite() {
        return true;
    }

    @Override
    protected ConnectionBase<Dlt645ServerConfiguration> getConnection(Configuration configuration,
                                                                      TransportInstance<?> transportInstance,
                                                                      AuditLog auditLog) {
        return new Dlt645ServerConnection((Dlt645ServerConfiguration) configuration, transportInstance, auditLog);
    }

    @Override
    public PlcTag prepareTag(String tagAddress) {
        if (Dlt645CommandTag.matches(tagAddress)) {
            return Dlt645CommandTag.of(tagAddress);
        }
        return Dlt645Tag.of(tagAddress);
    }
}
