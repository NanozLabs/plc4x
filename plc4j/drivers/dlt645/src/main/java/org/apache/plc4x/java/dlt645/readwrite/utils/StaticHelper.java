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
package org.apache.plc4x.java.dlt645.readwrite.utils;

import org.apache.plc4x.java.dlt645.readwrite.ControlCode;
import org.apache.plc4x.java.spi.buffers.api.ReadBuffer;
import org.apache.plc4x.java.spi.buffers.api.WriteBuffer;
import org.apache.plc4x.java.spi.buffers.api.exceptions.BufferException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StaticHelper {

    private static final int FRAME_START = 0x68;
    private static final int FRAME_END = 0x16;
    private static final int HEADER_SIZE_WITHOUT_DATA = 10; // 68 + addr(6) + 68 + control + length
    private static final int FIXED_FRAME_OVERHEAD = 12;     // start + addr + start + control + length + cs + end
    private static final int LENGTH_INDEX = 9;

    private StaticHelper() {
        // Utility class
    }

    /**
     * Reverse the bit order of a byte (e.g. 0x12 -> 0x48).
     * DL/T 645-2007 transmits address bytes 低位在前 (bit-reversed).
     */
    public static byte bitReverse(byte b) {
        int v = b & 0xFF;
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | (v & 1);
            v >>= 1;
        }
        return (byte) r;
    }

    /**
     * Checksum per DL/T 645-2007 Section 4.2:
     * CS = mod-256 sum of all bytes from first 0x68 to before CS.
     * Includes: start1(0x68) + address + start2(0x68) + control + length + wire data.
     * dataPlain is decoded; we re-encode to wire (+0x33) for checksum calculation.
     */
    public static short calcCs(byte[] address, ControlCode control, short length, byte[] dataPlain) {
        int sum = FRAME_START; // start1 = 0x68
        if (address != null) {
            for (byte b : address) {
                sum += b & 0xFF;
            }
        }
        sum += FRAME_START; // start2 = 0x68
        sum += control.getValue() & 0xFF;
        sum += length & 0xFF;
        if (dataPlain != null) {
            for (byte b : dataPlain) {
                sum += encode33(b) & 0xFF;
            }
        }
        return (short) (sum & 0xFF);
    }

    public static byte encode33(byte plainByte) {
        return (byte) (((plainByte & 0xFF) + 0x33) & 0xFF);
    }

    public static byte decode33(byte wireByte) {
        return (byte) (((wireByte & 0xFF) - 0x33) & 0xFF);
    }

    public static byte[] encode33(byte[] plainBytes) {
        if (plainBytes == null) {
            return new byte[0];
        }
        byte[] encoded = Arrays.copyOf(plainBytes, plainBytes.length);
        for (int i = 0; i < encoded.length; i++) {
            encoded[i] = encode33(encoded[i]);
        }
        return encoded;
    }

    public static byte[] decode33(byte[] wireBytes) {
        if (wireBytes == null) {
            return new byte[0];
        }
        byte[] decoded = Arrays.copyOf(wireBytes, wireBytes.length);
        for (int i = 0; i < decoded.length; i++) {
            decoded[i] = decode33(decoded[i]);
        }
        return decoded;
    }

    /**
     * Parse one wire byte and convert it to plain byte (-0x33).
     */
    public static byte parseDataByteMinus33(ReadBuffer readBuffer) {
        try {
            short wireByte = readBuffer.readUnsignedShort(8);
            return decode33((byte) (wireByte & 0xFF));
        } catch (BufferException e) {
            return 0;
        }
    }

    /**
     * Serialize one plain byte as wire byte (+0x33).
     */
    public static void serializeDataBytePlus33(WriteBuffer writeBuffer, byte plainByte) {
        try {
            writeBuffer.writeUnsignedShort(8, (short) (encode33(plainByte) & 0xFF));
        } catch (BufferException e) {
            // Keep behavior consistent with other helper implementations.
        }
    }

    /**
     * Find aligned frame start: first 0x68 whose +7 offset is also 0x68.
     */
    public static int findFrameStart(byte[] buffer) {
        return findFrameStart(buffer, 0);
    }

    public static int findFrameStart(byte[] buffer, int fromOffset) {
        if (buffer == null || buffer.length == 0) {
            return -1;
        }
        int start = Math.max(0, fromOffset);
        for (int i = start; i < buffer.length; i++) {
            if ((buffer[i] & 0xFF) != FRAME_START) {
                continue;
            }
            if (i + 7 >= buffer.length) {
                return -1;
            }
            if ((buffer[i + 7] & 0xFF) == FRAME_START) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Estimate frame length from aligned frame start.
     * Returns -1 when frame is incomplete or malformed.
     */
    public static int estimateFrameLength(byte[] buffer, int frameStartOffset) {
        if (buffer == null || frameStartOffset < 0 || frameStartOffset >= buffer.length) {
            return -1;
        }
        if (buffer.length < frameStartOffset + HEADER_SIZE_WITHOUT_DATA) {
            return -1;
        }
        if (((buffer[frameStartOffset] & 0xFF) != FRAME_START) ||
            ((buffer[frameStartOffset + 7] & 0xFF) != FRAME_START)) {
            return -1;
        }

        int dataLength = buffer[frameStartOffset + LENGTH_INDEX] & 0xFF;
        int frameLength = dataLength + FIXED_FRAME_OVERHEAD;
        int frameEndIndex = frameStartOffset + frameLength - 1;
        if (frameEndIndex >= buffer.length) {
            return -1;
        }
        if ((buffer[frameEndIndex] & 0xFF) != FRAME_END) {
            return -1;
        }
        return frameLength;
    }

    // --- Error code descriptions per DL/T 645-2007 ---

    /**
     * DL/T 645-2007 error code bit definitions.
     * The ERR byte in error response data field uses individual bits:
     * <ul>
     *   <li>Bit 0: Other error (其他错误)</li>
     *   <li>Bit 1: No requested data (无请求数据)</li>
     *   <li>Bit 2: Password error / unauthorized (密码错/未授权)</li>
     *   <li>Bit 3: Baud rate cannot change (通信速率不能更改)</li>
     *   <li>Bit 4: Year time zone overflow (年时区数超)</li>
     *   <li>Bit 5: Day period overflow (日时段数超)</li>
     *   <li>Bit 6: Rate number overflow (费率数超)</li>
     *   <li>Bit 7: Reserved (保留)</li>
     * </ul>
     */
    private static final String[] ERROR_DESCRIPTIONS = {
        "Other error",
        "No requested data",
        "Password error/unauthorized",
        "Baud rate cannot change",
        "Year time zone overflow",
        "Day period overflow",
        "Rate number overflow",
        "Reserved"
    };

    /**
     * Parse ERR byte from error response data field and return human-readable descriptions.
     *
     * @param errByte the ERR byte from the error response data field
     * @return list of error descriptions for all set bits
     */
    public static List<String> parseErrorCode(byte errByte) {
        var errors = new ArrayList<String>();
        int err = errByte & 0xFF;
        for (int i = 0; i < 8; i++) {
            if ((err & (1 << i)) != 0) {
                errors.add(ERROR_DESCRIPTIONS[i]);
            }
        }
        if (errors.isEmpty()) {
            errors.add("Unknown error (ERR=0x" + String.format("%02X", err) + ")");
        }
        return errors;
    }

    /**
     * Extract and format error description from an error response's plain data.
     * Per DL/T 645-2007, error response data = DI(4 bytes reversed) + ERR(1 byte).
     *
     * @param dataPlain decoded data field (after -0x33)
     * @return formatted error string, or empty string if data is too short
     */
    public static String describeError(byte[] dataPlain) {
        if (dataPlain == null || dataPlain.length < 5) {
            return dataPlain != null && dataPlain.length > 0
                ? "Error (insufficient data, length=" + dataPlain.length + ")"
                : "Error (no data)";
        }
        byte errByte = dataPlain[4];
        var errors = parseErrorCode(errByte);
        return "ERR=0x" + String.format("%02X", errByte & 0xFF)
            + " [" + String.join(", ", errors) + "]";
    }
}
