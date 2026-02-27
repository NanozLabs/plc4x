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
package org.apache.plc4x.java.dlt645.server.context;

import org.apache.plc4x.java.dlt645.server.config.Dlt645ServerConfiguration;
import org.apache.plc4x.java.spi.configuration.HasConfiguration;
import org.apache.plc4x.java.spi.context.DriverContext;
import org.apache.plc4x.java.transport.tcp.server.DeviceChannelRegistry;

/**
 * Driver context for DL/T 645-2007 Server mode.
 * <p>
 * Holds reference to the DeviceChannelRegistry for managing
 * multiple connected DTU devices.
 */
public class Dlt645ServerDriverContext implements DriverContext,
    HasConfiguration<Dlt645ServerConfiguration> {

    private int requestTimeout;
    private String targetDeviceId;
    private int deviceTimeout;
    private DeviceChannelRegistry deviceRegistry;

    @Override
    public void setConfiguration(Dlt645ServerConfiguration configuration) {
        this.requestTimeout = configuration.getRequestTimeout();
        this.targetDeviceId = configuration.getTargetDeviceId();
        this.deviceTimeout = configuration.getDeviceTimeout();
    }

    public int getRequestTimeout() {
        return requestTimeout;
    }

    public String getTargetDeviceId() {
        return targetDeviceId;
    }

    public int getDeviceTimeout() {
        return deviceTimeout;
    }

    public DeviceChannelRegistry getDeviceRegistry() {
        return deviceRegistry;
    }

    public void setDeviceRegistry(DeviceChannelRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    public boolean isDeviceConnected(String deviceId) {
        return deviceRegistry != null && deviceRegistry.isRegistered(deviceId);
    }

    public int getConnectedDeviceCount() {
        return deviceRegistry != null ? deviceRegistry.getDeviceCount() : 0;
    }
}
