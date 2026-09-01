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
package org.apache.plc4x.java.cjt188;

import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.authentication.PlcAuthentication;
import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.cjt188.config.Cjt188Configuration;
import org.apache.plc4x.java.cjt188.protocol.Cjt188Connection;
import org.apache.plc4x.java.cjt188.tag.Cjt188CommandTag;
import org.apache.plc4x.java.cjt188.tag.Cjt188Tag;
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
 * PLC4X Driver for CJ/T 188-2004 (household water / heat / gas meters).
 * <p>
 * Supports Serial (RS-485) and TCP transports.
 * <p>
 * Connection URI format:
 * <ul>
 *   <li>{@code cjt188:serial:///dev/ttyUSB0} — bus only; each tag names its meter</li>
 *   <li>{@code cjt188:serial:///dev/ttyUSB0?meter-address=123456789012&meter-type=10} — default meter</li>
 *   <li>{@code cjt188:tcp://192.168.1.100:8899?meter-address=10123456789012}</li>
 * </ul>
 */
public class Cjt188Driver extends DriverBase {

    @Override
    public String getProtocolCode() {
        return "cjt188";
    }

    @Override
    public String getProtocolName() {
        return "CJ/T 188-2004";
    }

    @Override
    protected Class<? extends Configuration> getConfigurationClass() {
        return Cjt188Configuration.class;
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
            !(connectionString.startsWith("cjt188:serial://") || connectionString.startsWith("cjt188://"))) {
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
    protected ConnectionBase<Cjt188Configuration> getConnection(Configuration configuration,
                                                                TransportInstance<?> transportInstance,
                                                                AuditLog auditLog) {
        return new Cjt188Connection((Cjt188Configuration) configuration, transportInstance, auditLog);
    }

    @Override
    public PlcTag prepareTag(String tagAddress) {
        if (Cjt188CommandTag.matches(tagAddress)) {
            return Cjt188CommandTag.of(tagAddress);
        }
        return Cjt188Tag.of(tagAddress);
    }

    @Override
    public Set<Integer> defaultPorts(String transportCode) {
        return Set.of(8899);
    }
}
