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
import org.apache.plc4x.java.api.types.PlcValueType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Cjt188TagTest {

    @Test
    void defaultsToDoublePrecisionAndRejectsUnknownType() {
        assertEquals(PlcValueType.LREAL, Cjt188Tag.of("901F").getPlcValueType());
        assertEquals(PlcValueType.REAL, Cjt188Tag.of("901F:REAL").getPlcValueType());
        assertThrows(PlcInvalidTagException.class, () -> Cjt188Tag.of("901F:NOT_A_TYPE"));
        assertThrows(PlcInvalidTagException.class, () -> Cjt188Tag.of("901F:BOOL"));
    }

    @Test
    void derivesTemporalAndRawTypesFromTheDiDescriptor() {
        assertEquals(PlcValueType.DATE_AND_TIME, Cjt188Tag.of("D120").getPlcValueType());
        assertEquals(PlcValueType.RAW_BYTE_ARRAY, Cjt188Tag.of("8102").getPlcValueType());
        assertThrows(PlcInvalidTagException.class, () -> Cjt188Tag.of("D120:REAL"));
    }

    @Test
    void unknownDiRequiresExplicitRawType() {
        assertThrows(PlcInvalidTagException.class, () -> Cjt188Tag.of("1234"));
        assertEquals(PlcValueType.RAW_BYTE_ARRAY,
            Cjt188Tag.of("1234:RAW_BYTE_ARRAY").getPlcValueType());
    }

    @Test
    void commandTagsMatchKnownKeywordsOnly() {
        assertTrue(Cjt188CommandTag.matches("cmd:read-address"));
        assertTrue(Cjt188CommandTag.matches("cmd:write-sync"));
        assertFalse(Cjt188CommandTag.matches("cmd:freeze"));
        var readAddr = Cjt188CommandTag.of("cmd:read-address");
        assertEquals(Cjt188CommandTag.CommandType.READ_ADDRESS, readAddr.getCommandType());
        assertEquals("cmd:read-address", readAddr.getAddressString());
        assertEquals(PlcValueType.STRING, readAddr.getPlcValueType());
        assertTrue(readAddr.getCommandType().isReadable());
        assertFalse(Cjt188CommandTag.of("cmd:write-address").getCommandType().isReadable());
        assertTrue(readAddr.toString().contains("read-address"));
        assertTrue(Cjt188Tag.of("901F").toString().contains("901F"));
        assertThrows(PlcInvalidTagException.class, () -> Cjt188CommandTag.of("cmd:nope"));
    }

    @Test
    void tagHandlerDispatchesCommandAndDi() {
        var handler = new Cjt188TagHandler();
        assertTrue(handler.parseTag("901F") instanceof Cjt188Tag);
        assertTrue(handler.parseTag("cmd:write-address") instanceof Cjt188CommandTag);
        assertThrows(PlcInvalidTagException.class, () -> handler.parseTag("not-a-tag"));
        assertThrows(UnsupportedOperationException.class, () -> handler.parseQuery("*"));
    }

    @Test
    void wildcardDiParsesAsStructUnlessRawIsRequested() {
        assertTrue(Cjt188Tag.of("90FF") instanceof Cjt188WildcardTag);
        assertEquals(PlcValueType.Struct, Cjt188Tag.of("90FF").getPlcValueType());
        assertEquals(PlcValueType.RAW_BYTE_ARRAY, Cjt188Tag.of("90FF:RAW_BYTE_ARRAY").getPlcValueType());
        assertFalse(Cjt188Tag.of("90FF:RAW_BYTE_ARRAY") instanceof Cjt188WildcardTag);
        var handler = new Cjt188TagHandler();
        assertTrue(handler.parseTag("90FF") instanceof Cjt188WildcardTag);
    }

    @Test
    void meterAddressMayBePrefixOrCurlyOptionsAndTagWinsOverPrefix() {
        Cjt188Tag prefix = Cjt188Tag.of("123456789012/901F");
        assertEquals("123456789012", prefix.getMeterAddress());
        assertEquals(PlcValueType.LREAL, prefix.getPlcValueType());
        assertTrue(prefix.getAddressString().startsWith("123456789012/901F"));

        Cjt188Tag fourteen = Cjt188Tag.of("20987654321098/901F");
        assertEquals("20987654321098", fourteen.getMeterAddress());

        Cjt188Tag curly = Cjt188Tag.of("901F{meter-address:123456789012,meter-type:10}");
        assertEquals("123456789012", curly.getMeterAddress());
        assertEquals("10", curly.getMeterType());

        Cjt188Tag override = Cjt188Tag.of("123456789012/901F{meter-address:999999999999}");
        assertEquals("999999999999", override.getMeterAddress());

        var handler = new Cjt188TagHandler();
        assertTrue(handler.parseTag("10123456789012/901F") instanceof Cjt188Tag);
        assertTrue(Cjt188CommandTag.of("cmd:write-sync{meter-address:123456789012,meter-type:10}")
            .getAddressString().contains("123456789012"));
    }

    @Test
    void wildcardTagKeepsMappings() {
        byte[] di = {(byte) 0x90, (byte) 0xFF};
        var mapping = new Cjt188WildcardTag.SubTagMapping("vol", 0, "901F", PlcValueType.LREAL);
        var tag = new Cjt188WildcardTag(di, List.of(mapping));
        assertEquals(PlcValueType.Struct, tag.getPlcValueType());
        assertEquals(1, tag.getMappings().size());
        assertEquals("901F", tag.getMappings().get(0).subItemDiHex());
        assertTrue(tag.toString().contains("90FF"));
    }
}
