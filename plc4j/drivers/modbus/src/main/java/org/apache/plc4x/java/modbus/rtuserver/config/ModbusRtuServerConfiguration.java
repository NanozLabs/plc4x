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
package org.apache.plc4x.java.modbus.rtuserver.config;

import org.apache.plc4x.java.modbus.rtu.config.ModbusRtuConfiguration;
import org.apache.plc4x.java.spi.configuration.annotations.ConfigurationParameter;
import org.apache.plc4x.java.spi.configuration.annotations.Description;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.IntDefaultValue;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.StringDefaultValue;

/**
 * Configuration for Modbus RTU Server connections (RTU over TCP).
 */
public class ModbusRtuServerConfiguration extends ModbusRtuConfiguration {

    @ConfigurationParameter("target-device-id")
    @Description("Default target device ID for operations.")
    private String targetDeviceId;

    @ConfigurationParameter("ping-address")
    @StringDefaultValue("holding-register:1:INT")
    @Description("Address used for ping requests.")
    private String pingAddress;

    @ConfigurationParameter("device-timeout")
    @IntDefaultValue(30000)
    @Description("Timeout in milliseconds to wait for a specific device.")
    private int deviceTimeout;

    public String getTargetDeviceId() {
        return targetDeviceId;
    }

    public void setTargetDeviceId(String targetDeviceId) {
        this.targetDeviceId = targetDeviceId;
    }

    public String getPingAddress() {
        return pingAddress;
    }

    public void setPingAddress(String pingAddress) {
        this.pingAddress = pingAddress;
    }

    public int getDeviceTimeout() {
        return deviceTimeout;
    }

    public void setDeviceTimeout(int deviceTimeout) {
        this.deviceTimeout = deviceTimeout;
    }
}
