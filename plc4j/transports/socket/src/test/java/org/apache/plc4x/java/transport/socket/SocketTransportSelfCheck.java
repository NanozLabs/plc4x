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
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.transport.socket.config.SocketTransportConfiguration;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Integration self-check: spins up a real {@code ServerSocket}, accepts a "DTU" client
 * connection, performs a (fake) registration handshake, then hands the accepted channel to
 * {@link SocketTransport#injectConnection} — exactly the user-owned listen/accept/register
 * flow the socket transport assumes. A {@code getConnection(url?did=X)} resolves the injected
 * channel and bytes flow both ways through the driver-side transport instance.
 *
 * <p>Run manually. Exits non-zero on failure.</p>
 */
public class SocketTransportSelfCheck {

    public static void main(String[] args) throws Exception {
        // 1. User-owned listen/accept/registration: accept a client and read its handshake bytes.
        try (ServerSocketChannel serverChannel = ServerSocketChannel.open();
             ServerSocket serverSocket = serverChannel.socket()) {
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            final int listenPort = serverSocket.getLocalPort();
            final String deviceId = "DEVICE001";

            CompletableFuture<SocketChannel> acceptedFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    // NOT try-with-resources: the accepted channel must survive past this lambda,
                    // it is handed to SocketTransport.injectConnection below.
                    SocketChannel accepted = serverChannel.accept();
                    accepted.configureBlocking(true);
                    // Fake registration handshake: the user reads exactly 10 bytes then consumes them.
                    InputStream in = accepted.socket().getInputStream();
                    byte[] handshake = new byte[10];
                    int read = 0;
                    while (read < handshake.length) {
                        int r = in.read(handshake, read, handshake.length - read);
                        if (r < 0) {
                            throw new IllegalStateException("client closed during handshake");
                        }
                        read += r;
                    }
                    String got = new String(handshake).trim();
                    if (!deviceId.equals(got)) {
                        throw new IllegalStateException("unexpected device id in handshake: " + got);
                    }
                    return accepted; // handshake bytes consumed; channel handed over
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // 2. Fake DTU client connects, sends the 10-byte registration packet.
            CompletableFuture<Void> clientFuture = CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(100);
                    try (Socket client = new Socket()) {
                        client.connect(new InetSocketAddress("127.0.0.1", listenPort));
                        OutputStream out = client.getOutputStream();
                        byte[] reg = new byte[10];
                        byte[] id = deviceId.getBytes();
                        System.arraycopy(id, 0, reg, 0, id.length);
                        out.write(reg);
                        out.flush();

                        // Protocol bytes after registration: a fake frame.
                        byte[] frame = new byte[]{0x01, 0x03, 0x00, 0x00, 0x00, 0x0A};
                        out.write(frame);
                        out.flush();

                        // Read the transport's routed echo.
                        InputStream in = client.getInputStream();
                        byte[] buf = new byte[16];
                        long deadline = System.currentTimeMillis() + 5000;
                        int total = 0;
                        while (total == 0 && System.currentTimeMillis() < deadline) {
                            int r = in.read(buf, total, buf.length - total);
                            if (r < 0) break;
                            total += r;
                        }
                        if (total != 4 || buf[0] != 'p' || buf[1] != 'i' || buf[2] != 'n' || buf[3] != 'g') {
                            throw new IllegalStateException("unexpected echo: " + total + " bytes");
                        }
                        Thread.sleep(300); // let the server drain the frame before we disconnect
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // 3. Inject the accepted channel under the device id.
            SocketChannel channel = acceptedFuture.get(10, TimeUnit.SECONDS);
            SocketTransport transport = new SocketTransport();
            transport.injectConnection(deviceId, channel);
            if (!transport.isDeviceRegistered(deviceId)) {
                throw new AssertionError("device not registered after inject");
            }

            // 4. Resolve via a connection-string-like flow (did → config → createTransportInstance).
            ConfigurationFactory configFactory = new ConfigurationFactory();
            SocketTransportConfiguration config = configFactory.createConfiguration(
                SocketTransportConfiguration.class, "did=" + deviceId);
            TransportInstance<SocketTransportConfiguration> instance =
                transport.createTransportInstance("socket://", config, AuditLog.builder().build());

            // 5. Write "ping", then drain the transport until the client's protocol frame
            //    (which may already be sitting in the ring buffer — a listener registered after
            //    data arrival is not re-notified) is observed.
            instance.write("ping".getBytes());

            byte[] data = null;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                if (instance.getNumBytesAvailable() > 0) {
                    data = instance.read(instance.getNumBytesAvailable());
                    break;
                }
                Thread.sleep(50);
            }
            if (data == null || data.length != 6) {
                throw new AssertionError("expected 6 protocol bytes but got " + (data == null ? "null" : data.length));
            }
            System.out.println("routed read: " + data.length + " bytes");

            clientFuture.get(10, TimeUnit.SECONDS);
            instance.close();
            System.out.println("ALL SOCKET TRANSPORT CHECKS PASSED");
        }
    }

}
