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
package org.apache.plc4x.java.transport.tcpserver.registration.impl;

import org.apache.plc4x.java.transport.tcpserver.registration.RegistrationPacketParser;
import org.apache.plc4x.java.transport.tcpserver.registration.RegistrationResult;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Parser for delimiter-terminated registration packets.
 *
 * <p>This parser reads bytes until a specified delimiter sequence is found,
 * then extracts the device identifier from the content before the delimiter.
 * Common delimiters include CRLF ({@code \r\n}), newline, or custom byte sequences.</p>
 *
 * <p>This parser is stateless and thread-safe.</p>
 *
 * <h3>Frame Format:</h3>
 * <pre>
 * +--------------------+-----------+
 * | Device ID Content  | Delimiter |
 * | (variable length)  | (1+ bytes)|
 * +--------------------+-----------+
 * </pre>
 *
 * @see RegistrationPacketParser
 */
public class DelimiterParser implements RegistrationPacketParser {

    private static final byte[] CRLF = new byte[]{'\r', '\n'};
    private static final byte[] LF = new byte[]{'\n'};
    private static final byte[] NULL_BYTE = new byte[]{0x00};
    private static final int DEFAULT_MAX_LENGTH = 256;

    private final byte[] delimiter;
    private final Charset charset;
    private final int maxLength;
    private final boolean stripDelimiter;

    /**
     * Creates a CRLF-delimited parser with default settings.
     *
     * @return a parser for CRLF-terminated packets
     */
    public static DelimiterParser crlf() {
        return new DelimiterParser(CRLF);
    }

    /**
     * Creates a newline-delimited parser with default settings.
     *
     * @return a parser for newline-terminated packets
     */
    public static DelimiterParser newline() {
        return new DelimiterParser(LF);
    }

    /**
     * Creates a null-terminated parser with default settings.
     *
     * @return a parser for null-terminated packets
     */
    public static DelimiterParser nullTerminated() {
        return new DelimiterParser(NULL_BYTE);
    }

    /**
     * Creates a delimiter parser with default charset (UTF-8) and max length (256).
     *
     * @param delimiter the delimiter byte sequence; must not be null or empty
     * @throws NullPointerException if delimiter is null
     * @throws IllegalArgumentException if delimiter is empty
     */
    public DelimiterParser(byte[] delimiter) {
        this(delimiter, StandardCharsets.UTF_8, DEFAULT_MAX_LENGTH);
    }

    /**
     * Creates a delimiter parser with the specified configuration.
     *
     * @param delimiter the delimiter byte sequence; must not be null or empty
     * @param charset the charset for decoding; must not be null
     * @param maxLength the maximum content length before delimiter; must be positive
     * @throws NullPointerException if delimiter or charset is null
     * @throws IllegalArgumentException if delimiter is empty or maxLength is not positive
     */
    public DelimiterParser(byte[] delimiter, Charset charset, int maxLength) {
        this(delimiter, charset, maxLength, true);
    }

    /**
     * Creates a delimiter parser with full configuration.
     *
     * @param delimiter the delimiter byte sequence; must not be null or empty
     * @param charset the charset for decoding; must not be null
     * @param maxLength the maximum content length before delimiter; must be positive
     * @param stripDelimiter whether to exclude delimiter from consumed bytes count
     * @throws NullPointerException if delimiter or charset is null
     * @throws IllegalArgumentException if delimiter is empty or maxLength is not positive
     */
    public DelimiterParser(byte[] delimiter, Charset charset, int maxLength, boolean stripDelimiter) {
        Objects.requireNonNull(delimiter, "Delimiter must not be null");
        if (delimiter.length == 0) {
            throw new IllegalArgumentException("Delimiter must not be empty");
        }
        this.delimiter = delimiter.clone();
        this.charset = Objects.requireNonNull(charset, "Charset must not be null");
        if (maxLength <= 0) {
            throw new IllegalArgumentException("Max length must be positive: " + maxLength);
        }
        this.maxLength = maxLength;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    public RegistrationResult parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "Buffer must not be null");

        if (buffer.length < delimiter.length) {
            return RegistrationResult.needMoreData();
        }

        // Search for delimiter within max length
        int delimiterIndex = findDelimiter(buffer);

        if (delimiterIndex < 0) {
            if (buffer.length > maxLength) {
                return RegistrationResult.invalid(
                    "Delimiter not found within max length of " + maxLength + " bytes");
            }
            return RegistrationResult.needMoreData();
        }

        // Read content before delimiter
        byte[] content = Arrays.copyOfRange(buffer, 0, delimiterIndex);

        String deviceId = new String(content, charset).trim();
        if (deviceId.isEmpty()) {
            return RegistrationResult.invalid("Empty device identifier");
        }

        int consumedBytes = stripDelimiter ? delimiterIndex + delimiter.length : delimiterIndex;
        return RegistrationResult.complete(deviceId, consumedBytes);
    }

    @Override
    public String getFormatDescription() {
        String delimDesc = formatDelimiter();
        return String.format("Delimiter[%s, maxLen=%d, %s]", delimDesc, maxLength, charset.name());
    }

    /**
     * Returns the configured delimiter.
     *
     * @return a copy of the delimiter bytes
     */
    public byte[] getDelimiter() {
        return delimiter.clone();
    }

    /**
     * Returns the configured charset.
     *
     * @return the charset for decoding
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * Returns the maximum content length.
     *
     * @return the maximum length before delimiter
     */
    public int getMaxLength() {
        return maxLength;
    }

    /**
     * Finds the position of the delimiter in the buffer.
     *
     * @param buffer the buffer to search
     * @return the index of the delimiter, or -1 if not found
     */
    private int findDelimiter(byte[] buffer) {
        int searchLimit = Math.min(buffer.length - delimiter.length + 1, maxLength + 1);

        for (int i = 0; i < searchLimit; i++) {
            if (matchesDelimiter(buffer, i)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Checks if the buffer matches the delimiter at the specified position.
     *
     * @param buffer the buffer to check
     * @param position the position to check at
     * @return true if delimiter matches at position
     */
    private boolean matchesDelimiter(byte[] buffer, int position) {
        if (position + delimiter.length > buffer.length) {
            return false;
        }

        for (int i = 0; i < delimiter.length; i++) {
            if (buffer[position + i] != delimiter[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Formats the delimiter for display purposes.
     *
     * @return a readable representation of the delimiter
     */
    private String formatDelimiter() {
        if (Arrays.equals(delimiter, CRLF)) {
            return "CRLF";
        }
        if (Arrays.equals(delimiter, LF)) {
            return "LF";
        }
        if (Arrays.equals(delimiter, NULL_BYTE)) {
            return "NULL";
        }

        StringBuilder sb = new StringBuilder("0x");
        for (byte b : delimiter) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
