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
package org.apache.plc4x.java.cjt188.readwrite.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CJ/T 188-2004 Data Identifier (DI) registry and semantic decoder.
 * <p>
 * DI is two bytes, displayed as {@code DI1 DI0} (4 hex digits). On the wire the
 * bytes are transmitted {@code DI0} first, then {@code DI1}.
 * <p>
 * 901F is the current accumulated quantity for every meter type: volume for
 * water/gas, heat energy for heat meters. Heat-specific DIs (power, temperatures,
 * operating time) are registered alongside the common settlement-day block.
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
        // Common accumulated quantity (Annex A/B/C). 4-byte BCD, 2 decimals.
        // Water/gas: m³. Heat: kWh (or GJ, meter-dependent).
        // ================================================================
        reg(map, "901F", "Current accumulated quantity", "", 4, 2);
        reg(map, "9010", "Settlement-day accumulated quantity", "", 4, 2);
        for (int i = 1; i <= 12; i++) {
            reg(map, String.format("90%02X", 0x10 + i),
                "Settlement-day -" + i + " accumulated quantity", "", 4, 2);
        }

        // ================================================================
        // Heat-meter instantaneous / totals (Annex B and common vendor DIs)
        // ================================================================
        reg(map, "9020", "Current heat power", "kW", 3, 2);
        reg(map, "90A0", "Instantaneous flow", "m3/h", 4, 2);
        reg(map, "80A0", "Accumulated flow", "m3", 4, 2);
        reg(map, "80AA", "Supply temperature", "\u2103", 2, 2);
        reg(map, "80AB", "Return temperature", "\u2103", 2, 2);
        reg(map, "80AC", "Accumulated operating time", "h", 3, 0);
        reg(map, "8110", "Supply temperature", "\u2103", 2, 2);
        reg(map, "8111", "Return temperature", "\u2103", 2, 2);
        reg(map, "8112", "Accumulated operating time", "h", 3, 0);

        // ================================================================
        // Status, remaining credit, valve, clock
        // ================================================================
        reg(map, "8102", "Status ST", "", 2, 0, DataFormat.RAW);
        reg(map, "8103", "Remaining amount", "currency", 4, 2);
        reg(map, "8104", "Remaining volume", "m3", 4, 2);
        reg(map, "A017", "Valve status / control", "", 1, 0, DataFormat.RAW);
        reg(map, "D120", "Real-time clock (YYMMDDhhmmss)", "", 6, 0, DataFormat.DATE_TIME);
        reg(map, "80B0", "Real-time clock (YYMMDDhhmmss)", "", 6, 0, DataFormat.DATE_TIME);

        REGISTRY = Collections.unmodifiableMap(map);
    }

    private static void reg(Map<String, DataItemDescriptor> map,
                            String di, String name, String unit,
                            int dataLength, int decimalPlaces) {
        reg(map, di, name, unit, dataLength, decimalPlaces, DataFormat.BCD);
    }

    private static void reg(Map<String, DataItemDescriptor> map,
                            String di, String name, String unit,
                            int dataLength, int decimalPlaces, DataFormat format) {
        map.put(di.toUpperCase(),
            new DataItemDescriptor(di, name, unit, dataLength, decimalPlaces, format));
    }

    public static DataItemDescriptor lookup(String diHex) {
        if (diHex == null) {
            return null;
        }
        return REGISTRY.get(diHex.toUpperCase());
    }

    public static DataItemDescriptor lookup(byte[] di) {
        if (di == null || di.length != 2) {
            return null;
        }
        return lookup(String.format("%02X%02X", di[0] & 0xFF, di[1] & 0xFF));
    }

    public static Map<String, DataItemDescriptor> getAll() {
        return REGISTRY;
    }

    public static boolean containsWildcard(String diHex) {
        if (diHex == null || diHex.length() != 4) {
            return false;
        }
        var upper = diHex.toUpperCase();
        return "FF".equals(upper.substring(0, 2)) || "FF".equals(upper.substring(2, 4));
    }

    public static boolean containsWildcard(byte[] di) {
        if (di == null || di.length != 2) {
            return false;
        }
        return (di[0] & 0xFF) == 0xFF || (di[1] & 0xFF) == 0xFF;
    }

    /**
     * Concrete sub-items matching a wildcard DI, ordered by the wildcard byte.
     * Example: {@code 90FF} matches 9010, 9011, ..., 901F, 9020, 90A0.
     */
    public static List<DataItemDescriptor> getSubItems(String wildcardDi) {
        if (wildcardDi == null || wildcardDi.length() != 4) {
            return Collections.emptyList();
        }
        var upper = wildcardDi.toUpperCase();
        boolean wild0 = "FF".equals(upper.substring(0, 2));
        boolean wild1 = "FF".equals(upper.substring(2, 4));
        byte fixed0 = wild0 ? 0 : (byte) Integer.parseInt(upper.substring(0, 2), 16);
        byte fixed1 = wild1 ? 0 : (byte) Integer.parseInt(upper.substring(2, 4), 16);

        var result = new ArrayList<DataItemDescriptor>();
        for (var entry : REGISTRY.entrySet()) {
            var di = entry.getKey();
            byte b0 = (byte) Integer.parseInt(di.substring(0, 2), 16);
            byte b1 = (byte) Integer.parseInt(di.substring(2, 4), 16);
            if (!wild0 && b0 != fixed0) {
                continue;
            }
            if (!wild1 && b1 != fixed1) {
                continue;
            }
            result.add(entry.getValue());
        }
        result.sort((a, b) -> a.getDi().compareToIgnoreCase(b.getDi()));
        return Collections.unmodifiableList(result);
    }

    public static String computeWildcardDi(List<String> diHexList) {
        if (diHexList == null || diHexList.size() < 2) {
            return null;
        }
        var first = diHexList.get(0).toUpperCase();
        byte first0 = (byte) Integer.parseInt(first.substring(0, 2), 16);
        byte first1 = (byte) Integer.parseInt(first.substring(2, 4), 16);
        boolean differ0 = false;
        boolean differ1 = false;
        for (int d = 1; d < diHexList.size(); d++) {
            var di = diHexList.get(d).toUpperCase();
            byte b0 = (byte) Integer.parseInt(di.substring(0, 2), 16);
            byte b1 = (byte) Integer.parseInt(di.substring(2, 4), 16);
            if (b0 != first0) {
                differ0 = true;
            }
            if (b1 != first1) {
                differ1 = true;
            }
        }
        if (differ0 == differ1) {
            return null;
        }
        return (differ0 ? "FF" : String.format("%02X", first0 & 0xFF))
            + (differ1 ? "FF" : String.format("%02X", first1 & 0xFF));
    }

    /**
     * Auto-merge is only safe when every matched sub-item has the same data length.
     * Settlement-day history {@code 9011}..{@code 901C} is the industrially useful block;
     * {@code 90FF} mixes 3-byte power with 4-byte totals and must not be auto-merged.
     */
    public static boolean isOptimizerSafeBlockDi(String wildcardDi) {
        if (wildcardDi == null || wildcardDi.length() != 4 || !containsWildcard(wildcardDi)) {
            return false;
        }
        var subItems = getSubItems(wildcardDi);
        if (subItems.size() < 2) {
            return false;
        }
        int length = subItems.get(0).getDataLength();
        DataFormat format = subItems.get(0).getFormat();
        for (var item : subItems) {
            if (item.getDataLength() != length || item.getFormat() != format) {
                return false;
            }
        }
        return true;
    }

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
     * Decode BCD (LSB first) using the DI's decimal-places configuration.
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
