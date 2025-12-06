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
package org.apache.plc4x.java.modbus.tcpserver.context;

import org.apache.plc4x.java.modbus.tcp.context.ModbusTcpContext;
import org.apache.plc4x.java.transport.tcp.server.DeviceChannelRegistry;

/**
 * Driver context for Modbus TCP Server mode.
 *
 * <p>This context extends the standard Modbus TCP context with server-specific
 * state, including reference to the device registry for managing multiple
 * connected DTU devices.</p>
 *
 * @since 0.14.0
 */
public class ModbusTcpServerContext extends ModbusTcpContext {

    private DeviceChannelRegistry deviceRegistry;
    private String currentDeviceId;

    /**
     * Gets the device registry for managing connected devices.
     *
     * @return the device registry, may be null if not set
     */
    public DeviceChannelRegistry getDeviceRegistry() {
        return deviceRegistry;
    }

    /**
     * Sets the device registry.
     *
     * @param deviceRegistry the device registry
     */
    public void setDeviceRegistry(DeviceChannelRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    /**
     * Gets the current device ID being addressed.
     *
     * @return the current device ID, may be null
     */
    public String getCurrentDeviceId() {
        return currentDeviceId;
    }

    /**
     * Sets the current device ID for operations.
     *
     * @param currentDeviceId the device ID
     */
    public void setCurrentDeviceId(String currentDeviceId) {
        this.currentDeviceId = currentDeviceId;
    }

    /**
     * Checks if a specific device is connected.
     *
     * @param deviceId the device identifier
     * @return true if the device is connected
     */
    public boolean isDeviceConnected(String deviceId) {
        return deviceRegistry != null && deviceRegistry.isRegistered(deviceId);
    }

    /**
     * Gets the number of connected devices.
     *
     * @return the device count
     */
    public int getConnectedDeviceCount() {
        return deviceRegistry != null ? deviceRegistry.getDeviceCount() : 0;
    }
}
