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
package org.apache.plc4x.java.dlt645.server.config;

import org.apache.plc4x.java.dlt645.config.Dlt645Configuration;
import org.apache.plc4x.java.spi.config.annotations.ConfigurationParameter;
import org.apache.plc4x.java.spi.config.annotations.Description;
import org.apache.plc4x.java.spi.config.annotations.defaults.IntDefaultValue;

/**
 * Configuration for DL/T 645-2007 Server mode.
 * <p>
 * In server mode, the PLC4X application listens for inbound TCP connections
 * from smart meters or DTU devices.
 * <p>
 * Extends {@link Dlt645Configuration} so the per-device sub-connections can be built
 * with the full client configuration (meter-address, password, operator-code).
 */
public class Dlt645ServerConfiguration extends Dlt645Configuration {

    public Dlt645ServerConfiguration() {
        // Server mode default: 10s (client default is 5s, set in Dlt645Configuration).
        // An explicit request-timeout URL parameter still overrides this via reflection.
        requestTimeout = 10_000;
    }

    @ConfigurationParameter("target-device-id")
    @Description("Target meter address for operations. If not specified, uses broadcast address.")
    private String targetDeviceId;

    @ConfigurationParameter("device-timeout")
    @IntDefaultValue(30_000)
    @Description("Timeout in milliseconds to wait for a specific device to connect.")
    private int deviceTimeout;

    public String getTargetDeviceId() {
        return targetDeviceId;
    }

    public void setTargetDeviceId(String targetDeviceId) {
        this.targetDeviceId = targetDeviceId;
    }

    public int getDeviceTimeout() {
        return deviceTimeout;
    }

    public void setDeviceTimeout(int deviceTimeout) {
        this.deviceTimeout = deviceTimeout;
    }

    @Override
    public String toString() {
        return "Dlt645ServerConfiguration{" +
            "requestTimeout=" + getRequestTimeout() +
            ", targetDeviceId='" + targetDeviceId + '\'' +
            ", deviceTimeout=" + deviceTimeout +
            '}';
    }
}
