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

import org.apache.plc4x.java.spi.configuration.PlcConnectionConfiguration;
import org.apache.plc4x.java.spi.configuration.annotations.ConfigurationParameter;
import org.apache.plc4x.java.spi.configuration.annotations.Description;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.IntDefaultValue;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.StringDefaultValue;

/**
 * Configuration for DL/T 645-2007 Server mode.
 * <p>
 * In server mode, the PLC4X application listens for inbound TCP connections
 * from smart meters or DTU devices.
 */
public class Dlt645ServerConfiguration implements PlcConnectionConfiguration {

    @ConfigurationParameter("request-timeout")
    @IntDefaultValue(10_000)
    @Description("Default timeout for all types of requests in milliseconds.")
    private int requestTimeout;

    @ConfigurationParameter("target-device-id")
    @Description("Target meter address for operations. If not specified, uses broadcast address.")
    private String targetDeviceId;

    @ConfigurationParameter("device-timeout")
    @IntDefaultValue(30_000)
    @Description("Timeout in milliseconds to wait for a specific device to connect.")
    private int deviceTimeout;

    @ConfigurationParameter("password")
    @StringDefaultValue("")
    @Description("Password for write/admin operations (8 hex digits, PA field per Section 8.2.3).")
    private String password;

    @ConfigurationParameter("operator-code")
    @StringDefaultValue("")
    @Description("Operator code for write/admin operations (8 hex digits, P0 field per Section 8.2.3).")
    private String operatorCode;

    public int getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(int requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getOperatorCode() {
        return operatorCode;
    }

    public void setOperatorCode(String operatorCode) {
        this.operatorCode = operatorCode;
    }

    @Override
    public String toString() {
        return "Dlt645ServerConfiguration{" +
            "requestTimeout=" + requestTimeout +
            ", targetDeviceId='" + targetDeviceId + '\'' +
            ", deviceTimeout=" + deviceTimeout +
            '}';
    }
}
