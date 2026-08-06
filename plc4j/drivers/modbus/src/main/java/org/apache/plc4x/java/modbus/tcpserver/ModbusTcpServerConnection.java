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

import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.modbus.base.tag.ModbusTag;
import org.apache.plc4x.java.modbus.tcp.ModbusTcpConnection;
import org.apache.plc4x.java.modbus.tcp.config.ModbusTcpConfiguration;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.transport.tcpserver.RoutingTransportInstance;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;

/**
 * Modbus TCP Server connection.
 *
 * <p>Extends the official {@link ModbusTcpConnection} and reuses its full read/write/ping
 * implementation. The only difference is device routing: before every request the targeted
 * device (from the tag's {@code device-id} config) is selected on the
 * {@link RoutingTransportInstance}, so the request is delivered to the correct registered DTU.</p>
 */
public class ModbusTcpServerConnection extends ModbusTcpConnection {

    public ModbusTcpServerConnection(ModbusTcpConfiguration configuration,
                                     TransportInstance<?> transportInstance,
                                     AuditLog auditLog) {
        super(configuration, transportInstance, auditLog);
    }

    @Override
    protected short getUnitId(PlcTag tag) {
        if (getTransportInstance() instanceof RoutingTransportInstance routing) {
            String deviceId = (tag instanceof ModbusTag modbusTag) ? modbusTag.getDeviceId() : null;
            // null → the transport falls back to target-device-id (or the single connected device).
            routing.setTargetDevice(deviceId);
        }
        return super.getUnitId(tag);
    }

}
