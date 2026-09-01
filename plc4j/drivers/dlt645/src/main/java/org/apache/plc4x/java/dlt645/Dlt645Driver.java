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
package org.apache.plc4x.java.dlt645;

import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.authentication.PlcAuthentication;
import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.dlt645.config.Dlt645Configuration;
import org.apache.plc4x.java.dlt645.protocol.Dlt645Connection;
import org.apache.plc4x.java.dlt645.tag.Dlt645CommandTag;
import org.apache.plc4x.java.dlt645.tag.Dlt645Tag;
import org.apache.plc4x.java.spi.config.Configuration;
import org.apache.plc4x.java.spi.drivers.ConnectionBase;
import org.apache.plc4x.java.spi.drivers.DriverBase;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PLC4X Driver for DL/T 645-2007 (China Smart Meter Communication Protocol).
 * <p>
 * Supports Serial (RS-485) and TCP transports.
 * <p>
 * Connection URI format:
 * <ul>
 *   <li>{@code dlt645:serial:///dev/ttyUSB0} — shared bus; put the meter on the tag</li>
 *   <li>{@code dlt645:serial:///dev/ttyUSB0?meter-address=123456789012} — single-meter default</li>
 *   <li>{@code dlt645:tcp://192.168.1.100:8899}</li>
 * </ul>
 */
public class Dlt645Driver extends DriverBase {

    @Override
    public String getProtocolCode() {
        return "dlt645";
    }

    @Override
    public String getProtocolName() {
        return "DL/T 645-2007";
    }

    @Override
    protected Class<? extends Configuration> getConfigurationClass() {
        return Dlt645Configuration.class;
    }

    @Override
    public PlcConnection getConnection(String connectionString) throws PlcConnectionException {
        return getConnection(connectionString, null);
    }

    @Override
    public PlcConnection getConnection(String connectionString, PlcAuthentication authentication)
        throws PlcConnectionException {
        return super.getConnection(withSerialDefaults(connectionString), authentication);
    }

    private static String withSerialDefaults(String connectionString) {
        if (connectionString == null ||
            !(connectionString.startsWith("dlt645:serial://") || connectionString.startsWith("dlt645://"))) {
            return connectionString;
        }
        StringBuilder defaults = new StringBuilder();
        appendDefault(defaults, connectionString, "serial.baud-rate", "2400");
        appendDefault(defaults, connectionString, "serial.data-bits", "8");
        appendDefault(defaults, connectionString, "serial.stop-bits", "1");
        appendDefault(defaults, connectionString, "serial.parity", "even");
        if (defaults.length() == 0) {
            return connectionString;
        }
        return connectionString + (connectionString.contains("?") ? "&" : "?") + defaults;
    }

    private static void appendDefault(StringBuilder defaults, String connectionString, String name, String value) {
        if (connectionString.matches(".*(?:[?&])" + java.util.regex.Pattern.quote(name) + "=[^&]*.*")) {
            return;
        }
        if (defaults.length() > 0) {
            defaults.append('&');
        }
        defaults.append(name).append('=').append(value);
    }

    @Override
    public Optional<String> getDefaultTransportCode() {
        return Optional.of("serial");
    }

    @Override
    public List<String> getSupportedTransportCodes() {
        return Arrays.asList("tcp", "serial");
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
    protected ConnectionBase<Dlt645Configuration> getConnection(Configuration configuration,
                                                                TransportInstance<?> transportInstance,
                                                                AuditLog auditLog) {
        return new Dlt645Connection((Dlt645Configuration) configuration, transportInstance, auditLog);
    }

    @Override
    public PlcTag prepareTag(String tagAddress) {
        if (Dlt645CommandTag.matches(tagAddress)) {
            return Dlt645CommandTag.of(tagAddress);
        }
        return Dlt645Tag.of(tagAddress);
    }

    @Override
    public Set<Integer> defaultPorts(String transportCode) {
        return Set.of(8899);
    }
}
