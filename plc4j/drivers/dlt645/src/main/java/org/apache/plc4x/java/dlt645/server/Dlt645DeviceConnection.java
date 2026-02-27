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
package org.apache.plc4x.java.dlt645.server;

import io.netty.channel.Channel;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.api.exceptions.PlcUnsupportedOperationException;
import org.apache.plc4x.java.api.messages.*;
import org.apache.plc4x.java.api.metadata.PlcConnectionMetadata;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.dlt645.context.Dlt645DriverContext;
import org.apache.plc4x.java.dlt645.readwrite.ControlCode;
import org.apache.plc4x.java.dlt645.readwrite.Dlt645Frame;
import org.apache.plc4x.java.dlt645.readwrite.utils.DataIdentifiers;
import org.apache.plc4x.java.dlt645.readwrite.utils.StaticHelper;
import org.apache.plc4x.java.dlt645.tag.Dlt645CommandTag;
import org.apache.plc4x.java.dlt645.tag.Dlt645CommandTag.CommandType;
import org.apache.plc4x.java.dlt645.tag.Dlt645Tag;
import org.apache.plc4x.java.dlt645.tag.Dlt645TagHandler;
import org.apache.plc4x.java.spi.messages.*;
import org.apache.plc4x.java.spi.messages.utils.DefaultPlcResponseItem;
import org.apache.plc4x.java.spi.values.DefaultPlcValueHandler;
import org.apache.plc4x.java.spi.values.PlcLREAL;
import org.apache.plc4x.java.spi.values.PlcSTRING;
import org.apache.plc4x.java.spi.values.PlcValueHandler;
import org.apache.plc4x.java.transport.tcp.server.DeviceConversationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A virtual {@link PlcConnection} bound to a single DL/T 645-2007 device.
 * <p>
 * Created via {@link Dlt645ServerConnection#getDeviceConnection(String)}.
 * This connection implements the standard PLC4X {@link PlcConnection} interface,
 * so it can be used exactly like a client-mode connection — with clean tag format,
 * no device-id in tags.
 *
 * <pre>{@code
 * Dlt645DeviceConnection device = server.getDeviceConnection("DTU_001");
 * PlcReadResponse resp = device.readRequestBuilder()
 *     .addTagAddress("energy", "00010000")
 *     .build().execute().get();
 * float energy = resp.getFloat("energy");
 * }</pre>
 *
 * Supports all DL/T 645-2007 operations:
 * read data (0x11), read subsequent (0x12), read/write address (0x13/0x15),
 * write data (0x14), freeze (0x16), change baud rate (0x17), change password (0x18),
 * clear max demand (0x19), clear meter (0x1A), clear event (0x1B).
 */
public class Dlt645DeviceConnection implements PlcConnection, PlcConnectionMetadata, PlcReader, PlcWriter {

    private static final Logger logger = LoggerFactory.getLogger(Dlt645DeviceConnection.class);

    private static final byte[] BROADCAST_ADDRESS = {
        (byte) 0x99, (byte) 0x99, (byte) 0x99,
        (byte) 0x99, (byte) 0x99, (byte) 0x99
    };

    private final String deviceId;
    private final Channel deviceChannel;
    private final DeviceConversationHandler<Dlt645Frame> handler;
    private final byte[] meterAddress;
    private final byte[] password;
    private final byte[] operatorCode;
    private final Duration requestTimeout;
    private final Dlt645TagHandler tagHandler = new Dlt645TagHandler();
    private final PlcValueHandler valueHandler = new DefaultPlcValueHandler();

    Dlt645DeviceConnection(String deviceId, Channel deviceChannel,
                           DeviceConversationHandler<Dlt645Frame> handler,
                           byte[] password, byte[] operatorCode,
                           Duration requestTimeout) {
        this.deviceId = deviceId;
        this.deviceChannel = deviceChannel;
        this.handler = handler;
        this.meterAddress = Dlt645DriverContext.parseMeterAddress(deviceId);
        this.password = Arrays.copyOf(password, password.length);
        this.operatorCode = Arrays.copyOf(operatorCode, operatorCode.length);
        this.requestTimeout = requestTimeout;
    }

    /**
     * Get the device identifier (from registration packet).
     */
    public String getDeviceId() {
        return deviceId;
    }

    // ========================================
    // PlcConnection
    // ========================================

    @Override
    public void connect() {
        // Already connected — this is a virtual sub-connection
    }

    @Override
    public boolean isConnected() {
        return deviceChannel.isActive();
    }

    @Override
    public void close() {
        deviceChannel.close();
    }

    @Override
    public PlcConnectionMetadata getMetadata() {
        return this;
    }

    @Override
    public Optional<PlcTag> parseTagAddress(String tagAddress) {
        try {
            return Optional.of(tagHandler.parseTag(tagAddress));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<PlcValue> parseTagValue(PlcTag tag, Object... values) {
        try {
            return Optional.of(valueHandler.newPlcValue(tag, values));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public CompletableFuture<? extends PlcPingResponse> ping() {
        var pingRequest = new DefaultPlcPingRequest(null);
        var diBytes = new byte[]{0x00, 0x01, 0x00, 0x00};
        var frame = buildReadDataFrame(diBytes);

        return handler.sendRequest(frame, r -> true, requestTimeout)
            .thenApply(response -> new DefaultPlcPingResponse(pingRequest, PlcResponseCode.OK));
    }

    @Override
    public PlcReadRequest.Builder readRequestBuilder() {
        return new DefaultPlcReadRequest.Builder(this, tagHandler);
    }

    @Override
    public PlcWriteRequest.Builder writeRequestBuilder() {
        return new DefaultPlcWriteRequest.Builder(this, tagHandler, valueHandler);
    }

    @Override
    public PlcSubscriptionRequest.Builder subscriptionRequestBuilder() {
        throw new PlcUnsupportedOperationException("DL/T 645-2007 does not support subscriptions");
    }

    @Override
    public PlcUnsubscriptionRequest.Builder unsubscriptionRequestBuilder() {
        throw new PlcUnsupportedOperationException("DL/T 645-2007 does not support subscriptions");
    }

    @Override
    public PlcBrowseRequest.Builder browseRequestBuilder() {
        throw new PlcUnsupportedOperationException("DL/T 645-2007 does not support browsing");
    }

    // ========================================
    // PlcConnectionMetadata
    // ========================================

    @Override public boolean isReadSupported() { return true; }
    @Override public boolean isWriteSupported() { return true; }
    @Override public boolean isSubscribeSupported() { return false; }
    @Override public boolean isBrowseSupported() { return false; }

    // ========================================
    // PlcReader
    // ========================================

    @Override
    public CompletableFuture<PlcReadResponse> read(PlcReadRequest readRequest) {
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

        // Standard DI tag
        return executeReadData(request, tagName, (Dlt645Tag) tag);
    }

    private CompletableFuture<PlcReadResponse> executeReadAddress(
        DefaultPlcReadRequest request, String tagName) {

        var frame = new Dlt645Frame(BROADCAST_ADDRESS, ControlCode.READ_ADDRESS,
            (short) 0, new byte[0]);

        return handler.sendRequest(frame, r -> true, requestTimeout)
            .thenApply(response -> {
                PlcValue plcValue = null;
                PlcResponseCode responseCode;

                var ctrl = response.getControl();
                if (ctrl == ControlCode.READ_ADDRESS_ERROR) {
                    logger.warn("[{}] Read address error: {}", deviceId,
                        StaticHelper.describeError(response.getDataPlain()));
                    responseCode = PlcResponseCode.REMOTE_ERROR;
                } else if (ctrl == ControlCode.READ_ADDRESS_RESPONSE) {
                    plcValue = new PlcSTRING(formatAddressMsbFirst(response.getDataPlain()));
                    responseCode = PlcResponseCode.OK;
                } else {
                    responseCode = PlcResponseCode.INTERNAL_ERROR;
                }

                return new DefaultPlcReadResponse(request,
                    Collections.singletonMap(tagName,
                        new DefaultPlcResponseItem<>(responseCode, plcValue)));
            });
    }

    private CompletableFuture<PlcReadResponse> executeReadData(
        DefaultPlcReadRequest request, String tagName, Dlt645Tag tag) {

        var frame = buildReadDataFrame(tag.getDataIdentifier());

        return handler.sendRequest(frame, r -> true, requestTimeout)
            .thenCompose(response -> {
                var ctrl = response.getControl();

                if (ctrl == ControlCode.READ_DATA_ERROR) {
                    logger.warn("[{}] Read error for {}: {}", deviceId, tagName,
                        StaticHelper.describeError(response.getDataPlain()));
                    return CompletableFuture.completedFuture(
                        new DefaultPlcReadResponse(request,
                            Collections.singletonMap(tagName,
                                new DefaultPlcResponseItem<>(PlcResponseCode.REMOTE_ERROR, null))));
                }

                if (ctrl == ControlCode.READ_DATA_RESPONSE) {
                    return CompletableFuture.completedFuture(
                        buildReadDataResponse(request, tagName, tag, response.getDataPlain()));
                }

                if (ctrl == ControlCode.READ_DATA_RESPONSE_MORE) {
                    // D5=1: more data — start subsequent reads
                    return accumulateSubsequentData(request, tagName, tag, response.getDataPlain(), 1);
                }

                return CompletableFuture.completedFuture(
                    new DefaultPlcReadResponse(request,
                        Collections.singletonMap(tagName,
                            new DefaultPlcResponseItem<>(PlcResponseCode.INTERNAL_ERROR, null))));
            });
    }

    private CompletableFuture<PlcReadResponse> accumulateSubsequentData(
        DefaultPlcReadRequest request, String tagName, Dlt645Tag tag,
        byte[] accumulatedData, int seqNumber) {

        var di = tag.getDataIdentifier();
        var dataPlain = new byte[5];
        for (int i = 0; i < 4; i++) {
            dataPlain[i] = di[3 - i];
        }
        dataPlain[4] = (byte) seqNumber;

        var subFrame = new Dlt645Frame(meterAddress, ControlCode.READ_SUBSEQUENT_DATA,
            (short) dataPlain.length, dataPlain);

        return handler.sendRequest(subFrame, r -> true, requestTimeout)
            .thenCompose(response -> {
                var ctrl = response.getControl();

                if (ctrl == ControlCode.READ_SUBSEQUENT_DATA_ERROR) {
                    // Return what we have so far
                    return CompletableFuture.completedFuture(
                        buildReadDataResponse(request, tagName, tag, accumulatedData));
                }

                var newData = mergeSubsequentData(accumulatedData, response.getDataPlain());

                if (ctrl == ControlCode.READ_SUBSEQUENT_DATA_RESPONSE) {
                    return CompletableFuture.completedFuture(
                        buildReadDataResponse(request, tagName, tag, newData));
                }

                if (ctrl == ControlCode.READ_SUBSEQUENT_DATA_RESPONSE_MORE) {
                    return accumulateSubsequentData(request, tagName, tag, newData, seqNumber + 1);
                }

                return CompletableFuture.completedFuture(
                    buildReadDataResponse(request, tagName, tag, newData));
            });
    }

    private DefaultPlcReadResponse buildReadDataResponse(
        DefaultPlcReadRequest request, String tagName, Dlt645Tag tag, byte[] dataPlain) {

        PlcValue plcValue = null;
        PlcResponseCode code;
        try {
            plcValue = parseResponseData(dataPlain, tag);
            code = PlcResponseCode.OK;
        } catch (Exception e) {
            logger.warn("[{}] Failed to parse response for {}: {}", deviceId, tagName, e.getMessage());
            code = PlcResponseCode.INTERNAL_ERROR;
        }
        return new DefaultPlcReadResponse(request,
            Collections.singletonMap(tagName, new DefaultPlcResponseItem<>(code, plcValue)));
    }

    // ========================================
    // PlcWriter
    // ========================================

    @Override
    public CompletableFuture<PlcWriteResponse> write(PlcWriteRequest writeRequest) {
        var request = (DefaultPlcWriteRequest) writeRequest;

        if (request.getTagNames().size() != 1) {
            return CompletableFuture.failedFuture(
                new PlcRuntimeException("DL/T 645-2007 only supports single tag requests"));
        }

        var tagName = request.getTagNames().iterator().next();
        PlcTag tag = request.getTag(tagName);
        var value = writeRequest.getPlcValue(tagName);

        if (tag instanceof Dlt645CommandTag) {
            return executeCommandWrite(request, tagName, (Dlt645CommandTag) tag, value);
        }

        return executeWriteData(request, tagName, (Dlt645Tag) tag, value);
    }

    private CompletableFuture<PlcWriteResponse> executeWriteData(
        DefaultPlcWriteRequest request, String tagName, Dlt645Tag tag, PlcValue value) {

        var frame = buildWriteDataFrame(tag.getDataIdentifier(), serializeValue(value));

        return handler.sendRequest(frame, r -> true, requestTimeout)
            .thenApply(response -> {
                PlcResponseCode code;
                var ctrl = response.getControl();
                if (ctrl == ControlCode.WRITE_DATA_ERROR) {
                    logger.warn("[{}] Write error for {}: {}", deviceId, tagName,
                        StaticHelper.describeError(response.getDataPlain()));
                    code = PlcResponseCode.REMOTE_ERROR;
                } else if (ctrl == ControlCode.WRITE_DATA_RESPONSE) {
                    code = PlcResponseCode.OK;
                } else {
                    code = PlcResponseCode.INTERNAL_ERROR;
                }
                return new DefaultPlcWriteResponse(request, Collections.singletonMap(tagName, code));
            });
    }

    private CompletableFuture<PlcWriteResponse> executeCommandWrite(
        DefaultPlcWriteRequest request, String tagName, Dlt645CommandTag cmdTag, PlcValue value) {

        var cmdType = cmdTag.getCommandType();
        switch (cmdType) {
            case WRITE_ADDRESS:
                return executeWriteAddress(request, tagName, value);
            case FREEZE:
                return sendSimpleCommand(request, tagName,
                    ControlCode.FREEZE_DATA, ControlCode.FREEZE_DATA_RESPONSE,
                    ControlCode.FREEZE_DATA_ERROR, buildFreezeData(value));
            case CHANGE_BAUD_RATE:
                return sendSimpleCommand(request, tagName,
                    ControlCode.CHANGE_BAUD_RATE, ControlCode.CHANGE_BAUD_RATE_RESPONSE,
                    ControlCode.CHANGE_BAUD_RATE_ERROR, buildBaudRateData(value));
            case CHANGE_PASSWORD:
                return sendSimpleCommand(request, tagName,
                    ControlCode.CHANGE_PASSWORD, ControlCode.CHANGE_PASSWORD_RESPONSE,
                    ControlCode.CHANGE_PASSWORD_ERROR, buildChangePasswordData(value));
            case CLEAR_MAX_DEMAND:
                return sendSimpleCommand(request, tagName,
                    ControlCode.CLEAR_MAX_DEMAND, ControlCode.CLEAR_MAX_DEMAND_RESPONSE,
                    ControlCode.CLEAR_MAX_DEMAND_ERROR, buildAuthOnlyData());
            case CLEAR_METER:
                return sendSimpleCommand(request, tagName,
                    ControlCode.CLEAR_METER, ControlCode.CLEAR_METER_RESPONSE,
                    ControlCode.CLEAR_METER_ERROR, buildAuthOnlyData());
            case CLEAR_EVENT:
                return sendSimpleCommand(request, tagName,
                    ControlCode.CLEAR_EVENT, ControlCode.CLEAR_EVENT_RESPONSE,
                    ControlCode.CLEAR_EVENT_ERROR, buildAuthOnlyData());
            default:
                return CompletableFuture.failedFuture(
                    new PlcRuntimeException("Command '" + cmdType.getKeyword() + "' is not a write command"));
        }
    }

    private CompletableFuture<PlcWriteResponse> executeWriteAddress(
        DefaultPlcWriteRequest request, String tagName, PlcValue value) {

        var newAddress = Dlt645DriverContext.parseMeterAddress(value.getString());
        var frame = new Dlt645Frame(BROADCAST_ADDRESS, ControlCode.WRITE_ADDRESS,
            (short) newAddress.length, newAddress);

        return handler.sendRequest(frame, r -> true, requestTimeout)
            .thenApply(response -> {
                PlcResponseCode code;
                var ctrl = response.getControl();
                if (ctrl == ControlCode.WRITE_ADDRESS_ERROR) {
                    code = PlcResponseCode.REMOTE_ERROR;
                } else if (ctrl == ControlCode.WRITE_ADDRESS_RESPONSE) {
                    code = PlcResponseCode.OK;
                } else {
                    code = PlcResponseCode.INTERNAL_ERROR;
                }
                return new DefaultPlcWriteResponse(request, Collections.singletonMap(tagName, code));
            });
    }

    private CompletableFuture<PlcWriteResponse> sendSimpleCommand(
        DefaultPlcWriteRequest request, String tagName,
        ControlCode reqCode, ControlCode respCode, ControlCode errCode,
        byte[] dataPlain) {

        var frame = new Dlt645Frame(meterAddress, reqCode, (short) dataPlain.length, dataPlain);

        return handler.sendRequest(frame, r -> true, requestTimeout)
            .thenApply(response -> {
                PlcResponseCode code;
                var ctrl = response.getControl();
                if (ctrl == errCode) {
                    logger.warn("[{}] Command {} error: {}", deviceId, reqCode.name(),
                        StaticHelper.describeError(response.getDataPlain()));
                    code = PlcResponseCode.REMOTE_ERROR;
                } else if (ctrl == respCode) {
                    code = PlcResponseCode.OK;
                } else {
                    code = PlcResponseCode.INTERNAL_ERROR;
                }
                return new DefaultPlcWriteResponse(request, Collections.singletonMap(tagName, code));
            });
    }

    // ========================================
    // Frame construction
    // ========================================

    private Dlt645Frame buildReadDataFrame(byte[] dataIdentifier) {
        var data = new byte[4];
        for (int i = 0; i < 4; i++) {
            data[i] = dataIdentifier[3 - i];
        }
        return new Dlt645Frame(meterAddress, ControlCode.READ_DATA, (short) data.length, data);
    }

    private Dlt645Frame buildWriteDataFrame(byte[] dataIdentifier, byte[] payload) {
        var data = new byte[4 + 4 + 4 + payload.length];
        for (int i = 0; i < 4; i++) {
            data[i] = dataIdentifier[3 - i];
        }
        System.arraycopy(password, 0, data, 4, 4);
        System.arraycopy(operatorCode, 0, data, 8, 4);
        System.arraycopy(payload, 0, data, 12, payload.length);
        return new Dlt645Frame(meterAddress, ControlCode.WRITE_DATA, (short) data.length, data);
    }

    // ========================================
    // Command data builders
    // ========================================

    private byte[] buildFreezeData(PlcValue value) {
        var payload = parseHexValue(value.getString());
        var data = new byte[4 + 4 + payload.length];
        System.arraycopy(password, 0, data, 0, 4);
        System.arraycopy(operatorCode, 0, data, 4, 4);
        System.arraycopy(payload, 0, data, 8, payload.length);
        return data;
    }

    private byte[] buildBaudRateData(PlcValue value) {
        var rateByte = parseHexValue(value.getString());
        var data = new byte[4 + 4 + 1];
        System.arraycopy(password, 0, data, 0, 4);
        System.arraycopy(operatorCode, 0, data, 4, 4);
        data[8] = rateByte.length > 0 ? rateByte[0] : 0x04;
        return data;
    }

    private byte[] buildChangePasswordData(PlcValue value) {
        var parts = value.getString().split(":");
        byte[] oldPwd, newPwd;
        if (parts.length >= 2) {
            oldPwd = parseHexField(parts[0].trim(), 4);
            newPwd = parseHexField(parts[1].trim(), 4);
        } else {
            oldPwd = password;
            newPwd = parseHexField(parts[0].trim(), 4);
        }
        var data = new byte[4 + 4 + 4];
        System.arraycopy(oldPwd, 0, data, 0, 4);
        System.arraycopy(operatorCode, 0, data, 4, 4);
        System.arraycopy(newPwd, 0, data, 8, 4);
        return data;
    }

    private byte[] buildAuthOnlyData() {
        var data = new byte[8];
        System.arraycopy(password, 0, data, 0, 4);
        System.arraycopy(operatorCode, 0, data, 4, 4);
        return data;
    }

    // ========================================
    // Data parsing
    // ========================================

    private PlcValue parseResponseData(byte[] dataPlain, Dlt645Tag tag) {
        if (dataPlain == null || dataPlain.length < 4) {
            return new PlcSTRING("");
        }
        var diBytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            diBytes[i] = dataPlain[3 - i];
        }
        var diHex = String.format("%02X%02X%02X%02X",
            diBytes[0] & 0xFF, diBytes[1] & 0xFF, diBytes[2] & 0xFF, diBytes[3] & 0xFF);
        var valueData = new byte[dataPlain.length - 4];
        System.arraycopy(dataPlain, 4, valueData, 0, valueData.length);
        return new PlcLREAL(DataIdentifiers.decodeValue(diHex, valueData));
    }

    private static String formatAddressMsbFirst(byte[] addrBytes) {
        if (addrBytes == null || addrBytes.length < 6) return "";
        var sb = new StringBuilder(12);
        for (int i = 5; i >= 0; i--) {
            sb.append(String.format("%02X", addrBytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private byte[] mergeSubsequentData(byte[] accumulated, byte[] subsequent) {
        if (subsequent == null || subsequent.length <= 5) return accumulated;
        var baos = new ByteArrayOutputStream();
        baos.write(accumulated, 0, accumulated.length);
        baos.write(subsequent, 5, subsequent.length - 5);
        return baos.toByteArray();
    }

    private byte[] serializeValue(PlcValue value) {
        var str = value.getString().replaceAll("[^0-9]", "");
        if (str.length() % 2 != 0) str = "0" + str;
        var bytes = new byte[str.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            var srcIdx = (bytes.length - 1 - i) * 2;
            int high = Character.digit(str.charAt(srcIdx), 10);
            int low = Character.digit(str.charAt(srcIdx + 1), 10);
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    private static byte[] parseHexField(String hex, int expectedLength) {
        if (hex == null || hex.isEmpty()) return new byte[expectedLength];
        var clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        if (clean.length() != expectedLength * 2) return new byte[expectedLength];
        var bytes = new byte[expectedLength];
        for (int i = 0; i < expectedLength; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static byte[] parseHexValue(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        var clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        if (clean.length() % 2 != 0) clean = "0" + clean;
        var bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    @Override
    public String toString() {
        return "Dlt645DeviceConnection{deviceId='" + deviceId + "', connected=" + isConnected() + '}';
    }
}
