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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketTransportRegistryTest {

    private static final String DEVICE_ID = "registry-test-device";

    @AfterEach
    void tearDown() {
        SocketTransport.closeAndRemoveChannel(DEVICE_ID);
    }

    @Test
    void injectThenCloseUnregisters() throws Exception {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", server.socket().getLocalPort()));
            SocketChannel accepted = server.accept();
            try {
                SocketTransport.injectChannel(DEVICE_ID, accepted);
                assertTrue(SocketTransport.isChannelRegistered(DEVICE_ID));

                SocketTransport.closeAndRemoveChannel(DEVICE_ID);
                assertFalse(SocketTransport.isChannelRegistered(DEVICE_ID));
            } finally {
                client.close();
            }
        }
    }

    @Test
    void remoteCloseUnregisters() throws Exception {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", server.socket().getLocalPort()));
            SocketChannel accepted = server.accept();
            SocketTransport.injectChannel(DEVICE_ID, accepted);
            assertTrue(SocketTransport.isChannelRegistered(DEVICE_ID));

            client.close();
            long deadline = System.currentTimeMillis() + 3000;
            while (SocketTransport.isChannelRegistered(DEVICE_ID) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertFalse(SocketTransport.isChannelRegistered(DEVICE_ID));
        }
    }
}
