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
package org.apache.plc4x.java.transport.tcp.server.registration;

import org.apache.plc4x.java.transport.tcp.server.registration.impl.DelimiterParser;
import org.apache.plc4x.java.transport.tcp.server.registration.impl.FixedLengthParser;
import org.apache.plc4x.java.transport.tcp.server.registration.impl.PrefixLengthParser;
import org.apache.plc4x.java.transport.tcp.server.registration.impl.RegexParser;

import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Factory for creating {@link RegistrationPacketParser} instances from configuration parameters.
 *
 * <p>This factory supports creating various parser types based on string configuration,
 * making it easy to configure parsers through connection URLs or configuration files.</p>
 *
 * <h3>Supported Parser Types:</h3>
 * <ul>
 *   <li>{@code fixed} - Fixed-length parser: {@code registration-type=fixed&registration-length=16}</li>
 *   <li>{@code regex} - Regex parser: {@code registration-type=regex&registration-pattern=REG:(.+)\r\n}</li>
 *   <li>{@code prefix} - Prefix-length parser: {@code registration-type=prefix&registration-prefix-bytes=2}</li>
 *   <li>{@code delimiter} - Delimiter parser: {@code registration-type=delimiter&registration-delimiter=CRLF}</li>
 * </ul>
 *
 * <h3>Common Configuration Parameters:</h3>
 * <ul>
 *   <li>{@code registration-type} - Parser type (required)</li>
 *   <li>{@code registration-charset} - Character encoding (default: UTF-8)</li>
 *   <li>{@code registration-max-length} - Maximum length for variable parsers</li>
 * </ul>
 *
 * @since 0.14.0
 */
public final class RegistrationParserFactory {

    /** Configuration key for parser type */
    public static final String KEY_TYPE = "registration-type";

    /** Configuration key for fixed length */
    public static final String KEY_LENGTH = "registration-length";

    /** Configuration key for charset */
    public static final String KEY_CHARSET = "registration-charset";

    /** Configuration key for regex pattern */
    public static final String KEY_PATTERN = "registration-pattern";

    /** Configuration key for prefix bytes */
    public static final String KEY_PREFIX_BYTES = "registration-prefix-bytes";

    /** Configuration key for byte order */
    public static final String KEY_BYTE_ORDER = "registration-byte-order";

    /** Configuration key for delimiter */
    public static final String KEY_DELIMITER = "registration-delimiter";

    /** Configuration key for max length */
    public static final String KEY_MAX_LENGTH = "registration-max-length";

    private RegistrationParserFactory() {
        // Utility class
    }

    /**
     * Creates a parser based on the provided configuration map.
     *
     * @param config the configuration map containing parser parameters
     * @return a configured parser instance
     * @throws IllegalArgumentException if required parameters are missing or invalid
     */
    public static RegistrationPacketParser create(Map<String, String> config) {
        Objects.requireNonNull(config, "Configuration map must not be null");

        String type = config.get(KEY_TYPE);
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: " + KEY_TYPE);
        }

        Charset charset = parseCharset(config.getOrDefault(KEY_CHARSET, "UTF-8"));

        return switch (type.toLowerCase()) {
            case "fixed" -> createFixedLengthParser(config, charset);
            case "regex" -> createRegexParser(config, charset);
            case "prefix", "prefix-length" -> createPrefixLengthParser(config, charset);
            case "delimiter" -> createDelimiterParser(config, charset);
            default -> throw new IllegalArgumentException("Unknown parser type: " + type);
        };
    }

    /**
     * Creates a fixed-length parser.
     *
     * @param length the number of bytes to read
     * @return a fixed-length parser
     */
    public static RegistrationPacketParser fixedLength(int length) {
        return new FixedLengthParser(length);
    }

    /**
     * Creates a fixed-length parser with specified charset.
     *
     * @param length the number of bytes to read
     * @param charset the charset for decoding
     * @return a fixed-length parser
     */
    public static RegistrationPacketParser fixedLength(int length, Charset charset) {
        return new FixedLengthParser(length, charset);
    }

    /**
     * Creates a regex parser.
     *
     * @param pattern the regex pattern with capturing group for device ID
     * @return a regex parser
     */
    public static RegistrationPacketParser regex(String pattern) {
        return new RegexParser(Pattern.compile(pattern));
    }

    /**
     * Creates a regex parser with specified charset and max length.
     *
     * @param pattern the regex pattern
     * @param charset the charset for decoding
     * @param maxLength the maximum bytes to read
     * @return a regex parser
     */
    public static RegistrationPacketParser regex(String pattern, Charset charset, int maxLength) {
        return new RegexParser(Pattern.compile(pattern), charset, maxLength);
    }

    /**
     * Creates a prefix-length parser.
     *
     * @param prefixBytes the number of bytes for length prefix (1, 2, or 4)
     * @return a prefix-length parser
     */
    public static RegistrationPacketParser prefixLength(int prefixBytes) {
        return new PrefixLengthParser(prefixBytes);
    }

    /**
     * Creates a prefix-length parser with specified byte order.
     *
     * @param prefixBytes the number of bytes for length prefix
     * @param byteOrder the byte order (BIG_ENDIAN or LITTLE_ENDIAN)
     * @return a prefix-length parser
     */
    public static RegistrationPacketParser prefixLength(int prefixBytes, ByteOrder byteOrder) {
        return new PrefixLengthParser(prefixBytes, byteOrder);
    }

    /**
     * Creates a CRLF-delimited parser.
     *
     * @return a CRLF-delimited parser
     */
    public static RegistrationPacketParser crlfDelimited() {
        return DelimiterParser.crlf();
    }

    /**
     * Creates a newline-delimited parser.
     *
     * @return a newline-delimited parser
     */
    public static RegistrationPacketParser newlineDelimited() {
        return DelimiterParser.newline();
    }

    /**
     * Creates a null-terminated parser.
     *
     * @return a null-terminated parser
     */
    public static RegistrationPacketParser nullTerminated() {
        return DelimiterParser.nullTerminated();
    }

    // Private factory methods for configuration-based creation

    private static RegistrationPacketParser createFixedLengthParser(
            Map<String, String> config, Charset charset) {
        String lengthStr = config.get(KEY_LENGTH);
        if (lengthStr == null || lengthStr.isBlank()) {
            throw new IllegalArgumentException(
                "Fixed-length parser requires '" + KEY_LENGTH + "' parameter");
        }

        int length;
        try {
            length = Integer.parseInt(lengthStr.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid value for '" + KEY_LENGTH + "': " + lengthStr);
        }

        return new FixedLengthParser(length, charset);
    }

    private static RegistrationPacketParser createRegexParser(
            Map<String, String> config, Charset charset) {
        String patternStr = config.get(KEY_PATTERN);
        if (patternStr == null || patternStr.isBlank()) {
            throw new IllegalArgumentException(
                "Regex parser requires '" + KEY_PATTERN + "' parameter");
        }

        int maxLength = parseMaxLength(config, 256);
        Pattern pattern = Pattern.compile(patternStr);

        return new RegexParser(pattern, charset, maxLength);
    }

    private static RegistrationPacketParser createPrefixLengthParser(
            Map<String, String> config, Charset charset) {
        String prefixBytesStr = config.get(KEY_PREFIX_BYTES);
        if (prefixBytesStr == null || prefixBytesStr.isBlank()) {
            throw new IllegalArgumentException(
                "Prefix-length parser requires '" + KEY_PREFIX_BYTES + "' parameter");
        }

        int prefixBytes;
        try {
            prefixBytes = Integer.parseInt(prefixBytesStr.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid value for '" + KEY_PREFIX_BYTES + "': " + prefixBytesStr);
        }

        ByteOrder byteOrder = parseByteOrder(config.getOrDefault(KEY_BYTE_ORDER, "BIG_ENDIAN"));

        return new PrefixLengthParser(prefixBytes, byteOrder, charset);
    }

    private static RegistrationPacketParser createDelimiterParser(
            Map<String, String> config, Charset charset) {
        String delimiterStr = config.getOrDefault(KEY_DELIMITER, "CRLF");
        int maxLength = parseMaxLength(config, 256);

        byte[] delimiter = parseDelimiter(delimiterStr);

        return new DelimiterParser(delimiter, charset, maxLength);
    }

    private static Charset parseCharset(String charsetName) {
        try {
            return Charset.forName(charsetName);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid charset: " + charsetName);
        }
    }

    private static ByteOrder parseByteOrder(String orderStr) {
        return switch (orderStr.toUpperCase()) {
            case "BIG_ENDIAN", "BE", "BIG" -> ByteOrder.BIG_ENDIAN;
            case "LITTLE_ENDIAN", "LE", "LITTLE" -> ByteOrder.LITTLE_ENDIAN;
            default -> throw new IllegalArgumentException("Invalid byte order: " + orderStr);
        };
    }

    private static int parseMaxLength(Map<String, String> config, int defaultValue) {
        String maxLengthStr = config.get(KEY_MAX_LENGTH);
        if (maxLengthStr == null || maxLengthStr.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(maxLengthStr.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid value for '" + KEY_MAX_LENGTH + "': " + maxLengthStr);
        }
    }

    private static byte[] parseDelimiter(String delimiterStr) {
        return switch (delimiterStr.toUpperCase()) {
            case "CRLF", "\\R\\N" -> new byte[]{'\r', '\n'};
            case "LF", "\\N" -> new byte[]{'\n'};
            case "CR", "\\R" -> new byte[]{'\r'};
            case "NULL", "\\0" -> new byte[]{0x00};
            default -> {
                // Try to parse as hex string (e.g., "0D0A" or "0x0D0A")
                String hex = delimiterStr.startsWith("0x") || delimiterStr.startsWith("0X")
                    ? delimiterStr.substring(2)
                    : delimiterStr;
                if (hex.matches("[0-9A-Fa-f]+") && hex.length() % 2 == 0) {
                    byte[] bytes = new byte[hex.length() / 2];
                    for (int i = 0; i < bytes.length; i++) {
                        bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                    }
                    yield bytes;
                }
                // Fall back to literal string bytes
                yield delimiterStr.getBytes(StandardCharsets.US_ASCII);
            }
        };
    }
}
