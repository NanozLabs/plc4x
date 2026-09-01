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
package org.apache.plc4x.java.cjt188.protocol;

import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.api.messages.PlcPingRequest;
import org.apache.plc4x.java.api.messages.PlcPingResponse;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.ConnectionStateChangeType;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.types.PlcValueType;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.cjt188.config.Cjt188Configuration;
import org.apache.plc4x.java.cjt188.context.Cjt188DriverContext;
import org.apache.plc4x.java.cjt188.readwrite.Cjt188Frame;
import org.apache.plc4x.java.cjt188.readwrite.ControlCode;
import org.apache.plc4x.java.cjt188.readwrite.utils.DataIdentifiers;
import org.apache.plc4x.java.cjt188.readwrite.utils.StaticHelper;
import org.apache.plc4x.java.cjt188.tag.Cjt188CommandTag;
import org.apache.plc4x.java.cjt188.tag.Cjt188CommandTag.CommandType;
import org.apache.plc4x.java.cjt188.tag.Cjt188Tag;
import org.apache.plc4x.java.cjt188.tag.Cjt188TagHandler;
import org.apache.plc4x.java.cjt188.tag.Cjt188WildcardTag;
import org.apache.plc4x.java.spi.drivers.exceptions.MessageCodecException;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcPingResponse;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcReadRequest;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcReadResponse;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcWriteRequest;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcWriteResponse;
import org.apache.plc4x.java.spi.drivers.messages.items.DefaultPlcResponseItem;
import org.apache.plc4x.java.spi.drivers.messages.items.PlcResponseItem;
import org.apache.plc4x.java.spi.drivers.tags.PlcTagHandler;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.values.DefaultPlcValueHandler;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CJ/T 188-2004 Connection (Client mode).
 * <p>
 * Supports:
 * <ul>
 *   <li>Read data (0x01) with automatic subsequent-frame accumulation (D5=1)</li>
 *   <li>Write data (0x04)</li>
 *   <li>Read communication address (0x03) via {@code cmd:read-address}</li>
 *   <li>Write communication address (0x15) via {@code cmd:write-address}</li>
 *   <li>Write electromechanical sync data (0x16) via {@code cmd:write-sync}</li>
 *   <li>One serial/TCP connection for several meters on the same RS-485 bus;
 *       per-tag {@code meter-address} overrides the connection default</li>
 * </ul>
 */
public class Cjt188Connection extends PollingSubscriptionConnectionBase<Cjt188Configuration> {

    private static final Logger logger = LoggerFactory.getLogger(Cjt188Connection.class);
    private static final int MAX_SUBSEQUENT_FRAMES = 255;
    private static final int MAX_ACCUMULATED_DATA_BYTES = 50_000;
    private static final int DI_LENGTH = 2;
    private static final int HEADER_DI_SER = 3;

    private static final byte[] WILDCARD_ADDRESS = Cjt188DriverContext.WILDCARD_ADDRESS;

    private Cjt188MessageCodec messageCodec;

    private final Set<CompletableFuture<?>> activeRequests = ConcurrentHashMap.newKeySet();
    private final Object requestChainLock = new Object();
    private CompletableFuture<?> requestTail = CompletableFuture.completedFuture(null);
    private PendingRequest pendingRequest;
    private long requestIdGenerator = 0;
    private final AtomicInteger sequence = new AtomicInteger(0);

    private byte[] meterAddress;

    public Cjt188Connection(Cjt188Configuration configuration, TransportInstance<?> transportInstance,
                            AuditLog auditLog) {
        super(configuration, transportInstance, auditLog);
    }

    @Override
    protected void onConnect() throws PlcConnectionException {
        String configured = getConfiguration().getMeterAddress();
        if (configured != null && !configured.isBlank()) {
            try {
                this.meterAddress = Cjt188DriverContext.parseMeterAddress(
                    configured, getConfiguration().getMeterType());
            } catch (IllegalArgumentException e) {
                throw new PlcConnectionException("Invalid meter-address / meter-type", e);
            }
        } else {
            this.meterAddress = null;
        }

        messageCodec = new Cjt188MessageCodec(transportInstance, this::handleIncomingMessage);

        startReceiving(() -> {
            try {
                messageCodec.processIncomingData();
            } catch (MessageCodecException e) {
                logger.error("Error processing incoming CJ/T 188 data", e);
            }
        });

        logger.info("CJ/T 188-2004 connection established");
        if (auditLog.isEnabled()) {
            auditLog.write(AuditLogEventType.CONNECT, "CJ/T 188-2004 connection established");
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
            messageCodec = null;
        }
        activeRequests.forEach(pending ->
            pending.completeExceptionally(new PlcRuntimeException("Connection closed")));
        activeRequests.clear();
        synchronized (requestChainLock) {
            pendingRequest = null;
        }
        super.close();
        logger.info("CJ/T 188-2004 connection closed");
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
        return new Cjt188TagHandler();
    }

    @Override
    protected PlcValueHandler getValueHandler() {
        return new DefaultPlcValueHandler();
    }

    @Override
    protected int getMaxConcurrentRequests() {
        return 1;
    }

    private void handleIncomingMessage(Cjt188Frame frame) {
        if (frame == null || frame.getControl() == null) {
            logger.warn("Ignoring CJ/T 188 frame with an unknown control code");
            return;
        }
        if (auditLog.isEnabled()) {
            auditLog.write(AuditLogEventType.INCOMING_MESSAGE,
                "Received CJ/T 188 frame, control=0x" + String.format("%02X", frame.getControl().getValue()));
        }
        PendingRequest pending;
        synchronized (requestChainLock) {
            pending = pendingRequest;
        }
        if (pending == null) {
            logger.warn("Received CJ/T 188 frame but no request is pending");
            return;
        }
        if (!pending.matches(frame)) {
            logger.warn("Ignoring CJ/T 188 frame that does not match pending request {}: control=0x{}",
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

    private byte nextSer() {
        return (byte) (sequence.getAndUpdate(v -> (v + 1) & 0xFF) & 0xFF);
    }

    // ========== Ping ==========

    @Override
    protected CompletableFuture<PlcPingResponse> onPing(PlcPingRequest pingRequest) {
        if (meterAddress == null) {
            return CompletableFuture.failedFuture(new PlcRuntimeException(
                "CJ/T 188 ping requires a connection meter-address, or use a read tag that names the meter"));
        }
        var frame = buildReadDataFrame(new byte[]{(byte) 0x90, 0x1F}, meterAddress);
        return sendRequest(frame).thenApply(response ->
            new DefaultPlcPingResponse(pingRequest, PlcResponseCode.OK));
    }

    // ========== Read ==========

    @Override
    protected CompletableFuture<PlcReadResponse> onRead(PlcReadRequest readRequest) {
        var request = (DefaultPlcReadRequest) readRequest;
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        Map<String, PlcResponseItem<PlcValue>> items = new LinkedHashMap<>();
        for (String tagName : request.getTagNames()) {
            chain = chain.thenCompose(v -> executeSingleRead(singleRead(request, tagName)).handle((r, e) -> {
                if (e != null) {
                    logger.warn("Sub-request failed: {}", e.getMessage());
                    PlcResponseCode code = e instanceof MissingMeterAddressException
                        || e.getCause() instanceof MissingMeterAddressException
                        ? PlcResponseCode.INVALID_ADDRESS
                        : PlcResponseCode.INTERNAL_ERROR;
                    items.put(tagName, new DefaultPlcResponseItem<>(code, null));
                } else {
                    items.put(tagName, new DefaultPlcResponseItem<>(
                        r.getResponseCode(tagName), r.getPlcValue(tagName)));
                }
                return null;
            }));
        }
        return chain.thenApply(v -> new DefaultPlcReadResponse(request, items));
    }

    private DefaultPlcReadRequest singleRead(DefaultPlcReadRequest parent, String tagName) {
        var tags = new LinkedHashMap<String, org.apache.plc4x.java.spi.drivers.messages.items.PlcTagItem<PlcTag>>();
        tags.put(tagName, parent.getTagItem(tagName));
        return new DefaultPlcReadRequest(parent.getReader(), tags);
    }

    private CompletableFuture<PlcReadResponse> executeSingleRead(PlcReadRequest readRequest) {
        var request = (DefaultPlcReadRequest) readRequest;
        if (request.getTagNames().size() != 1) {
            return CompletableFuture.failedFuture(
                new PlcRuntimeException("CJ/T 188-2004 only supports single tag requests"));
        }
        var tagName = request.getTagNames().iterator().next();
        PlcTag tag = request.getTag(tagName);
        if (tag instanceof Cjt188CommandTag cmdTag) {
            if (cmdTag.getCommandType() != CommandType.READ_ADDRESS) {
                return CompletableFuture.failedFuture(
                    new PlcRuntimeException("Only cmd:read-address is supported in read requests"));
            }
            return executeReadAddress(request, tagName);
        }
        return executeReadData(request, tagName, (Cjt188Tag) tag);
    }

    private CompletableFuture<PlcReadResponse> executeReadAddress(
        DefaultPlcReadRequest request, String tagName) {

        // CJ/T 188-2004 读地址 (03H): L=0, no DATA / no SER. SER only follows a DI.
        var frame = new Cjt188Frame(WILDCARD_ADDRESS, ControlCode.READ_ADDRESS, (short) 0, new byte[0]);

        return sendRequest(frame).thenApply(response -> {
            PlcValue plcValue = null;
            PlcResponseCode responseCode;
            var controlCode = response.getControl();
            if (controlCode == ControlCode.READ_ADDRESS_RESPONSE) {
                plcValue = new PlcSTRING(formatAddressResponse(response.getDataPlain()));
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

    private CompletableFuture<PlcReadResponse> executeReadData(
        DefaultPlcReadRequest request, String tagName, Cjt188Tag tag) {

        byte[] address;
        try {
            address = resolveMeterAddress(tag);
        } catch (MissingMeterAddressException e) {
            return CompletableFuture.completedFuture(
                completeReadError(request, tagName, PlcResponseCode.INVALID_ADDRESS));
        }
        var frame = buildReadDataFrame(tag.getDataIdentifier(), address);

        return sendRequest(frame).thenCompose(response -> {
            var controlCode = response.getControl();
            if (controlCode == ControlCode.READ_DATA_ERROR) {
                logger.warn("Read error for tag {}: {}",
                    tagName, StaticHelper.describeError(response.getDataPlain()));
                return CompletableFuture.completedFuture(
                    completeReadError(request, tagName, PlcResponseCode.REMOTE_ERROR));
            }
            if (controlCode == ControlCode.READ_DATA_RESPONSE) {
                return CompletableFuture.completedFuture(
                    completeReadDataResponse(request, tagName, tag, response.getDataPlain()));
            }
            if (controlCode == ControlCode.READ_DATA_RESPONSE_MORE) {
                return accumulateSubsequentData(request, tagName, tag, response.getDataPlain(),
                    (response.getDataPlain()[2] & 0xFF) + 1, 0);
            }
            logger.warn("Unexpected control code in read response: 0x{}",
                String.format("%02X", controlCode.getValue()));
            return CompletableFuture.completedFuture(
                completeReadError(request, tagName, PlcResponseCode.INTERNAL_ERROR));
        });
    }

    /**
     * Subsequent frames reuse 读数据 (0x01) with an incremented SER, per CJ/T 188-2004.
     */
    private CompletableFuture<PlcReadResponse> accumulateSubsequentData(
        DefaultPlcReadRequest request, String tagName, Cjt188Tag tag,
        byte[] accumulatedData, int seqNumber, int frameCount) {

        if (seqNumber < 0 || seqNumber > 255 || frameCount >= MAX_SUBSEQUENT_FRAMES ||
            accumulatedData.length > MAX_ACCUMULATED_DATA_BYTES) {
            return CompletableFuture.completedFuture(
                completeReadError(request, tagName, PlcResponseCode.INTERNAL_ERROR));
        }

        var di = tag.getDataIdentifier();
        var dataPlain = new byte[]{di[1], di[0], (byte) seqNumber};
        var subFrame = new Cjt188Frame(resolveMeterAddress(tag), ControlCode.READ_DATA,
            (short) dataPlain.length, dataPlain);

        return sendRequest(subFrame).thenCompose(response -> {
            var controlCode = response.getControl();
            if (controlCode == ControlCode.READ_DATA_ERROR) {
                logger.warn("Read subsequent error for tag {}: {}",
                    tagName, StaticHelper.describeError(response.getDataPlain()));
                return CompletableFuture.completedFuture(
                    completeReadError(request, tagName, PlcResponseCode.REMOTE_ERROR));
            }
            var newData = mergeSubsequentData(accumulatedData, response.getDataPlain());
            if (newData.length > MAX_ACCUMULATED_DATA_BYTES) {
                return CompletableFuture.completedFuture(
                    completeReadError(request, tagName, PlcResponseCode.INTERNAL_ERROR));
            }
            if (controlCode == ControlCode.READ_DATA_RESPONSE) {
                return CompletableFuture.completedFuture(
                    completeReadDataResponse(request, tagName, tag, newData));
            }
            if (controlCode == ControlCode.READ_DATA_RESPONSE_MORE) {
                return accumulateSubsequentData(request, tagName, tag, newData,
                    seqNumber + 1, frameCount + 1);
            }
            logger.warn("Unexpected control code in subsequent read: 0x{}",
                String.format("%02X", controlCode.getValue()));
            return CompletableFuture.completedFuture(
                completeReadError(request, tagName, PlcResponseCode.INTERNAL_ERROR));
        });
    }

    /**
     * Initial/subsequent layout: DI0 DI1 SER + value. Keep the first header and append value bytes.
     */
    private byte[] mergeSubsequentData(byte[] accumulated, byte[] subsequent) {
        if (subsequent == null || subsequent.length < HEADER_DI_SER) {
            return accumulated;
        }
        var baos = new ByteArrayOutputStream();
        baos.write(accumulated, 0, accumulated.length);
        baos.write(subsequent, HEADER_DI_SER, subsequent.length - HEADER_DI_SER);
        return baos.toByteArray();
    }

    private PlcReadResponse completeReadDataResponse(
        DefaultPlcReadRequest request, String tagName, Cjt188Tag tag, byte[] dataPlain) {

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

    private static PlcWriteResponse completeWrite(DefaultPlcWriteRequest request, String tagName,
                                                  PlcResponseCode responseCode) {
        return new DefaultPlcWriteResponse(request, Collections.singletonMap(tagName, responseCode));
    }

    // ========== Write ==========

    @Override
    protected CompletableFuture<PlcWriteResponse> onWrite(PlcWriteRequest writeRequest) {
        var request = (DefaultPlcWriteRequest) writeRequest;
        if (request.getTagNames().size() != 1) {
            return CompletableFuture.failedFuture(
                new PlcRuntimeException("CJ/T 188-2004 only supports single tag requests"));
        }
        var tagName = request.getTagNames().iterator().next();
        PlcTag tag = request.getTag(tagName);
        var value = writeRequest.getPlcValue(tagName);
        if (tag instanceof Cjt188CommandTag cmdTag) {
            return executeCommandWrite(request, tagName, cmdTag, value);
        }
        return executeWriteData(request, tagName, (Cjt188Tag) tag, value, ControlCode.WRITE_DATA,
            ControlCode.WRITE_DATA_RESPONSE, ControlCode.WRITE_DATA_ERROR);
    }

    private CompletableFuture<PlcWriteResponse> executeWriteData(
        DefaultPlcWriteRequest request, String tagName, Cjt188Tag tag, PlcValue value,
        ControlCode reqCode, ControlCode respCode, ControlCode errCode) {

        Cjt188Frame frame;
        try {
            frame = buildWriteDataFrame(tag.getDataIdentifier(), serializeValue(tag, value), reqCode,
                resolveMeterAddress(tag));
        } catch (MissingMeterAddressException e) {
            return CompletableFuture.completedFuture(
                completeWrite(request, tagName, PlcResponseCode.INVALID_ADDRESS));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(
                completeWrite(request, tagName, PlcResponseCode.INVALID_DATA));
        }
        return sendRequest(frame).thenApply(response -> {
            PlcResponseCode responseCode;
            var controlCode = response.getControl();
            if (controlCode == errCode) {
                logger.warn("Write error for tag {}: {}",
                    tagName, StaticHelper.describeError(response.getDataPlain()));
                responseCode = PlcResponseCode.REMOTE_ERROR;
            } else if (controlCode == respCode) {
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

    private CompletableFuture<PlcWriteResponse> executeCommandWrite(
        DefaultPlcWriteRequest request, String tagName,
        Cjt188CommandTag cmdTag, PlcValue value) {

        return switch (cmdTag.getCommandType()) {
            case WRITE_ADDRESS -> executeWriteAddress(request, tagName, value);
            case WRITE_SYNC -> executeWriteSync(request, tagName, cmdTag, value);
            default -> CompletableFuture.failedFuture(
                new PlcRuntimeException("Command '" + cmdTag.getCommandType().getKeyword()
                    + "' is not a write command"));
        };
    }

    private CompletableFuture<PlcWriteResponse> executeWriteAddress(
        DefaultPlcWriteRequest request, String tagName, PlcValue value) {

        byte[] newAddress;
        try {
            newAddress = Cjt188DriverContext.parseMeterAddress(
                value.getString(), getConfiguration().getMeterType());
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(
                completeWrite(request, tagName, PlcResponseCode.INVALID_ADDRESS));
        }
        // CJ/T 188-2004 写地址 (15H): DATA is the 7-byte wire address only. No SER.
        var frame = new Cjt188Frame(WILDCARD_ADDRESS, ControlCode.WRITE_ADDRESS,
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
     * Write-sync value: {@code DI1DI0:decimal} e.g. {@code 901F:1234.56}.
     */
    private CompletableFuture<PlcWriteResponse> executeWriteSync(
        DefaultPlcWriteRequest request, String tagName, Cjt188CommandTag cmdTag, PlcValue value) {

        Cjt188Frame frame;
        try {
            String input = value.getString();
            int colon = input.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException(
                    "write-sync value must be DI1DI0:payload, e.g. 901F:1234.56");
            }
            Cjt188Tag diTag = Cjt188Tag.of(input.substring(0, colon));
            PlcValue payload = new PlcSTRING(input.substring(colon + 1));
            byte[] address = resolveMeterAddress(cmdTag.getMeterAddress(), cmdTag.getMeterType());
            frame = buildWriteDataFrame(diTag.getDataIdentifier(), serializeValue(diTag, payload),
                ControlCode.WRITE_SYNC, address);
        } catch (MissingMeterAddressException e) {
            return CompletableFuture.completedFuture(
                completeWrite(request, tagName, PlcResponseCode.INVALID_ADDRESS));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(
                completeWrite(request, tagName, PlcResponseCode.INVALID_DATA));
        }
        return sendRequest(frame).thenApply(response -> {
            PlcResponseCode responseCode;
            var controlCode = response.getControl();
            if (controlCode == ControlCode.WRITE_SYNC_ERROR) {
                logger.warn("Write-sync error for tag {}: {}",
                    tagName, StaticHelper.describeError(response.getDataPlain()));
                responseCode = PlcResponseCode.REMOTE_ERROR;
            } else if (controlCode == ControlCode.WRITE_SYNC_RESPONSE) {
                responseCode = PlcResponseCode.OK;
            } else {
                logger.warn("Unexpected control code for write-sync: 0x{}",
                    String.format("%02X", controlCode.getValue()));
                responseCode = PlcResponseCode.INTERNAL_ERROR;
            }
            return (PlcWriteResponse) new DefaultPlcWriteResponse(request,
                Collections.singletonMap(tagName, responseCode));
        });
    }

    // ========== Frame construction ==========

    private Cjt188Frame buildReadDataFrame(byte[] dataIdentifier, byte[] address) {
        byte ser = nextSer();
        var dataPlain = new byte[]{dataIdentifier[1], dataIdentifier[0], ser};
        return new Cjt188Frame(address, ControlCode.READ_DATA,
            (short) dataPlain.length, dataPlain);
    }

    private Cjt188Frame buildWriteDataFrame(byte[] dataIdentifier, byte[] payload, ControlCode control,
                                            byte[] address) {
        if (payload.length > 200 - HEADER_DI_SER) {
            throw new IllegalArgumentException("CJ/T 188 write payload exceeds the DATA field limit");
        }
        var dataPlain = new byte[HEADER_DI_SER + payload.length];
        dataPlain[0] = dataIdentifier[1];
        dataPlain[1] = dataIdentifier[0];
        dataPlain[2] = nextSer();
        System.arraycopy(payload, 0, dataPlain, HEADER_DI_SER, payload.length);
        return new Cjt188Frame(address, control, (short) dataPlain.length, dataPlain);
    }

    /**
     * Tag-level meter-address / meter-type win over the connection URL default.
     */
    private byte[] resolveMeterAddress(Cjt188Tag tag) {
        return resolveMeterAddress(tag.getMeterAddress(), tag.getMeterType());
    }

    private byte[] resolveMeterAddress(String tagMeterAddress, String tagMeterType) {
        String address = firstNonBlank(tagMeterAddress, getConfiguration().getMeterAddress());
        String type = firstNonBlank(tagMeterType, getConfiguration().getMeterType());
        if (address == null || address.isBlank()) {
            throw new MissingMeterAddressException();
        }
        try {
            return Cjt188DriverContext.parseMeterAddress(address, type);
        } catch (IllegalArgumentException e) {
            throw new MissingMeterAddressException(e);
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    // ========== Data parsing ==========

    /**
     * Parse response data: DI0 DI1 SER + value.
     * Some meters append a 2-byte ST after the value; extra trailing bytes are ignored
     * when the DI has a known length.
     */
    private PlcValue parseResponseData(byte[] dataPlain, Cjt188Tag tag) {
        if (dataPlain == null || dataPlain.length < HEADER_DI_SER) {
            return new PlcSTRING("");
        }
        var diBytes = new byte[]{dataPlain[1], dataPlain[0]};
        if (!Arrays.equals(diBytes, tag.getDataIdentifier())) {
            byte[] expected = tag.getDataIdentifier();
            var expectedDi = String.format("%02X%02X", expected[0] & 0xFF, expected[1] & 0xFF);
            var actualDi = String.format("%02X%02X", diBytes[0] & 0xFF, diBytes[1] & 0xFF);
            throw new IllegalArgumentException(
                "DI mismatch in response: expected " + expectedDi + ", got " + actualDi);
        }
        var diHex = String.format("%02X%02X", diBytes[0] & 0xFF, diBytes[1] & 0xFF);
        var valueData = Arrays.copyOfRange(dataPlain, HEADER_DI_SER, dataPlain.length);
        if (DataIdentifiers.containsWildcard(diHex) && tag.getPlcValueType() != PlcValueType.RAW_BYTE_ARRAY) {
            return parseWildcardResponseData(diHex, valueData, tag);
        }
        return decodeValue(tag, diHex, valueData);
    }

    private PlcValue parseWildcardResponseData(String wildcardDiHex, byte[] valueData, Cjt188Tag requestTag) {
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
            var subData = Arrays.copyOfRange(valueData, offset, offset + subItem.getDataLength());
            Cjt188Tag subTag = resolveWildcardSubTag(requestTag, subItem.getDi());
            map.put(subItem.getDi().toUpperCase(), decodeValue(subTag, subItem.getDi(), subData));
            offset += subItem.getDataLength();
        }
        return new PlcStruct(map);
    }

    private static Cjt188Tag resolveWildcardSubTag(Cjt188Tag requestTag, String subItemDi) {
        if (requestTag instanceof Cjt188WildcardTag wildcardTag) {
            for (var mapping : wildcardTag.getMappings()) {
                if (mapping.subItemDiHex().equalsIgnoreCase(subItemDi) && mapping.requestedType() != null) {
                    return Cjt188Tag.of(subItemDi + ":" + mapping.requestedType().name());
                }
            }
        }
        return Cjt188Tag.of(subItemDi);
    }

    /**
     * Read-address DATA is 7 bytes (standard). Some meters prepend SER (8 bytes);
     * length decides, never the first byte's value — SER 0x10 would otherwise look
     * like a cold-water type.
     */
    private static String formatAddressResponse(byte[] dataPlain) {
        byte[] addr = communicationAddressFromData(dataPlain);
        return addr == null ? "" : Cjt188DriverContext.formatAddress(addr);
    }

    private static byte[] communicationAddressFromData(byte[] dataPlain) {
        if (dataPlain == null) {
            return null;
        }
        if (dataPlain.length == 7) {
            return dataPlain;
        }
        if (dataPlain.length >= 8) {
            byte[] addr = new byte[7];
            System.arraycopy(dataPlain, 1, addr, 0, 7);
            return addr;
        }
        return null;
    }

    private PlcValue decodeValue(Cjt188Tag tag, String diHex, byte[] valueData) {
        var descriptor = DataIdentifiers.lookup(diHex);
        var requestedType = tag.getPlcValueType();
        if (requestedType == PlcValueType.RAW_BYTE_ARRAY) {
            return new PlcRawByteArray(Arrays.copyOf(valueData, valueData.length));
        }
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown DI " + diHex + "; use :RAW_BYTE_ARRAY explicitly");
        }
        byte[] trimmed = valueData;
        if (valueData.length > descriptor.getDataLength()) {
            trimmed = Arrays.copyOf(valueData, descriptor.getDataLength());
        } else if (valueData.length != descriptor.getDataLength()) {
            throw new IllegalArgumentException("DI " + diHex + " expects " + descriptor.getDataLength()
                + " data bytes, got " + valueData.length);
        }
        return switch (descriptor.getFormat()) {
            case DATE_TIME -> new PlcDATE_AND_TIME(parseDateTime(trimmed));
            case DATE -> throw new IllegalArgumentException("DATE format is not used by CJ/T 188 DIs");
            case TIME -> throw new IllegalArgumentException("TIME format is not used by CJ/T 188 DIs");
            case RAW -> new PlcRawByteArray(Arrays.copyOf(trimmed, trimmed.length));
            case BCD, SIGNED_BCD -> numericPlcValue(requestedType,
                DataIdentifiers.decodeValue(diHex, trimmed));
        };
    }

    private static PlcValue numericPlcValue(PlcValueType type, double value) {
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
        // Wire order: ss mm hh DD MM YY (low unit first)
        return LocalDateTime.of(2000 + bcd(data[5]), bcd(data[4]), bcd(data[3]),
            bcd(data[2]), bcd(data[1]), bcd(data[0]));
    }

    private static int bcd(byte value) {
        int high = (value >>> 4) & 0x0F;
        int low = value & 0x0F;
        if (high > 9 || low > 9) {
            throw new IllegalArgumentException("Invalid BCD byte 0x" + String.format("%02X", value & 0xFF));
        }
        return high * 10 + low;
    }

    private byte[] serializeValue(Cjt188Tag tag, PlcValue value) {
        if (tag.getPlcValueType() == PlcValueType.RAW_BYTE_ARRAY) {
            return Arrays.copyOf(value.getRaw(), value.getRaw().length);
        }
        var descriptor = DataIdentifiers.lookup(tag.getDataIdentifier());
        if (descriptor != null && descriptor.getFormat() == DataIdentifiers.DataFormat.DATE_TIME) {
            return buildDateTimeData(value);
        }
        if (descriptor == null || (descriptor.getFormat() != DataIdentifiers.DataFormat.BCD &&
            descriptor.getFormat() != DataIdentifiers.DataFormat.SIGNED_BCD)) {
            throw new IllegalArgumentException(
                "DI " + tag.getAddressString().substring(0, 4) + " requires RAW_BYTE_ARRAY writes");
        }
        java.math.BigDecimal numeric;
        try {
            numeric = new java.math.BigDecimal(value.getString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("CJ/T 188 numeric write value is not a decimal number", e);
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

    private byte[] buildDateTimeData(PlcValue value) {
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
                "Date-time must be YYMMDDhhmmss or yyyy-MM-dd HH:mm:ss", e);
        }
        return new byte[]{
            toBcd(dateTime.getSecond()), toBcd(dateTime.getMinute()), toBcd(dateTime.getHour()),
            toBcd(dateTime.getDayOfMonth()), toBcd(dateTime.getMonthValue()),
            toBcd(dateTime.getYear() % 100)
        };
    }

    private static byte toBcd(int value) {
        return (byte) (((value / 10) << 4) | (value % 10));
    }

    // ========== Transport send ==========

    private CompletableFuture<Cjt188Frame> sendRequest(Cjt188Frame frame) {
        CompletableFuture<Cjt188Frame> responseFuture = new CompletableFuture<>();
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

    private void dispatchRequest(Cjt188Frame frame, long requestId, CompletableFuture<Cjt188Frame> responseFuture) {
        if (responseFuture.isDone()) {
            return;
        }
        discardStaleInput("dispatching request " + requestId);
        PendingRequest pending = new PendingRequest(requestId, frame, responseFuture);
        try {
            synchronized (requestChainLock) {
                if (pendingRequest != null && !pendingRequest.responseFuture.isDone()) {
                    responseFuture.completeExceptionally(
                        new PlcRuntimeException("Another CJ/T 188 request is already in flight"));
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
                    "Sending CJ/T 188 frame, control=0x" + String.format("%02X", frame.getControl().getValue()));
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
        private final Cjt188Frame request;
        private final CompletableFuture<Cjt188Frame> responseFuture;

        private PendingRequest(long requestId, Cjt188Frame request,
                               CompletableFuture<Cjt188Frame> responseFuture) {
            this.requestId = requestId;
            this.request = request;
            this.responseFuture = responseFuture;
        }

        private boolean matches(Cjt188Frame response) {
            if (response == null || response.getControl() == null || !matchesControl(response.getControl())) {
                return false;
            }
            if (!matchesAddress(response)) {
                return false;
            }
            byte[] data = response.getDataPlain();
            if (isError(response.getControl())) {
                return data != null && data.length >= 1 && data.length <= 2;
            }
            return matchesSuccessData(response.getControl(), data == null ? new byte[0] : data);
        }

        private boolean matchesControl(ControlCode responseControl) {
            return switch (request.getControl()) {
                case READ_DATA -> responseControl == ControlCode.READ_DATA_RESPONSE ||
                    responseControl == ControlCode.READ_DATA_RESPONSE_MORE ||
                    responseControl == ControlCode.READ_DATA_ERROR;
                case READ_ADDRESS -> responseControl == ControlCode.READ_ADDRESS_RESPONSE ||
                    responseControl == ControlCode.READ_ADDRESS_ERROR;
                case WRITE_DATA -> responseControl == ControlCode.WRITE_DATA_RESPONSE ||
                    responseControl == ControlCode.WRITE_DATA_ERROR;
                case WRITE_ADDRESS -> responseControl == ControlCode.WRITE_ADDRESS_RESPONSE ||
                    responseControl == ControlCode.WRITE_ADDRESS_ERROR;
                case WRITE_SYNC -> responseControl == ControlCode.WRITE_SYNC_RESPONSE ||
                    responseControl == ControlCode.WRITE_SYNC_ERROR;
                default -> false;
            };
        }

        private boolean matchesAddress(Cjt188Frame response) {
            if (request.getControl() == ControlCode.READ_ADDRESS) {
                if (response.getControl() != ControlCode.READ_ADDRESS_RESPONSE) {
                    return true;
                }
                byte[] reported = communicationAddressFromData(response.getDataPlain());
                return reported != null && Arrays.equals(response.getAddress(), reported);
            }
            if (request.getControl() == ControlCode.WRITE_ADDRESS) {
                return response.getControl() != ControlCode.WRITE_ADDRESS_RESPONSE
                    || Arrays.equals(response.getAddress(), request.getDataPlain());
            }
            return Arrays.equals(request.getAddress(), response.getAddress());
        }

        private boolean matchesSuccessData(ControlCode responseControl, byte[] data) {
            return switch (responseControl) {
                case READ_DATA_RESPONSE, READ_DATA_RESPONSE_MORE ->
                    data.length >= HEADER_DI_SER && startsWith(data, request.getDataPlain(), DI_LENGTH);
                case READ_ADDRESS_RESPONSE -> data.length >= 7;
                default -> true;
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

    private static final class MissingMeterAddressException extends PlcRuntimeException {
        private MissingMeterAddressException() {
            super("CJ/T 188 meter-address is required on the connection URL or on the tag");
        }

        private MissingMeterAddressException(Throwable cause) {
            super("Invalid meter-address / meter-type", cause);
        }
    }
}
