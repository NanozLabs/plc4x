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

import org.apache.plc4x.java.api.types.PlcValueType;

import java.util.Collections;
import java.util.List;

/**
 * CJ/T 188-2004 Wildcard Tag. Represents a DI containing 0xFF used for block reads.
 */
public class Cjt188WildcardTag extends Cjt188Tag {

    public record SubTagMapping(String originalTagName, int subItemIndex, String subItemDiHex,
                                PlcValueType requestedType) {
    }

    private final List<SubTagMapping> mappings;

    public Cjt188WildcardTag(byte[] wildcardDi, List<SubTagMapping> mappings) {
        this(wildcardDi, mappings, null, null);
    }

    public Cjt188WildcardTag(byte[] wildcardDi, List<SubTagMapping> mappings,
                             String meterAddress, String meterType) {
        super(wildcardDi, PlcValueType.Struct, meterAddress, meterType);
        this.mappings = Collections.unmodifiableList(mappings);
    }

    public List<SubTagMapping> getMappings() {
        return mappings;
    }

    @Override
    public String toString() {
        return "Cjt188WildcardTag{" +
            "di=" + getAddressString() +
            ", mappings=" + mappings.size() +
            '}';
    }
}
