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
package org.apache.plc4x.java.modbus.rtuserver;

import io.netty.buffer.ByteBuf;
import org.apache.plc4x.java.modbus.base.optimizer.ModbusOptimizer;
import org.apache.plc4x.java.modbus.base.tag.ModbusTag;
import org.apache.plc4x.java.modbus.readwrite.DriverType;
import org.apache.plc4x.java.modbus.readwrite.ModbusADU;
import org.apache.plc4x.java.modbus.readwrite.ModbusRtuADU;
import org.apache.plc4x.java.modbus.rtuserver.config.ModbusRtuServerConfiguration;
import org.apache.plc4x.java.modbus.rtuserver.context.ModbusRtuServerContext;
import org.apache.plc4x.java.modbus.rtuserver.protocol.ModbusRtuServerProtocolLogic;
import org.apache.plc4x.java.spi.configuration.PlcConnectionConfiguration;
import org.apache.plc4x.java.spi.configuration.PlcTransportConfiguration;
import org.apache.plc4x.java.spi.connection.GeneratedDriverBase;
import org.apache.plc4x.java.spi.connection.ProtocolStackConfigurer;
import org.apache.plc4x.java.spi.connection.SingleProtocolStackConfigurer;
import org.apache.plc4x.java.spi.generation.ParseException;
import org.apache.plc4x.java.spi.generation.ReadBufferByteBased;
import org.apache.plc4x.java.spi.optimizer.BaseOptimizer;
import org.apache.plc4x.java.transport.tcp.server.TcpServerTransportConfiguration;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * Modbus RTU Server Driver - RTU over TCP in server/passive mode.
 *
 * <p>Connection URL format:</p>
 * <pre>
 * modbus-rtu-server:tcpserver://[bind-address]:port[?options]
 * </pre>
 */
public class ModbusRtuServerDriver extends GeneratedDriverBase<ModbusRtuADU> {

    @Override
    public String getProtocolCode() {
        return "modbus-rtu-server";
    }

    @Override
    public String getProtocolName() {
        return "Modbus RTU Server";
    }

    @Override
    protected Class<? extends PlcConnectionConfiguration> getConfigurationClass() {
        return ModbusRtuServerConfiguration.class;
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

    @Override
    protected boolean awaitSetupComplete() {
        return false;
    }

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
    protected ProtocolStackConfigurer<ModbusRtuADU> getStackConfigurer() {
        return SingleProtocolStackConfigurer.builder(
                ModbusRtuADU.class,
                io -> (ModbusRtuADU) ModbusRtuADU.staticParse(io, DriverType.MODBUS_RTU, true))
            .withProtocol(ModbusRtuServerProtocolLogic.class)
            .withDriverContext(ModbusRtuServerContext.class)
            .withPacketSizeEstimator(ByteLengthEstimator.class)
            .build();
    }

    /**
     * Estimate the length of a Modbus RTU packet.
     */
    public static class ByteLengthEstimator implements ToIntFunction<ByteBuf> {
        @Override
        public int applyAsInt(ByteBuf byteBuf) {
            if (byteBuf.readableBytes() >= 4) {
                byte[] buf = new byte[byteBuf.readableBytes()];
                byteBuf.getBytes(byteBuf.readerIndex(), buf);
                ReadBufferByteBased reader = new ReadBufferByteBased(buf);
                try {
                    ModbusADU modbusADU = ModbusRtuADU.staticParse(reader, DriverType.MODBUS_RTU, true);
                    return modbusADU.getLengthInBytes();
                } catch (ParseException e) {
                    return -1;
                } catch (ArrayIndexOutOfBoundsException e) {
                    byteBuf.discardReadBytes();
                    return -1;
                }
            }
            return -1;
        }
    }

    @Override
    public ModbusTag prepareTag(String tagAddress) {
        return ModbusTag.of(tagAddress);
    }
}
