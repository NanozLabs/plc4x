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
 * software distributed under this work is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.plc4x.java.dlt645.optimizer;

import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.dlt645.tag.Dlt645Tag;
import org.apache.plc4x.java.dlt645.tag.Dlt645WildcardTag;
import org.apache.plc4x.java.spi.drivers.functions.PlcReader;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcReadRequest;
import org.apache.plc4x.java.spi.drivers.messages.items.DefaultPlcTagItem;
import org.apache.plc4x.java.spi.drivers.messages.items.PlcTagItem;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class Dlt645BlockOptimizerTest {

    private final Dlt645BlockOptimizer optimizer = new Dlt645BlockOptimizer();
    private final PlcReader reader = mock(PlcReader.class);

    @Test
    void mergesPhaseVoltagesIntoDi1Wildcard() {
        List<PlcReadRequest> subRequests = optimizer.processReadRequest(
            request("a", "02010100", "b", "02010200", "c", "02010300"));

        assertEquals(1, subRequests.size());
        PlcTag tag = subRequests.get(0).getTag(subRequests.get(0).getTagNames().iterator().next());
        assertInstanceOf(Dlt645WildcardTag.class, tag);
        assertEquals("0201FF00", ((Dlt645Tag) tag).getDiHex());
    }

    @Test
    void mergesRatesOfOneEnergyIntoDi1Wildcard() {
        List<PlcReadRequest> subRequests = optimizer.processReadRequest(
            request("total", "00010000", "r1", "00010100", "r2", "00010200"));

        assertEquals(1, subRequests.size());
        PlcTag tag = subRequests.get(0).getTag(subRequests.get(0).getTagNames().iterator().next());
        assertInstanceOf(Dlt645WildcardTag.class, tag);
        assertEquals("0001FF00", ((Dlt645Tag) tag).getDiHex());
    }

    @Test
    void doesNotMergeEnergyWithVoltageAcrossDi3() {
        List<PlcReadRequest> subRequests = optimizer.processReadRequest(
            request("energy", "00010100", "voltage", "02010100"));

        assertEquals(2, subRequests.size());
        assertInstanceOf(Dlt645Tag.class, subRequests.get(0).getTag("energy"));
        assertInstanceOf(Dlt645Tag.class, subRequests.get(1).getTag("voltage"));
    }

    @Test
    void doesNotMergeDifferentEnergyTypesAcrossDi2() {
        List<PlcReadRequest> subRequests = optimizer.processReadRequest(
            request("positive", "00010000", "negative", "00020000"));

        assertEquals(2, subRequests.size());
        assertInstanceOf(Dlt645Tag.class, subRequests.get(0).getTag("positive"));
        assertInstanceOf(Dlt645Tag.class, subRequests.get(1).getTag("negative"));
    }

    @Test
    void doesNotMergeMixedInstantaneousDi0() {
        List<PlcReadRequest> subRequests = optimizer.processReadRequest(
            request("avg", "02800003", "demand", "02800004"));

        assertEquals(2, subRequests.size());
        assertInstanceOf(Dlt645Tag.class, subRequests.get(0).getTag("avg"));
        assertInstanceOf(Dlt645Tag.class, subRequests.get(1).getTag("demand"));
    }

    @Test
    void doesNotMergeDateTimeWithDate() {
        List<PlcReadRequest> subRequests = optimizer.processReadRequest(
            request("dateTime", "04000101", "date", "04000102"));

        assertEquals(2, subRequests.size());
        assertInstanceOf(Dlt645Tag.class, subRequests.get(0).getTag("dateTime"));
        assertInstanceOf(Dlt645Tag.class, subRequests.get(1).getTag("date"));
    }

    @Test
    void doesNotMergeSameDiOnDifferentMeters() {
        List<PlcReadRequest> subRequests = optimizer.processReadRequest(
            request("a", "111111111111/02010100", "b", "222222222222/02010200"));

        assertEquals(2, subRequests.size());
        assertInstanceOf(Dlt645Tag.class, subRequests.get(0).getTag("a"));
        assertInstanceOf(Dlt645Tag.class, subRequests.get(1).getTag("b"));
    }

    @Test
    void mergesPhaseVoltagesOnTheSameMeter() {
        List<PlcReadRequest> subRequests = optimizer.processReadRequest(
            request("a", "123456789012/02010100", "b", "123456789012/02010200",
                "c", "123456789012/02010300"));

        assertEquals(1, subRequests.size());
        PlcTag tag = subRequests.get(0).getTag(subRequests.get(0).getTagNames().iterator().next());
        assertInstanceOf(Dlt645WildcardTag.class, tag);
        assertEquals("123456789012", ((Dlt645Tag) tag).getMeterAddress());
        assertEquals("0201FF00", ((Dlt645Tag) tag).getDiHex());
    }

    private PlcReadRequest request(String... nameAndDi) {
        var tags = new LinkedHashMap<String, PlcTagItem<PlcTag>>();
        for (int i = 0; i < nameAndDi.length; i += 2) {
            tags.put(nameAndDi[i], new DefaultPlcTagItem<>(Dlt645Tag.of(nameAndDi[i + 1])));
        }
        return new DefaultPlcReadRequest(reader, tags);
    }
}
