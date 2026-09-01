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
package org.apache.plc4x.java.cjt188.tag;

import org.apache.plc4x.java.api.exceptions.PlcInvalidTagException;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcValueType;
import org.apache.plc4x.java.cjt188.readwrite.utils.DataIdentifiers;
import org.apache.plc4x.java.spi.drivers.tags.TagConfigParser;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CJ/T 188-2004 Tag representation.
 * <p>
 * Tag address format:
 * {@code [meter-address/]&lt;DI1DI0&gt;[:dataType][{meter-address:...,meter-type:...}]}
 * <p>
 * The 2-byte DI is in displayed {@code DI1 DI0} order. Meter identity (A0 + 12-digit
 * BCD) may be omitted when the connection URL supplies a default; a tag-level
 * address always wins. That lets one serial connection poll several meters on
 * the same RS-485 bus.
 * <p>
 * Examples:
 * <ul>
 *   <li>{@code 901F} — current accumulated quantity, connection default meter</li>
 *   <li>{@code 123456789012/901F} — same DI on a specific 12-digit meter</li>
 *   <li>{@code 10123456789012/901F} — 14 hex digits including meter type {@code 10}</li>
 *   <li>{@code 901F{meter-address:123456789012,meter-type:20}}</li>
 * </ul>
 */
public class Cjt188Tag implements PlcTag {

    public static final Pattern ADDRESS_PATTERN = Pattern.compile(
        "^(?:(?<addr>[0-9A-Fa-f]{12}|[0-9A-Fa-f]{14})/)?"
            + "(?<di>[0-9A-Fa-f]{4})"
            + "(:(?<dataType>[a-zA-Z_]+))?"
            + "(?:\\{.*})?$");

    private final byte[] dataIdentifier;
    private final PlcValueType valueType;
    private final String meterAddress;
    private final String meterType;

    protected Cjt188Tag(byte[] dataIdentifier, PlcValueType valueType) {
        this(dataIdentifier, valueType, null, null);
    }

    protected Cjt188Tag(byte[] dataIdentifier, PlcValueType valueType,
                        String meterAddress, String meterType) {
        this.dataIdentifier = Arrays.copyOf(dataIdentifier, dataIdentifier.length);
        this.valueType = valueType;
        this.meterAddress = blankToNull(meterAddress);
        this.meterType = blankToNull(meterType);
    }

    public static boolean matches(String tagAddress) {
        return tagAddress != null && ADDRESS_PATTERN.matcher(tagAddress).matches();
    }

    public static Cjt188Tag of(String tagAddress) {
        var matcher = ADDRESS_PATTERN.matcher(tagAddress);
        if (!matcher.matches()) {
            throw new PlcInvalidTagException(tagAddress, ADDRESS_PATTERN,
                "[meter-address/]{di_hex_4}[:dataType][{meter-address:...,meter-type:...}]");
        }

        var diStr = matcher.group("di");
        var di = new byte[2];
        di[0] = (byte) Integer.parseInt(diStr.substring(0, 2), 16);
        di[1] = (byte) Integer.parseInt(diStr.substring(2, 4), 16);

        Map<String, String> config = TagConfigParser.parse(tagAddress);
        String meterAddress = firstNonBlank(config.get("meter-address"), matcher.group("addr"));
        String meterType = blankToNull(config.get("meter-type"));

        var descriptor = DataIdentifiers.lookup(diStr);
        var dataTypeStr = matcher.group("dataType");
        PlcValueType valueType;
        if (dataTypeStr != null && !dataTypeStr.isEmpty()) {
            try {
                valueType = PlcValueType.valueOf(dataTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new PlcInvalidTagException("Unsupported CJ/T 188 data type '" + dataTypeStr + "'");
            }
            if (DataIdentifiers.containsWildcard(diStr) && valueType == PlcValueType.RAW_BYTE_ARRAY) {
                return new Cjt188Tag(di, valueType, meterAddress, meterType);
            }
            if (DataIdentifiers.containsWildcard(diStr) && valueType == PlcValueType.Struct) {
                return new Cjt188WildcardTag(di, List.of(), meterAddress, meterType);
            }
            validateExplicitType(diStr, descriptor, valueType);
        } else if (DataIdentifiers.containsWildcard(diStr)) {
            return new Cjt188WildcardTag(di, List.of(), meterAddress, meterType);
        } else if (descriptor == null) {
            throw new PlcInvalidTagException(
                "Unknown CJ/T 188 DI '" + diStr + "'; use :RAW_BYTE_ARRAY explicitly");
        } else {
            valueType = defaultType(descriptor.getFormat());
        }

        return new Cjt188Tag(di, valueType, meterAddress, meterType);
    }

    private static PlcValueType defaultType(DataIdentifiers.DataFormat format) {
        return switch (format) {
            case BCD, SIGNED_BCD -> PlcValueType.LREAL;
            case DATE_TIME -> PlcValueType.DATE_AND_TIME;
            case DATE -> PlcValueType.DATE;
            case TIME -> PlcValueType.TIME_OF_DAY;
            case RAW -> PlcValueType.RAW_BYTE_ARRAY;
        };
    }

    private static void validateExplicitType(String di, DataIdentifiers.DataItemDescriptor descriptor,
                                             PlcValueType valueType) {
        if (valueType == PlcValueType.RAW_BYTE_ARRAY) {
            return;
        }
        if (descriptor == null) {
            throw new PlcInvalidTagException(
                "Unknown CJ/T 188 DI '" + di + "'; only RAW_BYTE_ARRAY is supported");
        }
        PlcValueType nativeType = defaultType(descriptor.getFormat());
        if (nativeType == PlcValueType.LREAL && isSupportedNumericType(valueType)) {
            return;
        }
        if (valueType != nativeType) {
            throw new PlcInvalidTagException(
                "CJ/T 188 DI '" + di + "' requires " + nativeType + " or RAW_BYTE_ARRAY");
        }
    }

    private static boolean isSupportedNumericType(PlcValueType valueType) {
        return switch (valueType) {
            case REAL, LREAL, SINT, USINT, INT, UINT, DINT, UDINT, LINT, STRING -> true;
            default -> false;
        };
    }

    /**
     * Displayed-order 2-byte Data Identifier (DI1, DI0).
     */
    public byte[] getDataIdentifier() {
        return Arrays.copyOf(dataIdentifier, dataIdentifier.length);
    }

    /**
     * Printed meter address from the tag, or {@code null} to use the connection default.
     */
    public String getMeterAddress() {
        return meterAddress;
    }

    /**
     * Meter-type A0 from the tag, or {@code null} to use the connection default.
     */
    public String getMeterType() {
        return meterType;
    }

    @Override
    public String getAddressString() {
        var sb = new StringBuilder();
        if (meterAddress != null) {
            sb.append(meterAddress).append('/');
        }
        for (byte b : dataIdentifier) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        if (valueType != PlcValueType.LREAL) {
            sb.append(':').append(valueType.name());
        }
        if (meterType != null && (meterAddress == null || meterAddress.length() != 14)) {
            sb.append("{meter-type:").append(meterType).append('}');
        }
        return sb.toString();
    }

    @Override
    public PlcValueType getPlcValueType() {
        return valueType;
    }

    @Override
    public String toString() {
        return "Cjt188Tag{" +
            "di=" + getAddressString() +
            '}';
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
