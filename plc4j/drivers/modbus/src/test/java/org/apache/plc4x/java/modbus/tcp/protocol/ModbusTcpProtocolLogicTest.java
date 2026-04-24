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
package org.apache.plc4x.java.modbus.tcp.protocol;

import org.apache.plc4x.java.api.authentication.PlcAuthentication;
import org.apache.plc4x.java.api.messages.PlcPingResponse;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.modbus.readwrite.ModbusPDU;
import org.apache.plc4x.java.modbus.readwrite.ModbusPDUReadHoldingRegistersResponse;
import org.apache.plc4x.java.modbus.readwrite.ModbusPDUWriteSingleRegisterResponse;
import org.apache.plc4x.java.modbus.readwrite.ModbusTcpADU;
import org.apache.plc4x.java.modbus.tcp.config.ModbusTcpConfiguration;
import org.apache.plc4x.java.modbus.types.ModbusByteOrder;
import org.apache.plc4x.java.spi.ConversationContext;
import org.apache.plc4x.java.spi.configuration.PlcConnectionConfiguration;
import org.apache.plc4x.java.spi.messages.DefaultPlcPingRequest;
import org.apache.plc4x.java.spi.messages.DefaultPlcReadRequest;
import org.apache.plc4x.java.spi.messages.DefaultPlcWriteRequest;
import org.apache.plc4x.java.spi.messages.PlcPinger;
import org.apache.plc4x.java.spi.messages.PlcReader;
import org.apache.plc4x.java.spi.messages.PlcWriter;
import org.apache.plc4x.java.spi.transaction.RequestTransactionManager;
import org.apache.plc4x.java.spi.values.DefaultPlcValueHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class ModbusTcpProtocolLogicTest {

    private ModbusTcpProtocolLogic protocolLogic;
    private TestConversationContext conversationContext;
    private RequestTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws Exception {
        protocolLogic = new ModbusTcpProtocolLogic();
        ModbusTcpConfiguration configuration = new ModbusTcpConfiguration();
        configuration.setRequestTimeout(50);
        configuration.setDefaultUnitIdentifier(1);
        configuration.setPingAddress("holding-register:1:INT");
        configuration.setDefaultPayloadByteOrder(ModbusByteOrder.BIG_ENDIAN);
        protocolLogic.setConfiguration(configuration);

        conversationContext = new TestConversationContext();
        protocolLogic.setConversationContext(conversationContext);
        transactionManager = extractTransactionManager(protocolLogic);
    }

    @Test
    void pingTimeoutReleasesTransactionAndAllowsNextRequest() throws Exception {
        CompletableFuture<PlcPingResponse> first = protocolLogic.ping(new DefaultPlcPingRequest((PlcPinger) protocolLogic::ping));

        CapturedRequest firstRequest = conversationContext.awaitRequest();
        assertThat(transactionManager.getNumberOfActiveRequests()).isEqualTo(1);

        firstRequest.timeout(new TimeoutException("boom"));
        assertFutureCause(first, TimeoutException.class);
        waitForActiveRequests(0);

        CompletableFuture<PlcPingResponse> second = protocolLogic.ping(new DefaultPlcPingRequest((PlcPinger) protocolLogic::ping));
        CapturedRequest secondRequest = conversationContext.awaitRequest();
        secondRequest.complete(conversationContext.responseForLastRequest(new ModbusPDUReadHoldingRegistersResponse(new byte[]{0x00, 0x01})));

        assertThat(second.get(1, TimeUnit.SECONDS).getResponseCode()).isEqualTo(PlcResponseCode.OK);
        waitForActiveRequests(0);
        assertThat(conversationContext.getRequestCount()).isEqualTo(2);
    }

    @Test
    void readErrorReleasesTransactionAndAllowsNextRequest() throws Exception {
        PlcReadRequest firstReadRequest = new DefaultPlcReadRequest.Builder((PlcReader) protocolLogic::read, protocolLogic.getTagHandler())
            .addTagAddress("value", "holding-register:1:INT")
            .build();

        CompletableFuture<PlcReadResponse> first = protocolLogic.read(firstReadRequest);
        CapturedRequest firstRequest = conversationContext.awaitRequest();
        assertThat(transactionManager.getNumberOfActiveRequests()).isEqualTo(1);

        firstRequest.error(new IllegalStateException("decode error"));
        assertFutureCause(first, IllegalStateException.class);
        waitForActiveRequests(0);

        PlcReadRequest secondReadRequest = new DefaultPlcReadRequest.Builder((PlcReader) protocolLogic::read, protocolLogic.getTagHandler())
            .addTagAddress("value", "holding-register:1:INT")
            .build();

        CompletableFuture<PlcReadResponse> second = protocolLogic.read(secondReadRequest);
        CapturedRequest secondRequest = conversationContext.awaitRequest();
        secondRequest.complete(conversationContext.responseForLastRequest(new ModbusPDUReadHoldingRegistersResponse(new byte[]{0x00, 0x2A})));

        PlcReadResponse response = second.get(1, TimeUnit.SECONDS);
        assertThat(response.getResponseCode("value")).isEqualTo(PlcResponseCode.OK);
        waitForActiveRequests(0);
        assertThat(conversationContext.getRequestCount()).isEqualTo(2);
    }

    @Test
    void writeSuccessReleasesTransactionOnlyOnce() throws Exception {
        PlcWriteRequest request = new DefaultPlcWriteRequest.Builder((PlcWriter) protocolLogic::write, protocolLogic.getTagHandler(), new DefaultPlcValueHandler())
            .addTagAddress("value", "holding-register:1:INT", 7)
            .build();

        CompletableFuture<PlcWriteResponse> first = protocolLogic.write(request);
        CapturedRequest firstRequest = conversationContext.awaitRequest();
        firstRequest.complete(conversationContext.responseForLastRequest(new ModbusPDUWriteSingleRegisterResponse(1, 7)));

        PlcWriteResponse firstResponse = first.get(1, TimeUnit.SECONDS);
        assertThat(firstResponse.getResponseCode("value")).isEqualTo(PlcResponseCode.OK);
        waitForActiveRequests(0);

        CompletableFuture<PlcWriteResponse> second = protocolLogic.write(request);
        CapturedRequest secondRequest = conversationContext.awaitRequest();
        secondRequest.complete(conversationContext.responseForLastRequest(new ModbusPDUWriteSingleRegisterResponse(1, 7)));

        PlcWriteResponse secondResponse = second.get(1, TimeUnit.SECONDS);
        assertThat(secondResponse.getResponseCode("value")).isEqualTo(PlcResponseCode.OK);
        waitForActiveRequests(0);
        assertThat(conversationContext.getRequestCount()).isEqualTo(2);
    }

    private void assertFutureCause(CompletableFuture<?> future, Class<? extends Throwable> expectedCauseType) {
        try {
            future.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(expectedCauseType);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (TimeoutException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("Future completed successfully unexpectedly");
    }

    private RequestTransactionManager extractTransactionManager(ModbusTcpProtocolLogic protocolLogic) throws Exception {
        Field tmField = protocolLogic.getClass().getSuperclass().getDeclaredField("tm");
        tmField.setAccessible(true);
        return (RequestTransactionManager) tmField.get(protocolLogic);
    }

    private void waitForActiveRequests(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (transactionManager.getNumberOfActiveRequests() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(transactionManager.getNumberOfActiveRequests()).isEqualTo(expected);
    }

    private static final class TestConversationContext implements ConversationContext<ModbusTcpADU> {

        private final LinkedBlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
        private final AtomicInteger requestCount = new AtomicInteger();
        private volatile ModbusTcpADU lastRequest;

        @Override
        public PlcAuthentication getAuthentication() {
            return null;
        }

        @Override
        public io.netty.channel.Channel getChannel() {
            return null;
        }

        @Override
        public boolean isPassive() {
            return false;
        }

        @Override
        public void sendToWire(ModbusTcpADU msg) {
        }

        @Override
        public void fireConnected() {
        }

        @Override
        public void fireDisconnected() {
        }

        @Override
        public void fireDiscovered(PlcConnectionConfiguration c) {
        }

        @Override
        public SendRequestContext<ModbusTcpADU> sendRequest(ModbusTcpADU packet) {
            lastRequest = packet;
            requestCount.incrementAndGet();
            return new TestSendRequestContext<>(requests);
        }

        @Override
        public ExpectRequestContext<ModbusTcpADU> expectRequest(Class<ModbusTcpADU> clazz, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        CapturedRequest awaitRequest() throws InterruptedException {
            CapturedRequest request = requests.poll(1, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            return request;
        }

        ModbusTcpADU responseForLastRequest(ModbusPDU pdu) {
            return new ModbusTcpADU(lastRequest.getTransactionIdentifier(), lastRequest.getUnitIdentifier(), pdu);
        }

        int getRequestCount() {
            return requestCount.get();
        }
    }

    private static final class TestSendRequestContext<T> implements ConversationContext.SendRequestContext<T> {

        private final LinkedBlockingQueue<CapturedRequest> requests;
        private final List<Command> commands;
        private final Consumer<TimeoutException> onTimeoutConsumer;
        private final BiConsumer<Object, Throwable> onErrorConsumer;

        private TestSendRequestContext(LinkedBlockingQueue<CapturedRequest> requests) {
            this(requests, List.of(), null, null);
        }

        private TestSendRequestContext(
            LinkedBlockingQueue<CapturedRequest> requests,
            List<Command> commands,
            Consumer<TimeoutException> onTimeoutConsumer,
            BiConsumer<Object, Throwable> onErrorConsumer
        ) {
            this.requests = requests;
            this.commands = commands;
            this.onTimeoutConsumer = onTimeoutConsumer;
            this.onErrorConsumer = onErrorConsumer;
        }

        @Override
        public ConversationContext.SendRequestContext<T> name(String name) {
            return this;
        }

        @Override
        public ConversationContext.SendRequestContext<T> expectResponse(Class<T> clazz, Duration timeout) {
            return this;
        }

        @Override
        public ConversationContext.SendRequestContext<T> check(Predicate<T> checker) {
            List<Command> nextCommands = new ArrayList<>(commands);
            nextCommands.add(value -> {
                if (!checker.test((T) value)) {
                    throw new AssertionError("Predicate rejected response");
                }
                return value;
            });
            return new TestSendRequestContext<>(requests, nextCommands, onTimeoutConsumer, onErrorConsumer);
        }

        @Override
        public ConversationContext.ContextHandler handle(Consumer<T> packetConsumer) {
            requests.add(new CapturedRequest(new ArrayList<>(commands), value -> packetConsumer.accept((T) value), onTimeoutConsumer, onErrorConsumer));
            return new NoOpContextHandler();
        }

        @Override
        public CompletableFuture<T> toFuture() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConversationContext.SendRequestContext<T> onTimeout(Consumer<TimeoutException> packetConsumer) {
            return new TestSendRequestContext<>(requests, commands, packetConsumer, onErrorConsumer);
        }

        @Override
        public <E extends Throwable> ConversationContext.SendRequestContext<T> onError(BiConsumer<T, E> packetConsumer) {
            return new TestSendRequestContext<>(requests, commands, onTimeoutConsumer, (value, throwable) -> packetConsumer.accept((T) value, (E) throwable));
        }

        @Override
        public <R> ConversationContext.SendRequestContext<R> unwrap(Function<T, R> unwrapper) {
            List<Command> nextCommands = new ArrayList<>(commands);
            nextCommands.add(value -> unwrapper.apply((T) value));
            return new TestSendRequestContext<>(requests, nextCommands, onTimeoutConsumer, onErrorConsumer);
        }

        @Override
        public <R> ConversationContext.SendRequestContext<R> only(Class<R> clazz) {
            throw new UnsupportedOperationException();
        }
    }

    @FunctionalInterface
    private interface Command {
        Object apply(Object value);
    }

    private static final class CapturedRequest {

        private final List<Command> commands;
        private final Consumer<Object> packetConsumer;
        private final Consumer<TimeoutException> onTimeoutConsumer;
        private final BiConsumer<Object, Throwable> onErrorConsumer;

        private CapturedRequest(
            List<Command> commands,
            Consumer<Object> packetConsumer,
            Consumer<TimeoutException> onTimeoutConsumer,
            BiConsumer<Object, Throwable> onErrorConsumer
        ) {
            this.commands = commands;
            this.packetConsumer = packetConsumer;
            this.onTimeoutConsumer = onTimeoutConsumer;
            this.onErrorConsumer = onErrorConsumer;
        }

        void complete(Object response) {
            Object value = response;
            for (Command command : commands) {
                value = command.apply(value);
            }
            packetConsumer.accept(value);
        }

        void timeout(TimeoutException timeoutException) {
            onTimeoutConsumer.accept(timeoutException);
        }

        void error(Throwable throwable) {
            onErrorConsumer.accept(null, throwable);
        }
    }

    private static final class NoOpContextHandler implements ConversationContext.ContextHandler {

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public void cancel() {
        }

        @Override
        public void await() {
        }
    }
}
