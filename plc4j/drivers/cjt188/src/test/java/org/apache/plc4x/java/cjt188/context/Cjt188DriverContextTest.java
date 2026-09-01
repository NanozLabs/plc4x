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
package org.apache.plc4x.java.cjt188.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Cjt188DriverContextTest {

    @Test
    void parsesPrintedAddressInLowByteFirstWireOrder() {
        assertArrayEquals(new byte[]{0x10, 0x12, (byte) 0x90, 0x78, 0x56, 0x34, 0x12},
            Cjt188DriverContext.parseMeterAddress("123456789012", "10"));
    }

    @Test
    void parsesFourteenDigitAddressIncludingType() {
        assertArrayEquals(new byte[]{0x20, 0x12, (byte) 0x90, 0x78, 0x56, 0x34, 0x12},
            Cjt188DriverContext.parseMeterAddress("20123456789012", "10"));
    }

    @Test
    void formatsWireAddressBackToPrintedForm() {
        byte[] wire = Cjt188DriverContext.parseMeterAddress("123456789012", "10");
        assertEquals("10123456789012", Cjt188DriverContext.formatAddress(wire));
    }

    @Test
    void rejectsNonBcdAndOversizedAddresses() {
        assertThrows(IllegalArgumentException.class,
            () -> Cjt188DriverContext.parseMeterAddress("12345678901A", "10"));
        assertThrows(IllegalArgumentException.class,
            () -> Cjt188DriverContext.parseMeterAddress("1234567890123", "10"));
        assertThrows(IllegalArgumentException.class,
            () -> Cjt188DriverContext.parseMeterAddress(null, "10"));
        assertThrows(IllegalArgumentException.class,
            () -> Cjt188DriverContext.parseMeterType("1"));
    }

    @Test
    void identifiesBroadcastAndWildcard() {
        assertTrue(Cjt188DriverContext.isBroadcastAddress(Cjt188DriverContext.BROADCAST_ADDRESS));
        assertTrue(Cjt188DriverContext.isWildcardAddress(Cjt188DriverContext.WILDCARD_ADDRESS));
        assertFalse(Cjt188DriverContext.isBroadcastAddress(
            Cjt188DriverContext.parseMeterAddress("123456789012", "10")));
    }
}
