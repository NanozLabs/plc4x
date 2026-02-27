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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DL/T 645-2007 Data Identifier (DI) registry and semantic decoder.
 * <p>
 * Provides human-readable descriptions and BCD decoding configuration
 * for common data items defined in DL/T 645-2007 Annex A.
 * <p>
 * DI format: DI3 DI2 DI1 DI0 (4 bytes, big-endian in tag address).
 */
public final class DataIdentifiers {

    private DataIdentifiers() {
        // Utility class
    }

    /**
     * Descriptor for a DL/T 645-2007 data item.
     */
    public static class DataItemDescriptor {
        private final String di;
        private final String name;
        private final String unit;
        private final int dataLength;
        private final int decimalPlaces;

        public DataItemDescriptor(String di, String name, String unit,
                                  int dataLength, int decimalPlaces) {
            this.di = di;
            this.name = name;
            this.unit = unit;
            this.dataLength = dataLength;
            this.decimalPlaces = decimalPlaces;
        }

        public String getDi() { return di; }
        public String getName() { return name; }
        public String getUnit() { return unit; }
        public int getDataLength() { return dataLength; }
        public int getDecimalPlaces() { return decimalPlaces; }

        @Override
        public String toString() {
            return name + " (" + di + ") [" + unit + "]";
        }
    }

    private static final Map<String, DataItemDescriptor> REGISTRY;

    static {
        var map = new LinkedHashMap<String, DataItemDescriptor>();

        // ================================================================
        // DI3=00: Energy accumulation (Annex A, Table A.1)
        // Format: XXXXXX.XX kWh/kvarh/kVAh, 4 bytes BCD, 2 decimal
        // ================================================================

        // 00 01: Positive active energy (组合有功, 正向有功)
        reg(map, "00010000", "Total positive active energy", "kWh", 4, 2);
        reg(map, "00010100", "Rate 1 positive active energy", "kWh", 4, 2);
        reg(map, "00010200", "Rate 2 positive active energy", "kWh", 4, 2);
        reg(map, "00010300", "Rate 3 positive active energy", "kWh", 4, 2);
        reg(map, "00010400", "Rate 4 positive active energy", "kWh", 4, 2);

        // 00 02: Negative active energy (反向有功)
        reg(map, "00020000", "Total negative active energy", "kWh", 4, 2);
        reg(map, "00020100", "Rate 1 negative active energy", "kWh", 4, 2);
        reg(map, "00020200", "Rate 2 negative active energy", "kWh", 4, 2);
        reg(map, "00020300", "Rate 3 negative active energy", "kWh", 4, 2);
        reg(map, "00020400", "Rate 4 negative active energy", "kWh", 4, 2);

        // 00 03: Combined reactive energy 1 (组合无功1)
        reg(map, "00030000", "Total combined reactive energy 1", "kvarh", 4, 2);
        reg(map, "00030100", "Rate 1 combined reactive energy 1", "kvarh", 4, 2);
        reg(map, "00030200", "Rate 2 combined reactive energy 1", "kvarh", 4, 2);
        reg(map, "00030300", "Rate 3 combined reactive energy 1", "kvarh", 4, 2);
        reg(map, "00030400", "Rate 4 combined reactive energy 1", "kvarh", 4, 2);

        // 00 04: Combined reactive energy 2 (组合无功2)
        reg(map, "00040000", "Total combined reactive energy 2", "kvarh", 4, 2);
        reg(map, "00040100", "Rate 1 combined reactive energy 2", "kvarh", 4, 2);
        reg(map, "00040200", "Rate 2 combined reactive energy 2", "kvarh", 4, 2);
        reg(map, "00040300", "Rate 3 combined reactive energy 2", "kvarh", 4, 2);
        reg(map, "00040400", "Rate 4 combined reactive energy 2", "kvarh", 4, 2);

        // 00 05: Quadrant 1 reactive energy (第一象限无功)
        reg(map, "00050000", "Total Q1 reactive energy", "kvarh", 4, 2);
        reg(map, "00050100", "Rate 1 Q1 reactive energy", "kvarh", 4, 2);
        reg(map, "00050200", "Rate 2 Q1 reactive energy", "kvarh", 4, 2);
        reg(map, "00050300", "Rate 3 Q1 reactive energy", "kvarh", 4, 2);
        reg(map, "00050400", "Rate 4 Q1 reactive energy", "kvarh", 4, 2);

        // 00 06: Quadrant 2 reactive energy (第二象限无功)
        reg(map, "00060000", "Total Q2 reactive energy", "kvarh", 4, 2);
        reg(map, "00060100", "Rate 1 Q2 reactive energy", "kvarh", 4, 2);
        reg(map, "00060200", "Rate 2 Q2 reactive energy", "kvarh", 4, 2);
        reg(map, "00060300", "Rate 3 Q2 reactive energy", "kvarh", 4, 2);
        reg(map, "00060400", "Rate 4 Q2 reactive energy", "kvarh", 4, 2);

        // 00 07: Quadrant 3 reactive energy (第三象限无功)
        reg(map, "00070000", "Total Q3 reactive energy", "kvarh", 4, 2);
        reg(map, "00070100", "Rate 1 Q3 reactive energy", "kvarh", 4, 2);
        reg(map, "00070200", "Rate 2 Q3 reactive energy", "kvarh", 4, 2);
        reg(map, "00070300", "Rate 3 Q3 reactive energy", "kvarh", 4, 2);
        reg(map, "00070400", "Rate 4 Q3 reactive energy", "kvarh", 4, 2);

        // 00 08: Quadrant 4 reactive energy (第四象限无功)
        reg(map, "00080000", "Total Q4 reactive energy", "kvarh", 4, 2);
        reg(map, "00080100", "Rate 1 Q4 reactive energy", "kvarh", 4, 2);
        reg(map, "00080200", "Rate 2 Q4 reactive energy", "kvarh", 4, 2);
        reg(map, "00080300", "Rate 3 Q4 reactive energy", "kvarh", 4, 2);
        reg(map, "00080400", "Rate 4 Q4 reactive energy", "kvarh", 4, 2);

        // 00 09: Positive apparent energy (正向视在)
        reg(map, "00090000", "Total positive apparent energy", "kVAh", 4, 2);
        reg(map, "00090100", "Rate 1 positive apparent energy", "kVAh", 4, 2);
        reg(map, "00090200", "Rate 2 positive apparent energy", "kVAh", 4, 2);
        reg(map, "00090300", "Rate 3 positive apparent energy", "kVAh", 4, 2);
        reg(map, "00090400", "Rate 4 positive apparent energy", "kVAh", 4, 2);

        // 00 0A: Negative apparent energy (反向视在)
        reg(map, "000A0000", "Total negative apparent energy", "kVAh", 4, 2);
        reg(map, "000A0100", "Rate 1 negative apparent energy", "kVAh", 4, 2);
        reg(map, "000A0200", "Rate 2 negative apparent energy", "kVAh", 4, 2);
        reg(map, "000A0300", "Rate 3 negative apparent energy", "kVAh", 4, 2);
        reg(map, "000A0400", "Rate 4 negative apparent energy", "kVAh", 4, 2);

        // ================================================================
        // DI3=02: Instantaneous variables (Annex A, Table A.2)
        // ================================================================

        // 02 01: Voltage (V), XX.X, 2 bytes BCD, 1 decimal
        reg(map, "02010100", "A-phase voltage", "V", 2, 1);
        reg(map, "02010200", "B-phase voltage", "V", 2, 1);
        reg(map, "02010300", "C-phase voltage", "V", 2, 1);

        // 02 02: Current (A), XXX.XXX, 3 bytes BCD, 3 decimal
        reg(map, "02020100", "A-phase current", "A", 3, 3);
        reg(map, "02020200", "B-phase current", "A", 3, 3);
        reg(map, "02020300", "C-phase current", "A", 3, 3);

        // 02 03: Active power (kW), XX.XXXX, 3 bytes BCD, 4 decimal
        reg(map, "02030000", "Total active power", "kW", 3, 4);
        reg(map, "02030100", "A-phase active power", "kW", 3, 4);
        reg(map, "02030200", "B-phase active power", "kW", 3, 4);
        reg(map, "02030300", "C-phase active power", "kW", 3, 4);

        // 02 04: Reactive power (kvar), XX.XXXX, 3 bytes BCD, 4 decimal
        reg(map, "02040000", "Total reactive power", "kvar", 3, 4);
        reg(map, "02040100", "A-phase reactive power", "kvar", 3, 4);
        reg(map, "02040200", "B-phase reactive power", "kvar", 3, 4);
        reg(map, "02040300", "C-phase reactive power", "kvar", 3, 4);

        // 02 05: Apparent power (kVA), XX.XXXX, 3 bytes BCD, 4 decimal
        reg(map, "02050000", "Total apparent power", "kVA", 3, 4);
        reg(map, "02050100", "A-phase apparent power", "kVA", 3, 4);
        reg(map, "02050200", "B-phase apparent power", "kVA", 3, 4);
        reg(map, "02050300", "C-phase apparent power", "kVA", 3, 4);

        // 02 06: Power factor, X.XXX, 2 bytes BCD, 3 decimal
        reg(map, "02060000", "Total power factor", "", 2, 3);
        reg(map, "02060100", "A-phase power factor", "", 2, 3);
        reg(map, "02060200", "B-phase power factor", "", 2, 3);
        reg(map, "02060300", "C-phase power factor", "", 2, 3);

        // 02 07: Phase angle (°), XXX.X, 2 bytes BCD, 1 decimal
        reg(map, "02070100", "A-phase angle", "\u00B0", 2, 1);
        reg(map, "02070200", "B-phase angle", "\u00B0", 2, 1);
        reg(map, "02070300", "C-phase angle", "\u00B0", 2, 1);

        // 02 80: Other instantaneous variables
        reg(map, "02800001", "Zero-line current", "A", 3, 3);
        reg(map, "02800002", "Grid frequency", "Hz", 2, 2);
        reg(map, "02800003", "Phase-sequence indicator", "", 1, 0);
        reg(map, "02800007", "Table internal temperature", "\u2103", 2, 1);
        reg(map, "02800008", "Clock battery voltage", "V", 2, 2);
        reg(map, "02800009", "Meter internal temperature", "\u2103", 2, 1);

        // ================================================================
        // DI3=04: Date/Time (Annex A, Table A.4)
        // ================================================================
        reg(map, "04000101", "Date and time (YYMMDDWWhhmmss)", "", 7, 0);
        reg(map, "04000102", "Date (YYMMDDWW)", "", 4, 0);
        reg(map, "04000103", "Time (hhmmss)", "", 3, 0);

        REGISTRY = Collections.unmodifiableMap(map);
    }

    private static void reg(Map<String, DataItemDescriptor> map,
                            String di, String name, String unit,
                            int dataLength, int decimalPlaces) {
        map.put(di.toUpperCase(), new DataItemDescriptor(di, name, unit, dataLength, decimalPlaces));
    }

    /**
     * Look up a data item descriptor by DI hex string.
     *
     * @param diHex 8-char hex string (e.g. "00010000")
     * @return descriptor or null if not found
     */
    public static DataItemDescriptor lookup(String diHex) {
        if (diHex == null) return null;
        return REGISTRY.get(diHex.toUpperCase());
    }

    /**
     * Look up a data item descriptor by DI bytes.
     *
     * @param di 4-byte DI array
     * @return descriptor or null if not found
     */
    public static DataItemDescriptor lookup(byte[] di) {
        if (di == null || di.length != 4) return null;
        var sb = new StringBuilder();
        for (byte b : di) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return lookup(sb.toString());
    }

    /**
     * Get all registered data item descriptors.
     */
    public static Map<String, DataItemDescriptor> getAll() {
        return REGISTRY;
    }

    /**
     * Parse BCD-encoded response data with semantic decoding.
     * <p>
     * Uses the DI's decimal places configuration to convert raw BCD
     * to a properly scaled double-precision value.
     * <p>
     * Note: Uses {@code double} (not {@code float}) to avoid precision loss
     * for large energy values (e.g. 99999999.99 kWh exceeds float's ~7 significant digits).
     *
     * @param diHex DI hex string (e.g. "00010000")
     * @param bcdData BCD-encoded data bytes (LSB first)
     * @return decoded value, or raw BCD value if DI is unknown
     */
    public static double decodeValue(String diHex, byte[] bcdData) {
        if (bcdData == null || bcdData.length == 0) {
            return 0.0;
        }

        // Parse raw BCD integer
        long rawValue = 0;
        for (int i = bcdData.length - 1; i >= 0; i--) {
            int high = (bcdData[i] >> 4) & 0x0F;
            int low = bcdData[i] & 0x0F;
            rawValue = rawValue * 100 + high * 10 + low;
        }

        var descriptor = lookup(diHex);
        if (descriptor == null || descriptor.getDecimalPlaces() == 0) {
            return (double) rawValue;
        }

        // Apply decimal scaling using double-precision lookup table
        // decimalPlaces range: 1-4, so direct power-of-10 is safe
        double divisor = Math.pow(10.0, descriptor.getDecimalPlaces());
        return rawValue / divisor;
    }
}
