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

import org.apache.plc4x.java.cjt188.readwrite.ControlCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataIdentifiersTest {

    @Test
    void currentQuantityIsFourByteBcdWithTwoDecimals() {
        var current = DataIdentifiers.lookup("901F");
        assertEquals("Current accumulated quantity", current.getName());
        assertEquals(4, current.getDataLength());
        assertEquals(2, current.getDecimalPlaces());
        assertEquals(1234.56,
            DataIdentifiers.decodeValue("901F", new byte[]{0x56, 0x34, 0x12, 0x00}), 0.00001);
    }

    @Test
    void heatPowerAndTemperaturesAreRegistered() {
        assertEquals(3, DataIdentifiers.lookup("9020").getDataLength());
        assertEquals(2, DataIdentifiers.lookup("80AA").getDecimalPlaces());
        assertEquals(DataIdentifiers.DataFormat.DATE_TIME, DataIdentifiers.lookup("D120").getFormat());
        assertEquals(DataIdentifiers.DataFormat.RAW, DataIdentifiers.lookup("A017").getFormat());
    }

    @Test
    void rejectsInvalidBcdNibble() {
        assertThrows(IllegalArgumentException.class,
            () -> DataIdentifiers.decodeValue("901F", new byte[]{0x00, 0x00, 0x00, (byte) 0xFA}));
    }

    @Test
    void wildcardHelpers() {
        assertTrue(DataIdentifiers.containsWildcard("90FF"));
        assertFalse(DataIdentifiers.containsWildcard("901F"));
        assertEquals("90FF", DataIdentifiers.computeWildcardDi(List.of("9010", "901F")));
        assertTrue(DataIdentifiers.getSubItems("90FF").size() >= 12);
        assertFalse(DataIdentifiers.isOptimizerSafeBlockDi("90FF"));
        assertFalse(DataIdentifiers.isOptimizerSafeBlockDi("901F"));
    }

    @Test
    void errorResponseUsesErrByte() {
        assertTrue(StaticHelper.describeError(new byte[]{0x04}).contains("Password error"));
        assertTrue(StaticHelper.describeError(new byte[]{0x01, 0x02}).contains("No requested data"));
        assertTrue(StaticHelper.describeError(new byte[0]).contains("invalid ERR"));
    }

    @Test
    void checksumAndFrameHelpers() {
        byte[] address = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77};
        byte[] data = {0x00, 0x00, 0x00};
        short cs = StaticHelper.calcCs(address, ControlCode.READ_DATA, (short) 3, data);
        assertEquals(0x49, cs & 0xFF);
        byte[] buffer = new byte[]{0x68, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x68, 0x01, 0x00, 0x00, 0x16};
        assertEquals(0, StaticHelper.findFrameStart(buffer));
        assertEquals(13, StaticHelper.estimateFrameLength(buffer, 0));
        assertEquals(0x32, StaticHelper.encode33((byte) 0xFF) & 0xFF);
        assertEquals(0xFF, StaticHelper.decode33((byte) 0x32) & 0xFF);
        assertArrayEquals(new byte[]{0x33}, StaticHelper.encode33(new byte[]{0x00}));
        assertArrayEquals(new byte[]{0x00}, StaticHelper.decode33(new byte[]{0x33}));
        assertEquals(-1, StaticHelper.findFrameStart(new byte[]{0x00, 0x01}));
        assertEquals(-1, StaticHelper.estimateFrameLength(new byte[]{0x68}, 0));
    }

    @Test
    void lookupByBytesAndSubItemIndex() {
        assertEquals("901F", DataIdentifiers.lookup(new byte[]{(byte) 0x90, 0x1F}).getDi());
        assertTrue(DataIdentifiers.containsWildcard(new byte[]{(byte) 0x90, (byte) 0xFF}));
        assertEquals(0, DataIdentifiers.getSubItemIndex("90FF", "9010"));
        var current = DataIdentifiers.lookup("901F");
        assertTrue(current.toString().contains("901F"));
        assertEquals("", current.getUnit());
        assertEquals(DataIdentifiers.DataFormat.BCD, current.getFormat());
        assertFalse(DataIdentifiers.getAll().isEmpty());
    }
}
