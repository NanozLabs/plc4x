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
package org.apache.plc4x.java.modbus.tcpserver.protocol;

import io.netty.channel.Channel;
import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.api.messages.*;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.modbus.base.protocol.ModbusProtocolLogic;
import org.apache.plc4x.java.modbus.base.tag.ModbusTag;
import org.apache.plc4x.java.modbus.base.tag.ModbusTagHandler;
import org.apache.plc4x.java.modbus.readwrite.*;
import org.apache.plc4x.java.modbus.tcpserver.config.ModbusTcpServerConfiguration;
import org.apache.plc4x.java.modbus.tcpserver.context.ModbusTcpServerContext;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * Protocol logic for Modbus TCP Server mode.
 *
 * <p>This protocol logic extends the standard Modbus TCP protocol logic to support
 * server mode operations, where DTU devices connect to the server. The key differences
 * from client mode are:</p>
 *
 * <ul>
 *   <li>Multiple device connections managed through DeviceChannelRegistry</li>
 *   <li>Device ID based routing for read/write operations</li>
 *   <li>Tag-based device addressing using device-id config parameter</li>
 * </ul>
 *
 * <h3>Device Addressing:</h3>
 * <p>In server mode, specify the target device in the tag address:</p>
 * <pre>
 * holding-register:1:INT{device-id:"DEVICE001"}
 * </pre>
 *
 * <p>Alternatively, set a default target device via the {@code target-device-id}
 * configuration parameter in the connection URL.</p>
 *
 * @since 0.14.0
 */
public class ModbusTcpServerProtocolLogic extends ModbusProtocolLogic<ModbusTcpADU>
    implements HasConfiguration<ModbusTcpServerConfiguration> {

    private static final Logger logger = LoggerFactory.getLogger(ModbusTcpServerProtocolLogic.class);

    private ModbusTcpServerConfiguration configuration;
    private ModbusTcpServerContext serverContext;

    public ModbusTcpServerProtocolLogic() {
        super(DriverType.MODBUS_TCP);
    }

    @Override
    public void setConfiguration(ModbusTcpServerConfiguration configuration) {
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
        if (driverContext instanceof ModbusTcpServerContext) {
            this.serverContext = (ModbusTcpServerContext) driverContext;
        }
    }

    @Override
    public void onConnect(ConversationContext<ModbusTcpADU> context) {
        super.onConnect(context);
        logger.info("ModbusTcpServerProtocolLogic connected in server mode");
    }

    @Override
    public PlcTagHandler getTagHandler() {
        return new ModbusTagHandler();
    }

    @Override
    public void close(ConversationContext<ModbusTcpADU> context) {
        if (tm != null) {
            tm.shutdown();
        }
    }

    /**
     * Resolves the target device ID from tag and configuration.
     *
     * <p>Priority order:</p>
     * <ol>
     *   <li>Device ID specified in tag: {@code holding-register:1:INT{device-id:"DEV001"}}</li>
     *   <li>Default device ID from configuration: {@code target-device-id=DEV001}</li>
     * </ol>
     *
     * @param tag the Modbus tag
     * @return the resolved device ID
     * @throws PlcRuntimeException if no device ID can be resolved
     */
    private String resolveDeviceId(ModbusTag tag) {
        // First check tag-level device ID
        String tagDeviceId = tag.getDeviceId();
        if (tagDeviceId != null && !tagDeviceId.isBlank()) {
            return tagDeviceId;
        }

        // Fall back to configured default
        String configDeviceId = configuration.getTargetDeviceId();
        if (configDeviceId != null && !configDeviceId.isBlank()) {
            return configDeviceId;
        }

        throw new PlcRuntimeException(
            "No target device specified. Use tag config {device-id:\"..\"} or set target-device-id parameter.");
    }

    /**
     * Gets the channel for a specific device.
     *
     * @param deviceId the device identifier
     * @return the channel
     * @throws PlcRuntimeException if device is not connected
     */
    private Channel getDeviceChannel(String deviceId) {
        if (serverContext == null || serverContext.getDeviceRegistry() == null) {
            throw new PlcRuntimeException("Device registry not available");
        }

        Channel channel = serverContext.getDeviceRegistry().getChannel(deviceId);
        if (channel == null || !channel.isActive()) {
            throw new PlcRuntimeException("Device not connected: " + deviceId);
        }

        return channel;
    }

    /**
     * Gets the conversation handler for a device channel.
     */
    private DeviceConversationHandler<ModbusTcpADU> getDeviceHandler(Channel channel) {
        DeviceConversationHandler<ModbusTcpADU> handler = DeviceConversationHandler.getFromChannel(channel);
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
            DeviceConversationHandler<ModbusTcpADU> handler = getDeviceHandler(deviceChannel);

            ModbusPDU readRequestPdu = getReadRequestPdu(pingAddress);
            final short unitId = getUnitId(pingAddress);
            int transactionIdentifier = transactionIdentifierGenerator.getAndIncrement();
            if (transactionIdentifierGenerator.get() == 0xFFFF) {
                transactionIdentifierGenerator.set(1);
            }

            ModbusTcpADU modbusTcpADU = new ModbusTcpADU(transactionIdentifier, unitId, readRequestPdu);

            handler.sendRequest(modbusTcpADU,
                    response -> response.getTransactionIdentifier() == transactionIdentifier
                        && response.getUnitIdentifier() == unitId,
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

            // Resolve target device from tag or configuration
            String deviceId = resolveDeviceId(tag);
            Channel deviceChannel = getDeviceChannel(deviceId);
            DeviceConversationHandler<ModbusTcpADU> handler = getDeviceHandler(deviceChannel);

            logger.debug("Reading from device '{}': {}", deviceId, tag);

            final ModbusPDU requestPdu = getReadRequestPdu(tag);
            final short unitId = getUnitId(tag);

            int transactionIdentifier = transactionIdentifierGenerator.getAndIncrement();
            if (transactionIdentifierGenerator.get() == 0xFFFF) {
                transactionIdentifierGenerator.set(1);
            }

            ModbusTcpADU modbusTcpADU = new ModbusTcpADU(transactionIdentifier, unitId, requestPdu);

            handler.sendRequest(modbusTcpADU,
                    response -> response.getTransactionIdentifier() == transactionIdentifier
                        && response.getUnitIdentifier() == unitId,
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

            // Resolve target device from tag or configuration
            String deviceId = resolveDeviceId(tag);
            Channel deviceChannel = getDeviceChannel(deviceId);
            DeviceConversationHandler<ModbusTcpADU> handler = getDeviceHandler(deviceChannel);

            logger.debug("Writing to device '{}': {}", deviceId, tag);

            final ModbusPDU requestPdu = getWriteRequestPdu(tag, writeRequest.getPlcValue(tagName));
            final short unitId = getUnitId(tag);

            int transactionIdentifier = transactionIdentifierGenerator.getAndIncrement();
            if (transactionIdentifierGenerator.get() == 0xFFFF) {
                transactionIdentifierGenerator.set(1);
            }

            ModbusTcpADU modbusTcpADU = new ModbusTcpADU(transactionIdentifier, unitId, requestPdu);

            handler.sendRequest(modbusTcpADU,
                    response -> response.getTransactionIdentifier() == transactionIdentifier,
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

    /**
     * Gets the count of currently connected devices.
     *
     * @return the number of connected devices
     */
    public int getConnectedDeviceCount() {
        if (serverContext != null && serverContext.getDeviceRegistry() != null) {
            return serverContext.getDeviceRegistry().getDeviceCount();
        }
        return 0;
    }

    /**
     * Checks if a specific device is connected.
     *
     * @param deviceId the device identifier
     * @return true if the device is connected
     */
    public boolean isDeviceConnected(String deviceId) {
        if (serverContext != null && serverContext.getDeviceRegistry() != null) {
            return serverContext.getDeviceRegistry().isRegistered(deviceId);
        }
        return false;
    }

    /**
     * Gets the device registry for advanced operations.
     *
     * @return the device registry, or null if not available
     */
    public DeviceChannelRegistry getDeviceRegistry() {
        return serverContext != null ? serverContext.getDeviceRegistry() : null;
    }
}
