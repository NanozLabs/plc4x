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
import org.apache.plc4x.java.dlt645.tag.Dlt645WildcardTag;
import org.apache.plc4x.java.spi.drivers.exceptions.MessageCodecException;
import org.apache.plc4x.java.spi.drivers.messages.*;
import org.apache.plc4x.java.spi.drivers.messages.items.DefaultPlcResponseItem;
import org.apache.plc4x.java.spi.drivers.messages.items.PlcResponseItem;
import org.apache.plc4x.java.spi.drivers.tags.PlcTagHandler;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.values.DefaultPlcValueHandler;
import org.apache.plc4x.java.spi.values.PlcDATE;
import org.apache.plc4x.java.spi.values.PlcDATE_AND_TIME;
import org.apache.plc4x.java.spi.values.PlcDINT;
import org.apache.plc4x.java.spi.values.PlcINT;
import org.apache.plc4x.java.spi.values.PlcLINT;
import org.apache.plc4x.java.spi.values.PlcLREAL;
import org.apache.plc4x.java.spi.values.PlcRawByteArray;
import org.apache.plc4x.java.spi.values.PlcREAL;
import org.apache.plc4x.java.spi.values.PlcSINT;
import org.apache.plc4x.java.spi.values.PlcSTRING;
import org.apache.plc4x.java.spi.values.PlcStruct;
import org.apache.plc4x.java.spi.values.PlcTIME_OF_DAY;
import org.apache.plc4x.java.spi.values.PlcUDINT;
import org.apache.plc4x.java.spi.values.PlcUINT;
import org.apache.plc4x.java.spi.values.PlcUSINT;
import org.apache.plc4x.java.spi.values.PlcValueHandler;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.apache.plc4x.java.utils.subscriptionemulation.PollingSubscriptionConnectionBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final int MAX_SUBSEQUENT_FRAMES = 255;
    private static final int MAX_ACCUMULATED_DATA_BYTES = 50_000;

    /** Broadcast address used by commands that do not require a response. */
    private static final byte[] BROADCAST_ADDRESS = Dlt645DriverContext.BROADCAST_ADDRESS;
    /** Point-to-point wildcard address used by read/write communication-address commands. */
    private static final byte[] WILDCARD_ADDRESS = {
        (byte) 0xAA, (byte) 0xAA, (byte) 0xAA,
        (byte) 0xAA, (byte) 0xAA, (byte) 0xAA
    };

    private Dlt645MessageCodec messageCodec;

    // DL/T 645 has no transaction id. Requests are queued, but only the request
    // actually dispatched on the wire is eligible to receive a response.
    private final Set<CompletableFuture<?>> activeRequests = ConcurrentHashMap.newKeySet();
    private final Object requestChainLock = new Object();
    private CompletableFuture<?> requestTail = CompletableFuture.completedFuture(null);
    private PendingRequest pendingRequest;
    private long requestIdGenerator = 0;

    private byte[] meterAddress;
    private byte[] password;
    private byte[] operatorCode;

    public Dlt645Connection(Dlt645Configuration configuration, TransportInstance<?> transportInstance,
                            AuditLog auditLog) {
        super(configuration, transportInstance, auditLog);
    }

    @Override
    protected void onConnect() throws PlcConnectionException {
        String configuredMeter = getConfiguration().getMeterAddress();
        if (configuredMeter != null && !configuredMeter.isBlank()) {
            try {
                this.meterAddress = Dlt645DriverContext.parseMeterAddress(configuredMeter);
            } catch (IllegalArgumentException e) {
                throw new PlcConnectionException("Invalid meter-address", e);
            }
        } else {
            this.meterAddress = null;
        }
        try {
            this.password = parseHexField(getConfiguration().getPassword(), 4);
        } catch (IllegalArgumentException e) {
            throw new PlcConnectionException("Invalid password", e);
        }
        try {
            this.operatorCode = parseHexField(getConfiguration().getOperatorCode(), 4);
        } catch (IllegalArgumentException e) {
            throw new PlcConnectionException("Invalid operator-code", e);
        }

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
            messageCodec.discardBufferedInput("connection closing");
            messageCodec.close();
        }
        activeRequests.forEach(pending ->
            pending.completeExceptionally(new PlcRuntimeException("Connection closed")));
        activeRequests.clear();
        synchronized (requestChainLock) {
            pendingRequest = null;
        }
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
        int pendingCount = activeRequests.size();
        if (pendingCount > 0) {
            logger.warn("Failing {} pending requests due to transport disconnect", pendingCount);
            activeRequests.forEach(pending -> pending.completeExceptionally(exception));
            activeRequests.clear();
            synchronized (requestChainLock) {
                pendingRequest = null;
            }
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
        if (frame == null || frame.getControl() == null) {
            logger.warn("Ignoring DL/T 645 frame with an unknown control code");
            return;
        }
        if (auditLog.isEnabled()) {
            auditLog.write(AuditLogEventType.INCOMING_MESSAGE,
                "Received DL/T 645 frame, control=0x" + String.format("%02X", frame.getControl().getValue()));
        }
        PendingRequest pending;
        synchronized (requestChainLock) {
            pending = pendingRequest;
        }
        if (pending == null) {
            logger.warn("Received DL/T 645 frame but no request is pending");
            return;
        }
        if (!pending.matches(frame)) {
            logger.warn("Ignoring DL/T 645 frame that does not match pending request {}: control=0x{}",
                pending.requestId, String.format("%02X", frame.getControl().getValue()));
            return;
        }
        synchronized (requestChainLock) {
            if (pendingRequest == pending) {
                pendingRequest = null;
            }
        }
        pending.responseFuture.complete(frame);
    }

    /** Parse a configured hex field, allowing empty input as an all-zero field. */
    private static byte[] parseHexField(String hex, int expectedLength) {
        if (hex == null || hex.isEmpty()) {
            return new byte[expectedLength];
        }
        if (!hex.matches("[0-9A-Fa-f]{" + (expectedLength * 2) + "}")) {
            throw new IllegalArgumentException(
                "Configured DL/T 645 hex field must contain exactly " + (expectedLength * 2) + " hex digits");
        }
        var bytes = new byte[expectedLength];
        for (int i = 0; i < expectedLength; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    // ========== Ping ==========

    @Override
    protected CompletableFuture<PlcPingResponse> onPing(PlcPingRequest pingRequest) {
        if (meterAddress == null) {
            return CompletableFuture.failedFuture(
                new PlcRuntimeException("Ping requires connection meter-address or is not used on a shared bus"));
        }
        var diBytes = new byte[]{0x00, 0x01, 0x00, 0x00};
        var frame = buildReadDataFrame(diBytes, meterAddress);
        return executeThrottled(() ->
            sendRequest(frame).thenApply(response ->
                new DefaultPlcPingResponse(pingRequest, PlcResponseCode.OK)
            )
        );
    }

    // ========== Read ==========

    @Override
    protected CompletableFuture<PlcReadResponse> onRead(PlcReadRequest readRequest) {
        // Use the optimizer to split multi-tag requests into wildcard blocks / single tags.
        var optimizer = new Dlt645BlockOptimizer();

        List<PlcReadRequest> subRequests;
        try {
            subRequests = optimizer.processReadRequest(readRequest);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                new PlcRuntimeException("Failed to split read request", e));
        }

        // Chain through the handle() future (not the raw sub-future). allOf(raw)
        // races the Treiber-stack dependents: processReadResponses can run before
        // the last put, and a failed sub-future fails the whole read.
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        Map<PlcReadRequest, Dlt645BlockOptimizer.SubResponse<PlcReadResponse>> responses =
            new LinkedHashMap<>();
        for (PlcReadRequest subRequest : subRequests) {
            chain = chain.thenCompose(v -> executeSingleRead(subRequest)).handle((r, e) -> {
                if (e != null) {
                    logger.warn("Sub-request failed: {}", e.getMessage());
                    responses.put(subRequest, new Dlt645BlockOptimizer.SubResponse<>(null, false));
                } else {
                    responses.put(subRequest, new Dlt645BlockOptimizer.SubResponse<>(r, true));
                }
                return null;
            });
        }

        return chain.thenApply(v -> optimizer.processReadResponses(readRequest, responses));
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

        // Read address uses the point-to-point wildcard address and no data.
        var frame = new Dlt645Frame(WILDCARD_ADDRESS, ControlCode.READ_ADDRESS, (short) 0, new byte[0]);

        return sendRequest(frame).thenApply(response -> {
            PlcValue plcValue = null;
            PlcResponseCode responseCode;

            var controlCode = response.getControl();
            if (controlCode == ControlCode.READ_ADDRESS_RESPONSE) {
                // Response data = 6-byte address (LSB first), convert to MSB-first string
                plcValue = new PlcSTRING(formatAddressMsbFirst(response.getDataPlain()));
                responseCode = PlcResponseCode.OK;
            } else if (controlCode == ControlCode.READ_ADDRESS_ERROR) {
                logger.warn("Read-address error: {}", StaticHelper.describeError(response.getDataPlain()));
                responseCode = PlcResponseCode.REMOTE_ERROR;
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

        byte[] meter = resolveMeter(tag);
        var frame = buildReadDataFrame(tag.getDataIdentifier(), meter);

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
                return accumulateSubsequentData(request, tagName, tag, meter, response.getDataPlain(), 1, 0);
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
        DefaultPlcReadRequest request, String tagName, Dlt645Tag tag, byte[] meter,
        byte[] accumulatedData, int seqNumber, int frameCount) {

        if (seqNumber < 1 || seqNumber > 255 || frameCount >= MAX_SUBSEQUENT_FRAMES ||
            accumulatedData.length > MAX_ACCUMULATED_DATA_BYTES) {
            return CompletableFuture.completedFuture(
                completeReadError(request, tagName, PlcResponseCode.INTERNAL_ERROR));
        }

        // Build READ_SUBSEQUENT_DATA frame: DI(4, reversed) + SEQ(1)
        var di = tag.getDataIdentifier();
        var dataPlain = new byte[5];
        for (int i = 0; i < 4; i++) {
            dataPlain[i] = di[3 - i];
        }
        dataPlain[4] = (byte) seqNumber;

        var subFrame = new Dlt645Frame(meter, ControlCode.READ_SUBSEQUENT_DATA,
            (short) dataPlain.length, dataPlain);

        return sendRequest(subFrame).thenCompose(response -> {
            var controlCode = response.getControl();

            if (controlCode == ControlCode.READ_SUBSEQUENT_DATA_ERROR) {
                logger.warn("Read subsequent error for tag {}: {}",
                    tagName, StaticHelper.describeError(response.getDataPlain()));
                return CompletableFuture.completedFuture(
                    completeReadError(request, tagName, PlcResponseCode.REMOTE_ERROR));
            }

            var respData = response.getDataPlain();
            var newData = mergeSubsequentData(accumulatedData, respData);
            if (newData.length > MAX_ACCUMULATED_DATA_BYTES) {
                return CompletableFuture.completedFuture(
                    completeReadError(request, tagName, PlcResponseCode.INTERNAL_ERROR));
            }

            if (controlCode == ControlCode.READ_SUBSEQUENT_DATA_RESPONSE) {
                // No more data
                return CompletableFuture.completedFuture(
                    completeReadDataResponse(request, tagName, tag, newData));
            }

            if (controlCode == ControlCode.READ_SUBSEQUENT_DATA_RESPONSE_MORE) {
                return accumulateSubsequentData(request, tagName, tag, meter, newData,
                    seqNumber + 1, frameCount + 1);
            }

            logger.warn("Unexpected control code in subsequent read: 0x{}",
                String.format("%02X", controlCode.getValue()));
            return CompletableFuture.completedFuture(
                completeReadError(request, tagName, PlcResponseCode.INTERNAL_ERROR));
        });
    }

    /**
     * Merge subsequent frame data into accumulated data.
     * Initial response: DI(4) + value_data
     * Subsequent response: DI(4) + value_data + SEQ(1)
     * We keep the DI prefix from the first frame and append value data from subsequent frames.
     */
    private byte[] mergeSubsequentData(byte[] accumulated, byte[] subsequent) {
        if (subsequent == null || subsequent.length < 5) {
            return accumulated;
        }
        var baos = new ByteArrayOutputStream();
        baos.write(accumulated, 0, accumulated.length);
        baos.write(subsequent, 4, subsequent.length - 5);
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

    private PlcReadResponse completeReadError(DefaultPlcReadRequest request, String tagName,
                                              PlcResponseCode responseCode) {
        return new DefaultPlcReadResponse(request,
            Collections.singletonMap(tagName, new DefaultPlcResponseItem<>(responseCode, null)));
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

        var frame = buildWriteDataFrame(tag.getDataIdentifier(), serializeValue(tag, value), resolveMeter(tag));

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
            case BROADCAST_TIME_SYNC:
                return executeBroadcastTimeSync(request, tagName, value);
            case FREEZE:
                return executeFreeze(request, tagName, cmdTag, value);
            case CHANGE_BAUD_RATE:
                return executeSimpleCommand(request, tagName, resolveMeter(cmdTag),
                    ControlCode.CHANGE_BAUD_RATE, ControlCode.CHANGE_BAUD_RATE_RESPONSE,
                    ControlCode.CHANGE_BAUD_RATE_ERROR,
                    buildBaudRateData(value));
            case CHANGE_PASSWORD:
                return executeSimpleCommand(request, tagName, resolveMeter(cmdTag),
                    ControlCode.CHANGE_PASSWORD, ControlCode.CHANGE_PASSWORD_RESPONSE,
                    ControlCode.CHANGE_PASSWORD_ERROR,
                    buildChangePasswordData(value));
            case CLEAR_MAX_DEMAND:
                return executeSimpleCommand(request, tagName, resolveMeter(cmdTag),
                    ControlCode.CLEAR_MAX_DEMAND, ControlCode.CLEAR_MAX_DEMAND_RESPONSE,
                    ControlCode.CLEAR_MAX_DEMAND_ERROR,
                    buildAuthOnlyData());
            case CLEAR_METER:
                return executeSimpleCommand(request, tagName, resolveMeter(cmdTag),
                    ControlCode.CLEAR_METER, ControlCode.CLEAR_METER_RESPONSE, ControlCode.CLEAR_METER_ERROR,
                    buildAuthOnlyData());
            case CLEAR_EVENT:
                return executeSimpleCommand(request, tagName, resolveMeter(cmdTag),
                    ControlCode.CLEAR_EVENT, ControlCode.CLEAR_EVENT_RESPONSE, ControlCode.CLEAR_EVENT_ERROR,
                    buildClearEventData(value));
            default:
                return CompletableFuture.failedFuture(
                    new PlcRuntimeException("Command '" + cmdType.getKeyword() + "' is not a write command"));
        }
    }

    /**
     * Execute WRITE_ADDRESS (0x15): write communication address.
     * Per DL/T 645-2007: uses point-to-point wildcard address (0xAAx6), data = new address.
     */
    private CompletableFuture<PlcWriteResponse> executeWriteAddress(
        DefaultPlcWriteRequest request, String tagName, PlcValue value) {

        var newAddress = Dlt645DriverContext.parseMeterAddress(value.getString());
        var frame = new Dlt645Frame(WILDCARD_ADDRESS, ControlCode.WRITE_ADDRESS,
            (short) newAddress.length, newAddress);

        return sendRequest(frame).thenApply(response -> {
            PlcResponseCode responseCode;
            var controlCode = response.getControl();
            if (controlCode == ControlCode.WRITE_ADDRESS_RESPONSE) {
                responseCode = PlcResponseCode.OK;
            } else if (controlCode == ControlCode.WRITE_ADDRESS_ERROR) {
                logger.warn("Write-address error: {}", StaticHelper.describeError(response.getDataPlain()));
                responseCode = PlcResponseCode.REMOTE_ERROR;
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
     * Freeze (0x16). Point-to-point waits for 96H/D6H. Broadcast address
     * {@code 99…99H} does not require a slave response (DL/T 645-2007 §7.7).
     */
    private CompletableFuture<PlcWriteResponse> executeFreeze(
        DefaultPlcWriteRequest request, String tagName, Dlt645CommandTag cmdTag, PlcValue value) {
        byte[] data = buildFreezeData(value);
        byte[] meter = resolveMeter(cmdTag);
        if (Dlt645DriverContext.isBroadcastAddress(meter)) {
            var frame = new Dlt645Frame(BROADCAST_ADDRESS, ControlCode.FREEZE_DATA,
                (short) data.length, data);
            return sendOneWay(frame).thenApply(ignored ->
                new DefaultPlcWriteResponse(request,
                    Collections.singletonMap(tagName, PlcResponseCode.OK)));
        }
        return executeSimpleCommand(request, tagName, meter,
            ControlCode.FREEZE_DATA, ControlCode.FREEZE_DATA_RESPONSE, ControlCode.FREEZE_DATA_ERROR,
            data);
    }

    private CompletableFuture<PlcWriteResponse> executeBroadcastTimeSync(
        DefaultPlcWriteRequest request, String tagName, PlcValue value) {
        byte[] data = buildBroadcastTimeData(value);
        var frame = new Dlt645Frame(BROADCAST_ADDRESS, ControlCode.BROADCAST_TIME_SYNC,
            (short) data.length, data);
        return sendOneWay(frame).thenApply(ignored ->
            new DefaultPlcWriteResponse(request,
                Collections.singletonMap(tagName, PlcResponseCode.OK)));
    }

    /**
     * Generic execution for admin commands (0x16-0x1B) that share the same request/response pattern.
     */
    private CompletableFuture<PlcWriteResponse> executeSimpleCommand(
        DefaultPlcWriteRequest request, String tagName, byte[] meter,
        ControlCode reqCode, ControlCode respCode, ControlCode errCode,
        byte[] dataPlain) {

        var frame = new Dlt645Frame(meter, reqCode, (short) dataPlain.length, dataPlain);

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
    private Dlt645Frame buildReadDataFrame(byte[] dataIdentifier, byte[] meter) {
        var dataPlain = new byte[4];
        for (int i = 0; i < 4; i++) {
            dataPlain[i] = dataIdentifier[3 - i];
        }
        return new Dlt645Frame(meter, ControlCode.READ_DATA,
            (short) dataPlain.length, dataPlain);
    }

    /**
     * Build WRITE_DATA (0x14) frame: DI(4, reversed) + PA(4) + P0(4) + payload.
     */
    private Dlt645Frame buildWriteDataFrame(byte[] dataIdentifier, byte[] payload, byte[] meter) {
        if (payload.length > 38) {
            throw new IllegalArgumentException(
                "DL/T 645 write payload exceeds 38 bytes (50-byte DATA field limit)");
        }
        var dataPlain = new byte[4 + 4 + 4 + payload.length];
        for (int i = 0; i < 4; i++) {
            dataPlain[i] = dataIdentifier[3 - i];
        }
        System.arraycopy(password, 0, dataPlain, 4, 4);
        System.arraycopy(operatorCode, 0, dataPlain, 8, 4);
        System.arraycopy(payload, 0, dataPlain, 12, payload.length);
        return new Dlt645Frame(meter, ControlCode.WRITE_DATA,
            (short) dataPlain.length, dataPlain);
    }

    /**
     * Tag meter wins; otherwise the connection default. Shared-bus clients put the
     * address on the tag and leave the connection without a default.
     */
    private byte[] resolveMeter(PlcTag tag) {
        if (tag instanceof Dlt645Tag dltTag && dltTag.getMeterAddressBytes() != null) {
            return dltTag.getMeterAddressBytes();
        }
        if (tag instanceof Dlt645CommandTag cmdTag && cmdTag.getMeterAddressBytes() != null) {
            return cmdTag.getMeterAddressBytes();
        }
        if (meterAddress != null) {
            return meterAddress;
        }
        throw new PlcRuntimeException(
            "DL/T 645 meter address is required on the tag or as connection meter-address");
    }

    // ========== Command data builders ==========

    /**
     * Build freeze command data (0x16).
     * Value format: eight BCD digits MMDDhhmm. 99999999 requests an immediate freeze.
     */
    private byte[] buildFreezeData(PlcValue value) {
        byte[] displayed = parseBcdDigits(value.getString(), 8, "freeze time MMDDhhmm");
        return reverse(displayed);
    }

    /**
     * Build baud rate change data (0x17).
     * Value: one-hot baud feature byte: 02/04/08/10/20/40 for 600..19200bps.
     */
    private byte[] buildBaudRateData(PlcValue value) {
        byte[] rate = parseHexExact(value.getString(), 1, "baud-rate feature byte");
        int code = rate[0] & 0xFF;
        if (code != 0x02 && code != 0x04 && code != 0x08 && code != 0x10 && code != 0x20 && code != 0x40) {
            throw new IllegalArgumentException("Baud-rate feature byte must be one of 02, 04, 08, 10, 20, 40");
        }
        return rate;
    }

    /**
     * Build change password data (0x18).
     * Value format: "DI3DI2DI1DI0:old_password:new_password".
     */
    private byte[] buildChangePasswordData(PlcValue value) {
        var parts = value.getString().split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                "Change-password value must be DI3DI2DI1DI0:old_password:new_password");
        }
        byte[] di = reverse(parseHexExact(parts[0], 4, "password DI"));
        byte[] oldPwd = parseHexExact(parts[1], 4, "old password");
        byte[] newPwd = parseHexExact(parts[2], 4, "new password");
        var data = new byte[12];
        System.arraycopy(di, 0, data, 0, 4);
        System.arraycopy(oldPwd, 0, data, 4, 4);
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

    private byte[] buildClearEventData(PlcValue value) {
        byte[] di = parseHexExact(value.getString(), 4, "event DI");
        if ((di[3] & 0xFF) != 0xFF) {
            throw new IllegalArgumentException("Event-clear DI must end in FF, or be FFFFFFFF for all events");
        }
        var data = new byte[12];
        System.arraycopy(password, 0, data, 0, 4);
        System.arraycopy(operatorCode, 0, data, 4, 4);
        byte[] wireDi = reverse(di);
        System.arraycopy(wireDi, 0, data, 8, 4);
        return data;
    }

    private byte[] buildBroadcastTimeData(PlcValue value) {
        String input = value.getString().trim();
        LocalDateTime dateTime;
        try {
            if (input.matches("[0-9]{12}")) {
                int year = 2000 + Integer.parseInt(input.substring(0, 2));
                dateTime = LocalDateTime.of(year,
                    Integer.parseInt(input.substring(2, 4)), Integer.parseInt(input.substring(4, 6)),
                    Integer.parseInt(input.substring(6, 8)), Integer.parseInt(input.substring(8, 10)),
                    Integer.parseInt(input.substring(10, 12)));
            } else {
                dateTime = LocalDateTime.parse(input, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
        } catch (DateTimeException | NumberFormatException e) {
            throw new IllegalArgumentException(
                "Broadcast time must be YYMMDDhhmmss or yyyy-MM-dd HH:mm:ss", e);
        }
        return new byte[]{
            toBcd(dateTime.getSecond()), toBcd(dateTime.getMinute()), toBcd(dateTime.getHour()),
            toBcd(dateTime.getDayOfMonth()), toBcd(dateTime.getMonthValue()),
            toBcd(dateTime.getYear() % 100)
        };
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
            throw new IllegalArgumentException(
                "DI mismatch in response: expected " + expectedDi + ", got " + actualDi);
        }

        var diHex = String.format("%02X%02X%02X%02X",
            diBytes[0] & 0xFF, diBytes[1] & 0xFF, diBytes[2] & 0xFF, diBytes[3] & 0xFF);

        var valueData = new byte[dataPlain.length - 4];
        System.arraycopy(dataPlain, 4, valueData, 0, valueData.length);

        // Wildcard DI: parse concatenated sub-values into a PlcStruct
        if (DataIdentifiers.containsWildcard(diHex)) {
            return parseWildcardResponseData(diHex, valueData, tag);
        }

        return decodeValue(tag, diHex, valueData);
    }

    /**
     * Parse wildcard response: concatenated BCD values for all sub-items.
     * <p>
     * DL/T 645-2007 wildcard response format: values are concatenated in ascending
     * order of the wildcard byte, each value's length is determined by DataIdentifiers.
     *
     * @param wildcardDiHex wildcard DI hex string (e.g. "0201FF00")
     * @param valueData     concatenated BCD value bytes (LSB first per sub-item)
     * @return PlcStruct mapping each sub-item's DI hex to its semantically decoded value
     */
    private PlcValue parseWildcardResponseData(String wildcardDiHex, byte[] valueData) {
        return parseWildcardResponseData(wildcardDiHex, valueData, null);
    }

    private PlcValue parseWildcardResponseData(String wildcardDiHex, byte[] valueData, Dlt645Tag requestTag) {
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
            Dlt645Tag subTag = resolveWildcardSubTag(requestTag, subItem.getDi());
            map.put(subItem.getDi().toUpperCase(), decodeValue(subTag, subItem.getDi(), subData));
            offset += subItem.getDataLength();
        }

        return new PlcStruct(map);
    }

    /**
     * Prefer the original tag's requested type when the optimizer merged a wildcard;
     * fall back to the registry default for unmapped sub-items.
     */
    private static Dlt645Tag resolveWildcardSubTag(Dlt645Tag requestTag, String subItemDi) {
        if (requestTag instanceof Dlt645WildcardTag wildcardTag) {
            for (var mapping : wildcardTag.getMappings()) {
                if (mapping.subItemDiHex().equalsIgnoreCase(subItemDi) && mapping.requestedType() != null) {
                    return Dlt645Tag.of(subItemDi + ":" + mapping.requestedType().name());
                }
            }
        }
        return Dlt645Tag.of(subItemDi);
    }

    /** Format a six-byte low-byte-first address as an MSB-first string. */
    private static String formatAddressMsbFirst(byte[] addrBytes) {
        if (addrBytes == null || addrBytes.length < 6) {
            return "";
        }
        var sb = new StringBuilder(12);
        for (int i = 5; i >= 0; i--) {
            sb.append(String.format("%02X", addrBytes[i] & 0xFF));
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
    private byte[] serializeValue(Dlt645Tag tag, PlcValue value) {
        if (tag.getPlcValueType() == org.apache.plc4x.java.api.types.PlcValueType.RAW_BYTE_ARRAY) {
            return Arrays.copyOf(value.getRaw(), value.getRaw().length);
        }
        var descriptor = DataIdentifiers.lookup(tag.getDataIdentifier());
        if (descriptor == null || (descriptor.getFormat() != DataIdentifiers.DataFormat.BCD &&
            descriptor.getFormat() != DataIdentifiers.DataFormat.SIGNED_BCD)) {
            throw new IllegalArgumentException(
                "DI " + tag.getAddressString().substring(0, 8) + " requires RAW_BYTE_ARRAY writes");
        }
        java.math.BigDecimal numeric;
        try {
            numeric = new java.math.BigDecimal(value.getString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("DL/T 645 numeric write value is not a decimal number", e);
        }
        boolean negative = numeric.signum() < 0;
        if (negative && descriptor.getFormat() != DataIdentifiers.DataFormat.SIGNED_BCD) {
            throw new IllegalArgumentException("DI does not permit a negative BCD value");
        }
        java.math.BigInteger scaled;
        try {
            scaled = numeric.abs().movePointRight(descriptor.getDecimalPlaces()).toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Value has more than " + descriptor.getDecimalPlaces()
                + " decimal places", e);
        }
        String digits = scaled.toString();
        int capacity = descriptor.getDataLength() * 2;
        if (digits.length() > capacity) {
            throw new IllegalArgumentException("Value does not fit in " + descriptor.getDataLength() + " BCD bytes");
        }
        digits = "0".repeat(capacity - digits.length()) + digits;
        byte[] encoded = new byte[descriptor.getDataLength()];
        for (int i = 0; i < encoded.length; i++) {
            int src = capacity - (i + 1) * 2;
            encoded[i] = (byte) (((digits.charAt(src) - '0') << 4) | (digits.charAt(src + 1) - '0'));
        }
        if (negative) {
            encoded[encoded.length - 1] |= (byte) 0x80;
        }
        return encoded;
    }

    private PlcValue decodeValue(Dlt645Tag tag, String diHex, byte[] valueData) {
        var descriptor = DataIdentifiers.lookup(diHex);
        var requestedType = tag.getPlcValueType();
        if (requestedType == org.apache.plc4x.java.api.types.PlcValueType.RAW_BYTE_ARRAY) {
            return new PlcRawByteArray(Arrays.copyOf(valueData, valueData.length));
        }
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown DI " + diHex + "; use :RAW_BYTE_ARRAY explicitly");
        }
        if (valueData.length != descriptor.getDataLength()) {
            throw new IllegalArgumentException("DI " + diHex + " expects " + descriptor.getDataLength()
                + " data bytes, got " + valueData.length);
        }
        return switch (descriptor.getFormat()) {
            case DATE_TIME -> new PlcDATE_AND_TIME(parseDateTime(valueData));
            case DATE -> new PlcDATE(parseDate(valueData));
            case TIME -> new PlcTIME_OF_DAY(parseTime(valueData));
            case RAW -> new PlcRawByteArray(Arrays.copyOf(valueData, valueData.length));
            case BCD, SIGNED_BCD -> numericPlcValue(requestedType,
                DataIdentifiers.decodeValue(diHex, valueData));
        };
    }

    private static PlcValue numericPlcValue(org.apache.plc4x.java.api.types.PlcValueType type, double value) {
        return switch (type) {
            case REAL -> new PlcREAL((float) value);
            case LREAL -> new PlcLREAL(value);
            case SINT -> new PlcSINT((byte) value);
            case USINT -> new PlcUSINT((short) value);
            case INT -> new PlcINT((short) value);
            case UINT -> new PlcUINT((int) value);
            case DINT -> new PlcDINT((int) value);
            case UDINT -> new PlcUDINT((long) value);
            case LINT -> new PlcLINT((long) value);
            case STRING -> new PlcSTRING(Double.toString(value));
            default -> throw new IllegalArgumentException("Unsupported numeric result type " + type);
        };
    }

    private static LocalDateTime parseDateTime(byte[] data) {
        return LocalDateTime.of(2000 + bcd(data[6]), bcd(data[5]), bcd(data[4]),
            bcd(data[2]), bcd(data[1]), bcd(data[0]));
    }

    private static java.time.LocalDate parseDate(byte[] data) {
        return java.time.LocalDate.of(2000 + bcd(data[3]), bcd(data[2]), bcd(data[1]));
    }

    private static java.time.LocalTime parseTime(byte[] data) {
        return java.time.LocalTime.of(bcd(data[2]), bcd(data[1]), bcd(data[0]));
    }

    private static int bcd(byte value) {
        int high = (value >>> 4) & 0x0F;
        int low = value & 0x0F;
        if (high > 9 || low > 9) {
            throw new IllegalArgumentException("Invalid BCD byte 0x" + String.format("%02X", value & 0xFF));
        }
        return high * 10 + low;
    }

    /**
     * Parse hex string value to raw bytes.
     */
    private static byte[] parseHexExact(String hex, int expectedBytes, String fieldName) {
        String clean = hex == null ? "" : hex.trim();
        if (!clean.matches("[0-9A-Fa-f]{" + (expectedBytes * 2) + "}")) {
            throw new IllegalArgumentException(
                fieldName + " must contain exactly " + (expectedBytes * 2) + " hexadecimal digits");
        }
        var bytes = new byte[expectedBytes];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static byte[] parseBcdDigits(String value, int expectedDigits, String fieldName) {
        String digits = value == null ? "" : value.trim();
        if (!digits.matches("[0-9]{" + expectedDigits + "}")) {
            throw new IllegalArgumentException(fieldName + " must contain exactly " + expectedDigits + " digits");
        }
        byte[] result = new byte[expectedDigits / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) ((digits.charAt(i * 2) - '0') << 4 |
                (digits.charAt(i * 2 + 1) - '0'));
        }
        return result;
    }

    private static byte[] reverse(byte[] value) {
        byte[] result = Arrays.copyOf(value, value.length);
        for (int i = 0; i < result.length / 2; i++) {
            byte current = result[i];
            result[i] = result[result.length - 1 - i];
            result[result.length - 1 - i] = current;
        }
        return result;
    }

    private static byte toBcd(int value) {
        return (byte) (((value / 10) << 4) | (value % 10));
    }

    // ========== Transport send ==========

    /**
     * Send a frame and wait for its response, chaining so only one transaction
     * is in flight (DL/T 645 over serial has no transaction id).
     */
    private CompletableFuture<Dlt645Frame> sendRequest(Dlt645Frame frame) {
        CompletableFuture<Dlt645Frame> responseFuture = new CompletableFuture<>();
        activeRequests.add(responseFuture);
        responseFuture.whenComplete((result, error) -> activeRequests.remove(responseFuture));

        long requestId;
        CompletableFuture<?> previous;
        synchronized (requestChainLock) {
            requestId = ++requestIdGenerator;
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

    private CompletableFuture<Void> sendOneWay(Dlt645Frame frame) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        activeRequests.add(completion);
        completion.whenComplete((result, error) -> activeRequests.remove(completion));

        CompletableFuture<?> previous;
        synchronized (requestChainLock) {
            previous = requestTail;
            requestTail = completion.handle((result, error) -> null);
        }
        Runnable dispatch = () -> {
            if (completion.isDone()) {
                return;
            }
            try {
                messageCodec.send(frame);
                completion.complete(null);
            } catch (MessageCodecException | RuntimeException e) {
                completion.completeExceptionally(new PlcRuntimeException("Failed to send one-way request", e));
            }
        };
        if (previous.isDone()) {
            dispatch.run();
        } else {
            previous.whenCompleteAsync((ignored, ignoredError) -> dispatch.run());
        }
        return completion;
    }

    private void dispatchRequest(Dlt645Frame frame, long requestId, CompletableFuture<Dlt645Frame> responseFuture) {
        if (responseFuture.isDone()) {
            return;
        }
        // Drop bytes left over from a timed-out or unmatched previous frame before
        // this request becomes eligible to match. DL/T 645 has no transaction id.
        discardStaleInput("dispatching request " + requestId);
        PendingRequest pending = new PendingRequest(requestId, frame, responseFuture);
        try {
            synchronized (requestChainLock) {
                if (pendingRequest != null && !pendingRequest.responseFuture.isDone()) {
                    responseFuture.completeExceptionally(
                        new PlcRuntimeException("Another DL/T 645 request is already in flight"));
                    return;
                }
                pendingRequest = pending;
            }
            responseFuture.whenComplete((result, error) -> {
                synchronized (requestChainLock) {
                    if (pendingRequest == pending) {
                        pendingRequest = null;
                    }
                }
                if (error instanceof TimeoutException) {
                    discardStaleInput("request " + requestId + " timed out");
                }
            });
            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.OUTGOING_MESSAGE,
                    "Sending DL/T 645 frame, control=0x" + String.format("%02X", frame.getControl().getValue()));
            }
            messageCodec.send(frame);
            responseFuture.orTimeout(getConfiguration().getRequestTimeout(), TimeUnit.MILLISECONDS);
        } catch (MessageCodecException e) {
            responseFuture.completeExceptionally(new PlcRuntimeException("Failed to send request", e));
        } catch (RuntimeException e) {
            responseFuture.completeExceptionally(e);
        }
    }

    private void discardStaleInput(String reason) {
        if (messageCodec != null) {
            messageCodec.discardBufferedInput(reason);
        }
    }

    private static final class PendingRequest {
        private final long requestId;
        private final Dlt645Frame request;
        private final CompletableFuture<Dlt645Frame> responseFuture;

        private PendingRequest(long requestId, Dlt645Frame request,
                               CompletableFuture<Dlt645Frame> responseFuture) {
            this.requestId = requestId;
            this.request = request;
            this.responseFuture = responseFuture;
        }

        private boolean matches(Dlt645Frame response) {
            if (response == null || response.getControl() == null || !matchesControl(response.getControl())) {
                return false;
            }
            if (!matchesAddress(response)) {
                return false;
            }
            byte[] data = response.getDataPlain();
            if (isError(response.getControl())) {
                return data != null && data.length == 1;
            }
            return matchesSuccessData(response.getControl(), data == null ? new byte[0] : data);
        }

        private boolean matchesControl(ControlCode responseControl) {
            return switch (request.getControl()) {
                case READ_DATA -> responseControl == ControlCode.READ_DATA_RESPONSE ||
                    responseControl == ControlCode.READ_DATA_RESPONSE_MORE ||
                    responseControl == ControlCode.READ_DATA_ERROR;
                case READ_SUBSEQUENT_DATA -> responseControl == ControlCode.READ_SUBSEQUENT_DATA_RESPONSE ||
                    responseControl == ControlCode.READ_SUBSEQUENT_DATA_RESPONSE_MORE ||
                    responseControl == ControlCode.READ_SUBSEQUENT_DATA_ERROR;
                case READ_ADDRESS -> responseControl == ControlCode.READ_ADDRESS_RESPONSE ||
                    responseControl == ControlCode.READ_ADDRESS_ERROR;
                case WRITE_DATA -> responseControl == ControlCode.WRITE_DATA_RESPONSE ||
                    responseControl == ControlCode.WRITE_DATA_ERROR;
                case WRITE_ADDRESS -> responseControl == ControlCode.WRITE_ADDRESS_RESPONSE ||
                    responseControl == ControlCode.WRITE_ADDRESS_ERROR;
                case FREEZE_DATA -> responseControl == ControlCode.FREEZE_DATA_RESPONSE ||
                    responseControl == ControlCode.FREEZE_DATA_ERROR;
                case CHANGE_BAUD_RATE -> responseControl == ControlCode.CHANGE_BAUD_RATE_RESPONSE ||
                    responseControl == ControlCode.CHANGE_BAUD_RATE_ERROR;
                case CHANGE_PASSWORD -> responseControl == ControlCode.CHANGE_PASSWORD_RESPONSE ||
                    responseControl == ControlCode.CHANGE_PASSWORD_ERROR;
                case CLEAR_MAX_DEMAND -> responseControl == ControlCode.CLEAR_MAX_DEMAND_RESPONSE ||
                    responseControl == ControlCode.CLEAR_MAX_DEMAND_ERROR;
                case CLEAR_METER -> responseControl == ControlCode.CLEAR_METER_RESPONSE ||
                    responseControl == ControlCode.CLEAR_METER_ERROR;
                case CLEAR_EVENT -> responseControl == ControlCode.CLEAR_EVENT_RESPONSE ||
                    responseControl == ControlCode.CLEAR_EVENT_ERROR;
                default -> false;
            };
        }

        private boolean matchesAddress(Dlt645Frame response) {
            if (request.getControl() == ControlCode.READ_ADDRESS) {
                return response.getControl() != ControlCode.READ_ADDRESS_RESPONSE ||
                    Arrays.equals(response.getAddress(), response.getDataPlain());
            }
            if (request.getControl() == ControlCode.WRITE_ADDRESS) {
                return response.getControl() != ControlCode.WRITE_ADDRESS_RESPONSE ||
                    Arrays.equals(response.getAddress(), request.getDataPlain());
            }
            return Arrays.equals(request.getAddress(), response.getAddress());
        }

        private boolean matchesSuccessData(ControlCode responseControl, byte[] data) {
            return switch (responseControl) {
                case READ_DATA_RESPONSE, READ_DATA_RESPONSE_MORE ->
                    data.length >= 4 && startsWith(data, request.getDataPlain(), 4);
                case READ_SUBSEQUENT_DATA_RESPONSE, READ_SUBSEQUENT_DATA_RESPONSE_MORE ->
                    data.length >= 5 && startsWith(data, request.getDataPlain(), 4) &&
                        data[data.length - 1] == request.getDataPlain()[4];
                case READ_ADDRESS_RESPONSE -> data.length == 6;
                case CHANGE_BAUD_RATE_RESPONSE -> data.length == 1 && data[0] == request.getDataPlain()[0];
                case CHANGE_PASSWORD_RESPONSE -> data.length == 4 &&
                    Arrays.equals(data, Arrays.copyOfRange(request.getDataPlain(), 8, 12));
                default -> data.length == 0;
            };
        }

        private static boolean startsWith(byte[] actual, byte[] expected, int length) {
            if (actual.length < length || expected == null || expected.length < length) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (actual[i] != expected[i]) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isError(ControlCode control) {
            return (control.getValue() & 0x40) != 0;
        }
    }
}
