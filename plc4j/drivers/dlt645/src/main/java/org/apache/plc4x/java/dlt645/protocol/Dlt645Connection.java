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
package org.apache.plc4x.java.dlt645.protocol;

import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.api.messages.*;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.ConnectionStateChangeType;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.dlt645.config.Dlt645Configuration;
import org.apache.plc4x.java.dlt645.context.Dlt645DriverContext;
import org.apache.plc4x.java.dlt645.optimizer.Dlt645BlockOptimizer;
import org.apache.plc4x.java.dlt645.readwrite.ControlCode;
import org.apache.plc4x.java.dlt645.readwrite.Dlt645Frame;
import org.apache.plc4x.java.dlt645.readwrite.utils.DataIdentifiers;
import org.apache.plc4x.java.dlt645.readwrite.utils.StaticHelper;
import org.apache.plc4x.java.dlt645.tag.Dlt645CommandTag;
import org.apache.plc4x.java.dlt645.tag.Dlt645CommandTag.CommandType;
import org.apache.plc4x.java.dlt645.tag.Dlt645Tag;
import org.apache.plc4x.java.dlt645.tag.Dlt645TagHandler;
import org.apache.plc4x.java.spi.drivers.exceptions.MessageCodecException;
import org.apache.plc4x.java.spi.drivers.messages.*;
import org.apache.plc4x.java.spi.drivers.messages.items.DefaultPlcResponseItem;
import org.apache.plc4x.java.spi.drivers.messages.items.PlcResponseItem;
import org.apache.plc4x.java.spi.drivers.tags.PlcTagHandler;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.values.DefaultPlcValueHandler;
import org.apache.plc4x.java.spi.values.PlcLREAL;
import org.apache.plc4x.java.spi.values.PlcSTRING;
import org.apache.plc4x.java.spi.values.PlcStruct;
import org.apache.plc4x.java.spi.values.PlcValueHandler;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.apache.plc4x.java.utils.subscriptionemulation.PollingSubscriptionConnectionBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * DL/T 645-2007 Connection (Client mode).
 * <p>
 * Supports:
 * <ul>
 *   <li>Read data (0x11) with automatic subsequent-frame accumulation (0x12)</li>
 *   <li>Write data (0x14) with PA + P0 authentication</li>
 *   <li>Read communication address (0x13) via {@code cmd:read-address}</li>
 *   <li>Write communication address (0x15) via {@code cmd:write-address}</li>
 *   <li>Freeze data (0x16) via {@code cmd:freeze}</li>
 *   <li>Change baud rate (0x17) via {@code cmd:change-baud-rate}</li>
 *   <li>Change password (0x18) via {@code cmd:change-password}</li>
 *   <li>Clear max demand (0x19) via {@code cmd:clear-max-demand}</li>
 *   <li>Clear meter (0x1A) via {@code cmd:clear-meter}</li>
 *   <li>Clear event (0x1B) via {@code cmd:clear-event}</li>
 * </ul>
 */
public class Dlt645Connection extends PollingSubscriptionConnectionBase<Dlt645Configuration> {

    private static final Logger logger = LoggerFactory.getLogger(Dlt645Connection.class);

    /** Broadcast address per DL/T 645-2007: 0x99 x 6 */
    private static final byte[] BROADCAST_ADDRESS = {
        (byte) 0x99, (byte) 0x99, (byte) 0x99,
        (byte) 0x99, (byte) 0x99, (byte) 0x99
    };

    private Dlt645MessageCodec messageCodec;

    // DL/T 645 is a single-outstanding-transaction protocol: requests are chained
    // so only one frame is in flight at a time. The pending future keyed by a
    // monotonically increasing request id is completed by handleIncomingMessage.
    private final Map<Long, CompletableFuture<Dlt645Frame>> pendingRequests = new ConcurrentHashMap<>();
    private final Object requestChainLock = new Object();
    private CompletableFuture<?> requestTail = CompletableFuture.completedFuture(null);
    private long requestIdGenerator = 0;

    private byte[] meterAddress;
    private byte[] password;
    private byte[] operatorCode;

    public Dlt645Connection(Dlt645Configuration configuration, TransportInstance<?> transportInstance, AuditLog auditLog) {
        super(configuration, transportInstance, auditLog);
    }

    @Override
    protected void onConnect() throws PlcConnectionException {
        this.meterAddress = Dlt645DriverContext.parseMeterAddress(getConfiguration().getMeterAddress());
        this.password = parseHexField(getConfiguration().getPassword(), 4);
        this.operatorCode = parseHexField(getConfiguration().getOperatorCode(), 4);

        messageCodec = new Dlt645MessageCodec(transportInstance, this::handleIncomingMessage);

        startReceiving(() -> {
            try {
                messageCodec.processIncomingData();
            } catch (MessageCodecException e) {
                logger.error("Error processing incoming Dlt645 data", e);
            }
        });

        logger.info("DL/T 645-2007 connection established");
        if (auditLog.isEnabled()) {
            auditLog.write(AuditLogEventType.CONNECT, "DL/T 645-2007 connection established");
        }
        fireConnectionStateChanged(ConnectionStateChangeType.CONNECTED, null);
    }

    @Override
    public boolean isConnected() {
        return messageCodec != null && messageCodec.isOpen();
    }

    @Override
    public void close() throws Exception {
        stopReceiving();
        if (messageCodec != null) {
            messageCodec.close();
        }
        pendingRequests.values().forEach(pending ->
            pending.completeExceptionally(new PlcRuntimeException("Connection closed")));
        pendingRequests.clear();
        super.close();
        logger.info("DL/T 645-2007 connection closed");
        fireConnectionStateChanged(ConnectionStateChangeType.DISCONNECTED, null);
    }

    @Override
    protected void onTransportDisconnected(Throwable cause) {
        super.onTransportDisconnected(cause);
        fireConnectionStateChanged(ConnectionStateChangeType.CONNECTION_LOST,
            cause != null ? cause.getMessage() : "Connection closed by remote");

        PlcRuntimeException exception = new PlcRuntimeException(
            cause != null ? "Connection lost: " + cause.getMessage() : "Connection closed by remote", cause);
        int pendingCount = pendingRequests.size();
        if (pendingCount > 0) {
            logger.warn("Failing {} pending requests due to transport disconnect", pendingCount);
            pendingRequests.values().forEach(pending -> pending.completeExceptionally(exception));
            pendingRequests.clear();
        }
    }

    @Override
    protected PlcTagHandler getTagHandler() {
        return new Dlt645TagHandler();
    }

    @Override
    protected PlcValueHandler getValueHandler() {
        return new DefaultPlcValueHandler();
    }

    @Override
    protected int getMaxConcurrentRequests() {
        return 1;
    }

    private void handleIncomingMessage(Dlt645Frame frame) {
        if (auditLog.isEnabled()) {
            auditLog.write(AuditLogEventType.INCOMING_MESSAGE,
                "Received DL/T 645 frame, control=0x" + String.format("%02X", frame.getControl().getValue()));
        }
        // DL/T 645 frames carry no transaction id; with a single outstanding
        // request, complete the single pending future.
        if (pendingRequests.isEmpty()) {
            logger.warn("Received DL/T 645 frame but no request is pending");
            return;
        }
        Map.Entry<Long, CompletableFuture<Dlt645Frame>> entry = pendingRequests.entrySet().iterator().next();
        pendingRequests.remove(entry.getKey());
        entry.getValue().complete(frame);
    }

    /**
     * Parse hex string to byte array. Returns zero-filled array if input is empty/invalid.
     */
    private static byte[] parseHexField(String hex, int expectedLength) {
        if (hex == null || hex.isEmpty()) {
            return new byte[expectedLength];
        }
        var clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        if (clean.length() != expectedLength * 2) {
            logger.warn("Expected {} hex digits for field, got '{}'. Using zero-fill.", expectedLength * 2, hex);
            return new byte[expectedLength];
        }
        var bytes = new byte[expectedLength];
        for (int i = 0; i < expectedLength; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    // ========== Ping ==========

    @Override
    protected CompletableFuture<PlcPingResponse> onPing(PlcPingRequest pingRequest) {
        var diBytes = new byte[]{0x00, 0x01, 0x00, 0x00};
        var frame = buildReadDataFrame(diBytes);
        return executeThrottled(() ->
            sendRequest(frame).thenApply(response ->
                new DefaultPlcPingResponse(pingRequest, PlcResponseCode.OK)
            )
        );
    }

    // ========== Read ==========

    @Override
    protected CompletableFuture<PlcReadResponse> onRead(PlcReadRequest readRequest) {
        var request = (DefaultPlcReadRequest) readRequest;

        // Use the optimizer to split multi-tag requests into wildcard blocks / single tags.
        var optimizer = new Dlt645BlockOptimizer();
        List<org.apache.plc4x.java.dlt645.optimizer.Dlt645BlockOptimizer.SubResponse<PlcReadResponse>> processed = null;
        List<CompletableFuture<PlcReadResponse>> subFutures = new java.util.ArrayList<>();

        List<PlcReadRequest> subRequests;
        try {
            subRequests = optimizer.processReadRequest(readRequest);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                new PlcRuntimeException("Failed to split read request", e));
        }

        // DL/T 645 supports only single-tag requests; chain them sequentially.
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        Map<PlcReadRequest, Dlt645BlockOptimizer.SubResponse<PlcReadResponse>> responses =
            new LinkedHashMap<>();
        for (PlcReadRequest subRequest : subRequests) {
            CompletableFuture<PlcReadResponse> subFuture =
                chain.thenComposeAsync(v -> executeSingleRead(subRequest));
            subFutures.add(subFuture);
            chain = subFuture.handle((r, e) -> {
                if (e != null) {
                    logger.warn("Sub-request failed: {}", e.getMessage());
                    responses.put(subRequest, new Dlt645BlockOptimizer.SubResponse<>(null, false));
                } else {
                    responses.put(subRequest, new Dlt645BlockOptimizer.SubResponse<>(r, true));
                }
                return null;
            });
        }

        return CompletableFuture.allOf(subFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> optimizer.processReadResponses(readRequest, responses));
    }

    private CompletableFuture<PlcReadResponse> executeSingleRead(PlcReadRequest readRequest) {
        var request = (DefaultPlcReadRequest) readRequest;

        if (request.getTagNames().size() != 1) {
            return CompletableFuture.failedFuture(
                new PlcRuntimeException("DL/T 645-2007 only supports single tag requests"));
        }

        var tagName = request.getTagNames().iterator().next();
        PlcTag tag = request.getTag(tagName);

        // Command tag: cmd:read-address
        if (tag instanceof Dlt645CommandTag) {
            var cmdTag = (Dlt645CommandTag) tag;
            if (cmdTag.getCommandType() != CommandType.READ_ADDRESS) {
                return CompletableFuture.failedFuture(
                    new PlcRuntimeException("Only cmd:read-address is supported in read requests"));
            }
            return executeReadAddress(request, tagName);
        }

        // Standard DI tag: read data (0x11)
        return executeReadData(request, tagName, (Dlt645Tag) tag);
    }

    /**
     * Execute READ_ADDRESS (0x13): read meter communication address.
     * Request: no data field. Response: 6-byte address (BCD, LSB first).
     */
    private CompletableFuture<PlcReadResponse> executeReadAddress(
        DefaultPlcReadRequest request, String tagName) {

        // Read address uses broadcast address and no data
        var frame = new Dlt645Frame(BROADCAST_ADDRESS, ControlCode.READ_ADDRESS, (short) 0, new byte[0]);

        return sendRequest(frame).thenApply(response -> {
            PlcValue plcValue = null;
            PlcResponseCode responseCode;

            var controlCode = response.getControl();
            if (controlCode == ControlCode.READ_ADDRESS_ERROR) {
                logger.warn("Read address error: {}", StaticHelper.describeError(response.getDataPlain()));
                responseCode = PlcResponseCode.REMOTE_ERROR;
            } else if (controlCode == ControlCode.READ_ADDRESS_RESPONSE) {
                // Response data = 6-byte address (LSB first), convert to MSB-first string
                plcValue = new PlcSTRING(formatAddressMsbFirst(response.getDataPlain()));
                responseCode = PlcResponseCode.OK;
            } else {
                logger.warn("Unexpected control code for read-address: 0x{}",
                    String.format("%02X", controlCode.getValue()));
                responseCode = PlcResponseCode.INTERNAL_ERROR;
            }

            return (PlcReadResponse) new DefaultPlcReadResponse(request,
                Collections.singletonMap(tagName,
                    new DefaultPlcResponseItem<>(responseCode, plcValue)));
        });
    }

    /**
     * Execute READ_DATA (0x11) with automatic multi-frame accumulation.
     * When D5=1 in response (READ_DATA_RESPONSE_MORE, 0xB1), sends READ_SUBSEQUENT_DATA (0x12)
     * with incrementing sequence number until all data is received.
     */
    private CompletableFuture<PlcReadResponse> executeReadData(
        DefaultPlcReadRequest request, String tagName, Dlt645Tag tag) {

        var frame = buildReadDataFrame(tag.getDataIdentifier());

        return sendRequest(frame).thenCompose(response -> {
            var controlCode = response.getControl();

            if (controlCode == ControlCode.READ_DATA_ERROR) {
                logger.warn("Read error for tag {}: {}",
                    tagName, StaticHelper.describeError(response.getDataPlain()));
                return CompletableFuture.completedFuture(
                    (PlcReadResponse) new DefaultPlcReadResponse(request,
                        Collections.singletonMap(tagName,
                            new DefaultPlcResponseItem<>(PlcResponseCode.REMOTE_ERROR, null))));
            }

            if (controlCode == ControlCode.READ_DATA_RESPONSE) {
                // Single-frame response, no more data
                return CompletableFuture.completedFuture(
                    completeReadDataResponse(request, tagName, tag, response.getDataPlain()));
            }

            if (controlCode == ControlCode.READ_DATA_RESPONSE_MORE) {
                // D5=1: more data follows, start subsequent reads
                return accumulateSubsequentData(request, tagName, tag, response.getDataPlain(), 1);
            }

            logger.warn("Unexpected control code in read response: 0x{}",
                String.format("%02X", controlCode.getValue()));
            return CompletableFuture.completedFuture(
                (PlcReadResponse) new DefaultPlcReadResponse(request,
                    Collections.singletonMap(tagName,
                        new DefaultPlcResponseItem<>(PlcResponseCode.INTERNAL_ERROR, null))));
        });
    }

    /**
     * Recursively send READ_SUBSEQUENT_DATA (0x12) frames to accumulate multi-frame response.
     */
    private CompletableFuture<PlcReadResponse> accumulateSubsequentData(
        DefaultPlcReadRequest request, String tagName, Dlt645Tag tag,
        byte[] accumulatedData, int seqNumber) {

        // Build READ_SUBSEQUENT_DATA frame: DI(4, reversed) + SEQ(1)
        var di = tag.getDataIdentifier();
        var dataPlain = new byte[5];
        for (int i = 0; i < 4; i++) {
            dataPlain[i] = di[3 - i];
        }
        dataPlain[4] = (byte) seqNumber;

        var subFrame = new Dlt645Frame(meterAddress, ControlCode.READ_SUBSEQUENT_DATA,
            (short) dataPlain.length, dataPlain);

        return sendRequest(subFrame).thenCompose(response -> {
            var controlCode = response.getControl();

            if (controlCode == ControlCode.READ_SUBSEQUENT_DATA_ERROR) {
                logger.warn("Read subsequent error for tag {}: {}",
                    tagName, StaticHelper.describeError(response.getDataPlain()));
                // Return what we have accumulated so far
                return CompletableFuture.completedFuture(
                    completeReadDataResponse(request, tagName, tag, accumulatedData));
            }

            // Merge data: skip DI(4) + SEQ(1) from subsequent response
            var respData = response.getDataPlain();
            var newData = mergeSubsequentData(accumulatedData, respData);

            if (controlCode == ControlCode.READ_SUBSEQUENT_DATA_RESPONSE) {
                // No more data
                return CompletableFuture.completedFuture(
                    completeReadDataResponse(request, tagName, tag, newData));
            }

            if (controlCode == ControlCode.READ_SUBSEQUENT_DATA_RESPONSE_MORE) {
                // More data follows
                return accumulateSubsequentData(request, tagName, tag, newData, seqNumber + 1);
            }

            logger.warn("Unexpected control code in subsequent read: 0x{}",
                String.format("%02X", controlCode.getValue()));
            return CompletableFuture.completedFuture(
                completeReadDataResponse(request, tagName, tag, newData));
        });
    }

    /**
     * Merge subsequent frame data into accumulated data.
     * Initial response: DI(4) + value_data
     * Subsequent response: DI(4) + SEQ(1) + value_data
     * We keep the DI prefix from the first frame and append value data from subsequent frames.
     */
    private byte[] mergeSubsequentData(byte[] accumulated, byte[] subsequent) {
        if (subsequent == null || subsequent.length <= 5) {
            return accumulated;
        }
        // subsequent: DI(4) + SEQ(1) + value_data → extract value_data (offset 5)
        var baos = new ByteArrayOutputStream();
        baos.write(accumulated, 0, accumulated.length);
        baos.write(subsequent, 5, subsequent.length - 5);
        return baos.toByteArray();
    }

    private PlcReadResponse completeReadDataResponse(
        DefaultPlcReadRequest request, String tagName, Dlt645Tag tag, byte[] dataPlain) {

        PlcValue plcValue = null;
        PlcResponseCode responseCode;
        try {
            plcValue = parseResponseData(dataPlain, tag);
            responseCode = PlcResponseCode.OK;
        } catch (Exception e) {
            logger.warn("Failed to parse read response for tag {}: {}", tagName, e.getMessage());
            responseCode = PlcResponseCode.INTERNAL_ERROR;
        }
        return new DefaultPlcReadResponse(request,
            Collections.singletonMap(tagName,
                new DefaultPlcResponseItem<>(responseCode, plcValue)));
    }

    // ========== Write ==========

    @Override
    protected CompletableFuture<PlcWriteResponse> onWrite(PlcWriteRequest writeRequest) {
        var request = (DefaultPlcWriteRequest) writeRequest;

        if (request.getTagNames().size() != 1) {
            return CompletableFuture.failedFuture(
                new PlcRuntimeException("DL/T 645-2007 only supports single tag requests"));
        }

        var tagName = request.getTagNames().iterator().next();
        PlcTag tag = request.getTag(tagName);
        var value = writeRequest.getPlcValue(tagName);

        // Command tag dispatch
        if (tag instanceof Dlt645CommandTag) {
            var cmdTag = (Dlt645CommandTag) tag;
            return executeCommandWrite(request, tagName, cmdTag, value);
        }

        // Standard DI tag: write data (0x14)
        return executeWriteData(request, tagName, (Dlt645Tag) tag, value);
    }

    /**
     * Execute standard WRITE_DATA (0x14): DI + PA + P0 + payload.
     */
    private CompletableFuture<PlcWriteResponse> executeWriteData(
        DefaultPlcWriteRequest request, String tagName, Dlt645Tag tag, PlcValue value) {

        var frame = buildWriteDataFrame(tag.getDataIdentifier(), serializeValue(value));

        return sendRequest(frame).thenApply(response -> {
            PlcResponseCode responseCode;
            var controlCode = response.getControl();
            if (controlCode == ControlCode.WRITE_DATA_ERROR) {
                logger.warn("Write error for tag {}: {}",
                    tagName, StaticHelper.describeError(response.getDataPlain()));
                responseCode = PlcResponseCode.REMOTE_ERROR;
            } else if (controlCode == ControlCode.WRITE_DATA_RESPONSE) {
                responseCode = PlcResponseCode.OK;
            } else {
                logger.warn("Unexpected control code in write response: 0x{}",
                    String.format("%02X", controlCode.getValue()));
                responseCode = PlcResponseCode.INTERNAL_ERROR;
            }
            return (PlcWriteResponse) new DefaultPlcWriteResponse(request,
                Collections.singletonMap(tagName, responseCode));
        });
    }

    /**
     * Execute command write operations (0x15-0x1B).
     */
    private CompletableFuture<PlcWriteResponse> executeCommandWrite(
        DefaultPlcWriteRequest request, String tagName,
        Dlt645CommandTag cmdTag, PlcValue value) {

        var cmdType = cmdTag.getCommandType();
        switch (cmdType) {
            case WRITE_ADDRESS:
                return executeWriteAddress(request, tagName, value);
            case FREEZE:
                return executeSimpleCommand(request, tagName,
                    ControlCode.FREEZE_DATA, ControlCode.FREEZE_DATA_RESPONSE, ControlCode.FREEZE_DATA_ERROR,
                    buildFreezeData(value));
            case CHANGE_BAUD_RATE:
                return executeSimpleCommand(request, tagName,
                    ControlCode.CHANGE_BAUD_RATE, ControlCode.CHANGE_BAUD_RATE_RESPONSE, ControlCode.CHANGE_BAUD_RATE_ERROR,
                    buildBaudRateData(value));
            case CHANGE_PASSWORD:
                return executeSimpleCommand(request, tagName,
                    ControlCode.CHANGE_PASSWORD, ControlCode.CHANGE_PASSWORD_RESPONSE, ControlCode.CHANGE_PASSWORD_ERROR,
                    buildChangePasswordData(value));
            case CLEAR_MAX_DEMAND:
                return executeSimpleCommand(request, tagName,
                    ControlCode.CLEAR_MAX_DEMAND, ControlCode.CLEAR_MAX_DEMAND_RESPONSE, ControlCode.CLEAR_MAX_DEMAND_ERROR,
                    buildAuthOnlyData());
            case CLEAR_METER:
                return executeSimpleCommand(request, tagName,
                    ControlCode.CLEAR_METER, ControlCode.CLEAR_METER_RESPONSE, ControlCode.CLEAR_METER_ERROR,
                    buildAuthOnlyData());
            case CLEAR_EVENT:
                return executeSimpleCommand(request, tagName,
                    ControlCode.CLEAR_EVENT, ControlCode.CLEAR_EVENT_RESPONSE, ControlCode.CLEAR_EVENT_ERROR,
                    buildAuthOnlyData());
            default:
                return CompletableFuture.failedFuture(
                    new PlcRuntimeException("Command '" + cmdType.getKeyword() + "' is not a write command"));
        }
    }

    /**
     * Execute WRITE_ADDRESS (0x15): write communication address.
     * Per DL/T 645-2007: uses broadcast address (0x99x6), data = new 6-byte address.
     */
    private CompletableFuture<PlcWriteResponse> executeWriteAddress(
        DefaultPlcWriteRequest request, String tagName, PlcValue value) {

        var newAddress = Dlt645DriverContext.parseMeterAddress(value.getString());
        var frame = new Dlt645Frame(BROADCAST_ADDRESS, ControlCode.WRITE_ADDRESS,
            (short) newAddress.length, newAddress);

        return sendRequest(frame).thenApply(response -> {
            PlcResponseCode responseCode;
            var controlCode = response.getControl();
            if (controlCode == ControlCode.WRITE_ADDRESS_ERROR) {
                logger.warn("Write address error: {}", StaticHelper.describeError(response.getDataPlain()));
                responseCode = PlcResponseCode.REMOTE_ERROR;
            } else if (controlCode == ControlCode.WRITE_ADDRESS_RESPONSE) {
                responseCode = PlcResponseCode.OK;
            } else {
                logger.warn("Unexpected control code for write-address: 0x{}",
                    String.format("%02X", controlCode.getValue()));
                responseCode = PlcResponseCode.INTERNAL_ERROR;
            }
            return (PlcWriteResponse) new DefaultPlcWriteResponse(request,
                Collections.singletonMap(tagName, responseCode));
        });
    }

    /**
     * Generic execution for admin commands (0x16-0x1B) that share the same request/response pattern.
     */
    private CompletableFuture<PlcWriteResponse> executeSimpleCommand(
        DefaultPlcWriteRequest request, String tagName,
        ControlCode reqCode, ControlCode respCode, ControlCode errCode,
        byte[] dataPlain) {

        var frame = new Dlt645Frame(meterAddress, reqCode, (short) dataPlain.length, dataPlain);

        return sendRequest(frame).thenApply(response -> {
            PlcResponseCode responseCode;
            var controlCode = response.getControl();
            if (controlCode == errCode) {
                logger.warn("Command {} error: {}",
                    reqCode.name(), StaticHelper.describeError(response.getDataPlain()));
                responseCode = PlcResponseCode.REMOTE_ERROR;
            } else if (controlCode == respCode) {
                responseCode = PlcResponseCode.OK;
            } else {
                logger.warn("Unexpected control code for {}: 0x{}",
                    reqCode.name(), String.format("%02X", controlCode.getValue()));
                responseCode = PlcResponseCode.INTERNAL_ERROR;
            }
            return (PlcWriteResponse) new DefaultPlcWriteResponse(request,
                Collections.singletonMap(tagName, responseCode));
        });
    }

    // ========== Frame construction ==========

    /**
     * Build READ_DATA (0x11) frame: data = DI (4 bytes, reversed).
     */
    private Dlt645Frame buildReadDataFrame(byte[] dataIdentifier) {
        var dataPlain = new byte[4];
        for (int i = 0; i < 4; i++) {
            dataPlain[i] = dataIdentifier[3 - i];
        }
        return new Dlt645Frame(meterAddress, ControlCode.READ_DATA,
            (short) dataPlain.length, dataPlain);
    }

    /**
     * Build WRITE_DATA (0x14) frame: DI(4, reversed) + PA(4) + P0(4) + payload.
     */
    private Dlt645Frame buildWriteDataFrame(byte[] dataIdentifier, byte[] payload) {
        var dataPlain = new byte[4 + 4 + 4 + payload.length];
        for (int i = 0; i < 4; i++) {
            dataPlain[i] = dataIdentifier[3 - i];
        }
        System.arraycopy(password, 0, dataPlain, 4, 4);
        System.arraycopy(operatorCode, 0, dataPlain, 8, 4);
        System.arraycopy(payload, 0, dataPlain, 12, payload.length);
        return new Dlt645Frame(meterAddress, ControlCode.WRITE_DATA,
            (short) dataPlain.length, dataPlain);
    }

    // ========== Command data builders ==========

    /**
     * Build freeze command data (0x16).
     * Value format: hex string of freeze data content (e.g., "MMDD" or full data block).
     */
    private byte[] buildFreezeData(PlcValue value) {
        var payload = parseHexValue(value.getString());
        // Freeze: PA(4) + P0(4) + freeze data
        var data = new byte[4 + 4 + payload.length];
        System.arraycopy(password, 0, data, 0, 4);
        System.arraycopy(operatorCode, 0, data, 4, 4);
        System.arraycopy(payload, 0, data, 8, payload.length);
        return data;
    }

    /**
     * Build baud rate change data (0x17).
     * Value: baud rate code as string, e.g., "04" for 2400bps, "08" for 9600bps.
     */
    private byte[] buildBaudRateData(PlcValue value) {
        var rateByte = parseHexValue(value.getString());
        // Change baud rate: PA(4) + P0(4) + baud rate code(1)
        var data = new byte[4 + 4 + 1];
        System.arraycopy(password, 0, data, 0, 4);
        System.arraycopy(operatorCode, 0, data, 4, 4);
        data[8] = rateByte.length > 0 ? rateByte[0] : 0x04;
        return data;
    }

    /**
     * Build change password data (0x18).
     * Value format: "old_password:new_password" (each 8 hex digits, 4 bytes).
     */
    private byte[] buildChangePasswordData(PlcValue value) {
        var parts = value.getString().split(":");
        byte[] oldPwd, newPwd;
        if (parts.length >= 2) {
            oldPwd = parseHexField(parts[0].trim(), 4);
            newPwd = parseHexField(parts[1].trim(), 4);
        } else {
            // If only one value, use current password as old, value as new
            oldPwd = password;
            newPwd = parseHexField(parts[0].trim(), 4);
        }
        // Change password: PA(4, old password) + P0(4) + new PA(4)
        var data = new byte[4 + 4 + 4];
        System.arraycopy(oldPwd, 0, data, 0, 4);
        System.arraycopy(operatorCode, 0, data, 4, 4);
        System.arraycopy(newPwd, 0, data, 8, 4);
        return data;
    }

    /**
     * Build authentication-only data for clear commands (0x19, 0x1A, 0x1B).
     * Data = PA(4) + P0(4).
     */
    private byte[] buildAuthOnlyData() {
        var data = new byte[8];
        System.arraycopy(password, 0, data, 0, 4);
        System.arraycopy(operatorCode, 0, data, 4, 4);
        return data;
    }

    // ========== Data parsing ==========

    /**
     * Parse response data: DI(4 bytes, reversed) + value data.
     * Verifies DI echo, then delegates to semantic decoding.
     * <p>
     * For wildcard DI (containing 0xFF), parses concatenated sub-values
     * and returns a PlcStruct keyed by each sub-item's DI hex string.
     */
    private PlcValue parseResponseData(byte[] dataPlain, Dlt645Tag tag) {
        if (dataPlain == null || dataPlain.length < 4) {
            return new PlcSTRING("");
        }

        var diBytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            diBytes[i] = dataPlain[3 - i];
        }

        if (!Arrays.equals(diBytes, tag.getDataIdentifier())) {
            var expectedDi = tag.getAddressString().substring(0, 8);
            var actualDi = String.format("%02X%02X%02X%02X",
                diBytes[0] & 0xFF, diBytes[1] & 0xFF, diBytes[2] & 0xFF, diBytes[3] & 0xFF);
            logger.warn("DI mismatch in response: expected {}, got {}", expectedDi, actualDi);
        }

        var diHex = String.format("%02X%02X%02X%02X",
            diBytes[0] & 0xFF, diBytes[1] & 0xFF, diBytes[2] & 0xFF, diBytes[3] & 0xFF);

        var valueData = new byte[dataPlain.length - 4];
        System.arraycopy(dataPlain, 4, valueData, 0, valueData.length);

        // Wildcard DI: parse concatenated sub-values into a PlcStruct
        if (DataIdentifiers.containsWildcard(diHex)) {
            return parseWildcardResponseData(diHex, valueData);
        }

        return new PlcLREAL(DataIdentifiers.decodeValue(diHex, valueData));
    }

    /**
     * Parse wildcard response: concatenated BCD values for all sub-items.
     * <p>
     * DL/T 645-2007 wildcard response format: values are concatenated in ascending
     * order of the wildcard byte, each value's length is determined by DataIdentifiers.
     *
     * @param wildcardDiHex wildcard DI hex string (e.g. "0201FF00")
     * @param valueData     concatenated BCD value bytes (LSB first per sub-item)
     * @return PlcStruct mapping each sub-item's DI hex to its decoded PlcLREAL value
     */
    private PlcValue parseWildcardResponseData(String wildcardDiHex, byte[] valueData) {
        var subItems = DataIdentifiers.getSubItems(wildcardDiHex);
        if (subItems.isEmpty()) {
            logger.warn("No sub-items found for wildcard DI: {}", wildcardDiHex);
            return new PlcSTRING("");
        }

        var map = new LinkedHashMap<String, PlcValue>();
        int offset = 0;
        for (var subItem : subItems) {
            if (offset + subItem.getDataLength() > valueData.length) {
                logger.debug("Wildcard response truncated at offset {}/{} for sub-item {}",
                    offset, valueData.length, subItem.getDi());
                break;
            }
            var subData = new byte[subItem.getDataLength()];
            System.arraycopy(valueData, offset, subData, 0, subItem.getDataLength());
            map.put(subItem.getDi().toUpperCase(),
                new PlcLREAL(DataIdentifiers.decodeValue(subItem.getDi(), subData)));
            offset += subItem.getDataLength();
        }

        return new PlcStruct(map);
    }

    /**
     * Format 6-byte address (LSB first, bit-reversed wire order) as MSB-first hex string.
     * Each wire byte is bit-reversed back to its BCD form before the reversed
     * concatenation (DL/T 645-2007 6.1.2).
     */
    private static String formatAddressMsbFirst(byte[] addrBytes) {
        if (addrBytes == null || addrBytes.length < 6) {
            return "";
        }
        var sb = new StringBuilder(12);
        for (int i = 5; i >= 0; i--) {
            sb.append(String.format("%02X", StaticHelper.bitReverse(addrBytes[i]) & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Parse BCD-encoded data to float (LSB first).
     */
    public static float parseBcdValue(byte[] bcdData) {
        if (bcdData == null || bcdData.length == 0) {
            return 0.0f;
        }
        var sb = new StringBuilder();
        for (int i = bcdData.length - 1; i >= 0; i--) {
            int high = (bcdData[i] >> 4) & 0x0F;
            int low = bcdData[i] & 0x0F;
            sb.append(high).append(low);
        }
        try {
            return Float.parseFloat(sb.toString());
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }

    /**
     * Serialize PlcValue to BCD bytes for write data command.
     */
    private byte[] serializeValue(PlcValue value) {
        var str = value.getString().replaceAll("[^0-9]", "");
        if (str.length() % 2 != 0) {
            str = "0" + str;
        }
        var bytes = new byte[str.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            var srcIdx = (bytes.length - 1 - i) * 2;
            int high = Character.digit(str.charAt(srcIdx), 10);
            int low = Character.digit(str.charAt(srcIdx + 1), 10);
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    /**
     * Parse hex string value to raw bytes.
     */
    private static byte[] parseHexValue(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        var clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        if (clean.length() % 2 != 0) {
            clean = "0" + clean;
        }
        var bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    // ========== Transport send ==========

    /**
     * Send a frame and wait for its response, chaining so only one transaction
     * is in flight (DL/T 645 over serial has no transaction id).
     */
    private CompletableFuture<Dlt645Frame> sendRequest(Dlt645Frame frame) {
        CompletableFuture<Dlt645Frame> responseFuture = new CompletableFuture<>();
        long requestId;
        synchronized (requestChainLock) {
            requestId = ++requestIdGenerator;
        }
        pendingRequests.put(requestId, responseFuture);

        long timeoutMs = getConfiguration().getRequestTimeout();
        responseFuture.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .whenComplete((result, error) -> {
                if (error instanceof TimeoutException) {
                    pendingRequests.remove(requestId);
                }
            });

        CompletableFuture<?> previous;
        synchronized (requestChainLock) {
            previous = requestTail;
            requestTail = responseFuture.handle((result, error) -> null);
        }
        if (previous.isDone()) {
            dispatchRequest(frame, requestId, responseFuture);
        } else {
            previous.whenCompleteAsync((ignored, ignoredError) -> dispatchRequest(frame, requestId, responseFuture));
        }
        return responseFuture;
    }

    private void dispatchRequest(Dlt645Frame frame, long requestId, CompletableFuture<Dlt645Frame> responseFuture) {
        if (responseFuture.isDone()) {
            return;
        }
        try {
            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.OUTGOING_MESSAGE,
                    "Sending DL/T 645 frame, control=0x" + String.format("%02X", frame.getControl().getValue()));
            }
            messageCodec.send(frame);
        } catch (MessageCodecException e) {
            pendingRequests.remove(requestId);
            responseFuture.completeExceptionally(new PlcRuntimeException("Failed to send request", e));
        } catch (RuntimeException e) {
            pendingRequests.remove(requestId);
            responseFuture.completeExceptionally(e);
        }
    }
}
