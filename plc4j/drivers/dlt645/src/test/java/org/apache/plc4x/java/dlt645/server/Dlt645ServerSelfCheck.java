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
package org.apache.plc4x.java.dlt645.server;

import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.dlt645.readwrite.ControlCode;
import org.apache.plc4x.java.dlt645.readwrite.Dlt645Frame;
import org.apache.plc4x.java.spi.drivers.ConnectionBase;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.transport.tcpserver.RoutingTransportInstance;
import org.apache.plc4x.java.transport.tcpserver.TcpServerChannelRegistry;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime self-check for the DL/T 645-2007 Server driver (SPI3).
 *
 * <p>Spins up the {@code tcp-server} transport on an ephemeral port, connects a fake smart
 * meter that registers as {@code 123456789012}, then reads the meter address and a data
 * identifier ({@code 00010000}) through a {@link Dlt645DeviceConnection} bound to that
 * device — verifying the server connection, device routing and frame codec end to end.</p>
 *
 * <p>Run manually (no JUnit). Exits non-zero on failure.</p>
 */
public class Dlt645ServerSelfCheck {

    private static final String DEVICE_ID = "123456789012";

    public static void main(String[] args) throws Exception {
        try (PlcConnection connection = new DefaultPlcDriverManager().getConnection(
                "dlt645-server:tcp-server://127.0.0.1:0?tcp-server.registration-type=fixed&tcp-server.registration-length=16")) {

            Dlt645ServerConnection server = (Dlt645ServerConnection) connection;
            TransportInstance<?> transportInstance = ((ConnectionBase<?>) connection).getTransportInstance();
            if (!(transportInstance instanceof RoutingTransportInstance routing)) {
                throw new AssertionError("Expected RoutingTransportInstance but got " + transportInstance.getClass());
            }
            int serverPort = routing.getLocalPort();
            if (serverPort == 0) {
                throw new AssertionError("Server port not bound");
            }
            System.out.println("DL/T 645 server bound on port " + serverPort);

            AtomicReference<Dlt645ServerConnection.DeviceEvent> connectedEvent = new AtomicReference<>();
            CountDownLatch connectedLatch = new CountDownLatch(1);
            server.onDeviceConnected(event -> {
                connectedEvent.set(event);
                connectedLatch.countDown();
            });

            // Fake smart meter: registers as DEVICE_ID, then answers each DL/T 645 frame.
            CompletableFuture<Void> meter = connectMeter(serverPort);

            if (!connectedLatch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Device did not register within 10s");
            }
            if (!DEVICE_ID.equals(connectedEvent.get().getDeviceId())) {
                throw new AssertionError("Unexpected connected device: " + connectedEvent.get());
            }
            waitRegistered(routing, DEVICE_ID, 5000);
            System.out.println("Device " + DEVICE_ID + " registered (event + registry)");

            // Device sub-connection bound to the meter, clean tags (no device-id).
            Dlt645DeviceConnection device = server.getDeviceConnection(DEVICE_ID);
            if (!device.isConnected()) {
                throw new AssertionError("Device connection reports not connected");
            }
            System.out.println("Device connection bound: " + device.getDeviceId());

            // Read meter address (0x13): fake meter answers "123456789012".
            PlcReadResponse addressResponse = device.readRequestBuilder()
                .addTagAddress("address", "cmd:read-address")
                .build().execute().get(10, TimeUnit.SECONDS);
            String address = addressResponse.getString("address");
            if (!DEVICE_ID.equals(address)) {
                throw new AssertionError("Read-address: expected " + DEVICE_ID + " but got " + address);
            }
            System.out.println("Read meter address OK: " + address);

            // Read data identifier 00010000 (total positive active energy, 4-byte BCD, 2 decimals).
            // Fake meter answers 0x91 with value data 0000 0001 23 (LSB first) → 123.00 kWh.
            PlcReadResponse energyResponse = device.readRequestBuilder()
                .addTagAddress("energy", "00010000")
                .build().execute().get(10, TimeUnit.SECONDS);
            float energy = energyResponse.getFloat("energy");
            System.out.println("Read energy: " + energy + " kWh");
            if (Math.abs(energy - 123.00f) > 0.001f) {
                throw new AssertionError("Energy: expected 123.0 but got " + energy);
            }

            meter.get(10, TimeUnit.SECONDS);
            System.out.println("ALL DL/T 645 SERVER CHECKS PASSED (device=" + DEVICE_ID + ", energy=" + energy + ")");
        }
    }

    /**
     * Connects a fake smart meter: sends the fixed-length registration packet (16 bytes,
     * device id padded with spaces), then serves DL/T 645 frames on the socket.
     */
    private static CompletableFuture<Void> connectMeter(int serverPort) {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(300); // let the server accept loop settle
                try (Socket socket = new Socket("127.0.0.1", serverPort)) {
                    OutputStream out = socket.getOutputStream();
                    // Registration packet: fixed-length, padded with a space.
                    byte[] reg = new byte[16];
                    byte[] id = DEVICE_ID.getBytes(StandardCharsets.UTF_8);
                    System.arraycopy(id, 0, reg, 0, id.length);
                    reg[id.length] = ' ';
                    out.write(reg);
                    out.flush();

                    InputStream in = socket.getInputStream();
                    long deadline = System.currentTimeMillis() + 8000;
                    // Serve exactly two requests (read-address + read-data).
                    for (int i = 0; i < 2; i++) {
                        byte[] header = readFully(in, 10, deadline);
                        int dataLength = header[9] & 0xFF;
                        byte[] rest = readFully(in, 2 + dataLength, deadline); // data + CS + 0x16
                        byte[] request = concat(header, rest);
                        System.out.println("Meter received request: " + toHex(request));

                        byte[] response;
                        if (header[8] == ControlCode.READ_ADDRESS.getValue()) {
                            // 0x13: answer with the 6-byte address, LSB first (wire order).
                            response = buildFrame(DEVICE_ID, ControlCode.READ_ADDRESS_RESPONSE, addressWireBytes(DEVICE_ID));
                        } else {
                            // 0x11: answer with DI(0x00010000 wire) + value data (LSB first BCD) = 123.00 kWh.
                            // dataPlain is stored decoded in the frame; the serializer +0x33-encodes it on the wire.
                            // 12300 decimal in 4-byte BCD, LSB first: 00 23 01 00.
                            response = buildFrame(DEVICE_ID, ControlCode.READ_DATA_RESPONSE,
                                new byte[]{0x00, 0x00, 0x01, 0x00, 0x00, 0x23, 0x01, 0x00});
                        }
                        out.write(response);
                        out.flush();
                        System.out.println("Meter sent response: " + toHex(response));
                    }
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
            TcpServerChannelRegistry registry = routing.getRegistry();
            if (registry != null && registry.isRegistered(deviceId)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Device '" + deviceId + "' did not register within " + timeoutMs + "ms");
    }

    private static byte[] readFully(InputStream in, int count, long deadline) throws Exception {
        byte[] buf = new byte[count];
        int off = 0;
        while (off < count && System.currentTimeMillis() < deadline) {
            int r = in.read(buf, off, count - off);
            if (r < 0) {
                throw new java.io.EOFException("Meter socket closed by server");
            }
            off += r;
        }
        if (off < count) {
            throw new java.io.EOFException("Timeout reading " + count + " bytes from meter socket");
        }
        return buf;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * Builds a wire DL/T 645 frame via the generated serializer (68 + addr + 68 + ctrl + len + data(+0x33) + CS + 16).
     */
    private static byte[] buildFrame(String deviceId, ControlCode control, byte[] dataPlain) {
        try {
            var frame = new Dlt645Frame(addressWireBytes(deviceId), control, (short) dataPlain.length, dataPlain);
            var buffer = new org.apache.plc4x.java.spi.buffers.bytebased.WriteBufferByteBased(new byte[frame.getLengthInBytes()]);
            frame.serialize(buffer);
            return buffer.getBytes();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 12-hex-digit MSB-first device id → 6-byte LSB-first wire order.
     */
    private static byte[] addressWireBytes(String deviceId) {
        var bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) Integer.parseInt(deviceId.substring(i * 2, i * 2 + 2), 16);
        }
        for (int i = 0; i < 3; i++) {
            byte tmp = bytes[i];
            bytes[i] = bytes[5 - i];
            bytes[5 - i] = tmp;
        }
        return bytes;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
