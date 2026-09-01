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
package org.apache.plc4x.java.cjt188.context;

/**
 * CJ/T 188-2004 helper utilities for the driver.
 * <p>
 * Address field is 7 bytes: A0 = meter type, A1..A6 = 12-digit BCD transmitted
 * low byte first (CJ/T 188-2004 §6.2).
 */
public final class Cjt188DriverContext {

    /** Point-to-point wildcard address used by read/write communication-address commands. */
    public static final byte[] WILDCARD_ADDRESS = {
        (byte) 0xAA, (byte) 0xAA, (byte) 0xAA, (byte) 0xAA,
        (byte) 0xAA, (byte) 0xAA, (byte) 0xAA
    };

    /** Broadcast address {@code 99H} x 7. */
    public static final byte[] BROADCAST_ADDRESS = {
        (byte) 0x99, (byte) 0x99, (byte) 0x99, (byte) 0x99,
        (byte) 0x99, (byte) 0x99, (byte) 0x99
    };

    public static final byte TYPE_COLD_WATER = 0x10;
    public static final byte TYPE_HOT_WATER = 0x11;
    public static final byte TYPE_DRINKING_WATER = 0x12;
    public static final byte TYPE_RECLAIMED_WATER = 0x13;
    public static final byte TYPE_HEAT = 0x20;
    public static final byte TYPE_GAS = 0x30;

    private Cjt188DriverContext() {
        // Utility class
    }

    public static boolean isBroadcastAddress(byte[] address) {
        return isFilled(address, 0x99);
    }

    public static boolean isWildcardAddress(byte[] address) {
        return isFilled(address, 0xAA);
    }

    private static boolean isFilled(byte[] address, int value) {
        if (address == null || address.length != 7) {
            return false;
        }
        for (byte b : address) {
            if ((b & 0xFF) != value) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parse a printed meter address into the 7-byte wire address.
     * <p>
     * Accepts:
     * <ul>
     *   <li>12 BCD digits plus {@code meterType} (two hex digits) — A0 from type, A1..A6 from the address</li>
     *   <li>14 hex digits — first two digits are A0 (meter type), remaining 12 are the printed address</li>
     * </ul>
     * Example: type {@code 10}, printed {@code 123456789012} → wire
     * {@code 10 12 90 78 56 34 12}.
     */
    public static byte[] parseMeterAddress(String addressStr, String meterType) {
        if (addressStr == null || addressStr.isEmpty()) {
            throw new IllegalArgumentException("CJ/T 188 meter address is required");
        }
        String digits = addressStr.trim();
        byte type;
        String printed;
        if (digits.matches("[0-9A-Fa-f]{14}")) {
            type = (byte) Integer.parseInt(digits.substring(0, 2), 16);
            printed = digits.substring(2);
            if (!printed.matches("[0-9]{12}")) {
                throw new IllegalArgumentException(
                    "CJ/T 188 14-digit address must be type(2 hex) + 12 BCD digits");
            }
        } else if (digits.matches("[0-9]{1,12}")) {
            type = parseMeterType(meterType);
            printed = String.format("%12s", digits).replace(' ', '0');
        } else {
            throw new IllegalArgumentException(
                "CJ/T 188 meter address must be 1-12 BCD digits, or 14 hex digits including meter type");
        }
        var bytes = new byte[7];
        bytes[0] = type;
        for (int i = 0; i < 6; i++) {
            bytes[i + 1] = (byte) Integer.parseInt(printed.substring(i * 2, i * 2 + 2), 16);
        }
        // Reverse A1..A6 to low-byte-first wire order. A0 (type) stays first.
        for (int i = 0; i < 3; i++) {
            byte tmp = bytes[1 + i];
            bytes[1 + i] = bytes[6 - i];
            bytes[6 - i] = tmp;
        }
        return bytes;
    }

    public static byte parseMeterType(String meterType) {
        if (meterType == null || meterType.isEmpty()) {
            return TYPE_COLD_WATER;
        }
        if (!meterType.matches("[0-9A-Fa-f]{2}")) {
            throw new IllegalArgumentException("CJ/T 188 meter-type must be two hex digits");
        }
        return (byte) Integer.parseInt(meterType, 16);
    }

    /**
     * Format a 7-byte wire address as a 14-hex-digit string: type + printed 12-digit address.
     */
    public static String formatAddress(byte[] addrBytes) {
        if (addrBytes == null || addrBytes.length < 7) {
            return "";
        }
        var sb = new StringBuilder(14);
        sb.append(String.format("%02X", addrBytes[0] & 0xFF));
        for (int i = 6; i >= 1; i--) {
            sb.append(String.format("%02X", addrBytes[i] & 0xFF));
        }
        return sb.toString();
    }
}
