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
package org.apache.plc4x.java.modbus.tcpserver;

import io.netty.buffer.ByteBuf;
import org.apache.plc4x.java.modbus.base.optimizer.ModbusOptimizer;
import org.apache.plc4x.java.modbus.base.tag.ModbusTag;
import org.apache.plc4x.java.modbus.readwrite.DriverType;
import org.apache.plc4x.java.modbus.readwrite.ModbusTcpADU;
import org.apache.plc4x.java.modbus.tcpserver.config.ModbusTcpServerConfiguration;
import org.apache.plc4x.java.modbus.tcpserver.context.ModbusTcpServerContext;
import org.apache.plc4x.java.modbus.tcpserver.protocol.ModbusTcpServerProtocolLogic;
import org.apache.plc4x.java.spi.configuration.PlcConnectionConfiguration;
import org.apache.plc4x.java.spi.configuration.PlcTransportConfiguration;
import org.apache.plc4x.java.spi.connection.GeneratedDriverBase;
import org.apache.plc4x.java.spi.connection.ProtocolStackConfigurer;
import org.apache.plc4x.java.spi.connection.SingleProtocolStackConfigurer;
import org.apache.plc4x.java.spi.optimizer.BaseOptimizer;
import org.apache.plc4x.java.transport.tcp.server.TcpServerTransportConfiguration;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * Modbus TCP Server Driver for accepting inbound DTU device connections.
 *
 * <p>This driver operates in server/passive mode, listening for incoming TCP
 * connections from DTU (Data Transfer Unit) devices. Once a device connects
 * and completes registration, a transparent Modbus TCP channel is established.</p>
 *
 * <h3>Connection URL Format:</h3>
 * <pre>
 * modbus-tcp-server:tcpserver://[bind-address]:port[?options]
 * </pre>
 *
 * <h3>Examples:</h3>
 * <pre>{@code
 * // Listen on all interfaces, port 502
 * modbus-tcp-server:tcpserver://0.0.0.0:502
 *
 * // With fixed-length registration (16 bytes)
 * modbus-tcp-server:tcpserver://0.0.0.0:502?registration-type=fixed&registration-length=16
 *
 * // With regex registration pattern
 * modbus-tcp-server:tcpserver://0.0.0.0:502?registration-type=regex&registration-pattern=REG:(.+)\r\n
 *
 * // With prefix-length registration
 * modbus-tcp-server:tcpserver://0.0.0.0:502?registration-type=prefix&registration-prefix-bytes=2
 * }</pre>
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>DTU device registration with flexible packet formats</li>
 *   <li>Multi-device connection management</li>
 *   <li>Device ID based routing and addressing</li>
 *   <li>Automatic connection lifecycle management</li>
 *   <li>Compatible with standard Modbus TCP protocol after registration</li>
 * </ul>
 *
 * @since 0.14.0
 * @see ModbusTcpServerConfiguration
 * @see ModbusTcpServerProtocolLogic
 */
public class ModbusTcpServerDriver extends GeneratedDriverBase<ModbusTcpADU> {

    @Override
    public String getProtocolCode() {
        return "modbus-tcp-server";
    }

    @Override
    public String getProtocolName() {
        return "Modbus TCP Server";
    }

    @Override
    protected Class<? extends PlcConnectionConfiguration> getConfigurationClass() {
        return ModbusTcpServerConfiguration.class;
    }

    @Override
    protected Optional<Class<? extends PlcTransportConfiguration>> getTransportConfigurationClass(String transportCode) {
        if ("tcpserver".equals(transportCode)) {
            return Optional.of(TcpServerTransportConfiguration.class);
        }
        return Optional.empty();
    }

    @Override
    protected Optional<String> getDefaultTransportCode() {
        return Optional.of("tcpserver");
    }

    @Override
    protected List<String> getSupportedTransportCodes() {
        return Collections.singletonList("tcpserver");
    }

    /**
     * Modbus doesn't have a login procedure, so there is no need to wait for a login to finish.
     * @return false
     */
    @Override
    protected boolean awaitSetupComplete() {
        return false;
    }

    /**
     * This protocol doesn't have a disconnect procedure, so there is no need to wait for a disconnect to finish.
     * @return false
     */
    @Override
    protected boolean awaitDisconnectComplete() {
        return false;
    }

    @Override
    protected boolean canPing() {
        return true;
    }

    @Override
    protected boolean canRead() {
        return true;
    }

    @Override
    protected boolean canWrite() {
        return true;
    }

    @Override
    protected BaseOptimizer getOptimizer() {
        return new ModbusOptimizer();
    }

    @Override
    protected ProtocolStackConfigurer<ModbusTcpADU> getStackConfigurer() {
        return SingleProtocolStackConfigurer.builder(
                ModbusTcpADU.class,
                (io) -> (ModbusTcpADU) ModbusTcpADU.staticParse(io, DriverType.MODBUS_TCP, true))
            .withProtocol(ModbusTcpServerProtocolLogic.class)
            .withDriverContext(ModbusTcpServerContext.class)
            .withPacketSizeEstimator(ByteLengthEstimator.class)
            .build();
    }

    /**
     * Estimate the Length of a Modbus TCP Packet.
     *
     * <p>Modbus TCP frame format:</p>
     * <pre>
     * +--------+--------+--------+--------+--------+--------+--------+...
     * | Trans  | Trans  | Proto  | Proto  | Length | Length | Unit   | PDU...
     * | ID Hi  | ID Lo  | ID Hi  | ID Lo  | Hi     | Lo     | ID     |
     * +--------+--------+--------+--------+--------+--------+--------+...
     *    0        1        2        3        4        5        6
     * </pre>
     *
     * <p>The length field (bytes 4-5) indicates the number of following bytes,
     * so total frame length = length field value + 6 (header bytes).</p>
     */
    public static class ByteLengthEstimator implements ToIntFunction<ByteBuf> {
        @Override
        public int applyAsInt(ByteBuf byteBuf) {
            if (byteBuf.readableBytes() >= 6) {
                // Length field is at offset 4 (2 bytes, big-endian)
                return byteBuf.getUnsignedShort(byteBuf.readerIndex() + 4) + 6;
            }
            return -1;
        }
    }

    @Override
    public ModbusTag prepareTag(String tagAddress) {
        return ModbusTag.of(tagAddress);
    }
}
