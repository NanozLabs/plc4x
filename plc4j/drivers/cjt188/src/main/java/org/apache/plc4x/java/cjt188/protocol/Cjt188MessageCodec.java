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
import org.apache.plc4x.java.spi.buffers.api.exceptions.BufferException;
import org.apache.plc4x.java.spi.buffers.bytebased.ReadBufferByteBased;
import org.apache.plc4x.java.spi.buffers.bytebased.WriteBufferByteBased;
import org.apache.plc4x.java.spi.drivers.MessageCodecBase;
import org.apache.plc4x.java.spi.drivers.exceptions.MessageCodecException;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.transports.api.exceptions.TransportException;

import java.util.function.Consumer;

/**
 * MessageCodec for CJ/T 188-2004.
 * <p>
 * Frame: 0x68 + address(7) + 0x68 + control + length + data(length) + CS + 0x16.
 * The length byte at offset 10 determines the total frame size: {@code 13 + length}.
 */
public class Cjt188MessageCodec extends MessageCodecBase<Cjt188Frame> {

    // start1(1) + address(7) + start2(1) + control(1) + length(1) = 11 bytes header
    private static final int HEADER_SIZE = 11;
    private static final int FIXED_FRAME_OVERHEAD = 13;
    private static final int FRAME_START = 0x68;
    private static final int SECOND_START_OFFSET = 8;
    private static final int LENGTH_INDEX = 10;
    private static final int MAX_DATA_LENGTH = 200;
    private static final int PREAMBLE_LENGTH = 4;

    private final Object receiveLock = new Object();
    private long resyncSkippedBytes;

    public Cjt188MessageCodec(TransportInstance<?> transportInstance, Consumer<Cjt188Frame> messageHandler) {
        super("CJ/T 188", transportInstance, messageHandler);
    }

    /**
     * Drop every currently buffered byte. Used after a timeout or before dispatching
     * the next request: CJ/T 188 has no transaction id.
     */
    public void discardBufferedInput(String reason) {
        synchronized (receiveLock) {
            try {
                int available = getTransportInstance().getNumBytesAvailable();
                if (available <= 0) {
                    return;
                }
                getTransportInstance().read(available);
                resyncSkippedBytes = 0;
                logger.warn("Discarded {} leftover CJ/T 188 byte(s) ({})", available, reason);
            } catch (TransportException e) {
                logger.warn("Failed to discard leftover CJ/T 188 bytes ({})", reason, e);
            }
        }
    }

    @Override
    protected int getMinimumHeaderSize() {
        return HEADER_SIZE;
    }

    @Override
    protected int calculateTotalMessageSize(byte[] header, int availableBytes) {
        int dataLength = header[LENGTH_INDEX] & 0xFF;
        return FIXED_FRAME_OVERHEAD + dataLength;
    }

    @Override
    protected Cjt188Frame parseMessage(ReadBufferByteBased readBuffer) throws BufferException {
        return Cjt188Frame.staticParse(readBuffer, true);
    }

    @Override
    public void send(Cjt188Frame message) throws MessageCodecException {
        try {
            WriteBufferByteBased writeBuffer = createWriteBuffer(message.getLengthInBytes());
            message.serialize(writeBuffer);
            byte[] frame = writeBuffer.getBytes();
            byte[] wireBytes = new byte[PREAMBLE_LENGTH + frame.length];
            java.util.Arrays.fill(wireBytes, 0, PREAMBLE_LENGTH, (byte) 0xFE);
            System.arraycopy(frame, 0, wireBytes, PREAMBLE_LENGTH, frame.length);
            fireMessageExchange(true, message);
            getTransportInstance().write(wireBytes);
        } catch (BufferException | TransportException e) {
            throw new MessageCodecException("Failed to send CJ/T 188 message", e);
        }
    }

    @Override
    public void processIncomingData() throws MessageCodecException {
        try {
            while (true) {
                Cjt188Frame message;
                synchronized (receiveLock) {
                    int availableBytes = getTransportInstance().getNumBytesAvailable();
                    if (availableBytes < HEADER_SIZE) {
                        return;
                    }
                    byte[] header = getTransportInstance().peekReadableBytes(HEADER_SIZE);
                    if ((header[0] & 0xFF) != FRAME_START || (header[SECOND_START_OFFSET] & 0xFF) != FRAME_START) {
                        skipOneByte("candidate does not contain aligned 0x68 start bytes");
                        continue;
                    }
                    int expectedSize = calculateTotalMessageSize(header, availableBytes);
                    if (expectedSize > FIXED_FRAME_OVERHEAD + MAX_DATA_LENGTH) {
                        skipOneByte("data length exceeds the CJ/T 188 maximum of 200 bytes");
                        continue;
                    }
                    if (availableBytes < expectedSize) {
                        return;
                    }
                    byte[] frameBytes = getTransportInstance().peekReadableBytes(expectedSize);
                    try {
                        message = parseMessage(createReadBuffer(frameBytes));
                    } catch (BufferException | RuntimeException e) {
                        skipOneByte("frame failed validation: " + e.getMessage());
                        continue;
                    }
                    getTransportInstance().read(expectedSize);
                    noteResyncComplete();
                }
                messageHandler.accept(message);
            }
        } catch (TransportException e) {
            throw new MessageCodecException("Failed to receive CJ/T 188 message", e);
        }
    }

    private void skipOneByte(String reason) throws TransportException {
        if (resyncSkippedBytes == 0) {
            logger.warn("CJ/T 188 stream out of sync ({}), resynchronizing byte-wise", reason);
        }
        getTransportInstance().read(1);
        resyncSkippedBytes++;
    }

    private void noteResyncComplete() {
        if (resyncSkippedBytes > 0) {
            logger.warn("CJ/T 188 stream resynchronized after skipping {} bytes", resyncSkippedBytes);
            resyncSkippedBytes = 0;
        }
    }

}
