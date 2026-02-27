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

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DL/T 645-2007 Tag representation.
 * <p>
 * Tag address format: {@code DI0DI1DI2DI3[:dataType]}
 * <p>
 * Examples:
 * <ul>
 *   <li>{@code 00010000} - Total active energy (default REAL type)</li>
 *   <li>{@code 02010100:REAL} - A-phase voltage, explicit REAL type</li>
 *   <li>{@code 04000101} - Date and time</li>
 * </ul>
 * <p>
 * The 4-byte Data Identifier (DI) is defined by DL/T 645-2007 Annex A.
 */
public class Dlt645Tag implements PlcTag {

    /**
     * Pattern: 8 hex digits for DI, optionally followed by :dataType.
     */
    public static final Pattern ADDRESS_PATTERN =
        Pattern.compile("^(?<di>[0-9A-Fa-f]{8})(:(?<dataType>[a-zA-Z_]+))?$");

    private final byte[] dataIdentifier;
    private final PlcValueType valueType;

    protected Dlt645Tag(byte[] dataIdentifier, PlcValueType valueType) {
        this.dataIdentifier = Arrays.copyOf(dataIdentifier, dataIdentifier.length);
        this.valueType = valueType;
    }

    public static boolean matches(String tagAddress) {
        return ADDRESS_PATTERN.matcher(tagAddress).matches();
    }

    public static Dlt645Tag of(String tagAddress) {
        var matcher = ADDRESS_PATTERN.matcher(tagAddress);
        if (!matcher.matches()) {
            throw new PlcInvalidTagException(tagAddress, ADDRESS_PATTERN,
                "{di_hex_8}[:dataType]");
        }

        var diStr = matcher.group("di");
        var di = new byte[4];
        for (int i = 0; i < 4; i++) {
            di[i] = (byte) Integer.parseInt(diStr.substring(i * 2, i * 2 + 2), 16);
        }

        var dataTypeStr = matcher.group("dataType");
        PlcValueType valueType;
        if (dataTypeStr != null && !dataTypeStr.isEmpty()) {
            try {
                valueType = PlcValueType.valueOf(dataTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                valueType = PlcValueType.REAL;
            }
        } else {
            valueType = PlcValueType.REAL;
        }

        return new Dlt645Tag(di, valueType);
    }

    /**
     * Get the 4-byte Data Identifier for the DL/T 645-2007 protocol.
     */
    public byte[] getDataIdentifier() {
        return Arrays.copyOf(dataIdentifier, dataIdentifier.length);
    }

    @Override
    public String getAddressString() {
        var sb = new StringBuilder();
        for (byte b : dataIdentifier) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        if (valueType != PlcValueType.REAL) {
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
