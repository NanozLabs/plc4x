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

import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.transports.api.exceptions.TransportException;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Integration self-check: spins up the TCP server transport on an ephemeral port,
 * connects a fake DTU client that sends a registration packet followed by protocol
 * bytes, and verifies that the RoutingTransportInstance routes to the registered device.
 *
 * <p>Run manually. Exits non-zero on failure.</p>
 */
public class TcpServerTransportSelfCheck {

    public static void main(String[] args) throws Exception {
        TcpServerTransport transport = new TcpServerTransport();
        // Use a 10-byte fixed-length registration packet to match the fake DTU below.
        org.apache.plc4x.java.spi.config.ConfigurationFactory configFactory =
            new org.apache.plc4x.java.spi.config.ConfigurationFactory();
        TcpServerTransportConfiguration configuration = configFactory.createConfiguration(
            TcpServerTransportConfiguration.class, "registration-type=fixed&registration-length=10");

        // Bind ephemeral port via the URL.
        String url = "0.0.0.0:0";
        TransportInstance<TcpServerTransportConfiguration> instance =
            transport.createTransportInstance(url, configuration, AuditLog.builder().build());

        if (!(instance instanceof RoutingTransportInstance routing)) {
            throw new AssertionError("Expected RoutingTransportInstance but was " + instance.getClass());
        }

        // Connect a fake DTU: send "DEVICE001" fixed 10-byte registration then a request.
        final int serverPort = routing.getLocalPort();
        if (serverPort == 0) {
            throw new AssertionError("Server port not bound");
        }
        CompletableFuture<Void> clientFuture = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(200);
                try (Socket socket = new Socket("127.0.0.1", serverPort)) {
                    OutputStream out = socket.getOutputStream();
                    // Registration packet: 10 bytes "DEVICE001 "
                    byte[] reg = new byte[10];
                    byte[] id = "DEVICE001".getBytes(StandardCharsets.UTF_8);
                    System.arraycopy(id, 0, reg, 0, id.length);
                    reg[9] = ' ';
                    out.write(reg);
                    out.flush();

                    // Protocol bytes: a fake Modbus-ish frame.
                    byte[] request = new byte[]{0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x01, 0x03, 0x00, 0x00, 0x00, 0x0A};
                    out.write(request);
                    out.flush();

                    // Read the routed echo response (server should have written something).
                    InputStream in = socket.getInputStream();
                    byte[] buf = new byte[64];
                    long deadline = System.currentTimeMillis() + 5000;
                    int total = 0;
                    while (total == 0 && System.currentTimeMillis() < deadline) {
                        int r = in.read(buf, total, buf.length - total);
                        if (r < 0) break;
                        total += r;
                    }
                    if (total == 0) {
                        System.out.println("client: no response received (ok for echo-less test)");
                    } else {
                        System.out.println("client: read " + total + " bytes response");
                    }
                    // Keep the socket open briefly so the server read loop can drain
                    // the second protocol chunk before we disconnect.
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for the device to register.
        waitForDevice(routing, "DEVICE001", 5000);

        // Route a write to the device and verify it is delivered.
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicInteger receivedCount = new AtomicInteger();
        routing.setTargetDevice("DEVICE001");
        routing.registerDataListener(() -> {
            try {
                int available = routing.getNumBytesAvailable();
                if (available > 0) {
                    received.set(routing.read(available));
                    receivedCount.incrementAndGet();
                }
            } catch (TransportException e) {
                throw new RuntimeException(e);
            }
        });

        byte[] payload = "ping".getBytes(StandardCharsets.UTF_8);
        routing.write(payload);

        // Wait for the routed read to observe the client's protocol bytes. The protocol
        // bytes may arrive across multiple reads (and partly in the same read as the
        // registration packet), so accumulate until all 12 bytes are seen.
        java.io.ByteArrayOutputStream accumulated = new java.io.ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 5000;
        while (accumulated.size() < 12 && System.currentTimeMillis() < deadline) {
            byte[] chunk = received.getAndSet(null);
            if (chunk != null) {
                accumulated.write(chunk, 0, chunk.length);
            } else if (routing.getRegistry().isRegistered("DEVICE001")
                    && routing.getNumBytesAvailable() > 0) {
                accumulated.write(routing.read(routing.getNumBytesAvailable()));
            } else {
                Thread.sleep(50);
            }
        }
        byte[] data = accumulated.toByteArray();
        System.out.println("routed read: " + data.length + " bytes: " + toHex(data));
        if (data.length != 12) {
            throw new AssertionError("Expected 12 protocol bytes but got " + data.length);
        }

        clientFuture.get(10, TimeUnit.SECONDS);

        routing.close();
        System.out.println("ALL TCP SERVER TRANSPORT CHECKS PASSED");
    }

    private static void waitForDevice(RoutingTransportInstance routing, String deviceId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (routing.getRegistry().isRegistered(deviceId)) {
                System.out.println("device '" + deviceId + "' registered");
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Device '" + deviceId + "' did not register within " + timeoutMs + "ms");
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
