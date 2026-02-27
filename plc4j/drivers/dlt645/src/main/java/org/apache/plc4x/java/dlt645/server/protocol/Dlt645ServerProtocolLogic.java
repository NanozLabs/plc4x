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
package org.apache.plc4x.java.dlt645.server.protocol;

import io.netty.channel.Channel;
import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.api.messages.*;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.dlt645.context.Dlt645DriverContext;
import org.apache.plc4x.java.dlt645.readwrite.ControlCode;
import org.apache.plc4x.java.dlt645.readwrite.Dlt645Frame;
import org.apache.plc4x.java.dlt645.server.config.Dlt645ServerConfiguration;
import org.apache.plc4x.java.dlt645.server.context.Dlt645ServerDriverContext;
import org.apache.plc4x.java.dlt645.readwrite.utils.DataIdentifiers;
import org.apache.plc4x.java.dlt645.readwrite.utils.StaticHelper;
import org.apache.plc4x.java.dlt645.tag.Dlt645CommandTag;
import org.apache.plc4x.java.dlt645.tag.Dlt645CommandTag.CommandType;
import org.apache.plc4x.java.dlt645.tag.Dlt645Tag;
import org.apache.plc4x.java.dlt645.tag.Dlt645TagHandler;
import org.apache.plc4x.java.spi.ConversationContext;
import org.apache.plc4x.java.spi.Plc4xProtocolBase;
import org.apache.plc4x.java.spi.configuration.HasConfiguration;
import org.apache.plc4x.java.spi.connection.PlcTagHandler;
import org.apache.plc4x.java.spi.context.DriverContext;
import org.apache.plc4x.java.spi.messages.*;
import org.apache.plc4x.java.spi.messages.utils.DefaultPlcResponseItem;
import org.apache.plc4x.java.spi.transaction.RequestTransactionManager;
import org.apache.plc4x.java.spi.values.PlcLREAL;
import org.apache.plc4x.java.spi.values.PlcSTRING;
import org.apache.plc4x.java.transport.tcp.server.DeviceChannelRegistry;
import org.apache.plc4x.java.transport.tcp.server.DeviceConversationHandler;
import org.apache.plc4x.java.transport.tcp.server.TcpServerChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * DL/T 645-2007 Server Protocol Logic.
 * <p>
 * Handles inbound connections from smart meters (DTU devices).
 * Supports all standard DL/T 645-2007 commands including:
 * read data (0x11), read subsequent (0x12), read/write address (0x13/0x15),
 * freeze (0x16), change baud rate (0x17), change password (0x18),
 * clear max demand (0x19), clear meter (0x1A), clear event (0x1B).
 */
public class Dlt645ServerProtocolLogic extends Plc4xProtocolBase<Dlt645Frame>
    implements HasConfiguration<Dlt645ServerConfiguration> {

    private static final Logger logger = LoggerFactory.getLogger(Dlt645ServerProtocolLogic.class);

    private static final byte[] BROADCAST_ADDRESS = {
        (byte) 0x99, (byte) 0x99, (byte) 0x99,
        (byte) 0x99, (byte) 0x99, (byte) 0x99
    };

    private Duration requestTimeout;
    private String targetDeviceId;
    private byte[] targetMeterAddress;
    private byte[] password;
    private byte[] operatorCode;
    private Dlt645ServerDriverContext serverContext;
    private RequestTransactionManager tm;

    @Override
    public void setConfiguration(Dlt645ServerConfiguration configuration) {
        this.requestTimeout = Duration.ofMillis(configuration.getRequestTimeout());
        this.targetDeviceId = configuration.getTargetDeviceId();

        var targetId = configuration.getTargetDeviceId();
        this.targetMeterAddress = (targetId != null && !targetId.isEmpty())
            ? Dlt645DriverContext.parseMeterAddress(targetId)
            : new byte[]{(byte) 0x99, (byte) 0x99, (byte) 0x99,
                (byte) 0x99, (byte) 0x99, (byte) 0x99};
        this.password = parseHexField(configuration.getPassword(), 4);
        this.operatorCode = parseHexField(configuration.getOperatorCode(), 4);
        this.tm = new RequestTransactionManager(1);
    }

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

    @Override
    public void setDriverContext(DriverContext driverContext) {
        super.setDriverContext(driverContext);
        if (driverContext instanceof Dlt645ServerDriverContext) {
            this.serverContext = (Dlt645ServerDriverContext) driverContext;
        }
    }

    @Override
    public PlcTagHandler getTagHandler() {
        return new Dlt645TagHandler();
    }

    @Override
    public void close(ConversationContext<Dlt645Frame> context) {
        if (tm != null) {
            tm.shutdown();
        }
    }

    @Override
    public void onConnect(ConversationContext<Dlt645Frame> context) {
        if (serverContext != null && serverContext.getDeviceRegistry() == null) {
            Channel channel = context.getChannel();
            if (channel.parent() != null) {
                channel = channel.parent();
            }
            DeviceChannelRegistry registry = channel.attr(TcpServerChannelFactory.DEVICE_REGISTRY_KEY).get();
            if (registry != null) {
                serverContext.setDeviceRegistry(registry);
                logger.info("DL/T 645-2007 server started, device registry initialized");
            }
        }
        logger.info("DL/T 645-2007 server protocol logic connected");
    }

    @Override
    public void onDisconnect(ConversationContext<Dlt645Frame> context) {
        logger.info("DL/T 645-2007 device disconnected (server mode)");
    }

    // --- Device registry access ---

    private DeviceChannelRegistry getDeviceRegistry() {
        if (serverContext != null && serverContext.getDeviceRegistry() != null) {
            return serverContext.getDeviceRegistry();
        }
        if (conversationContext != null && serverContext != null) {
            Channel ch = conversationContext.getChannel();
            if (ch.parent() != null) {
                ch = ch.parent();
            }
            DeviceChannelRegistry registry = ch.attr(TcpServerChannelFactory.DEVICE_REGISTRY_KEY).get();
            if (registry != null) {
                serverContext.setDeviceRegistry(registry);
                return registry;
            }
        }
        return null;
    }

    private String resolveDeviceId() {
        if (targetDeviceId != null && !targetDeviceId.isBlank()) {
            return targetDeviceId;
        }
        DeviceChannelRegistry registry = getDeviceRegistry();
        if (registry != null && registry.getDeviceCount() > 0) {
            var devices = registry.getRegisteredDevices();
            if (!devices.isEmpty()) {
                return devices.iterator().next();
            }
        }
        throw new PlcRuntimeException(
            "No target device specified. Set target-device-id parameter or wait for a device to connect.");
    }

    private Channel getDeviceChannel(String deviceId) {
        DeviceChannelRegistry registry = getDeviceRegistry();
        if (registry == null) {
            throw new PlcRuntimeException("Device registry not available");
        }
        Channel channel = registry.getChannel(deviceId);
        if (channel == null || !channel.isActive()) {
            throw new PlcRuntimeException("Device not connected: " + deviceId);
        }
        return channel;
    }

    @SuppressWarnings("unchecked")
    private DeviceConversationHandler<Dlt645Frame> getDeviceHandler(Channel channel) {
        DeviceConversationHandler<Dlt645Frame> handler = DeviceConversationHandler.getFromChannel(channel);
        if (handler == null) {
            throw new PlcRuntimeException("Device conversation handler not found on channel");
        }
        return handler;
    }

    // ========== Ping ==========

    @Override
    public CompletableFuture<PlcPingResponse> ping(PlcPingRequest pingRequest) {
        var future = new CompletableFuture<PlcPingResponse>();
        try {
            var deviceId = resolveDeviceId();
            var deviceChannel = getDeviceChannel(deviceId);
            var handler = getDeviceHandler(deviceChannel);

            var diBytes = new byte[]{0x00, 0x01, 0x00, 0x00};
            var frame = buildReadDataFrame(targetMeterAddress, diBytes);

            handler.sendRequest(frame, response -> true, requestTimeout)
                .whenComplete((response, error) -> {
                    if (error != null) {
                        future.completeExceptionally(error);
                    } else {
                        future.complete(new DefaultPlcPingResponse(pingRequest, PlcResponseCode.OK));
                    }
                });
        } catch (PlcRuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    // ========== Read ==========

    @Override
    public CompletableFuture<PlcReadResponse> read(PlcReadRequest readRequest) {
        var future = new CompletableFuture<PlcReadResponse>();
        var request = (DefaultPlcReadRequest) readRequest;

        if (request.getTagNames().size() != 1) {
            future.completeExceptionally(
                new PlcRuntimeException("DL/T 645-2007 only supports single tag requests"));
            return future;
        }

        var tagName = request.getTagNames().iterator().next();
        PlcTag tag = request.getTag(tagName);

        try {
            var deviceId = resolveDeviceId();
            var deviceChannel = getDeviceChannel(deviceId);
            var handler = getDeviceHandler(deviceChannel);

            // Command tag: cmd:read-address
            if (tag instanceof Dlt645CommandTag) {
                var cmdTag = (Dlt645CommandTag) tag;
                if (cmdTag.getCommandType() != CommandType.READ_ADDRESS) {
                    future.completeExceptionally(
                        new PlcRuntimeException("Only cmd:read-address is supported in read requests"));
                    return future;
                }
                return executeReadAddress(request, tagName, deviceId, handler);
            }

            // Standard DI tag: read data (0x11)
            var dlt645Tag = (Dlt645Tag) tag;
            return executeReadData(request, tagName, dlt645Tag, deviceId, handler);

        } catch (PlcRuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private CompletableFuture<PlcReadResponse> executeReadAddress(
        DefaultPlcReadRequest request, String tagName,
        String deviceId, DeviceConversationHandler<Dlt645Frame> handler) {

        var future = new CompletableFuture<PlcReadResponse>();
        var frame = new Dlt645Frame(BROADCAST_ADDRESS, ControlCode.READ_ADDRESS,
            (short) 0, new byte[0]);

        handler.sendRequest(frame, response -> true, requestTimeout)
            .whenComplete((response, error) -> {
                if (error != null) {
                    future.completeExceptionally(error);
                    return;
                }

                PlcValue plcValue = null;
                PlcResponseCode responseCode;

                var controlCode = response.getControl();
                if (controlCode == ControlCode.READ_ADDRESS_ERROR) {
                    logger.warn("Read address error from device '{}': {}",
                        deviceId, StaticHelper.describeError(response.getDataPlain()));
                    responseCode = PlcResponseCode.REMOTE_ERROR;
                } else if (controlCode == ControlCode.READ_ADDRESS_RESPONSE) {
                    plcValue = new PlcSTRING(formatAddressMsbFirst(response.getDataPlain()));
                    responseCode = PlcResponseCode.OK;
                } else {
                    logger.warn("Unexpected control code for read-address from '{}': 0x{}",
                        deviceId, String.format("%02X", controlCode.getValue()));
                    responseCode = PlcResponseCode.INTERNAL_ERROR;
                }

                future.complete(new DefaultPlcReadResponse(request,
                    Collections.singletonMap(tagName,
                        new DefaultPlcResponseItem<>(responseCode, plcValue))));
            });

        return future;
    }

    private CompletableFuture<PlcReadResponse> executeReadData(
        DefaultPlcReadRequest request, String tagName, Dlt645Tag tag,
        String deviceId, DeviceConversationHandler<Dlt645Frame> handler) {

        var future = new CompletableFuture<PlcReadResponse>();
        var frame = buildReadDataFrame(targetMeterAddress, tag.getDataIdentifier());

        logger.debug("Reading from device '{}': {}", deviceId, tag);

        handler.sendRequest(frame, response -> true, requestTimeout)
            .whenComplete((response, error) -> {
                if (error != null) {
                    future.completeExceptionally(error);
                    return;
                }

                var controlCode = response.getControl();

                if (controlCode == ControlCode.READ_DATA_ERROR) {
                    logger.warn("Read error from device '{}' for tag {}: {}",
                        deviceId, tagName,
                        StaticHelper.describeError(response.getDataPlain()));
                    future.complete(new DefaultPlcReadResponse(request,
                        Collections.singletonMap(tagName,
                            new DefaultPlcResponseItem<>(PlcResponseCode.REMOTE_ERROR, null))));
                    return;
                }

                if (controlCode == ControlCode.READ_DATA_RESPONSE) {
                    completeReadDataResponse(future, request, tagName, tag, response.getDataPlain());
                    return;
                }

                if (controlCode == ControlCode.READ_DATA_RESPONSE_MORE) {
                    // D5=1: accumulate subsequent frames
                    accumulateSubsequentData(future, request, tagName, tag,
                        response.getDataPlain(), 1, deviceId, handler);
                    return;
                }

                logger.warn("Unexpected control code in read response from '{}': 0x{}",
                    deviceId, String.format("%02X", controlCode.getValue()));
                future.complete(new DefaultPlcReadResponse(request,
                    Collections.singletonMap(tagName,
                        new DefaultPlcResponseItem<>(PlcResponseCode.INTERNAL_ERROR, null))));
            });

        return future;
    }

    private void accumulateSubsequentData(
        CompletableFuture<PlcReadResponse> future, DefaultPlcReadRequest request,
        String tagName, Dlt645Tag tag, byte[] accumulatedData, int seqNumber,
        String deviceId, DeviceConversationHandler<Dlt645Frame> handler) {

        var di = tag.getDataIdentifier();
        var dataPlain = new byte[5];
        for (int i = 0; i < 4; i++) {
            dataPlain[i] = di[3 - i];
        }
        dataPlain[4] = (byte) seqNumber;

        var subFrame = new Dlt645Frame(targetMeterAddress, ControlCode.READ_SUBSEQUENT_DATA,
            (short) dataPlain.length, dataPlain);

        handler.sendRequest(subFrame, response -> true, requestTimeout)
            .whenComplete((response, error) -> {
                if (error != null) {
                    // Return what we have
                    completeReadDataResponse(future, request, tagName, tag, accumulatedData);
                    return;
                }

                var controlCode = response.getControl();

                if (controlCode == ControlCode.READ_SUBSEQUENT_DATA_ERROR) {
                    logger.warn("Read subsequent error from '{}' for tag {}: {}",
                        deviceId, tagName,
                        StaticHelper.describeError(response.getDataPlain()));
                    completeReadDataResponse(future, request, tagName, tag, accumulatedData);
                    return;
                }

                var newData = mergeSubsequentData(accumulatedData, response.getDataPlain());

                if (controlCode == ControlCode.READ_SUBSEQUENT_DATA_RESPONSE) {
                    completeReadDataResponse(future, request, tagName, tag, newData);
                    return;
                }

                if (controlCode == ControlCode.READ_SUBSEQUENT_DATA_RESPONSE_MORE) {
                    accumulateSubsequentData(future, request, tagName, tag, newData,
                        seqNumber + 1, deviceId, handler);
                    return;
                }

                completeReadDataResponse(future, request, tagName, tag, newData);
            });
    }

    private byte[] mergeSubsequentData(byte[] accumulated, byte[] subsequent) {
        if (subsequent == null || subsequent.length <= 5) {
            return accumulated;
        }
        var baos = new ByteArrayOutputStream();
        baos.write(accumulated, 0, accumulated.length);
        baos.write(subsequent, 5, subsequent.length - 5);
        return baos.toByteArray();
    }

    private void completeReadDataResponse(
        CompletableFuture<PlcReadResponse> future, DefaultPlcReadRequest request,
        String tagName, Dlt645Tag tag, byte[] dataPlain) {

        PlcValue plcValue = null;
        PlcResponseCode responseCode;
        try {
            plcValue = parseResponseData(dataPlain, tag);
            responseCode = PlcResponseCode.OK;
        } catch (Exception e) {
            logger.warn("Failed to parse read response for tag {}: {}", tagName, e.getMessage());
            responseCode = PlcResponseCode.INTERNAL_ERROR;
        }
        future.complete(new DefaultPlcReadResponse(request,
            Collections.singletonMap(tagName,
                new DefaultPlcResponseItem<>(responseCode, plcValue))));
    }

    // ========== Write ==========

    @Override
    public CompletableFuture<PlcWriteResponse> write(PlcWriteRequest writeRequest) {
        var future = new CompletableFuture<PlcWriteResponse>();
        var request = (DefaultPlcWriteRequest) writeRequest;

        if (request.getTagNames().size() != 1) {
            future.completeExceptionally(
                new PlcRuntimeException("DL/T 645-2007 only supports single tag requests"));
            return future;
        }

        try {
            var deviceId = resolveDeviceId();
            var deviceChannel = getDeviceChannel(deviceId);
            var handler = getDeviceHandler(deviceChannel);

            var tagName = request.getTagNames().iterator().next();
            PlcTag tag = request.getTag(tagName);
            var value = writeRequest.getPlcValue(tagName);

            // Command tag dispatch
            if (tag instanceof Dlt645CommandTag) {
                var cmdTag = (Dlt645CommandTag) tag;
                return executeCommandWrite(request, tagName, cmdTag, value, deviceId, handler);
            }

            // Standard DI tag: write data (0x14)
            var dlt645Tag = (Dlt645Tag) tag;
            return executeWriteData(request, tagName, dlt645Tag, value, deviceId, handler);

        } catch (PlcRuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private CompletableFuture<PlcWriteResponse> executeWriteData(
        DefaultPlcWriteRequest request, String tagName, Dlt645Tag tag, PlcValue value,
        String deviceId, DeviceConversationHandler<Dlt645Frame> handler) {

        var future = new CompletableFuture<PlcWriteResponse>();
        var frame = buildWriteDataFrame(targetMeterAddress, tag.getDataIdentifier(), serializeValue(value));

        logger.debug("Writing to device '{}': {}", deviceId, tag);

        handler.sendRequest(frame, response -> true, requestTimeout)
            .whenComplete((response, error) -> {
                if (error != null) {
                    future.completeExceptionally(error);
                    return;
                }

                PlcResponseCode responseCode;
                var controlCode = response.getControl();
                if (controlCode == ControlCode.WRITE_DATA_ERROR) {
                    logger.warn("Write error from device '{}' for tag {}: {}",
                        deviceId, tagName,
                        StaticHelper.describeError(response.getDataPlain()));
                    responseCode = PlcResponseCode.REMOTE_ERROR;
                } else if (controlCode == ControlCode.WRITE_DATA_RESPONSE) {
                    responseCode = PlcResponseCode.OK;
                } else {
                    logger.warn("Unexpected control code in write response from '{}': 0x{}",
                        deviceId, String.format("%02X", controlCode.getValue()));
                    responseCode = PlcResponseCode.INTERNAL_ERROR;
                }

                future.complete(new DefaultPlcWriteResponse(request,
                    Collections.singletonMap(tagName, responseCode)));
            });

        return future;
    }

    private CompletableFuture<PlcWriteResponse> executeCommandWrite(
        DefaultPlcWriteRequest request, String tagName,
        Dlt645CommandTag cmdTag, PlcValue value,
        String deviceId, DeviceConversationHandler<Dlt645Frame> handler) {

        var cmdType = cmdTag.getCommandType();
        switch (cmdType) {
            case WRITE_ADDRESS:
                return executeWriteAddress(request, tagName, value, deviceId, handler);
            case FREEZE:
                return executeSimpleCommand(request, tagName, deviceId, handler,
                    ControlCode.FREEZE_DATA, ControlCode.FREEZE_DATA_RESPONSE, ControlCode.FREEZE_DATA_ERROR,
                    buildFreezeData(value));
            case CHANGE_BAUD_RATE:
                return executeSimpleCommand(request, tagName, deviceId, handler,
                    ControlCode.CHANGE_BAUD_RATE, ControlCode.CHANGE_BAUD_RATE_RESPONSE, ControlCode.CHANGE_BAUD_RATE_ERROR,
                    buildBaudRateData(value));
            case CHANGE_PASSWORD:
                return executeSimpleCommand(request, tagName, deviceId, handler,
                    ControlCode.CHANGE_PASSWORD, ControlCode.CHANGE_PASSWORD_RESPONSE, ControlCode.CHANGE_PASSWORD_ERROR,
                    buildChangePasswordData(value));
            case CLEAR_MAX_DEMAND:
                return executeSimpleCommand(request, tagName, deviceId, handler,
                    ControlCode.CLEAR_MAX_DEMAND, ControlCode.CLEAR_MAX_DEMAND_RESPONSE, ControlCode.CLEAR_MAX_DEMAND_ERROR,
                    buildAuthOnlyData());
            case CLEAR_METER:
                return executeSimpleCommand(request, tagName, deviceId, handler,
                    ControlCode.CLEAR_METER, ControlCode.CLEAR_METER_RESPONSE, ControlCode.CLEAR_METER_ERROR,
                    buildAuthOnlyData());
            case CLEAR_EVENT:
                return executeSimpleCommand(request, tagName, deviceId, handler,
                    ControlCode.CLEAR_EVENT, ControlCode.CLEAR_EVENT_RESPONSE, ControlCode.CLEAR_EVENT_ERROR,
                    buildAuthOnlyData());
            default:
                var future = new CompletableFuture<PlcWriteResponse>();
                future.completeExceptionally(
                    new PlcRuntimeException("Command '" + cmdType.getKeyword() + "' is not a write command"));
                return future;
        }
    }

    private CompletableFuture<PlcWriteResponse> executeWriteAddress(
        DefaultPlcWriteRequest request, String tagName, PlcValue value,
        String deviceId, DeviceConversationHandler<Dlt645Frame> handler) {

        var future = new CompletableFuture<PlcWriteResponse>();
        var newAddress = Dlt645DriverContext.parseMeterAddress(value.getString());
        var frame = new Dlt645Frame(BROADCAST_ADDRESS, ControlCode.WRITE_ADDRESS,
            (short) newAddress.length, newAddress);

        handler.sendRequest(frame, response -> true, requestTimeout)
            .whenComplete((response, error) -> {
                if (error != null) {
                    future.completeExceptionally(error);
                    return;
                }

                PlcResponseCode responseCode;
                var controlCode = response.getControl();
                if (controlCode == ControlCode.WRITE_ADDRESS_ERROR) {
                    logger.warn("Write address error from device '{}': {}",
                        deviceId, StaticHelper.describeError(response.getDataPlain()));
                    responseCode = PlcResponseCode.REMOTE_ERROR;
                } else if (controlCode == ControlCode.WRITE_ADDRESS_RESPONSE) {
                    responseCode = PlcResponseCode.OK;
                } else {
                    logger.warn("Unexpected control code for write-address from '{}': 0x{}",
                        deviceId, String.format("%02X", controlCode.getValue()));
                    responseCode = PlcResponseCode.INTERNAL_ERROR;
                }
                future.complete(new DefaultPlcWriteResponse(request,
                    Collections.singletonMap(tagName, responseCode)));
            });

        return future;
    }

    private CompletableFuture<PlcWriteResponse> executeSimpleCommand(
        DefaultPlcWriteRequest request, String tagName,
        String deviceId, DeviceConversationHandler<Dlt645Frame> handler,
        ControlCode reqCode, ControlCode respCode, ControlCode errCode,
        byte[] dataPlain) {

        var future = new CompletableFuture<PlcWriteResponse>();
        var frame = new Dlt645Frame(targetMeterAddress, reqCode, (short) dataPlain.length, dataPlain);

        handler.sendRequest(frame, response -> true, requestTimeout)
            .whenComplete((response, error) -> {
                if (error != null) {
                    future.completeExceptionally(error);
                    return;
                }

                PlcResponseCode responseCode;
                var controlCode = response.getControl();
                if (controlCode == errCode) {
                    logger.warn("Command {} error from '{}': {}",
                        reqCode.name(), deviceId,
                        StaticHelper.describeError(response.getDataPlain()));
                    responseCode = PlcResponseCode.REMOTE_ERROR;
                } else if (controlCode == respCode) {
                    responseCode = PlcResponseCode.OK;
                } else {
                    logger.warn("Unexpected control code for {} from '{}': 0x{}",
                        reqCode.name(), deviceId,
                        String.format("%02X", controlCode.getValue()));
                    responseCode = PlcResponseCode.INTERNAL_ERROR;
                }
                future.complete(new DefaultPlcWriteResponse(request,
                    Collections.singletonMap(tagName, responseCode)));
            });

        return future;
    }

    // --- Device query API ---

    public int getConnectedDeviceCount() {
        DeviceChannelRegistry registry = getDeviceRegistry();
        return registry != null ? registry.getDeviceCount() : 0;
    }

    public boolean isDeviceConnected(String deviceId) {
        DeviceChannelRegistry registry = getDeviceRegistry();
        return registry != null && registry.isRegistered(deviceId);
    }

    public DeviceChannelRegistry getPublicDeviceRegistry() {
        return getDeviceRegistry();
    }

    public byte[] getPassword() {
        return password;
    }

    public byte[] getOperatorCode() {
        return operatorCode;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    // ========== Frame construction ==========

    private Dlt645Frame buildReadDataFrame(byte[] address, byte[] dataIdentifier) {
        var dataPlain = new byte[4];
        for (int i = 0; i < 4; i++) {
            dataPlain[i] = dataIdentifier[3 - i];
        }
        return new Dlt645Frame(address, ControlCode.READ_DATA, (short) dataPlain.length, dataPlain);
    }

    private Dlt645Frame buildWriteDataFrame(byte[] address, byte[] dataIdentifier, byte[] payload) {
        var dataPlain = new byte[4 + 4 + 4 + payload.length];
        for (int i = 0; i < 4; i++) {
            dataPlain[i] = dataIdentifier[3 - i];
        }
        System.arraycopy(password, 0, dataPlain, 4, 4);
        System.arraycopy(operatorCode, 0, dataPlain, 8, 4);
        System.arraycopy(payload, 0, dataPlain, 12, payload.length);
        return new Dlt645Frame(address, ControlCode.WRITE_DATA, (short) dataPlain.length, dataPlain);
    }

    // ========== Command data builders ==========

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

    // ========== Data parsing ==========

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
        return new PlcLREAL(DataIdentifiers.decodeValue(diHex, valueData));
    }

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
}
