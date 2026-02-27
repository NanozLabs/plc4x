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

import io.netty.buffer.ByteBuf;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.dlt645.Dlt645Driver;
import org.apache.plc4x.java.dlt645.readwrite.Dlt645Frame;
import org.apache.plc4x.java.dlt645.server.config.Dlt645ServerConfiguration;
import org.apache.plc4x.java.dlt645.server.context.Dlt645ServerDriverContext;
import org.apache.plc4x.java.dlt645.server.protocol.Dlt645ServerProtocolLogic;
import org.apache.plc4x.java.dlt645.tag.Dlt645CommandTag;
import org.apache.plc4x.java.dlt645.tag.Dlt645Tag;
import org.apache.plc4x.java.spi.configuration.PlcConnectionConfiguration;
import org.apache.plc4x.java.spi.configuration.PlcTransportConfiguration;
import org.apache.plc4x.java.spi.connection.GeneratedDriverBase;
import org.apache.plc4x.java.spi.connection.ProtocolStackConfigurer;
import org.apache.plc4x.java.spi.connection.SingleProtocolStackConfigurer;
import org.apache.plc4x.java.spi.optimizer.BaseOptimizer;
import org.apache.plc4x.java.spi.optimizer.SingleTagOptimizer;
import org.apache.plc4x.java.transport.tcp.server.TcpServerTransportConfiguration;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * PLC4X Server Driver for DL/T 645-2007 (reverse connection mode).
 * <p>
 * Listens for inbound TCP connections from smart meters or DTU devices.
 * <p>
 * Connection URI format:
 * <pre>
 * dlt645-server:tcpserver://0.0.0.0:8899
 * dlt645-server:tcpserver://0.0.0.0:8899?target-device-id=123456789012
 * </pre>
 */
public class Dlt645ServerDriver extends GeneratedDriverBase<Dlt645Frame> {

    @Override
    public String getProtocolCode() {
        return "dlt645-server";
    }

    @Override
    public String getProtocolName() {
        return "DL/T 645-2007 Server";
    }

    @Override
    protected Class<? extends PlcConnectionConfiguration> getConfigurationClass() {
        return Dlt645ServerConfiguration.class;
    }

    @Override
    protected Optional<Class<? extends PlcTransportConfiguration>> getTransportConfigurationClass(
        String transportCode) {
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
        return new SingleTagOptimizer();
    }

    @Override
    protected ProtocolStackConfigurer<Dlt645Frame> getStackConfigurer() {
        return SingleProtocolStackConfigurer.builder(
                Dlt645Frame.class,
                io -> Dlt645Frame.staticParse(io, true))
            .withProtocol(Dlt645ServerProtocolLogic.class)
            .withDriverContext(Dlt645ServerDriverContext.class)
            .withPacketSizeEstimator(Dlt645Driver.Dlt645ByteLengthEstimator.class)
            .build();
    }

    @Override
    public PlcTag prepareTag(String tagAddress) {
        if (Dlt645CommandTag.matches(tagAddress)) {
            return Dlt645CommandTag.of(tagAddress);
        }
        return Dlt645Tag.of(tagAddress);
    }
}
