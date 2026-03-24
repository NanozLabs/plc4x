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
package org.apache.plc4x.java.dlt645;

import io.netty.buffer.ByteBuf;
import org.apache.plc4x.java.dlt645.config.Dlt645Configuration;
import org.apache.plc4x.java.dlt645.context.Dlt645DriverContext;
import org.apache.plc4x.java.dlt645.protocol.Dlt645ProtocolLogic;
import org.apache.plc4x.java.dlt645.readwrite.Dlt645Frame;
import org.apache.plc4x.java.dlt645.readwrite.utils.StaticHelper;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.dlt645.tag.Dlt645CommandTag;
import org.apache.plc4x.java.dlt645.tag.Dlt645Tag;
import org.apache.plc4x.java.spi.configuration.PlcConnectionConfiguration;
import org.apache.plc4x.java.spi.connection.GeneratedDriverBase;
import org.apache.plc4x.java.spi.connection.ProtocolStackConfigurer;
import org.apache.plc4x.java.spi.connection.SingleProtocolStackConfigurer;
import org.apache.plc4x.java.dlt645.optimizer.Dlt645BlockOptimizer;
import org.apache.plc4x.java.spi.optimizer.BaseOptimizer;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * PLC4X Driver for DL/T 645-2007 (China Smart Meter Communication Protocol).
 * <p>
 * Supports Serial (RS-485) and TCP transports.
 * <p>
 * Connection URI format:
 * <ul>
 *   <li>{@code dlt645:serial:///dev/ttyUSB0?meter-address=123456789012}</li>
 *   <li>{@code dlt645:tcp://192.168.1.100:8899?meter-address=123456789012}</li>
 * </ul>
 */
public class Dlt645Driver extends GeneratedDriverBase<Dlt645Frame> {

    @Override
    public String getProtocolCode() {
        return "dlt645";
    }

    @Override
    public String getProtocolName() {
        return "DL/T 645-2007";
    }

    @Override
    protected Class<? extends PlcConnectionConfiguration> getConfigurationClass() {
        return Dlt645Configuration.class;
    }

    @Override
    protected Optional<String> getDefaultTransportCode() {
        return Optional.of("serial");
    }

    @Override
    protected List<String> getSupportedTransportCodes() {
        return Arrays.asList("tcp", "serial");
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
        return new Dlt645BlockOptimizer();
    }

    @Override
    protected ProtocolStackConfigurer<Dlt645Frame> getStackConfigurer() {
        return SingleProtocolStackConfigurer.builder(
                Dlt645Frame.class,
                io -> Dlt645Frame.staticParse(io, true))
            .withProtocol(Dlt645ProtocolLogic.class)
            .withDriverContext(Dlt645DriverContext.class)
            .withPacketSizeEstimator(Dlt645ByteLengthEstimator.class)
            .build();
    }

    @Override
    public PlcTag prepareTag(String tagAddress) {
        if (Dlt645CommandTag.matches(tagAddress)) {
            return Dlt645CommandTag.of(tagAddress);
        }
        return Dlt645Tag.of(tagAddress);
    }

    /**
     * Estimate packet size for DL/T 645-2007 frames.
     * <p>
     * Frame: 68H + address(6) + 68H + control + length + data(length) + CS + 16H
     * Total = 12 + length bytes.
     */
    public static class Dlt645ByteLengthEstimator implements ToIntFunction<ByteBuf> {
        @Override
        public int applyAsInt(ByteBuf byteBuf) {
            if (byteBuf.readableBytes() < 10) {
                return -1;
            }

            var readerIndex = byteBuf.readerIndex();
            var buf = new byte[byteBuf.readableBytes()];
            byteBuf.getBytes(readerIndex, buf);

            // Find frame start (0x68 at offset 0, 0x68 at offset 7)
            var frameStart = StaticHelper.findFrameStart(buf);
            if (frameStart < 0) {
                return -1;
            }

            // Skip garbage bytes before frame start
            if (frameStart > 0) {
                byteBuf.skipBytes(frameStart);
                return -1;
            }

            var frameLength = StaticHelper.estimateFrameLength(buf, 0);
            if (frameLength < 0) {
                return -1;
            }
            return frameLength;
        }
    }
}
