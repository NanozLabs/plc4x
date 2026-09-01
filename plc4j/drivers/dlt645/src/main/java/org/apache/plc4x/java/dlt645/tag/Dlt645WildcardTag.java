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

import org.apache.plc4x.java.api.types.PlcValueType;

import java.util.Collections;
import java.util.List;

/**
 * DL/T 645-2007 Wildcard Tag representation.
 * <p>
 * Represents a wildcard DI (containing 0xFF bytes) used for block reads.
 * Carries mapping metadata so the optimizer can correlate wildcard response
 * values back to individual original tag names.
 * <p>
 * Example: wildcard DI "0201FF00" reads all phase voltages (A=01, B=02, C=03).
 * The response contains concatenated values in order, which are split using
 * DataIdentifiers registry.
 */
public class Dlt645WildcardTag extends Dlt645Tag {

    /**
     * Mapping from an original tag name to its position within the wildcard response.
     *
     * @param originalTagName the tag name in the original PlcReadRequest
     * @param subItemIndex    0-based index within the wildcard sub-items list
     * @param subItemDiHex    the concrete DI hex string (e.g. "02010200")
     * @param requestedType   the original tag's {@link PlcValueType}, used when decoding
     */
    public record SubTagMapping(String originalTagName, int subItemIndex, String subItemDiHex,
                                PlcValueType requestedType) {
    }

    private final List<SubTagMapping> mappings;

    /**
     * @param wildcardDi 4-byte DI with 0xFF wildcard byte(s)
     * @param mappings   ordered mapping from original tag names to sub-item positions
     */
    public Dlt645WildcardTag(byte[] wildcardDi, List<SubTagMapping> mappings) {
        this(wildcardDi, mappings, null);
    }

    public Dlt645WildcardTag(byte[] wildcardDi, List<SubTagMapping> mappings, String meterAddress) {
        super(wildcardDi, PlcValueType.Struct, meterAddress);
        this.mappings = Collections.unmodifiableList(mappings);
    }

    public List<SubTagMapping> getMappings() {
        return mappings;
    }

    @Override
    public String toString() {
        return "Dlt645WildcardTag{" +
            "di=" + getAddressString() +
            ", mappings=" + mappings.size() +
            '}';
    }
}
