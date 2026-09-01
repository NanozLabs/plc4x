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
package org.apache.plc4x.java.cjt188.protocol;

import org.apache.plc4x.java.cjt188.readwrite.Cjt188Frame;
import org.apache.plc4x.java.cjt188.readwrite.ControlCode;
import org.apache.plc4x.java.spi.buffers.bytebased.WriteBufferByteBased;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.transports.api.config.TransportConfiguration;
import org.apache.plc4x.java.spi.transports.api.exceptions.TransportException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Cjt188MessageCodecTest {

    private static final byte[] ADDRESS = {0x10, 0x12, (byte) 0x90, 0x78, 0x56, 0x34, 0x12};

    @Test
    void skipsWakePreambleAndDispatchesFrame() throws Exception {
        var transport = new ScriptedTransport();
        var received = new ArrayList<Cjt188Frame>();
        var codec = new Cjt188MessageCodec(transport, received::add);
        transport.deliver(concat(new byte[]{(byte) 0xFE, (byte) 0xFE}, wireFrame()));

        codec.processIncomingData();

        assertEquals(1, received.size());
        assertEquals(0, transport.getNumBytesAvailable());
    }

    @Test
    void corruptFrameIsSkippedAndNextFrameParses() throws Exception {
        var transport = new ScriptedTransport();
        List<Cjt188Frame> received = new ArrayList<>();
        var codec = new Cjt188MessageCodec(transport, received::add);
        byte[] corrupt = wireFrame();
        corrupt[corrupt.length - 2] ^= 0x01;
        transport.deliver(concat(corrupt, wireFrame()));

        codec.processIncomingData();

        assertEquals(1, received.size());
    }

    @Test
    void partialAlignedFrameIsNotConsumed() throws Exception {
        var transport = new ScriptedTransport();
        List<Cjt188Frame> received = new ArrayList<>();
        var codec = new Cjt188MessageCodec(transport, received::add);
        byte[] frame = wireFrame();
        transport.deliver(Arrays.copyOf(frame, 11));

        codec.processIncomingData();

        assertTrue(received.isEmpty());
        assertEquals(11, transport.getNumBytesAvailable());
    }

    @Test
    void impossibleLengthDoesNotBlockAFollowingValidFrame() throws Exception {
        var transport = new ScriptedTransport();
        List<Cjt188Frame> received = new ArrayList<>();
        var codec = new Cjt188MessageCodec(transport, received::add);
        byte[] corruptHeader = Arrays.copyOf(wireFrame(), 11);
        corruptHeader[10] = (byte) 0xFF;
        transport.deliver(concat(corruptHeader, wireFrame()));

        codec.processIncomingData();

        assertEquals(1, received.size());
        assertEquals(0, transport.getNumBytesAvailable());
    }

    @Test
    void discardBufferedInputDropsLeftoverBytes() throws Exception {
        var transport = new ScriptedTransport();
        var codec = new Cjt188MessageCodec(transport, ignored -> { });
        transport.deliver(wireFrame());

        codec.discardBufferedInput("test");

        assertEquals(0, transport.getNumBytesAvailable());
        codec.processIncomingData();
    }

    @Test
    void sendPrependsFourWakeBytes() throws Exception {
        var transport = new ScriptedTransport();
        var codec = new Cjt188MessageCodec(transport, ignored -> { });
        var message = frame();

        codec.send(message);

        assertNotNull(transport.written);
        assertArrayEquals(new byte[]{(byte) 0xFE, (byte) 0xFE, (byte) 0xFE, (byte) 0xFE},
            Arrays.copyOf(transport.written, 4));
        assertArrayEquals(wireFrame(), Arrays.copyOfRange(transport.written, 4, transport.written.length));
    }

    private static Cjt188Frame frame() {
        return new Cjt188Frame(ADDRESS, ControlCode.READ_DATA_RESPONSE, (short) 7,
            new byte[]{0x1F, (byte) 0x90, 0x00, 0x56, 0x34, 0x12, 0x00});
    }

    private static byte[] wireFrame() throws Exception {
        Cjt188Frame frame = frame();
        var buffer = new WriteBufferByteBased(new byte[frame.getLengthInBytes()]);
        frame.serialize(buffer);
        return buffer.getBytes();
    }

    private static byte[] concat(byte[]... values) {
        var output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.writeBytes(value);
        }
        return output.toByteArray();
    }

    private static final class ScriptedTransport implements TransportInstance<TransportConfiguration> {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int readPosition;
        private byte[] written;

        void deliver(byte[] bytes) { buffer.writeBytes(bytes); }
        @Override public TransportConfiguration getConfiguration() { return null; }
        @Override public boolean isOpen() { return true; }
        @Override public int getNumBytesAvailable() { return buffer.size() - readPosition; }
        @Override public byte[] peekReadableBytes(int count) throws TransportException {
            if (count > getNumBytesAvailable()) throw new TransportException("peek beyond available");
            return Arrays.copyOfRange(buffer.toByteArray(), readPosition, readPosition + count);
        }
        @Override public byte[] read(int count) throws TransportException {
            byte[] result = peekReadableBytes(count);
            readPosition += count;
            return result;
        }
        @Override public void write(byte[] bytes) { written = Arrays.copyOf(bytes, bytes.length); }
        @Override public void close() { }
    }
}
