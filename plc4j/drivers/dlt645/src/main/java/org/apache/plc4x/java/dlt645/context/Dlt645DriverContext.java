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

import org.apache.plc4x.java.dlt645.readwrite.utils.StaticHelper;

/**
 * DL/T 645-2007 helper utilities for the driver.
 * <p>
 * In SPI3 the old DriverContext class hierarchy was removed; only the static
 * meter-address parsing helper is retained here.
 */
public final class Dlt645DriverContext {

    private Dlt645DriverContext() {
        // Utility class
    }

    /**
     * Parse 12-hex-digit meter address string into 6-byte array in wire order.
     * <p>
     * DL/T 645-2007 Section 4.2: Address field is 6 bytes BCD, transmitted
     * A0 (low byte) first, A5 (high byte) last, and each byte is transmitted
     * with its bit order reversed (低位在前). The input string is in
     * human-readable order (MSB first, as printed on the meter label).
     * <p>
     * Example: "123456789012" → wire bytes [0x48, 0x09, 0x1E, 0x6A, 0x2C, 0x48]
     * (byte order reversed AND each byte bit-reversed).
     */
    public static byte[] parseMeterAddress(String addressStr) {
        if (addressStr == null || addressStr.isEmpty()) {
            return new byte[]{(byte) 0x99, (byte) 0x99, (byte) 0x99,
                (byte) 0x99, (byte) 0x99, (byte) 0x99};
        }
        // Pad to 12 hex digits
        var padded = String.format("%12s", addressStr).replace(' ', '0');
        var bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) Integer.parseInt(padded.substring(i * 2, i * 2 + 2), 16);
        }
        // Reverse to DL/T 645-2007 wire order: A0 (low byte) first, then
        // bit-reverse each byte (低位在前 per 6.1.2).
        for (int i = 0; i < 3; i++) {
            byte tmp = bytes[i];
            bytes[i] = StaticHelper.bitReverse(bytes[5 - i]);
            bytes[5 - i] = StaticHelper.bitReverse(tmp);
        }
        return bytes;
    }
}
