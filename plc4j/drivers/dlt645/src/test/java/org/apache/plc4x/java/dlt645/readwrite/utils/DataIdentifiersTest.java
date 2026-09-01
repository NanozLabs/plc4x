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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataIdentifiersTest {

    @Test
    void decodesSignedBcdSignBit() {
        assertEquals(-1.2345,
            DataIdentifiers.decodeValue("02030000", new byte[]{0x45, 0x23, (byte) 0x81}), 0.00001);
    }

    @Test
    void annexAInstantaneousDescriptorsHaveCorrectMeaningAndScale() {
        var averagePower = DataIdentifiers.lookup("02800003");
        assertEquals("One-minute total active average power", averagePower.getName());
        assertEquals(3, averagePower.getDataLength());
        assertEquals(4, averagePower.getDecimalPlaces());
        assertEquals(-1.2345,
            DataIdentifiers.decodeValue("02800003", new byte[]{0x45, 0x23, (byte) 0x81}), 0.00001);

        var externalBattery = DataIdentifiers.lookup("02800009");
        assertEquals("Meter-reading battery voltage", externalBattery.getName());
        assertEquals(2, externalBattery.getDataLength());
        assertEquals(2, externalBattery.getDecimalPlaces());
    }

    @Test
    void combinedReactiveEnergyIsUnsigned() {
        assertEquals(800000.0,
            DataIdentifiers.decodeValue("00030000", new byte[]{0, 0, 0, (byte) 0x80}));
    }

    @Test
    void rejectsInvalidBcdNibble() {
        assertThrows(IllegalArgumentException.class,
            () -> DataIdentifiers.decodeValue("02010100", new byte[]{0x00, (byte) 0xFA}));
    }

    @Test
    void errorResponseUsesSingleErrByte() {
        assertTrue(StaticHelper.describeError(new byte[]{0x04}).contains("Password error"));
        assertTrue(StaticHelper.describeError(new byte[]{0, 0, 0, 0, 4}).contains("invalid ERR data length"));
    }

    @Test
    void optimizerRejectsDi3AndDi2Wildcards() {
        assertTrue(DataIdentifiers.isOptimizerSafeBlockDi("0201FF00"));
        assertTrue(DataIdentifiers.isOptimizerSafeBlockDi("0001FF00"));
        assertTrue(DataIdentifiers.isOptimizerSafeBlockDi("000100FF"));
        assertFalse(DataIdentifiers.isOptimizerSafeBlockDi("028000FF"));
        assertFalse(DataIdentifiers.isOptimizerSafeBlockDi("040001FF"));
        assertFalse(DataIdentifiers.isOptimizerSafeBlockDi("FF010100"));
        assertFalse(DataIdentifiers.isOptimizerSafeBlockDi("00FF0000"));
        assertFalse(DataIdentifiers.isOptimizerSafeBlockDi("00010000"));
        assertEquals("FF010100",
            DataIdentifiers.computeWildcardDi(java.util.List.of("00010100", "02010100")));
        assertEquals("00FF0000",
            DataIdentifiers.computeWildcardDi(java.util.List.of("00010000", "00020000")));
    }
}
