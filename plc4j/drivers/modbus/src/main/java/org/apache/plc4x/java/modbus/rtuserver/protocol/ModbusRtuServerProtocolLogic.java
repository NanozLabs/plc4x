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
package org.apache.plc4x.java.modbus.rtuserver.protocol;

import io.netty.channel.Channel;
import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.api.messages.*;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.modbus.base.protocol.ModbusProtocolLogic;
import org.apache.plc4x.java.modbus.base.tag.ModbusTag;
import org.apache.plc4x.java.modbus.base.tag.ModbusTagHandler;
import org.apache.plc4x.java.modbus.readwrite.*;
import org.apache.plc4x.java.modbus.rtuserver.config.ModbusRtuServerConfiguration;
import org.apache.plc4x.java.modbus.rtuserver.context.ModbusRtuServerContext;
import org.apache.plc4x.java.modbus.types.ModbusByteOrder;
import org.apache.plc4x.java.spi.ConversationContext;
import org.apache.plc4x.java.spi.configuration.HasConfiguration;
import org.apache.plc4x.java.spi.context.DriverContext;
import org.apache.plc4x.java.spi.connection.PlcTagHandler;
import org.apache.plc4x.java.spi.generation.ParseException;
import org.apache.plc4x.java.spi.messages.*;
import org.apache.plc4x.java.spi.messages.utils.DefaultPlcResponseItem;
import org.apache.plc4x.java.spi.transaction.RequestTransactionManager;
import org.apache.plc4x.java.transport.tcp.server.DeviceChannelRegistry;
import org.apache.plc4x.java.transport.tcp.server.DeviceConversationHandler;
import org.apache.plc4x.java.transport.tcp.server.TcpServerChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * Protocol logic for Modbus RTU Server mode (RTU over TCP).
 *
 * <p>Uses ModbusRtuADU framing (address + PDU + CRC) instead of ModbusTcpADU
 * (MBAP header). This is required for DTU devices that speak Modbus RTU
 * transparently over TCP connections.</p>
 */
public class ModbusRtuServerProtocolLogic extends ModbusProtocolLogic<ModbusRtuADU>
    implements HasConfiguration<ModbusRtuServerConfiguration> {

    private static final Logger logger = LoggerFactory.getLogger(ModbusRtuServerProtocolLogic.class);

    private ModbusRtuServerConfiguration configuration;
    private ModbusRtuServerContext serverContext;

    public ModbusRtuServerProtocolLogic() {
        super(DriverType.MODBUS_RTU);
    }

    @Override
    public void setConfiguration(ModbusRtuServerConfiguration configuration) {
        this.configuration = configuration;
        this.requestTimeout = Duration.ofMillis(configuration.getRequestTimeout());
        this.unitIdentifier = (short) configuration.getDefaultUnitIdentifier();
        this.pingAddress = new ModbusTagHandler().parseTag(configuration.getPingAddress());
        this.defaultPayloadByteOrder = configuration.getDefaultPayloadByteOrder();
        this.tm = new RequestTransactionManager(1);
    }

    @Override
    public void setDriverContext(DriverContext driverContext) {
        super.setDriverContext(driverContext);
        if (driverContext instanceof ModbusRtuServerContext) {
            this.serverContext = (ModbusRtuServerContext) driverContext;
        }
    }

    @Override
    public void onConnect(ConversationContext<ModbusRtuADU> context) {
        super.onConnect(context);
        if (serverContext != null && serverContext.getDeviceRegistry() == null) {
            Channel channel = context.getChannel();
            if (channel.parent() != null) {
                channel = channel.parent();
            }
            DeviceChannelRegistry registry = channel.attr(TcpServerChannelFactory.DEVICE_REGISTRY_KEY).get();
            if (registry != null) {
                serverContext.setDeviceRegistry(registry);
            }
        }
        logger.info("ModbusRtuServerProtocolLogic connected in server mode");
    }

    @Override
    public PlcTagHandler getTagHandler() {
        return new ModbusTagHandler();
    }

    @Override
    public void close(ConversationContext<ModbusRtuADU> context) {
        if (tm != null) {
            tm.shutdown();
        }
    }

    private String resolveDeviceId(ModbusTag tag) {
        String tagDeviceId = tag.getDeviceId();
        if (tagDeviceId != null && !tagDeviceId.isBlank()) {
            return tagDeviceId;
        }
        String configDeviceId = configuration.getTargetDeviceId();
        if (configDeviceId != null && !configDeviceId.isBlank()) {
            return configDeviceId;
        }
        throw new PlcRuntimeException(
            "No target device specified. Use tag config {device-id:\"..\"} or set target-device-id parameter.");
    }

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

    private DeviceConversationHandler<ModbusRtuADU> getDeviceHandler(Channel channel) {
        DeviceConversationHandler<ModbusRtuADU> handler = DeviceConversationHandler.getFromChannel(channel);
        if (handler == null) {
            throw new PlcRuntimeException("Device conversation handler not found on channel");
        }
        return handler;
    }

    @Override
    public CompletableFuture<PlcPingResponse> ping(PlcPingRequest pingRequest) {
        CompletableFuture<PlcPingResponse> future = new CompletableFuture<>();
        try {
            String deviceId = resolveDeviceId((ModbusTag) pingAddress);
            Channel deviceChannel = getDeviceChannel(deviceId);
            DeviceConversationHandler<ModbusRtuADU> handler = getDeviceHandler(deviceChannel);

            ModbusPDU readRequestPdu = getReadRequestPdu(pingAddress);
            final short unitId = getUnitId(pingAddress);
            ModbusRtuADU modbusRtuADU = new ModbusRtuADU(unitId, readRequestPdu);

            handler.sendRequest(modbusRtuADU,
                    response -> response.getAddress() == unitId,
                    requestTimeout)
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

    @Override
    public CompletableFuture<PlcReadResponse> read(PlcReadRequest readRequest) {
        CompletableFuture<PlcReadResponse> future = new CompletableFuture<>();
        DefaultPlcReadRequest request = (DefaultPlcReadRequest) readRequest;

        if (request.getTagNames().size() != 1) {
            future.completeExceptionally(new PlcRuntimeException("Modbus only supports single tag requests"));
            return future;
        }

        try {
            String tagName = request.getTagNames().iterator().next();
            ModbusTag tag = (ModbusTag) request.getTag(tagName);

            String deviceId = resolveDeviceId(tag);
            Channel deviceChannel = getDeviceChannel(deviceId);
            DeviceConversationHandler<ModbusRtuADU> handler = getDeviceHandler(deviceChannel);

            logger.debug("Reading from device '{}': {}", deviceId, tag);

            final ModbusPDU requestPdu = getReadRequestPdu(tag);
            final short unitId = getUnitId(tag);
            ModbusRtuADU modbusRtuADU = new ModbusRtuADU(unitId, requestPdu);

            handler.sendRequest(modbusRtuADU,
                    response -> response.getAddress() == unitId,
                    requestTimeout)
                .whenComplete((response, error) -> {
                    if (error != null) {
                        future.completeExceptionally(error);
                        return;
                    }

                    PlcValue plcValue = null;
                    PlcResponseCode responseCode;
                    ModbusPDU responsePdu = response.getPdu();

                    if (responsePdu instanceof ModbusPDUError errorResponse) {
                        responseCode = getErrorCode(errorResponse);
                    } else {
                        try {
                            ModbusByteOrder byteOrder = defaultPayloadByteOrder;
                            if (tag.getByteOrder() != null) {
                                byteOrder = tag.getByteOrder();
                            }
                            plcValue = toPlcValue(requestPdu, responsePdu, tag.getDataType(), byteOrder);
                            responseCode = PlcResponseCode.OK;
                        } catch (ParseException e) {
                            responseCode = PlcResponseCode.INTERNAL_ERROR;
                            logger.error("Error parsing response for device '{}': {}", deviceId, e.getMessage());
                        }
                    }

                    PlcReadResponse plcResponse = new DefaultPlcReadResponse(request,
                        Collections.singletonMap(tagName, new DefaultPlcResponseItem<>(responseCode, plcValue)));
                    future.complete(plcResponse);
                });

        } catch (PlcRuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public CompletableFuture<PlcWriteResponse> write(PlcWriteRequest writeRequest) {
        CompletableFuture<PlcWriteResponse> future = new CompletableFuture<>();
        DefaultPlcWriteRequest request = (DefaultPlcWriteRequest) writeRequest;

        if (request.getTagNames().size() != 1) {
            future.completeExceptionally(new PlcRuntimeException("Modbus only supports single tag requests"));
            return future;
        }

        try {
            String tagName = request.getTagNames().iterator().next();
            ModbusTag tag = (ModbusTag) request.getTag(tagName);

            String deviceId = resolveDeviceId(tag);
            Channel deviceChannel = getDeviceChannel(deviceId);
            DeviceConversationHandler<ModbusRtuADU> handler = getDeviceHandler(deviceChannel);

            logger.debug("Writing to device '{}': {}", deviceId, tag);

            final ModbusPDU requestPdu = getWriteRequestPdu(tag, writeRequest.getPlcValue(tagName));
            final short unitId = getUnitId(tag);
            ModbusRtuADU modbusRtuADU = new ModbusRtuADU(unitId, requestPdu);

            handler.sendRequest(modbusRtuADU,
                    response -> response.getAddress() == unitId,
                    requestTimeout)
                .whenComplete((response, error) -> {
                    if (error != null) {
                        future.completeExceptionally(error);
                        return;
                    }

                    PlcResponseCode responseCode;
                    ModbusPDU responsePdu = response.getPdu();

                    if (responsePdu instanceof ModbusPDUError errorResponse) {
                        responseCode = getErrorCode(errorResponse);
                    } else {
                        responseCode = PlcResponseCode.OK;
                        if (responsePdu instanceof ModbusPDUWriteSingleCoilResponse coilResponse) {
                            ModbusPDUWriteSingleCoilRequest coilRequest =
                                (ModbusPDUWriteSingleCoilRequest) requestPdu;
                            if (!((coilResponse.getValue() == coilRequest.getValue()) &&
                                (coilResponse.getAddress() == coilRequest.getAddress()))) {
                                responseCode = PlcResponseCode.REMOTE_ERROR;
                            }
                        }
                    }

                    PlcWriteResponse plcResponse = new DefaultPlcWriteResponse(request,
                        Collections.singletonMap(tagName, responseCode));
                    future.complete(plcResponse);
                });

        } catch (PlcRuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

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
}
