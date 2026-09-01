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
package org.apache.plc4x.java.dlt645.protocol;

import org.apache.plc4x.java.dlt645.readwrite.Dlt645Frame;
import org.apache.plc4x.java.spi.buffers.api.exceptions.BufferException;
import org.apache.plc4x.java.spi.buffers.bytebased.ReadBufferByteBased;
import org.apache.plc4x.java.spi.buffers.bytebased.WriteBufferByteBased;
import org.apache.plc4x.java.spi.drivers.MessageCodecBase;
import org.apache.plc4x.java.spi.drivers.exceptions.MessageCodecException;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.transports.api.exceptions.TransportException;

import java.util.function.Consumer;

/**
 * MessageCodec for DL/T 645-2007.
 * <p>
 * Frame: 0x68 + address(6) + 0x68 + control + length + data(length) + CS + 0x16.
 * The length byte at offset 9 determines the total frame size: {@code 12 + length}.
 * <p>
 * The receive loop peeks a candidate frame, only consumes it once the generated
 * parser has validated its CS checksum, and otherwise resynchronizes byte-wise
 * (mirroring the Modbus RTU codec).
 */
public class Dlt645MessageCodec extends MessageCodecBase<Dlt645Frame> {

    // start1(1) + address(6) + start2(1) + control(1) + length(1) = 10 bytes header
    private static final int HEADER_SIZE = 10;
    // 0x68 + addr + 0x68 + control + length + cs + 0x16 fixed overhead
    private static final int FIXED_FRAME_OVERHEAD = 12;
    private static final int FRAME_START = 0x68;
    private static final int MAX_DATA_LENGTH = 200;
    private static final int PREAMBLE_LENGTH = 4;

    private final Object receiveLock = new Object();
    private long resyncSkippedBytes;

    public Dlt645MessageCodec(TransportInstance<?> transportInstance, Consumer<Dlt645Frame> messageHandler) {
        super("DL/T 645", transportInstance, messageHandler);
    }

    /**
     * Drop every currently buffered byte. Used after a timeout or before dispatching
     * the next request: DL/T 645 has no transaction id, so a late 91H for the same
     * DI would otherwise complete the next future with a stale value.
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
                logger.warn("Discarded {} leftover DL/T 645 byte(s) ({})", available, reason);
            } catch (TransportException e) {
                logger.warn("Failed to discard leftover DL/T 645 bytes ({})", reason, e);
            }
        }
    }

    @Override
    protected int getMinimumHeaderSize() {
        return HEADER_SIZE;
    }

    /**
     * DL/T 645 carries an explicit length byte (offset 9): total frame size = 12 + length.
     */
    @Override
    protected int calculateTotalMessageSize(byte[] header, int availableBytes) {
        int dataLength = header[9] & 0xFF;
        return FIXED_FRAME_OVERHEAD + dataLength;
    }

    @Override
    protected Dlt645Frame parseMessage(ReadBufferByteBased readBuffer) throws BufferException {
        return Dlt645Frame.staticParse(readBuffer, true);
    }

    @Override
    public void send(Dlt645Frame message) throws MessageCodecException {
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
            throw new MessageCodecException("Failed to send DL/T 645 message", e);
        }
    }

    /**
     * DL/T 645 receive loop: peek before consume. A frame is only consumed after
     * the generated parser (which validates the CS checksum field) accepted it from
     * a peek; anything unparseable advances by a single byte (byte-wise resynchronization).
     */
    @Override
    public void processIncomingData() throws MessageCodecException {
        try {
            while (true) {
                Dlt645Frame message;
                synchronized (receiveLock) {
                    int availableBytes = getTransportInstance().getNumBytesAvailable();
                    if (availableBytes < HEADER_SIZE) {
                        return;
                    }
                    byte[] header = getTransportInstance().peekReadableBytes(HEADER_SIZE);
                    if ((header[0] & 0xFF) != FRAME_START || (header[7] & 0xFF) != FRAME_START) {
                        skipOneByte("candidate does not contain aligned 0x68 start bytes");
                        continue;
                    }
                    int expectedSize = calculateTotalMessageSize(header, availableBytes);
                    if (expectedSize > FIXED_FRAME_OVERHEAD + MAX_DATA_LENGTH) {
                        skipOneByte("data length exceeds the DL/T 645 maximum of 200 bytes");
                        continue;
                    }
                    if (availableBytes < expectedSize) {
                        return; // never consume a partial frame
                    }
                    byte[] frameBytes = getTransportInstance().peekReadableBytes(expectedSize);
                    try {
                        message = parseMessage(createReadBuffer(frameBytes));
                    } catch (BufferException | RuntimeException e) {
                        skipOneByte("frame failed validation: " + e.getMessage());
                        continue;
                    }
                    getTransportInstance().read(expectedSize); // consume the validated frame
                    noteResyncComplete();
                }
                // Release receiveLock before the handler so a subsequent send/discard
                // on this thread (thenCompose) cannot deadlock.
                messageHandler.accept(message);
            }
        } catch (TransportException e) {
            throw new MessageCodecException("Failed to receive DL/T 645 message", e);
        }
    }

    private void skipOneByte(String reason) throws TransportException {
        if (resyncSkippedBytes == 0) {
            logger.warn("DL/T 645 stream out of sync ({}), resynchronizing byte-wise", reason);
        }
        getTransportInstance().read(1);
        resyncSkippedBytes++;
    }

    private void noteResyncComplete() {
        if (resyncSkippedBytes > 0) {
            logger.warn("DL/T 645 stream resynchronized after skipping {} bytes", resyncSkippedBytes);
            resyncSkippedBytes = 0;
        }
    }

}
