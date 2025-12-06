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
package org.apache.plc4x.java.transport.tcp.server;

import org.apache.plc4x.java.spi.configuration.PlcTransportConfiguration;
import org.apache.plc4x.java.spi.configuration.annotations.ConfigurationParameter;
import org.apache.plc4x.java.spi.configuration.annotations.Description;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.BooleanDefaultValue;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.IntDefaultValue;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.LongDefaultValue;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.StringDefaultValue;

/**
 * Configuration for TCP Server Transport.
 *
 * <p>This configuration class defines all parameters needed for the TCP server
 * transport, including network settings, connection limits, and registration
 * packet parsing options.</p>
 *
 * <h3>Configuration Parameters:</h3>
 * <table>
 *   <tr><th>Parameter</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>bind-address</td><td>0.0.0.0</td><td>Address to bind the server socket</td></tr>
 *   <tr><td>worker-threads</td><td>CPU cores</td><td>Number of worker threads</td></tr>
 *   <tr><td>max-connections</td><td>1000</td><td>Maximum concurrent connections</td></tr>
 *   <tr><td>registration-timeout</td><td>5000</td><td>Registration timeout (ms)</td></tr>
 *   <tr><td>keep-alive</td><td>true</td><td>Enable TCP keep-alive</td></tr>
 *   <tr><td>registration-type</td><td>fixed</td><td>Parser type</td></tr>
 * </table>
 *
 * @since 0.14.0
 */
public class TcpServerTransportConfiguration implements PlcTransportConfiguration {

    /** Default port for Modbus TCP */
    public static final int DEFAULT_PORT = 502;

    // Network configuration

    @ConfigurationParameter("bind-address")
    @StringDefaultValue("0.0.0.0")
    @Description("The address to bind the server socket to. Use 0.0.0.0 to listen on all interfaces.")
    private String bindAddress = "0.0.0.0";

    @ConfigurationParameter("worker-threads")
    @IntDefaultValue(0)
    @Description("Number of worker threads for handling connections. 0 means use available processors.")
    private int workerThreads = 0;

    @ConfigurationParameter("backlog")
    @IntDefaultValue(128)
    @Description("The maximum queue length for incoming connection requests.")
    private int backlog = 128;

    // Connection limits

    @ConfigurationParameter("max-connections")
    @IntDefaultValue(1000)
    @Description("Maximum number of concurrent client connections.")
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

    // Getters and setters

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public int getWorkerThreads() {
        return workerThreads <= 0 ? Runtime.getRuntime().availableProcessors() : workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getBacklog() {
        return backlog;
    }

    public void setBacklog(int backlog) {
        this.backlog = backlog;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public long getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }

    public void setTcpNoDelay(boolean tcpNoDelay) {
        this.tcpNoDelay = tcpNoDelay;
    }

    public int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    public void setReceiveBufferSize(int receiveBufferSize) {
        this.receiveBufferSize = receiveBufferSize;
    }

    public int getSendBufferSize() {
        return sendBufferSize;
    }

    public void setSendBufferSize(int sendBufferSize) {
        this.sendBufferSize = sendBufferSize;
    }

    public long getRegistrationTimeout() {
        return registrationTimeout;
    }

    public void setRegistrationTimeout(long registrationTimeout) {
        this.registrationTimeout = registrationTimeout;
    }

    public String getRegistrationType() {
        return registrationType;
    }

    public void setRegistrationType(String registrationType) {
        this.registrationType = registrationType;
    }

    public int getRegistrationLength() {
        return registrationLength;
    }

    public void setRegistrationLength(int registrationLength) {
        this.registrationLength = registrationLength;
    }

    public String getRegistrationPattern() {
        return registrationPattern;
    }

    public void setRegistrationPattern(String registrationPattern) {
        this.registrationPattern = registrationPattern;
    }

    public int getRegistrationPrefixBytes() {
        return registrationPrefixBytes;
    }

    public void setRegistrationPrefixBytes(int registrationPrefixBytes) {
        this.registrationPrefixBytes = registrationPrefixBytes;
    }

    public String getRegistrationByteOrder() {
        return registrationByteOrder;
    }

    public void setRegistrationByteOrder(String registrationByteOrder) {
        this.registrationByteOrder = registrationByteOrder;
    }

    public String getRegistrationDelimiter() {
        return registrationDelimiter;
    }

    public void setRegistrationDelimiter(String registrationDelimiter) {
        this.registrationDelimiter = registrationDelimiter;
    }

    public int getRegistrationMaxLength() {
        return registrationMaxLength;
    }

    public void setRegistrationMaxLength(int registrationMaxLength) {
        this.registrationMaxLength = registrationMaxLength;
    }

    public String getRegistrationCharset() {
        return registrationCharset;
    }

    public void setRegistrationCharset(String registrationCharset) {
        this.registrationCharset = registrationCharset;
    }

    public String getDuplicateHandling() {
        return duplicateHandling;
    }

    public void setDuplicateHandling(String duplicateHandling) {
        this.duplicateHandling = duplicateHandling;
    }

    @Override
    public String toString() {
        return "TcpServerTransportConfiguration{" +
            "bindAddress='" + bindAddress + '\'' +
            ", workerThreads=" + getWorkerThreads() +
            ", maxConnections=" + maxConnections +
            ", registrationTimeout=" + registrationTimeout +
            ", registrationType='" + registrationType + '\'' +
            ", keepAlive=" + keepAlive +
            '}';
    }
}
