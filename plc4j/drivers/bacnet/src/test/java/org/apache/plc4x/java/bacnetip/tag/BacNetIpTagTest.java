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
package org.apache.plc4x.java.bacnetip.tag;

import org.apache.plc4x.java.api.exceptions.PlcInvalidTagException;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetObjectType;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetPropertyIdentifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacNetIpTagTest {

    @Test
    void parsesNamedObjectAndProperty() {
        BacNetIpTag tag = BacNetIpTag.of("ANALOG_INPUT,1/PRESENT_VALUE");
        assertEquals(BACnetObjectType.ANALOG_INPUT.getValue(), tag.getObjectType());
        assertEquals(1L, tag.getObjectInstance());
        assertEquals((int) BACnetPropertyIdentifier.PRESENT_VALUE.getValue(), tag.firstProperty().getPropertyIdentifier());
        assertEquals("ANALOG_INPUT,1/PRESENT_VALUE", tag.getAddressString());
    }

    @Test
    void parsesNumericObjectAndProperty() {
        BacNetIpTag tag = BacNetIpTag.of("0,17/85");
        assertEquals(0, tag.getObjectType());
        assertEquals(17L, tag.getObjectInstance());
        assertEquals(85, tag.firstProperty().getPropertyIdentifier());
        assertEquals("ANALOG_INPUT,17/PRESENT_VALUE", tag.getAddressString());
    }

    @Test
    void parsesArrayIndexAndWritePriority() {
        BacNetIpTag tag = BacNetIpTag.of("ANALOG_VALUE,10/PRESENT_VALUE[2]{8}");
        assertEquals(2, tag.firstProperty().getArrayIndex());
        assertEquals(8, tag.firstProperty().getWritePriority());
        assertEquals("ANALOG_VALUE,10/PRESENT_VALUE[2]{8}", tag.getAddressString());
    }

    @Test
    void parsesMultipleProperties() {
        BacNetIpTag tag = BacNetIpTag.of("ANALOG_INPUT,1/PRESENT_VALUE&STATUS_FLAGS");
        assertEquals(2, tag.getProperties().size());
        assertEquals((int) BACnetPropertyIdentifier.STATUS_FLAGS.getValue(), tag.getProperties().get(1).getPropertyIdentifier());
        assertTrue(tag.getAddressString().contains("&"));
    }

    @Test
    void rejectsUnknownObjectType() {
        assertThrows(PlcInvalidTagException.class, () -> BacNetIpTag.of("NOT_A_TYPE,1/PRESENT_VALUE"));
    }

    @Test
    void rejectsPriorityOutOfRange() {
        assertThrows(PlcInvalidTagException.class, () -> BacNetIpTag.of("ANALOG_VALUE,1/PRESENT_VALUE{17}"));
    }
}
