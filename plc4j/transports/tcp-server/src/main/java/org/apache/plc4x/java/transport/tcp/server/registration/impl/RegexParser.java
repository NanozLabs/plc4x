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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser that extracts device ID using regular expression matching.
 *
 * <p>This parser decodes the buffer as a string (bounded by maxBytes) and applies
 * a regex pattern to extract the device identifier. The regex must contain either
 * a named group "deviceId" or a capturing group (group 1) for the device identifier.</p>
 *
 * <h3>Pattern Requirements:</h3>
 * <ul>
 *   <li>Must contain a capturing group for device ID extraction</li>
 *   <li>Named group "deviceId" takes precedence over group(1)</li>
 *   <li>Pattern should anchor to prevent partial matches if needed</li>
 * </ul>
 *
 * <h3>Configuration Examples:</h3>
 * <pre>{@code
 * // Match "REG:DEVICE123\r\n" format
 * Pattern pattern = Pattern.compile("REG:(?<deviceId>[A-Za-z0-9]+)\\r\\n");
 * RegistrationPacketParser parser = new RegexParser(pattern, StandardCharsets.US_ASCII, 64);
 *
 * // Match "ID=xxx;" format
 * Pattern idPattern = Pattern.compile("ID=([^;]+);");
 * RegistrationPacketParser idParser = new RegexParser(idPattern, StandardCharsets.UTF_8, 128);
 * }</pre>
 *
 * <h3>Security Considerations:</h3>
 * <p>Always set a reasonable maxBytes limit to prevent unbounded buffering.
 * Patterns should be designed to prevent ReDoS attacks.</p>
 *
 * @since 0.14.0
 */
public class RegexParser implements RegistrationPacketParser {

    private static final String DEVICE_ID_GROUP_NAME = "deviceId";
    private static final int DEFAULT_MAX_BYTES = 256;

    private final Pattern pattern;
    private final Charset charset;
    private final int maxBytes;

    /**
     * Creates a regex parser with UTF-8 charset and default max bytes (256).
     *
     * @param pattern the regex pattern for matching; must not be null
     * @throws NullPointerException if pattern is null
     */
    public RegexParser(Pattern pattern) {
        this(pattern, StandardCharsets.UTF_8, DEFAULT_MAX_BYTES);
    }

    /**
     * Creates a regex parser with the specified configuration.
     *
     * @param pattern the regex pattern for matching; must not be null
     * @param charset the charset for decoding bytes; must not be null
     * @param maxBytes the maximum bytes to read for pattern matching; must be positive
     * @throws NullPointerException if pattern or charset is null
     * @throws IllegalArgumentException if maxBytes is not positive
     */
    public RegexParser(Pattern pattern, Charset charset, int maxBytes) {
        this.pattern = Objects.requireNonNull(pattern, "Pattern must not be null");
        this.charset = Objects.requireNonNull(charset, "Charset must not be null");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("Max bytes must be positive: " + maxBytes);
        }
        this.maxBytes = maxBytes;
    }

    @Override
    public RegistrationResult parse(ByteBuf buffer) {
        Objects.requireNonNull(buffer, "Buffer must not be null");

        if (buffer.readableBytes() == 0) {
            return RegistrationResult.needMoreData();
        }

        buffer.markReaderIndex();
        int readable = Math.min(buffer.readableBytes(), maxBytes);

        // Read bytes without advancing the reader index permanently yet
        byte[] bytes = new byte[readable];
        buffer.readBytes(bytes);

        String payload = new String(bytes, charset);
        Matcher matcher = pattern.matcher(payload);

        if (!matcher.find()) {
            buffer.resetReaderIndex();
            if (readable >= maxBytes) {
                return RegistrationResult.invalid(
                    "Pattern did not match within max length of " + maxBytes + " bytes");
            }
            return RegistrationResult.needMoreData();
        }

        // Extract device ID from named group or first capturing group
        String deviceId = extractDeviceId(matcher);
        if (deviceId == null || deviceId.isBlank()) {
            buffer.resetReaderIndex();
            return RegistrationResult.invalid("No device ID captured by pattern");
        }

        // Calculate the number of bytes consumed (up to the end of the match)
        int matchEndInChars = matcher.end();
        int consumedBytes = calculateConsumedBytes(payload, matchEndInChars);

        // Reset and re-read only the consumed portion
        buffer.resetReaderIndex();
        buffer.skipBytes(consumedBytes);

        return RegistrationResult.complete(deviceId.trim(), consumedBytes);
    }

    @Override
    public String getFormatDescription() {
        return String.format("Regex[pattern=%s, maxBytes=%d, %s]",
            pattern.pattern(), maxBytes, charset.name());
    }

    /**
     * Returns the configured pattern.
     *
     * @return the regex pattern
     */
    public Pattern getPattern() {
        return pattern;
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
     * Returns the maximum bytes limit.
     *
     * @return the maximum bytes to read
     */
    public int getMaxBytes() {
        return maxBytes;
    }

    /**
     * Extracts the device ID from the matcher result.
     *
     * <p>Tries named group "deviceId" first, then falls back to group(1).</p>
     *
     * @param matcher the successful matcher
     * @return the device ID, or null if not found
     */
    private String extractDeviceId(Matcher matcher) {
        // Try named group first
        try {
            String namedGroup = matcher.group(DEVICE_ID_GROUP_NAME);
            if (namedGroup != null) {
                return namedGroup;
            }
        } catch (IllegalArgumentException ignored) {
            // Named group doesn't exist, try numbered group
        }

        // Fall back to first capturing group
        if (matcher.groupCount() >= 1) {
            return matcher.group(1);
        }

        return null;
    }

    /**
     * Calculates bytes consumed based on character position.
     *
     * <p>This handles multi-byte character encodings correctly.</p>
     *
     * @param payload the decoded string
     * @param charPosition the character position
     * @return the byte count
     */
    private int calculateConsumedBytes(String payload, int charPosition) {
        String consumed = payload.substring(0, charPosition);
        return consumed.getBytes(charset).length;
    }
}
