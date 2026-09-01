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
import org.apache.plc4x.java.api.types.PlcValueType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Dlt645TagTest {

    @Test
    void defaultsToDoublePrecisionAndRejectsUnknownType() {
        assertEquals(PlcValueType.LREAL, Dlt645Tag.of("00010000").getPlcValueType());
        assertThrows(PlcInvalidTagException.class, () -> Dlt645Tag.of("00010000:NOT_A_TYPE"));
        assertThrows(PlcInvalidTagException.class, () -> Dlt645Tag.of("00010000:BOOL"));
    }

    @Test
    void derivesTemporalAndRawTypesFromTheDiDescriptor() {
        assertEquals(PlcValueType.DATE_AND_TIME, Dlt645Tag.of("04000101").getPlcValueType());
        assertThrows(PlcInvalidTagException.class, () -> Dlt645Tag.of("04000101:REAL"));
    }

    @Test
    void unknownDiRequiresExplicitRawType() {
        assertThrows(PlcInvalidTagException.class, () -> Dlt645Tag.of("12345678"));
        assertEquals(PlcValueType.RAW_BYTE_ARRAY,
            Dlt645Tag.of("12345678:RAW_BYTE_ARRAY").getPlcValueType());
    }

    @Test
    void optionalMeterPrefixIsNormalizedAndRoundTrips() {
        Dlt645Tag tag = Dlt645Tag.of("12/00010000");
        assertEquals("000000000012", tag.getMeterAddress());
        assertEquals("00010000", tag.getDiHex());
        assertEquals("000000000012/00010000", tag.getAddressString());
        assertNotNull(tag.getMeterAddressBytes());
        assertEquals(6, tag.getMeterAddressBytes().length);
        assertNull(Dlt645Tag.of("00010000").getMeterAddress());
    }

    @Test
    void commandTagAcceptsMeterPrefix() {
        Dlt645CommandTag tag = Dlt645CommandTag.of("123456789012/cmd:freeze");
        assertEquals("123456789012", tag.getMeterAddress());
        assertEquals("123456789012/cmd:freeze", tag.getAddressString());
        assertTrue(Dlt645CommandTag.matches("12/cmd:read-address"));
        assertFalse(Dlt645CommandTag.matches("00010000"));
    }
}
