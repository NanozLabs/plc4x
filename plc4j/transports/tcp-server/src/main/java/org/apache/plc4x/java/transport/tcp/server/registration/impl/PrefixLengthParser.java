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
package org.apache.plc4x.java.transport.tcp.server.registration.impl;

import io.netty.buffer.ByteBuf;
import org.apache.plc4x.java.transport.tcp.server.registration.RegistrationPacketParser;
import org.apache.plc4x.java.transport.tcp.server.registration.RegistrationResult;

import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Parser for length-prefixed registration packets.
 *
 * <p>This parser reads a length prefix (1, 2, or 4 bytes) followed by the device
 * identifier payload. It is commonly used by DTU devices that send variable-length
 * registration frames with a size header.</p>
 *
 * <h3>Frame Format:</h3>
 * <pre>
 * +----------------+--------------------+
 * | Length Prefix  | Device ID Payload  |
 * | (1/2/4 bytes)  | (variable length)  |
 * +----------------+--------------------+
 * </pre>
 *
 * <h3>Configuration Examples:</h3>
 * <pre>{@code
 * // Single-byte length prefix (max 255 byte payload)
 * RegistrationPacketParser parser = new PrefixLengthParser(1);
 *
 * // Two-byte big-endian length prefix
 * RegistrationPacketParser beParser = new PrefixLengthParser(2, ByteOrder.BIG_ENDIAN);
 *
 * // Four-byte little-endian with ASCII charset
 * RegistrationPacketParser leParser = new PrefixLengthParser(
 *     4, ByteOrder.LITTLE_ENDIAN, StandardCharsets.US_ASCII);
 * }</pre>
 *
 * @since 0.14.0
 */
public class PrefixLengthParser implements RegistrationPacketParser {

    private static final int MAX_PAYLOAD_LENGTH = 64 * 1024; // 64KB safety limit

    private final int prefixBytes;
    private final ByteOrder byteOrder;
    private final Charset charset;
    private final int maxPayloadLength;

    /**
     * Creates a prefix-length parser with default settings (big-endian, UTF-8).
     *
     * @param prefixBytes the number of bytes for the length prefix (1, 2, or 4)
     * @throws IllegalArgumentException if prefixBytes is not 1, 2, or 4
     */
    public PrefixLengthParser(int prefixBytes) {
        this(prefixBytes, ByteOrder.BIG_ENDIAN, StandardCharsets.UTF_8);
    }

    /**
     * Creates a prefix-length parser with the specified byte order.
     *
     * @param prefixBytes the number of bytes for the length prefix (1, 2, or 4)
     * @param byteOrder the byte order for reading the length prefix
     * @throws IllegalArgumentException if prefixBytes is not 1, 2, or 4
     * @throws NullPointerException if byteOrder is null
     */
    public PrefixLengthParser(int prefixBytes, ByteOrder byteOrder) {
        this(prefixBytes, byteOrder, StandardCharsets.UTF_8);
    }

    /**
     * Creates a prefix-length parser with full configuration.
     *
     * @param prefixBytes the number of bytes for the length prefix (1, 2, or 4)
     * @param byteOrder the byte order for reading the length prefix
     * @param charset the charset for decoding the payload
     * @throws IllegalArgumentException if prefixBytes is not 1, 2, or 4
     * @throws NullPointerException if byteOrder or charset is null
     */
    public PrefixLengthParser(int prefixBytes, ByteOrder byteOrder, Charset charset) {
        this(prefixBytes, byteOrder, charset, MAX_PAYLOAD_LENGTH);
    }

    /**
     * Creates a prefix-length parser with full configuration including max payload.
     *
     * @param prefixBytes the number of bytes for the length prefix (1, 2, or 4)
     * @param byteOrder the byte order for reading the length prefix
     * @param charset the charset for decoding the payload
     * @param maxPayloadLength the maximum allowed payload length
     * @throws IllegalArgumentException if prefixBytes is not 1, 2, or 4
     * @throws NullPointerException if byteOrder or charset is null
     */
    public PrefixLengthParser(int prefixBytes, ByteOrder byteOrder, Charset charset, int maxPayloadLength) {
        if (prefixBytes != 1 && prefixBytes != 2 && prefixBytes != 4) {
            throw new IllegalArgumentException(
                "Prefix bytes must be 1, 2, or 4, but was: " + prefixBytes);
        }
        this.prefixBytes = prefixBytes;
        this.byteOrder = Objects.requireNonNull(byteOrder, "Byte order must not be null");
        this.charset = Objects.requireNonNull(charset, "Charset must not be null");
        this.maxPayloadLength = maxPayloadLength;
    }

    @Override
    public RegistrationResult parse(ByteBuf buffer) {
        Objects.requireNonNull(buffer, "Buffer must not be null");

        // Check if we have enough bytes for the prefix
        if (buffer.readableBytes() < prefixBytes) {
            return RegistrationResult.needMoreData();
        }

        buffer.markReaderIndex();

        // Read the length prefix
        int payloadLength = readLength(buffer);

        // Validate length
        if (payloadLength < 0) {
            buffer.resetReaderIndex();
            return RegistrationResult.invalid("Negative payload length: " + payloadLength);
        }

        if (payloadLength == 0) {
            buffer.resetReaderIndex();
            return RegistrationResult.invalid("Zero payload length");
        }

        if (payloadLength > maxPayloadLength) {
            buffer.resetReaderIndex();
            return RegistrationResult.invalid(String.format(
                "Payload length %d exceeds maximum %d", payloadLength, maxPayloadLength));
        }

        // Check if we have enough bytes for the payload
        if (buffer.readableBytes() < payloadLength) {
            buffer.resetReaderIndex();
            return RegistrationResult.needMoreData();
        }

        // Read the payload
        byte[] payload = new byte[payloadLength];
        buffer.readBytes(payload);

        String deviceId = new String(payload, charset).trim();
        if (deviceId.isEmpty()) {
            buffer.resetReaderIndex();
            return RegistrationResult.invalid("Empty device identifier after trimming");
        }

        return RegistrationResult.complete(deviceId, prefixBytes + payloadLength);
    }

    @Override
    public String getFormatDescription() {
        String endianness = byteOrder == ByteOrder.BIG_ENDIAN ? "BE" : "LE";
        return String.format("PrefixLength[%d-byte %s prefix, %s]",
            prefixBytes, endianness, charset.name());
    }

    /**
     * Returns the configured prefix byte count.
     *
     * @return the number of bytes in the length prefix
     */
    public int getPrefixBytes() {
        return prefixBytes;
    }

    /**
     * Returns the configured byte order.
     *
     * @return the byte order for reading the prefix
     */
    public ByteOrder getByteOrder() {
        return byteOrder;
    }

    /**
     * Returns the configured charset.
     *
     * @return the charset for decoding the payload
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * Reads the length prefix from the buffer according to configuration.
     *
     * @param buffer the buffer to read from
     * @return the decoded length value
     */
    private int readLength(ByteBuf buffer) {
        return switch (prefixBytes) {
            case 1 -> buffer.readUnsignedByte();
            case 2 -> byteOrder == ByteOrder.BIG_ENDIAN
                ? buffer.readUnsignedShort()
                : buffer.readUnsignedShortLE();
            case 4 -> byteOrder == ByteOrder.BIG_ENDIAN
                ? buffer.readInt()
                : buffer.readIntLE();
            default -> throw new IllegalStateException("Unexpected prefix bytes: " + prefixBytes);
        };
    }
}
