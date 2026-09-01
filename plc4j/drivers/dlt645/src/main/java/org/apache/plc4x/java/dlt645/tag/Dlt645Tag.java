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
package org.apache.plc4x.java.dlt645.tag;

import org.apache.plc4x.java.api.exceptions.PlcInvalidTagException;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcValueType;
import org.apache.plc4x.java.dlt645.context.Dlt645DriverContext;
import org.apache.plc4x.java.dlt645.readwrite.utils.DataIdentifiers;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DL/T 645-2007 Tag representation.
 * <p>
 * Tag address format: {@code [meter-address/]DI3DI2DI1DI0[:dataType]}
 * <p>
 * Examples:
 * <ul>
 *   <li>{@code 00010000} — Total active energy, uses the connection default meter</li>
 *   <li>{@code 123456789012/00010000} — Same DI on a specific meter on a shared bus</li>
 *   <li>{@code 123456789012/02010100:REAL} — A-phase voltage, explicit REAL type</li>
 * </ul>
 * <p>
 * The 4-byte Data Identifier (DI) is defined by DL/T 645-2007 Annex A.
 * Meter address is the printed 1–12 digit BCD communication address.
 */
public class Dlt645Tag implements PlcTag {

    /**
     * Optional printed meter, then 8 hex digits for DI, optionally followed by :dataType.
     */
    public static final Pattern ADDRESS_PATTERN =
        Pattern.compile("^(?:(?<meter>\\d{1,12})/)?(?<di>[0-9A-Fa-f]{8})(:(?<dataType>[a-zA-Z_]+))?$");

    private final byte[] dataIdentifier;
    private final PlcValueType valueType;
    private final String meterAddress;
    private final byte[] meterAddressBytes;

    protected Dlt645Tag(byte[] dataIdentifier, PlcValueType valueType) {
        this(dataIdentifier, valueType, null);
    }

    protected Dlt645Tag(byte[] dataIdentifier, PlcValueType valueType, String meterAddress) {
        this.dataIdentifier = Arrays.copyOf(dataIdentifier, dataIdentifier.length);
        this.valueType = valueType;
        if (meterAddress == null || meterAddress.isEmpty()) {
            this.meterAddress = null;
            this.meterAddressBytes = null;
        } else {
            this.meterAddress = Dlt645DriverContext.formatPrintedAddress(meterAddress);
            this.meterAddressBytes = Dlt645DriverContext.parseMeterAddress(meterAddress);
        }
    }

    public static boolean matches(String tagAddress) {
        return tagAddress != null && ADDRESS_PATTERN.matcher(tagAddress).matches();
    }

    public static Dlt645Tag of(String tagAddress) {
        var matcher = ADDRESS_PATTERN.matcher(tagAddress);
        if (!matcher.matches()) {
            throw new PlcInvalidTagException(tagAddress, ADDRESS_PATTERN,
                "[meter-address/]{di_hex_8}[:dataType]");
        }

        var diStr = matcher.group("di");
        var di = new byte[4];
        for (int i = 0; i < 4; i++) {
            di[i] = (byte) Integer.parseInt(diStr.substring(i * 2, i * 2 + 2), 16);
        }

        var descriptor = DataIdentifiers.lookup(diStr);
        var dataTypeStr = matcher.group("dataType");
        PlcValueType valueType;
        if (dataTypeStr != null && !dataTypeStr.isEmpty()) {
            try {
                valueType = PlcValueType.valueOf(dataTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new PlcInvalidTagException("Unsupported DL/T 645 data type '" + dataTypeStr + "'");
            }
            validateExplicitType(diStr, descriptor, valueType);
        } else if (descriptor == null) {
            throw new PlcInvalidTagException(
                "Unknown DL/T 645 DI '" + diStr + "'; use :RAW_BYTE_ARRAY explicitly");
        } else {
            valueType = defaultType(descriptor.getFormat());
        }

        return new Dlt645Tag(di, valueType, meterGroup(matcher));
    }

    private static String meterGroup(Matcher matcher) {
        String meter = matcher.group("meter");
        return meter == null || meter.isEmpty() ? null : meter;
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
                "Unknown DL/T 645 DI '" + di + "'; only RAW_BYTE_ARRAY is supported");
        }
        PlcValueType nativeType = defaultType(descriptor.getFormat());
        if (nativeType == PlcValueType.LREAL && isSupportedNumericType(valueType)) {
            return;
        }
        if (valueType != nativeType) {
            throw new PlcInvalidTagException(
                "DL/T 645 DI '" + di + "' requires " + nativeType + " or RAW_BYTE_ARRAY");
        }
    }

    private static boolean isSupportedNumericType(PlcValueType valueType) {
        return switch (valueType) {
            case REAL, LREAL, SINT, USINT, INT, UINT, DINT, UDINT, LINT, STRING -> true;
            default -> false;
        };
    }

    /**
     * Get the 4-byte Data Identifier for the DL/T 645-2007 protocol.
     */
    public byte[] getDataIdentifier() {
        return Arrays.copyOf(dataIdentifier, dataIdentifier.length);
    }

    /** Displayed 8-hex-digit DI, independent of meter prefix or value type. */
    public String getDiHex() {
        var sb = new StringBuilder(8);
        for (byte b : dataIdentifier) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Printed 12-digit meter address when the tag names a specific meter; {@code null}
     * means the connection default should be used.
     */
    public String getMeterAddress() {
        return meterAddress;
    }

    /** Wire-order meter address bytes, or {@code null} when the tag has no meter. */
    public byte[] getMeterAddressBytes() {
        return meterAddressBytes == null ? null : Arrays.copyOf(meterAddressBytes, meterAddressBytes.length);
    }

    @Override
    public String getAddressString() {
        var sb = new StringBuilder();
        if (meterAddress != null) {
            sb.append(meterAddress).append('/');
        }
        sb.append(getDiHex());
        if (valueType != PlcValueType.LREAL) {
            sb.append(':').append(valueType.name());
        }
        return sb.toString();
    }

    @Override
    public PlcValueType getPlcValueType() {
        return valueType;
    }

    @Override
    public String toString() {
        return "Dlt645Tag{" +
            "di=" + getAddressString() +
            '}';
    }
}
