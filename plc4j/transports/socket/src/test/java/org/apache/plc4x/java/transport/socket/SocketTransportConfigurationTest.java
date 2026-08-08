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
package org.apache.plc4x.java.transport.socket;

import org.apache.plc4x.java.spi.config.ConfigurationFactory;
import org.apache.plc4x.java.transport.socket.config.SocketTransportConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the {@code socket.} prefixed parameters of the connection URL are parsed into
 * {@link SocketTransportConfiguration} (mirroring the {@code tcp.} prefix of the TCP transport).
 */
class SocketTransportConfigurationTest {

    @Test
    void parsesPrefixedDid() {
        ConfigurationFactory factory = new ConfigurationFactory();
        SocketTransportConfiguration config = factory.createPrefixedConfiguration(
            SocketTransportConfiguration.class, "socket",
            "socket.did=DEVICE001&socket.tcp-no-delay=false&socket.keep-alive=true&foo=bar");

        assertEquals("DEVICE001", config.did);
        assertEquals(false, config.tcpNoDelay);
        assertEquals(true, config.keepAlive);
    }

}
