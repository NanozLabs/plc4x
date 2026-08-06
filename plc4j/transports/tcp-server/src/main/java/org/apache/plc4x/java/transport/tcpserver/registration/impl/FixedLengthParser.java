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
 * Parser for fixed-length registration packets.
 *
 * <p>This parser reads an exact number of bytes from the buffer and interprets
 * them as the device identifier string. It is the simplest and most efficient
 * parser for DTU devices that send fixed-size registration frames.</p>
 *
 * <p>This parser is stateless and thread-safe.</p>
 *
 * @see RegistrationPacketParser
 */
public class FixedLengthParser implements RegistrationPacketParser {

    private final int length;
    private final Charset charset;

    /**
     * Creates a fixed-length parser with UTF-8 charset.
     *
     * @param length the exact number of bytes to read; must be positive
     * @throws IllegalArgumentException if length is not positive
     */
    public FixedLengthParser(int length) {
        this(length, StandardCharsets.UTF_8);
    }

    /**
     * Creates a fixed-length parser with the specified charset.
     *
     * @param length the exact number of bytes to read; must be positive
     * @param charset the charset for decoding bytes to string; must not be null
     * @throws IllegalArgumentException if length is not positive
     * @throws NullPointerException if charset is null
     */
    public FixedLengthParser(int length, Charset charset) {
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive: " + length);
        }
        this.length = length;
        this.charset = Objects.requireNonNull(charset, "Charset must not be null");
    }

    @Override
    public RegistrationResult parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "Buffer must not be null");

        if (buffer.length < length) {
            return RegistrationResult.needMoreData();
        }

        byte[] deviceBytes = Arrays.copyOfRange(buffer, 0, length);

        String deviceId = new String(deviceBytes, charset).trim();
        if (deviceId.isEmpty()) {
            return RegistrationResult.invalid("Empty device identifier after trimming");
        }

        // Validate device ID contains only printable characters
        if (!isValidDeviceId(deviceId)) {
            return RegistrationResult.invalid("Device identifier contains invalid characters");
        }

        return RegistrationResult.complete(deviceId, length);
    }

    @Override
    public String getFormatDescription() {
        return String.format("FixedLength[%d bytes, %s]", length, charset.name());
    }

    /**
     * Returns the configured packet length.
     *
     * @return the expected packet length in bytes
     */
    public int getLength() {
        return length;
    }

    /**
     * Returns the configured charset.
     *
     * @return the charset used for decoding
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * Validates that the device ID contains only acceptable characters.
     *
     * @param deviceId the device ID to validate
     * @return true if valid, false otherwise
     */
    protected boolean isValidDeviceId(String deviceId) {
        for (char c : deviceId.toCharArray()) {
            // Allow alphanumeric, hyphen, underscore, and dot
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '.') {
                return false;
            }
        }
        return true;
    }
}
