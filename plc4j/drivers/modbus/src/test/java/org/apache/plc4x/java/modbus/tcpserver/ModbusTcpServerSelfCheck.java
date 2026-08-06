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
package org.apache.plc4x.java.modbus.tcpserver;

import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.spi.drivers.ConnectionBase;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.transport.tcpserver.RoutingTransportInstance;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runtime self-check for the Modbus TCP Server driver.
 *
 * <p>Spins up the {@code tcp-server} transport on an ephemeral port, connects two fake DTUs
 * that register as {@code DEVICE001} / {@code DEVICE002}, then reads a holding register from
 * each via the {@code device-id} tag config and verifies the responses are routed to the
 * correct device.</p>
 *
 * <p>Run manually (no JUnit). Exits non-zero on failure.</p>
 */
public class ModbusTcpServerSelfCheck {

    public static void main(String[] args) throws Exception {
        // Transport parameters are prefixed with the transport code ("tcp-server.") by DriverBase.
        try (PlcConnection connection = new DefaultPlcDriverManager().getConnection(
                "modbus-tcp-server:tcp-server://127.0.0.1:0?tcp-server.registration-type=fixed&tcp-server.registration-length=10")) {

            TransportInstance<?> transportInstance = ((ConnectionBase<?>) connection).getTransportInstance();
            if (!(transportInstance instanceof RoutingTransportInstance routing)) {
                throw new AssertionError("Expected RoutingTransportInstance but got " + transportInstance.getClass());
            }
            int serverPort = routing.getLocalPort();
            if (serverPort == 0) {
                throw new AssertionError("Server port not bound");
            }
            System.out.println("Modbus TCP server bound on port " + serverPort);

            // Two fake DTUs: DEVICE001 answers 42, DEVICE002 answers 7.
            CompletableFuture<Void> dtu1 = connectDtu(serverPort, "DEVICE001", 42);
            CompletableFuture<Void> dtu2 = connectDtu(serverPort, "DEVICE002", 7);

            waitRegistered(routing, "DEVICE001", 5000);
            waitRegistered(routing, "DEVICE002", 5000);
            System.out.println("Both DTUs registered");

            PlcReadResponse resp1 = connection.readRequestBuilder()
                .addTagAddress("value", "holding-register:1:INT{device-id:'DEVICE001'}")
                .build().execute().get(10, TimeUnit.SECONDS);
            Integer v1 = resp1.getInteger("value");
            if (v1 == null || v1 != 42) {
                throw new AssertionError("DEVICE001: expected 42 but got " + v1);
            }

            PlcReadResponse resp2 = connection.readRequestBuilder()
                .addTagAddress("value", "holding-register:1:INT{device-id:'DEVICE002'}")
                .build().execute().get(10, TimeUnit.SECONDS);
            Integer v2 = resp2.getInteger("value");
            if (v2 == null || v2 != 7) {
                throw new AssertionError("DEVICE002: expected 7 but got " + v2);
            }

            dtu1.get(10, TimeUnit.SECONDS);
            dtu2.get(10, TimeUnit.SECONDS);
            System.out.println("ALL MODBUS TCP SERVER CHECKS PASSED (DEVICE001=" + v1 + ", DEVICE002=" + v2 + ")");
        }
    }

    /**
     * Connects a fake DTU that registers under the given id and answers every
     * read-holding-registers request with the given value.
     */
    private static CompletableFuture<Void> connectDtu(int serverPort, String deviceId, int value) {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(300); // let the server accept loop settle
                try (Socket socket = new Socket("127.0.0.1", serverPort)) {
                    OutputStream out = socket.getOutputStream();
                    // Registration packet: fixed-length, padded with a space.
                    byte[] reg = new byte[10];
                    byte[] id = deviceId.getBytes(StandardCharsets.UTF_8);
                    System.arraycopy(id, 0, reg, 0, id.length);
                    reg[id.length] = ' ';
                    out.write(reg);
                    out.flush();

                    InputStream in = socket.getInputStream();
                    long deadline = System.currentTimeMillis() + 8000;
                    // Answer exactly one request (the self-check reads once per device).
                    byte[] header = readFully(in, 6, deadline);
                    int length = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
                    byte[] rest = readFully(in, length, deadline);
                    byte[] request = concat(header, rest);
                    System.out.println(deviceId + " received request: " + toHex(request));

                    // Echo transaction id and unit id back with a read-holding-register
                    // response: FC 0x03, byte count 2, value.
                    byte[] response = new byte[]{
                        request[0], request[1],       // transaction id
                        0x00, 0x00,                   // protocol id
                        0x00, 0x05,                   // length: unitId(1) + fc(1) + byteCount(1) + 2
                        request[6],                   // unit id
                        0x03,                         // read holding registers
                        0x02,                         // byte count
                        (byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)
                    };
                    out.write(response);
                    out.flush();
                    System.out.println(deviceId + " sent response: " + toHex(response));

                    // Keep the socket open briefly so the server-side read loop drains.
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void waitRegistered(RoutingTransportInstance routing, String deviceId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (routing.getRegistry().isRegistered(deviceId)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("DTU '" + deviceId + "' did not register within " + timeoutMs + "ms");
    }

    private static byte[] readFully(InputStream in, int count, long deadline) throws Exception {
        byte[] buf = new byte[count];
        int off = 0;
        while (off < count && System.currentTimeMillis() < deadline) {
            int r = in.read(buf, off, count - off);
            if (r < 0) {
                throw new java.io.EOFException("DTU socket closed by server");
            }
            off += r;
        }
        if (off < count) {
            throw new java.io.EOFException("Timeout reading " + count + " bytes from DTU socket");
        }
        return buf;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
