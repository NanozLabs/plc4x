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
package org.apache.plc4x.java.dlt645.context;

/**
 * DL/T 645-2007 helper utilities for the driver.
 * <p>
 * In SPI3 the old DriverContext class hierarchy was removed; only the static
 * meter-address parsing helper is retained here.
 */
public final class Dlt645DriverContext {

    /** Broadcast address {@code 99 99 99 99 99 99H} (DL/T 645-2007 §5.2.2). */
    public static final byte[] BROADCAST_ADDRESS = {
        (byte) 0x99, (byte) 0x99, (byte) 0x99,
        (byte) 0x99, (byte) 0x99, (byte) 0x99
    };

    private Dlt645DriverContext() {
        // Utility class
    }

    /**
     * True when the 6-byte wire address is the all-{@code 99H} broadcast address.
     */
    public static boolean isBroadcastAddress(byte[] address) {
        if (address == null || address.length != 6) {
            return false;
        }
        for (byte b : address) {
            if ((b & 0xFF) != 0x99) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pad a printed 1–12 digit BCD address to 12 digits. Validates the same way as
     * {@link #parseMeterAddress(String)}.
     */
    public static String formatPrintedAddress(String addressStr) {
        parseMeterAddress(addressStr);
        return String.format("%12s", addressStr).replace(' ', '0');
    }

    /**
     * Parse 12-hex-digit meter address string into 6-byte array in wire order.
     * <p>
     * DL/T 645-2007 Section 4.2: Address field is 6 bytes BCD, transmitted
     * A0 (low byte) first and A5 (high byte) last. UART bit order is handled
     * by the physical transport and must not be applied to the address bytes.
     * The input string is in
     * human-readable order (MSB first, as printed on the meter label).
     * <p>
     * Example: "123456789012" -> wire bytes [0x12, 0x90, 0x78, 0x56, 0x34, 0x12].
     */
    public static byte[] parseMeterAddress(String addressStr) {
        if (addressStr == null || addressStr.isEmpty()) {
            throw new IllegalArgumentException("DL/T 645 meter address is required");
        }
        if (!addressStr.matches("[0-9]{1,12}")) {
            throw new IllegalArgumentException("DL/T 645 meter address must contain 1 to 12 BCD digits");
        }
        // Pad shorter printed addresses to 12 BCD digits.
        var padded = String.format("%12s", addressStr).replace(' ', '0');
        var bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) Integer.parseInt(padded.substring(i * 2, i * 2 + 2), 16);
        }
        // Reverse to DL/T 645-2007 wire order: A0 (low byte) first.
        for (int i = 0; i < 3; i++) {
            byte tmp = bytes[i];
            bytes[i] = bytes[5 - i];
            bytes[5 - i] = tmp;
        }
        return bytes;
    }
}
