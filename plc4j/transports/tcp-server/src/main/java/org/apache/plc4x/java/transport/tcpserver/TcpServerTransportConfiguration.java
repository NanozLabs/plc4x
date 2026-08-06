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
package org.apache.plc4x.java.transport.tcpserver;

import org.apache.plc4x.java.spi.config.annotations.ConfigurationParameter;
import org.apache.plc4x.java.spi.config.annotations.Description;
import org.apache.plc4x.java.spi.config.annotations.defaults.BooleanDefaultValue;
import org.apache.plc4x.java.spi.config.annotations.defaults.IntDefaultValue;
import org.apache.plc4x.java.spi.config.annotations.defaults.LongDefaultValue;
import org.apache.plc4x.java.spi.config.annotations.defaults.StringDefaultValue;
import org.apache.plc4x.java.spi.transports.api.config.TransportConfiguration;

/**
 * Configuration for the TCP Server transport.
 *
 * <p>Defines all parameters needed for the TCP server transport, including network
 * settings, connection limits, and registration packet parsing options.</p>
 *
 * <h3>Configuration Parameters:</h3>
 * <table>
 *   <tr><th>Parameter</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>bind-address</td><td>0.0.0.0</td><td>Address to bind the server socket</td></tr>
 *   <tr><td>port</td><td>0</td><td>Port to listen on (0 = derived from the URL)</td></tr>
 *   <tr><td>max-connections</td><td>1000</td><td>Maximum concurrent connections</td></tr>
 *   <tr><td>registration-timeout</td><td>5000</td><td>Registration timeout (ms)</td></tr>
 *   <tr><td>registration-type</td><td>fixed</td><td>Parser type</td></tr>
 *   <tr><td>target-device-id</td><td>-</td><td>Default target device for routing</td></tr>
 * </table>
 */
public class TcpServerTransportConfiguration implements TransportConfiguration {

    /** Default port for Modbus TCP */
    public static final int DEFAULT_PORT = 502;

    /** Port value that means "take the port from the connection URL". */
    public static final int PORT_FROM_URL = 0;

    // Network configuration

    @ConfigurationParameter("bind-address")
    @StringDefaultValue("0.0.0.0")
    @Description("The address to bind the server socket to. Use 0.0.0.0 to listen on all interfaces.")
    private String bindAddress = "0.0.0.0";

    @ConfigurationParameter("port")
    @IntDefaultValue(0)
    @Description("The port to listen on. 0 means the port is taken from the connection URL.")
    private int port = PORT_FROM_URL;

    @ConfigurationParameter("backlog")
    @IntDefaultValue(128)
    @Description("The maximum queue length for incoming connection requests.")
    private int backlog = 128;

    // Connection limits

    @ConfigurationParameter("max-connections")
    @IntDefaultValue(1000)
    @Description("Maximum number of concurrent client connections. 0 for unlimited.")
    private int maxConnections = 1000;

    @ConfigurationParameter("idle-timeout")
    @LongDefaultValue(300000)
    @Description("Idle timeout in milliseconds. Connections idle longer will be closed. 0 to disable.")
    private long idleTimeout = 300000;

    // TCP options

    @ConfigurationParameter("keep-alive")
    @BooleanDefaultValue(true)
    @Description("Enable TCP keep-alive packets for connection health monitoring.")
    private boolean keepAlive = true;

    @ConfigurationParameter("tcp-no-delay")
    @BooleanDefaultValue(true)
    @Description("Disable Nagle's algorithm for reduced latency.")
    private boolean tcpNoDelay = true;

    @ConfigurationParameter("receive-buffer-size")
    @IntDefaultValue(65536)
    @Description("Size of the TCP receive buffer in bytes.")
    private int receiveBufferSize = 65536;

    @ConfigurationParameter("send-buffer-size")
    @IntDefaultValue(65536)
    @Description("Size of the TCP send buffer in bytes.")
    private int sendBufferSize = 65536;

    // Registration configuration

    @ConfigurationParameter("registration-timeout")
    @LongDefaultValue(5000)
    @Description("Timeout in milliseconds waiting for registration packet after connection.")
    private long registrationTimeout = 5000;

    @ConfigurationParameter("registration-type")
    @StringDefaultValue("fixed")
    @Description("Registration packet parser type: fixed, regex, prefix, delimiter")
    private String registrationType = "fixed";

    @ConfigurationParameter("registration-length")
    @IntDefaultValue(16)
    @Description("For fixed-length parser: the exact number of bytes to read.")
    private int registrationLength = 16;

    @ConfigurationParameter("registration-pattern")
    @Description("For regex parser: the pattern with capturing group for device ID.")
    private String registrationPattern;

    @ConfigurationParameter("registration-prefix-bytes")
    @IntDefaultValue(2)
    @Description("For prefix-length parser: number of bytes for length prefix (1, 2, or 4).")
    private int registrationPrefixBytes = 2;

    @ConfigurationParameter("registration-byte-order")
    @StringDefaultValue("BIG_ENDIAN")
    @Description("For prefix-length parser: byte order (BIG_ENDIAN or LITTLE_ENDIAN).")
    private String registrationByteOrder = "BIG_ENDIAN";

    @ConfigurationParameter("registration-delimiter")
    @StringDefaultValue("CRLF")
    @Description("For delimiter parser: the delimiter (CRLF, LF, NULL, or hex like 0D0A).")
    private String registrationDelimiter = "CRLF";

    @ConfigurationParameter("registration-max-length")
    @IntDefaultValue(256)
    @Description("Maximum length for variable-length registration packets.")
    private int registrationMaxLength = 256;

    @ConfigurationParameter("registration-charset")
    @StringDefaultValue("UTF-8")
    @Description("Character encoding for registration packet decoding.")
    private String registrationCharset = "UTF-8";

    // Duplicate handling

    @ConfigurationParameter("duplicate-handling")
    @StringDefaultValue("replace")
    @Description("How to handle duplicate device registrations: replace (close old), reject, or allow")
    private String duplicateHandling = "replace";

    // Routing

    @ConfigurationParameter("target-device-id")
    @Description("Default target device ID used when a tag does not specify a device-id.")
    private String targetDeviceId;

    public String getBindAddress() {
        return bindAddress;
    }

    public int getPort() {
        return port;
    }

    public int getBacklog() {
        return backlog;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public long getIdleTimeout() {
        return idleTimeout;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }

    public int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    public int getSendBufferSize() {
        return sendBufferSize;
    }

    public long getRegistrationTimeout() {
        return registrationTimeout;
    }

    public String getRegistrationType() {
        return registrationType;
    }

    public int getRegistrationLength() {
        return registrationLength;
    }

    public String getRegistrationPattern() {
        return registrationPattern;
    }

    public int getRegistrationPrefixBytes() {
        return registrationPrefixBytes;
    }

    public String getRegistrationByteOrder() {
        return registrationByteOrder;
    }

    public String getRegistrationDelimiter() {
        return registrationDelimiter;
    }

    public int getRegistrationMaxLength() {
        return registrationMaxLength;
    }

    public String getRegistrationCharset() {
        return registrationCharset;
    }

    public String getDuplicateHandling() {
        return duplicateHandling;
    }

    public String getTargetDeviceId() {
        return targetDeviceId;
    }

    @Override
    public String toString() {
        return "TcpServerTransportConfiguration{" +
            "bindAddress='" + bindAddress + '\'' +
            ", port=" + port +
            ", maxConnections=" + maxConnections +
            ", registrationTimeout=" + registrationTimeout +
            ", registrationType='" + registrationType + '\'' +
            ", keepAlive=" + keepAlive +
            ", targetDeviceId='" + targetDeviceId + '\'' +
            '}';
    }
}
