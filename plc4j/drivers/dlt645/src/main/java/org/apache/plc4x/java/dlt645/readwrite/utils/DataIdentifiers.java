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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    public enum DataFormat {
        BCD,
        SIGNED_BCD,
        DATE_TIME,
        DATE,
        TIME,
        RAW
    }

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
        private final DataFormat format;

        public DataItemDescriptor(String di, String name, String unit,
                                  int dataLength, int decimalPlaces) {
            this(di, name, unit, dataLength, decimalPlaces, DataFormat.BCD);
        }

        public DataItemDescriptor(String di, String name, String unit,
                                  int dataLength, int decimalPlaces, DataFormat format) {
            this.di = di;
            this.name = name;
            this.unit = unit;
            this.dataLength = dataLength;
            this.decimalPlaces = decimalPlaces;
            this.format = format;
        }

        public String getDi() { return di; }
        public String getName() { return name; }
        public String getUnit() { return unit; }
        public int getDataLength() { return dataLength; }
        public int getDecimalPlaces() { return decimalPlaces; }
        public DataFormat getFormat() { return format; }

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
        regSigned(map, "02020100", "A-phase current", "A", 3, 3);
        regSigned(map, "02020200", "B-phase current", "A", 3, 3);
        regSigned(map, "02020300", "C-phase current", "A", 3, 3);

        // 02 03: Active power (kW), XX.XXXX, 3 bytes BCD, 4 decimal
        regSigned(map, "02030000", "Total active power", "kW", 3, 4);
        regSigned(map, "02030100", "A-phase active power", "kW", 3, 4);
        regSigned(map, "02030200", "B-phase active power", "kW", 3, 4);
        regSigned(map, "02030300", "C-phase active power", "kW", 3, 4);

        // 02 04: Reactive power (kvar), XX.XXXX, 3 bytes BCD, 4 decimal
        regSigned(map, "02040000", "Total reactive power", "kvar", 3, 4);
        regSigned(map, "02040100", "A-phase reactive power", "kvar", 3, 4);
        regSigned(map, "02040200", "B-phase reactive power", "kvar", 3, 4);
        regSigned(map, "02040300", "C-phase reactive power", "kvar", 3, 4);

        // 02 05: Apparent power (kVA), XX.XXXX, 3 bytes BCD, 4 decimal
        regSigned(map, "02050000", "Total apparent power", "kVA", 3, 4);
        regSigned(map, "02050100", "A-phase apparent power", "kVA", 3, 4);
        regSigned(map, "02050200", "B-phase apparent power", "kVA", 3, 4);
        regSigned(map, "02050300", "C-phase apparent power", "kVA", 3, 4);

        // 02 06: Power factor, X.XXX, 2 bytes BCD, 3 decimal
        regSigned(map, "02060000", "Total power factor", "", 2, 3);
        regSigned(map, "02060100", "A-phase power factor", "", 2, 3);
        regSigned(map, "02060200", "B-phase power factor", "", 2, 3);
        regSigned(map, "02060300", "C-phase power factor", "", 2, 3);

        // 02 07: Phase angle (°), XXX.X, 2 bytes BCD, 1 decimal
        reg(map, "02070100", "A-phase angle", "\u00B0", 2, 1);
        reg(map, "02070200", "B-phase angle", "\u00B0", 2, 1);
        reg(map, "02070300", "C-phase angle", "\u00B0", 2, 1);

        // 02 80: Other instantaneous variables
        regSigned(map, "02800001", "Zero-line current", "A", 3, 3);
        reg(map, "02800002", "Grid frequency", "Hz", 2, 2);
        regSigned(map, "02800003", "One-minute total active average power", "kW", 3, 4);
        regSigned(map, "02800004", "Current active demand", "kW", 3, 4);
        regSigned(map, "02800005", "Current reactive demand", "kvar", 3, 4);
        regSigned(map, "02800006", "Current apparent demand", "kVA", 3, 4);
        regSigned(map, "02800007", "Table internal temperature", "\u2103", 2, 1);
        reg(map, "02800008", "Clock battery voltage", "V", 2, 2);
        reg(map, "02800009", "Meter-reading battery voltage", "V", 2, 2);
        reg(map, "0280000A", "Internal battery operating time", "min", 4, 0);
        reg(map, "0280000B", "Current tier electricity price", "currency/kWh", 4, 4);

        // ================================================================
        // DI3=04: Date/Time (Annex A, Table A.4)
        // ================================================================
        reg(map, "04000101", "Date and time (YYMMDDWWhhmmss)", "", 7, 0, DataFormat.DATE_TIME);
        reg(map, "04000102", "Date (YYMMDDWW)", "", 4, 0, DataFormat.DATE);
        reg(map, "04000103", "Time (hhmmss)", "", 3, 0, DataFormat.TIME);

        REGISTRY = Collections.unmodifiableMap(map);
    }

    private static void reg(Map<String, DataItemDescriptor> map,
                            String di, String name, String unit,
                            int dataLength, int decimalPlaces) {
        reg(map, di, name, unit, dataLength, decimalPlaces, DataFormat.BCD);
    }

    private static void regSigned(Map<String, DataItemDescriptor> map,
                                  String di, String name, String unit,
                                  int dataLength, int decimalPlaces) {
        reg(map, di, name, unit, dataLength, decimalPlaces, DataFormat.SIGNED_BCD);
    }

    private static void reg(Map<String, DataItemDescriptor> map,
                            String di, String name, String unit,
                            int dataLength, int decimalPlaces, DataFormat format) {
        map.put(di.toUpperCase(),
            new DataItemDescriptor(di, name, unit, dataLength, decimalPlaces, format));
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
     * Check if a DI hex string contains a wildcard (0xFF) byte.
     *
     * @param diHex 8-char hex string (e.g. "0201FF00")
     * @return true if any byte is 0xFF
     */
    public static boolean containsWildcard(String diHex) {
        if (diHex == null || diHex.length() != 8) return false;
        var upper = diHex.toUpperCase();
        for (int i = 0; i < 4; i++) {
            if ("FF".equals(upper.substring(i * 2, i * 2 + 2))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a 4-byte DI array contains a wildcard (0xFF) byte.
     */
    public static boolean containsWildcard(byte[] di) {
        if (di == null || di.length != 4) return false;
        for (byte b : di) {
            if ((b & 0xFF) == 0xFF) return true;
        }
        return false;
    }

    /**
     * Get all concrete sub-items matching a wildcard DI, ordered by the wildcard byte's value.
     * <p>
     * For example, "0201FF00" matches 02010100, 02010200, 02010300 (A/B/C-phase voltage).
     * The returned list is ordered by the varying byte value (ascending).
     *
     * @param wildcardDi 8-char hex string with one or more FF bytes (e.g. "0201FF00")
     * @return ordered list of matching descriptors, empty if no match
     */
    public static List<DataItemDescriptor> getSubItems(String wildcardDi) {
        if (wildcardDi == null || wildcardDi.length() != 8) return Collections.emptyList();
        var upper = wildcardDi.toUpperCase();

        // Find which byte positions are FF (wildcard)
        var wildcardPositions = new boolean[4];
        var fixedBytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            var byteStr = upper.substring(i * 2, i * 2 + 2);
            if ("FF".equals(byteStr)) {
                wildcardPositions[i] = true;
            } else {
                fixedBytes[i] = (byte) Integer.parseInt(byteStr, 16);
            }
        }

        // Find all registry entries matching the non-FF positions
        var result = new ArrayList<DataItemDescriptor>();
        for (var entry : REGISTRY.entrySet()) {
            var di = entry.getKey();
            boolean matches = true;
            for (int i = 0; i < 4; i++) {
                if (wildcardPositions[i]) continue;
                var diByteStr = di.substring(i * 2, i * 2 + 2);
                var diByte = (byte) Integer.parseInt(diByteStr, 16);
                if (diByte != fixedBytes[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                result.add(entry.getValue());
            }
        }

        // Sort by the first wildcard position's byte value (ascending)
        for (int i = 0; i < 4; i++) {
            if (wildcardPositions[i]) {
                final int pos = i;
                result.sort((a, b) -> {
                    var aVal = Integer.parseInt(a.getDi().toUpperCase().substring(pos * 2, pos * 2 + 2), 16);
                    var bVal = Integer.parseInt(b.getDi().toUpperCase().substring(pos * 2, pos * 2 + 2), 16);
                    return Integer.compare(aVal, bVal);
                });
                break;
            }
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * Compute the wildcard DI for a group of DIs that differ in exactly one byte position.
     * <p>
     * Given ["02010100", "02010200", "02010300"], returns "0201FF00" (position 2 varies).
     * Returns null if the DIs don't form a valid wildcard group.
     *
     * @param diHexList list of 8-char hex DI strings
     * @return wildcard DI hex string, or null if not groupable
     */
    public static String computeWildcardDi(List<String> diHexList) {
        if (diHexList == null || diHexList.size() < 2) return null;

        // Parse all DIs into byte arrays
        var first = diHexList.get(0).toUpperCase();
        var firstBytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            firstBytes[i] = (byte) Integer.parseInt(first.substring(i * 2, i * 2 + 2), 16);
        }

        // Find positions that differ across all DIs
        var differs = new boolean[4];
        for (int d = 1; d < diHexList.size(); d++) {
            var di = diHexList.get(d).toUpperCase();
            for (int i = 0; i < 4; i++) {
                var b = (byte) Integer.parseInt(di.substring(i * 2, i * 2 + 2), 16);
                if (b != firstBytes[i]) {
                    differs[i] = true;
                }
            }
        }

        // Exactly one position must differ
        int differCount = 0;
        int differPos = -1;
        for (int i = 0; i < 4; i++) {
            if (differs[i]) {
                differCount++;
                differPos = i;
            }
        }
        if (differCount != 1) return null;

        // Build wildcard DI
        var sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i == differPos) {
                sb.append("FF");
            } else {
                sb.append(String.format("%02X", firstBytes[i] & 0xFF));
            }
        }
        return sb.toString();
    }

    /**
     * Whether an optimizer may auto-merge tags into this wildcard DI.
     * <p>
     * DL/T 645-2007 §6 allows FF in DI2 / DI1 / DI0, never in DI3. Auto-merge is
     * further restricted to the industrially common blocks:
     * <ul>
     *   <li>DI1=FF — rates or phases of one quantity ({@code 0001FF00}, {@code 0201FF00})</li>
     *   <li>DI0=FF and DI3=00 — settlement days of an energy item ({@code 000100FF})</li>
     * </ul>
     * Instantaneous (DI3=02) and parameter (DI3=04) DI0 wildcards such as
     * {@code 028000FF} or {@code 040001FF} mix different lengths and meanings
     * and must not be auto-merged. DI2=FF would mean "every quantity type"
     * (for example {@code 00FF0000} for all energies), which many meters do
     * not implement.
     */
    public static boolean isOptimizerSafeBlockDi(String wildcardDi) {
        if (wildcardDi == null || wildcardDi.length() != 8 || !containsWildcard(wildcardDi)) {
            return false;
        }
        var upper = wildcardDi.toUpperCase();
        boolean di3 = "FF".equals(upper.substring(0, 2));
        boolean di2 = "FF".equals(upper.substring(2, 4));
        boolean di1 = "FF".equals(upper.substring(4, 6));
        boolean di0 = "FF".equals(upper.substring(6, 8));
        if (di3 || di2) {
            return false;
        }
        if (di1 && !di0) {
            return true;
        }
        // Settlement-day block: only energy (DI3=00).
        return di0 && !di1 && "00".equals(upper.substring(0, 2));
    }

    /**
     * Find the index of a concrete DI within the sub-items of a wildcard DI.
     *
     * @param wildcardDi wildcard DI hex string (e.g. "0201FF00")
     * @param concreteDi concrete DI hex string (e.g. "02010200")
     * @return 0-based index, or -1 if not found
     */
    public static int getSubItemIndex(String wildcardDi, String concreteDi) {
        var subItems = getSubItems(wildcardDi);
        for (int i = 0; i < subItems.size(); i++) {
            if (subItems.get(i).getDi().equalsIgnoreCase(concreteDi)) {
                return i;
            }
        }
        return -1;
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

        var descriptor = lookup(diHex);
        if (descriptor != null && bcdData.length != descriptor.getDataLength()) {
            throw new IllegalArgumentException("DI " + diHex + " expects " + descriptor.getDataLength()
                + " data bytes, got " + bcdData.length);
        }

        boolean negative = descriptor != null && descriptor.getFormat() == DataFormat.SIGNED_BCD &&
            (bcdData[bcdData.length - 1] & 0x80) != 0;
        byte[] digits = Arrays.copyOf(bcdData, bcdData.length);
        if (negative) {
            digits[digits.length - 1] &= 0x7F;
        }

        long rawValue = 0;
        for (int i = digits.length - 1; i >= 0; i--) {
            int high = (digits[i] >> 4) & 0x0F;
            int low = digits[i] & 0x0F;
            if (high > 9 || low > 9) {
                throw new IllegalArgumentException("DI " + diHex + " contains invalid BCD data");
            }
            rawValue = rawValue * 100 + high * 10 + low;
        }

        double value = descriptor == null || descriptor.getDecimalPlaces() == 0
            ? rawValue : rawValue / Math.pow(10.0, descriptor.getDecimalPlaces());
        return negative ? -value : value;
    }
}
