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
package org.apache.plc4x.java.modbus.tcpserver.config;

import org.apache.plc4x.java.modbus.tcp.config.ModbusTcpConfiguration;
import org.apache.plc4x.java.spi.configuration.annotations.ConfigurationParameter;
import org.apache.plc4x.java.spi.configuration.annotations.Description;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.BooleanDefaultValue;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.IntDefaultValue;

/**
 * Configuration for Modbus TCP Server connections.
 *
 * <p>This configuration extends the standard Modbus TCP configuration with
 * server-specific options for managing multiple device connections and
 * device routing.</p>
 *
 * @since 0.14.0
 */
public class ModbusTcpServerConfiguration extends ModbusTcpConfiguration {

    @ConfigurationParameter("target-device-id")
    @Description("Target device ID for operations. If not specified, operations target all connected devices or require explicit device addressing.")
    private String targetDeviceId;

    @ConfigurationParameter("broadcast-writes")
    @BooleanDefaultValue(false)
    @Description("If true, write operations are sent to all connected devices.")
    private boolean broadcastWrites = false;

    @ConfigurationParameter("device-timeout")
    @IntDefaultValue(30000)
    @Description("Timeout in milliseconds to wait for a specific device to be available.")
    private int deviceTimeout = 30000;

    public String getTargetDeviceId() {
        return targetDeviceId;
    }

    public void setTargetDeviceId(String targetDeviceId) {
        this.targetDeviceId = targetDeviceId;
    }

    public boolean isBroadcastWrites() {
        return broadcastWrites;
    }

    public void setBroadcastWrites(boolean broadcastWrites) {
        this.broadcastWrites = broadcastWrites;
    }

    public int getDeviceTimeout() {
        return deviceTimeout;
    }

    public void setDeviceTimeout(int deviceTimeout) {
        this.deviceTimeout = deviceTimeout;
    }

    @Override
    public String toString() {
        return "ModbusTcpServerConfiguration{" +
            "targetDeviceId='" + targetDeviceId + '\'' +
            ", broadcastWrites=" + broadcastWrites +
            ", deviceTimeout=" + deviceTimeout +
            ", " + super.toString() +
            '}';
    }
}
