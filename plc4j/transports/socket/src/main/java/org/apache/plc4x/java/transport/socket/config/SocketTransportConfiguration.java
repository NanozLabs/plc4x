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
package org.apache.plc4x.java.transport.socket.config;

import org.apache.plc4x.java.spi.config.annotations.ConfigurationParameter;
import org.apache.plc4x.java.spi.config.annotations.defaults.BooleanDefaultValue;
import org.apache.plc4x.java.spi.config.annotations.defaults.IntDefaultValue;
import org.apache.plc4x.java.spi.transports.api.config.TransportConfiguration;

/**
 * Configuration for the socket transport.
 *
 * <p>Each device connection is addressed by {@code did} (device id): the identifier the
 * user registered the injected {@link java.nio.channels.SocketChannel} under. The user owns
 * listen/accept/registration and hands plc4x an established channel per device; this transport
 * only wraps it and looks it up by {@code socket.did}.</p>
 */
public class SocketTransportConfiguration implements TransportConfiguration {

    /**
     * Device id. Required to pick the right injected connection from the registry.
     */
    @ConfigurationParameter("did")
    public String did;

    /**
     * Enable TCP_NODELAY (disable Nagle's algorithm).
     */
    @ConfigurationParameter("tcp-no-delay")
    @BooleanDefaultValue(true)
    public boolean tcpNoDelay;

    /**
     * Enable SO_KEEPALIVE.
     */
    @ConfigurationParameter("keep-alive")
    @BooleanDefaultValue(false)
    public boolean keepAlive;

    /**
     * Send buffer size in bytes. 0 uses system default.
     */
    @ConfigurationParameter("send-buffer-size")
    @IntDefaultValue(0)
    public int sendBufferSize;

    /**
     * Receive buffer size in bytes. 0 uses the default ring-buffer size.
     */
    @ConfigurationParameter("receive-buffer-size")
    @IntDefaultValue(81920)
    public int receiveBufferSize;

}
