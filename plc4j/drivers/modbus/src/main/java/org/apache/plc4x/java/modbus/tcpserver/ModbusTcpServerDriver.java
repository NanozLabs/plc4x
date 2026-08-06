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

import org.apache.plc4x.java.modbus.base.tag.ModbusTag;
import org.apache.plc4x.java.modbus.tcp.config.ModbusTcpConfiguration;
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
 * Modbus TCP Server driver.
 *
 * <p>Operates in passive/server mode on top of the {@code tcp-server} transport. DTU devices
 * connect to this server, register their device-id, and the driver routes each Modbus request
 * to the device referenced by the tag's {@code device-id} config.</p>
 *
 * <p>Connection URL format:</p>
 * <pre>{@code
 * modbus-tcp-server:tcp-server://0.0.0.0:502?registration-type=fixed&registration-length=16
 * }</pre>
 */
public class ModbusTcpServerDriver extends DriverBase {

    @Override
    public String getProtocolCode() {
        return "modbus-tcp-server";
    }

    @Override
    public String getProtocolName() {
        return "Modbus TCP Server";
    }

    @Override
    protected Class<? extends Configuration> getConfigurationClass() {
        return ModbusTcpConfiguration.class;
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
    protected ConnectionBase<ModbusTcpConfiguration> getConnection(Configuration configuration, TransportInstance<?> transportInstance, AuditLog auditLog) {
        return new ModbusTcpServerConnection((ModbusTcpConfiguration) configuration, transportInstance, auditLog);
    }

    @Override
    public ModbusTag prepareTag(String tagAddress) {
        return ModbusTag.of(tagAddress);
    }

}
