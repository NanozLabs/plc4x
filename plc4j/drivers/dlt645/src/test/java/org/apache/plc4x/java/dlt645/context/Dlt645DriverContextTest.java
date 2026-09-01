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
package org.apache.plc4x.java.dlt645.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Dlt645DriverContextTest {

    @Test
    void parsesPrintedAddressInLowByteFirstWireOrder() {
        assertArrayEquals(new byte[]{0x12, (byte) 0x90, 0x78, 0x56, 0x34, 0x12},
            Dlt645DriverContext.parseMeterAddress("123456789012"));
    }

    @Test
    void rejectsNonBcdAndOversizedAddresses() {
        assertThrows(IllegalArgumentException.class,
            () -> Dlt645DriverContext.parseMeterAddress("12345678901A"));
        assertThrows(IllegalArgumentException.class,
            () -> Dlt645DriverContext.parseMeterAddress("1234567890123"));
    }

    @Test
    void rejectsMissingAddressInsteadOfDefaultingToBroadcast() {
        assertThrows(IllegalArgumentException.class, () -> Dlt645DriverContext.parseMeterAddress(null));
        assertThrows(IllegalArgumentException.class, () -> Dlt645DriverContext.parseMeterAddress(""));
    }

    @Test
    void padsPrintedAddressToTwelveDigits() {
        assertEquals("000000000012", Dlt645DriverContext.formatPrintedAddress("12"));
        assertEquals("123456789012", Dlt645DriverContext.formatPrintedAddress("123456789012"));
    }

    @Test
    void parsesExplicitBroadcastAddress() {
        assertArrayEquals(Dlt645DriverContext.BROADCAST_ADDRESS,
            Dlt645DriverContext.parseMeterAddress("999999999999"));
        assertTrue(Dlt645DriverContext.isBroadcastAddress(Dlt645DriverContext.BROADCAST_ADDRESS));
        assertFalse(Dlt645DriverContext.isBroadcastAddress(new byte[]{0x12, (byte) 0x90, 0x78, 0x56, 0x34, 0x12}));
    }
}
