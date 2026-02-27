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
package org.apache.plc4x.java.modbus.rtuserver.context;

import org.apache.plc4x.java.modbus.base.context.ModbusContext;
import org.apache.plc4x.java.modbus.rtuserver.config.ModbusRtuServerConfiguration;
import org.apache.plc4x.java.spi.configuration.HasConfiguration;
import org.apache.plc4x.java.transport.tcp.server.DeviceChannelRegistry;

/**
 * Driver context for Modbus RTU Server mode (RTU over TCP).
 * Extends ModbusContext directly (not ModbusRtuContext) because the framework's
 * ConfigurationFactory only checks direct interfaces via getGenericInterfaces().
 */
public class ModbusRtuServerContext extends ModbusContext implements HasConfiguration<ModbusRtuServerConfiguration> {

    private DeviceChannelRegistry deviceRegistry;

    @Override
    public void setConfiguration(ModbusRtuServerConfiguration configuration) {
        setByteOrder(configuration.getDefaultPayloadByteOrder());
        setMaxCoilsPerRequest(configuration.getMaxCoilsPerRequest());
        setMaxRegistersPerRequest(configuration.getMaxRegistersPerRequest());
    }

    public DeviceChannelRegistry getDeviceRegistry() {
        return deviceRegistry;
    }

    public void setDeviceRegistry(DeviceChannelRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }
}
