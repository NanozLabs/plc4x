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
package org.apache.plc4x.java.dlt645.optimizer;

import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.dlt645.readwrite.utils.DataIdentifiers;
import org.apache.plc4x.java.dlt645.tag.Dlt645Tag;
import org.apache.plc4x.java.dlt645.tag.Dlt645WildcardTag;
import org.apache.plc4x.java.dlt645.tag.Dlt645WildcardTag.SubTagMapping;
import org.apache.plc4x.java.spi.drivers.functions.PlcWriter;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcReadRequest;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcReadResponse;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcWriteRequest;
import org.apache.plc4x.java.spi.drivers.messages.items.DefaultPlcResponseItem;
import org.apache.plc4x.java.spi.drivers.messages.items.DefaultPlcTagItem;
import org.apache.plc4x.java.spi.drivers.messages.items.DefaultPlcTagValueItem;
import org.apache.plc4x.java.spi.drivers.messages.items.PlcResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * DL/T 645-2007 Block Read Optimizer.
 * <p>
 * Merges consecutive data identifier (DI) tag reads into wildcard (0xFF) block reads
 * per DL/T 645-2007 specification. Tags sharing the same DI prefix with only one byte
 * position varying are combined into a single wildcard request, but only when the
 * resulting DI is an optimizer-safe block ({@link DataIdentifiers#isOptimizerSafeBlockDi}):
 * DI3 and DI2 must not be FF.
 * <p>
 * Example: reading A-phase voltage (02010100), B-phase voltage (02010200),
 * C-phase voltage (02010300) becomes one wildcard read of 0201FF00.
 * <p>
 * Tags that cannot form a wildcard group (singleton or mixed-prefix) fall back
 * to individual single-tag requests.
 * <p>
 * Write requests always use single-tag splitting (DL/T 645 does not support wildcard writes).
 */
public class Dlt645BlockOptimizer {

    private static final Logger logger = LoggerFactory.getLogger(Dlt645BlockOptimizer.class);

    /** Minimum group size to trigger wildcard merging (2+ tags needed) */
    private static final int MIN_WILDCARD_GROUP_SIZE = 2;

    /**
     * Split a multi-tag read request into a list of sub-requests (wildcard blocks + single tags).
     */
    public List<PlcReadRequest> processReadRequest(PlcReadRequest readRequest) {
        if (readRequest.getNumberOfTags() <= 1) {
            return Collections.singletonList(readRequest);
        }

        var defaultRequest = (DefaultPlcReadRequest) readRequest;

        // Separate DLT645 tags from non-DLT645 tags (e.g., command tags)
        var dlt645Tags = new LinkedHashMap<String, Dlt645Tag>();
        var otherTags = new LinkedHashMap<String, PlcTag>();

        for (String tagName : readRequest.getTagNames()) {
            PlcTag tag = readRequest.getTag(tagName);
            if (tag instanceof Dlt645Tag && !(tag instanceof Dlt645WildcardTag)) {
                dlt645Tags.put(tagName, (Dlt645Tag) tag);
            } else {
                otherTags.put(tagName, tag);
            }
        }

        var subRequests = new ArrayList<PlcReadRequest>();

        // Group DLT645 tags by meter, then by potential wildcard prefix
        var groups = groupByWildcardPrefix(dlt645Tags);

        for (var group : groups) {
            if (group.size() >= MIN_WILDCARD_GROUP_SIZE) {
                // Attempt wildcard merge
                var wildcardRequest = buildWildcardRequest(defaultRequest, group);
                if (wildcardRequest != null) {
                    subRequests.add(wildcardRequest);
                    continue;
                }
            }
            // Fallback: individual single-tag requests
            for (var entry : group.entrySet()) {
                subRequests.add(new DefaultPlcReadRequest(
                    defaultRequest.getReader(),
                    new LinkedHashMap<>(Collections.singletonMap(
                        entry.getKey(), new DefaultPlcTagItem<>(entry.getValue())))));
            }
        }

        // Non-DLT645 tags: individual requests
        for (var entry : otherTags.entrySet()) {
            subRequests.add(new DefaultPlcReadRequest(
                defaultRequest.getReader(),
                new LinkedHashMap<>(Collections.singletonMap(
                    entry.getKey(), new DefaultPlcTagItem<>(entry.getValue())))));
        }

        return subRequests;
    }

    /**
     * Merge per-sub-request responses back into the original request's response.
     */
    public PlcReadResponse processReadResponses(PlcReadRequest readRequest,
                                                Map<PlcReadRequest, SubResponse<PlcReadResponse>> readResponses) {
        Map<String, PlcResponseItem<PlcValue>> resultTags = new HashMap<>();

        for (var entry : readResponses.entrySet()) {
            PlcReadRequest subRequest = entry.getKey();
            SubResponse<PlcReadResponse> subResp = entry.getValue();

            for (String tagName : subRequest.getTagNames()) {
                PlcTag tag = subRequest.getTag(tagName);

                if (tag instanceof Dlt645WildcardTag wildcardTag && subResp.isSuccess()) {
                    // Extract individual values from the PlcStruct response
                    PlcReadResponse subReadResponse = subResp.getResponse();
                    PlcResponseCode code = subReadResponse.getResponseCode(tagName);

                    if (code == PlcResponseCode.OK) {
                        PlcValue value = subReadResponse.getAsPlcValue().getValue(tagName);
                        if (value != null && value.isStruct()) {
                            // Map each original tag name to its value from the struct
                            for (SubTagMapping mapping : wildcardTag.getMappings()) {
                                if (value.hasKey(mapping.subItemDiHex())) {
                                    resultTags.put(mapping.originalTagName(),
                                        new DefaultPlcResponseItem<>(PlcResponseCode.OK,
                                            value.getValue(mapping.subItemDiHex())));
                                } else {
                                    // Sub-item not present in response (truncated?)
                                    resultTags.put(mapping.originalTagName(),
                                        new DefaultPlcResponseItem<>(PlcResponseCode.REMOTE_ERROR, null));
                                }
                            }
                        } else {
                            // Response is not a struct (unexpected)
                            for (SubTagMapping mapping : wildcardTag.getMappings()) {
                                resultTags.put(mapping.originalTagName(),
                                    new DefaultPlcResponseItem<>(PlcResponseCode.INTERNAL_ERROR, null));
                            }
                        }
                    } else {
                        // Wildcard read failed: propagate error to all original tags
                        for (SubTagMapping mapping : wildcardTag.getMappings()) {
                            resultTags.put(mapping.originalTagName(),
                                new DefaultPlcResponseItem<>(code, null));
                        }
                    }
                } else if (subResp.isSuccess()) {
                    // Normal single-tag response
                    PlcReadResponse subReadResponse = subResp.getResponse();
                    PlcResponseCode responseCode = subReadResponse.getResponseCode(tagName);
                    PlcValue value = subReadResponse.getAsPlcValue().getValue(tagName);
                    resultTags.put(tagName, new DefaultPlcResponseItem<>(responseCode, value));
                } else {
                    resultTags.put(tagName, new DefaultPlcResponseItem<>(PlcResponseCode.INTERNAL_ERROR, null));
                }
            }
        }

        return new DefaultPlcReadResponse(readRequest, resultTags);
    }

    /**
     * DL/T 645 does not support wildcard writes; always split to single-tag.
     */
    public List<PlcWriteRequest> processWriteRequest(PlcWriteRequest writeRequest, PlcWriter writer) {
        if (writeRequest.getNumberOfTags() == 1) {
            return Collections.singletonList(writeRequest);
        }
        List<PlcWriteRequest> subRequests = new ArrayList<>(writeRequest.getNumberOfTags());
        for (String tagName : writeRequest.getTagNames()) {
            PlcTag tag = writeRequest.getTag(tagName);
            PlcValue value = writeRequest.getPlcValue(tagName);
            subRequests.add(new DefaultPlcWriteRequest(
                writer,
                new LinkedHashMap<>(Collections.singletonMap(
                    tagName, new DefaultPlcTagValueItem<>(tag, value)))));
        }
        return subRequests;
    }

    /**
     * Wrapper for a sub-request's response plus success flag.
     */
    public static class SubResponse<R> {
        private final R response;
        private final boolean success;

        public SubResponse(R response, boolean success) {
            this.response = response;
            this.success = success;
        }

        public R getResponse() {
            return response;
        }

        public boolean isSuccess() {
            return success;
        }
    }

    // ========== Grouping Logic ==========

    /**
     * Group DLT645 tags by meter first, then by potential wildcard prefix.
     * Tags for different meters must never share a wildcard frame.
     */
    private List<LinkedHashMap<String, Dlt645Tag>> groupByWildcardPrefix(
        LinkedHashMap<String, Dlt645Tag> tags) {

        if (tags.isEmpty()) return Collections.emptyList();

        var byMeter = new LinkedHashMap<String, LinkedHashMap<String, Dlt645Tag>>();
        for (var entry : tags.entrySet()) {
            String meter = entry.getValue().getMeterAddress();
            String key = meter == null ? "" : meter;
            byMeter.computeIfAbsent(key, k -> new LinkedHashMap<>())
                .put(entry.getKey(), entry.getValue());
        }

        var groups = new ArrayList<LinkedHashMap<String, Dlt645Tag>>();
        for (var partition : byMeter.values()) {
            groups.addAll(groupOneMeter(partition));
        }
        return groups;
    }

    /**
     * Two tags can be in the same group if they differ in exactly one DI byte position
     * and their non-differing bytes are identical.
     */
    private List<LinkedHashMap<String, Dlt645Tag>> groupOneMeter(
        LinkedHashMap<String, Dlt645Tag> tags) {

        var bestGroups = new ArrayList<LinkedHashMap<String, Dlt645Tag>>();
        var assigned = new HashSet<String>();

        for (int pos = 0; pos < 4; pos++) {
            var groupsByPrefix = new LinkedHashMap<String, LinkedHashMap<String, Dlt645Tag>>();

            for (var entry : tags.entrySet()) {
                if (assigned.contains(entry.getKey())) continue;

                var diHex = entry.getValue().getDiHex();
                var prefix = buildPrefixKey(diHex, pos);
                groupsByPrefix.computeIfAbsent(prefix, k -> new LinkedHashMap<>())
                    .put(entry.getKey(), entry.getValue());
            }

            for (var group : groupsByPrefix.values()) {
                if (group.size() >= MIN_WILDCARD_GROUP_SIZE) {
                    var diList = new ArrayList<String>();
                    for (var t : group.values()) {
                        diList.add(t.getDiHex());
                    }
                    var wildcardDi = DataIdentifiers.computeWildcardDi(diList);
                    if (wildcardDi != null
                        && DataIdentifiers.isOptimizerSafeBlockDi(wildcardDi)
                        && !DataIdentifiers.getSubItems(wildcardDi).isEmpty()) {
                        bestGroups.add(group);
                        assigned.addAll(group.keySet());
                    }
                }
            }
        }

        for (var entry : tags.entrySet()) {
            if (!assigned.contains(entry.getKey())) {
                var singleton = new LinkedHashMap<String, Dlt645Tag>();
                singleton.put(entry.getKey(), entry.getValue());
                bestGroups.add(singleton);
            }
        }

        return bestGroups;
    }

    /**
     * Build a prefix key by masking out the specified byte position.
     * E.g., for DI "02010100" with pos=2: "0201__00"
     */
    private String buildPrefixKey(String diHex, int maskPosition) {
        var sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i == maskPosition) {
                sb.append("__");
            } else {
                sb.append(diHex, i * 2, i * 2 + 2);
            }
        }
        return sb.toString();
    }

    /**
     * Build a wildcard sub-request from a group of DLT645 tags.
     *
     * @return wildcard read request, or null if wildcard cannot be formed
     */
    private PlcReadRequest buildWildcardRequest(DefaultPlcReadRequest original,
                                                 LinkedHashMap<String, Dlt645Tag> group) {
        var diList = new ArrayList<String>();
        var tagNames = new ArrayList<String>();

        String meterAddress = null;
        for (var entry : group.entrySet()) {
            tagNames.add(entry.getKey());
            diList.add(entry.getValue().getDiHex());
            if (meterAddress == null) {
                meterAddress = entry.getValue().getMeterAddress();
            }
        }

        var wildcardDi = DataIdentifiers.computeWildcardDi(diList);
        if (wildcardDi == null || !DataIdentifiers.isOptimizerSafeBlockDi(wildcardDi)) {
            return null;
        }

        var subItems = DataIdentifiers.getSubItems(wildcardDi);
        if (subItems.isEmpty()) return null;

        // Build sub-tag mappings
        var mappings = new ArrayList<SubTagMapping>();
        for (int i = 0; i < tagNames.size(); i++) {
            var tagName = tagNames.get(i);
            var diHex = diList.get(i);
            var index = DataIdentifiers.getSubItemIndex(wildcardDi, diHex);
            if (index < 0) {
                logger.warn("Tag {} DI {} not found in wildcard {} sub-items, skipping wildcard merge",
                    tagName, diHex, wildcardDi);
                return null;
            }
            mappings.add(new SubTagMapping(tagName, index, diHex, group.get(tagName).getPlcValueType()));
        }

        // Parse wildcard DI bytes
        var wildcardDiBytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            wildcardDiBytes[i] = (byte) Integer.parseInt(wildcardDi.substring(i * 2, i * 2 + 2), 16);
        }

        // Use a synthetic tag name for the wildcard sub-request
        var wildcardTagName = "wc_" + wildcardDi;
        var wildcardTag = new Dlt645WildcardTag(wildcardDiBytes, mappings, meterAddress);

        logger.debug("Merged {} tags into wildcard {}: {}", group.size(), wildcardDi, tagNames);

        return new DefaultPlcReadRequest(
            original.getReader(),
            new LinkedHashMap<>(Collections.singletonMap(
                wildcardTagName, new DefaultPlcTagItem<>(wildcardTag))));
    }
}
