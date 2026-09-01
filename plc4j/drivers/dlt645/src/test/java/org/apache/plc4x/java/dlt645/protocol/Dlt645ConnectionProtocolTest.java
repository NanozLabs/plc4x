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
package org.apache.plc4x.java.dlt645.protocol;

import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.types.PlcValueType;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.dlt645.config.Dlt645Configuration;
import org.apache.plc4x.java.dlt645.context.Dlt645DriverContext;
import org.apache.plc4x.java.dlt645.readwrite.ControlCode;
import org.apache.plc4x.java.dlt645.readwrite.Dlt645Frame;
import org.apache.plc4x.java.dlt645.tag.Dlt645CommandTag;
import org.apache.plc4x.java.dlt645.tag.Dlt645Tag;
import org.apache.plc4x.java.dlt645.tag.Dlt645WildcardTag;
import org.apache.plc4x.java.dlt645.tag.Dlt645WildcardTag.SubTagMapping;
import org.apache.plc4x.java.spi.buffers.bytebased.WriteBufferByteBased;
import org.apache.plc4x.java.spi.drivers.exceptions.MessageCodecException;
import org.apache.plc4x.java.spi.drivers.functions.PlcReader;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcReadRequest;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcWriteRequest;
import org.apache.plc4x.java.spi.drivers.messages.items.DefaultPlcTagItem;
import org.apache.plc4x.java.spi.drivers.messages.items.PlcTagItem;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.transports.api.config.TransportConfiguration;
import org.apache.plc4x.java.spi.transports.api.exceptions.TransportException;
import org.apache.plc4x.java.spi.values.PlcDATE_AND_TIME;
import org.apache.plc4x.java.spi.values.PlcLREAL;
import org.apache.plc4x.java.spi.values.PlcREAL;
import org.apache.plc4x.java.spi.values.PlcStruct;
import org.apache.plc4x.java.spi.values.PlcSTRING;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Dlt645ConnectionProtocolTest {

    private static final byte[] METER_ADDRESS = Dlt645DriverContext.parseMeterAddress("123456789012");

    private Dlt645Configuration configuration;
    private Dlt645Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        configuration = new Dlt645Configuration();
        TransportInstance<?> transport = mock(TransportInstance.class);
        AuditLog auditLog = mock(AuditLog.class);
        when(auditLog.isEnabled()).thenReturn(false);
        connection = new Dlt645Connection(configuration, transport, auditLog);
        setField("password", new byte[]{1, 2, 3, 4});
        setField("operatorCode", new byte[]{5, 6, 7, 8});
        setField("meterAddress", METER_ADDRESS);
    }

    @Test
    void mergesSubsequentDataBeforeTrailingSequenceByte() throws Exception {
        byte[] initial = {0, 0, 1, 0, 0x11, 0x22};
        byte[] next = {0, 0, 1, 0, 0x33, 0x44, 0x01};

        assertArrayEquals(new byte[]{0, 0, 1, 0, 0x11, 0x22, 0x33, 0x44},
            (byte[]) invoke("mergeSubsequentData", new Class<?>[]{byte[].class, byte[].class}, initial, next));
    }

    @Test
    void managementPayloadsMatchTheStandard() throws Exception {
        assertArrayEquals(new byte[]{0x30, 0x12, 0x15, 0x08},
            (byte[]) invoke("buildFreezeData", new Class<?>[]{PlcValue.class}, new PlcSTRING("08151230")));
        assertArrayEquals(new byte[]{0x08},
            (byte[]) invoke("buildBaudRateData", new Class<?>[]{PlcValue.class}, new PlcSTRING("08")));
        assertArrayEquals(new byte[]{0, 0, 0x0C, 0x04, 0, 0, 0, 4, 0x12, 0x34, 0x56, 0x78},
            (byte[]) invoke("buildChangePasswordData", new Class<?>[]{PlcValue.class},
                new PlcSTRING("040C0000:00000004:12345678")));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, (byte) 0xFF, 0, 1, 3},
            (byte[]) invoke("buildClearEventData", new Class<?>[]{PlcValue.class},
                new PlcSTRING("030100FF")));
        assertArrayEquals(new byte[]{0x05, 0x04, 0x03, 0x02, 0x01, 0x24},
            (byte[]) invoke("buildBroadcastTimeData", new Class<?>[]{PlcValue.class},
                new PlcSTRING("240102030405")));
    }

    @Test
    void numericWritesAreFixedLengthAndScaled() throws Exception {
        Dlt645Tag tag = Dlt645Tag.of("00010000");
        assertArrayEquals(new byte[]{0x56, 0x34, 0x12, 0x00},
            (byte[]) invoke("serializeValue", new Class<?>[]{Dlt645Tag.class, PlcValue.class},
                tag, new PlcLREAL(1234.56)));
    }

    @Test
    void rejectsRawWritesThatExceedTheStandardDataLength() throws Exception {
        byte[] payload = new byte[39];

        var exception = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> invoke("buildWriteDataFrame", new Class<?>[]{byte[].class, byte[].class, byte[].class},
                new byte[4], payload, METER_ADDRESS));

        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    }

    @Test
    void sendFailureClearsPendingRequest() throws Exception {
        Dlt645MessageCodec codec = mock(Dlt645MessageCodec.class);
        Dlt645Frame frame = new Dlt645Frame(new byte[6], ControlCode.READ_DATA, (short) 4, new byte[4]);
        doThrow(new MessageCodecException("send failed")).when(codec).send(frame);
        setField("messageCodec", codec);
        CompletableFuture<Dlt645Frame> response = new CompletableFuture<>();

        invoke("dispatchRequest",
            new Class<?>[]{Dlt645Frame.class, long.class, CompletableFuture.class}, frame, 1L, response);

        assertTrue(response.isCompletedExceptionally());
        assertNull(getField("pendingRequest"));
    }

    @Test
    void ignoresFramesWithUnknownControlCodes() {
        Dlt645Frame frame = new Dlt645Frame(new byte[6], null, (short) 0, new byte[0]);

        assertDoesNotThrow(() -> invoke("handleIncomingMessage",
            new Class<?>[]{Dlt645Frame.class}, frame));
    }

    @Test
    void wildcardDecodingPreservesTemporalSemantics() throws Exception {
        byte[] values = {
            0x05, 0x04, 0x03, 0x04, 0x02, 0x01, 0x24,
            0x04, 0x02, 0x01, 0x24,
            0x05, 0x04, 0x03
        };

        PlcStruct result = (PlcStruct) invoke("parseWildcardResponseData",
            new Class<?>[]{String.class, byte[].class}, "040001FF", values);

        assertInstanceOf(PlcDATE_AND_TIME.class, result.getValue("04000101"));
    }

    @Test
    void pendingMatcherRejectsWrongDiAndSequence() throws Exception {
        byte[] address = {0x12, (byte) 0x90, 0x78, 0x56, 0x34, 0x12};
        byte[] requestData = {0, 0, 1, 0, 1};
        Dlt645Frame request = new Dlt645Frame(address, ControlCode.READ_SUBSEQUENT_DATA,
            (short) requestData.length, requestData);
        Class<?> pendingClass = Arrays.stream(Dlt645Connection.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("PendingRequest"))
            .findFirst().orElseThrow();
        Constructor<?> constructor = pendingClass.getDeclaredConstructor(long.class, Dlt645Frame.class,
            CompletableFuture.class);
        constructor.setAccessible(true);
        Object pending = constructor.newInstance(1L, request, new CompletableFuture<Dlt645Frame>());
        Method matches = pendingClass.getDeclaredMethod("matches", Dlt645Frame.class);
        matches.setAccessible(true);

        Dlt645Frame correct = new Dlt645Frame(address, ControlCode.READ_SUBSEQUENT_DATA_RESPONSE,
            (short) 7, new byte[]{0, 0, 1, 0, 0x12, 0x34, 1});
        Dlt645Frame wrongSequence = new Dlt645Frame(address, ControlCode.READ_SUBSEQUENT_DATA_RESPONSE,
            (short) 7, new byte[]{0, 0, 1, 0, 0x12, 0x34, 2});
        Dlt645Frame wrongDi = new Dlt645Frame(address, ControlCode.READ_SUBSEQUENT_DATA_RESPONSE,
            (short) 7, new byte[]{0, 0, 2, 0, 0x12, 0x34, 1});

        assertEquals(true, matches.invoke(pending, correct));
        assertEquals(false, matches.invoke(pending, wrongSequence));
        assertEquals(false, matches.invoke(pending, wrongDi));
    }

    @Test
    void tagMeterOverridesConnectionDefault() throws Exception {
        setField("meterAddress", METER_ADDRESS);
        Dlt645Tag tagged = Dlt645Tag.of("999999999999/00010000");
        byte[] resolved = (byte[]) invoke("resolveMeter", new Class<?>[]{PlcTag.class}, tagged);
        assertArrayEquals(Dlt645DriverContext.parseMeterAddress("999999999999"), resolved);
        byte[] fallback = (byte[]) invoke("resolveMeter", new Class<?>[]{PlcTag.class}, Dlt645Tag.of("00010000"));
        assertArrayEquals(METER_ADDRESS, fallback);
    }

    @Test
    void onConnectAllowsMissingMeterAddress() throws Exception {
        try {
            invoke("onConnect", new Class<?>[0]);
        } catch (InvocationTargetException ignored) {
            // Mock transport may fail after the address is accepted.
        }
        assertNull(getField("meterAddress"));
    }

    @Test
    void onConnectRejectsInvalidMeterAddress() {
        configuration.setMeterAddress("not-a-meter");
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> invoke("onConnect", new Class<?>[0]));
        assertInstanceOf(PlcConnectionException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("meter-address"));
    }

    @Test
    void onConnectRejectsInvalidPassword() {
        configuration.setMeterAddress("123456789012");
        configuration.setPassword("zzzzzzzz");
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> invoke("onConnect", new Class<?>[0]));
        assertInstanceOf(PlcConnectionException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("password"));
    }

    @Test
    void onConnectRejectsInvalidOperatorCode() {
        configuration.setMeterAddress("123456789012");
        configuration.setOperatorCode("12");
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> invoke("onConnect", new Class<?>[0]));
        assertInstanceOf(PlcConnectionException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("operator-code"));
    }

    @Test
    void pendingMatcherAcceptsReadAndWriteAddressErrors() throws Exception {
        byte[] wildcard = {
            (byte) 0xAA, (byte) 0xAA, (byte) 0xAA,
            (byte) 0xAA, (byte) 0xAA, (byte) 0xAA
        };
        Object readPending = newPending(new Dlt645Frame(wildcard, ControlCode.READ_ADDRESS, (short) 0, new byte[0]));
        Object writePending = newPending(new Dlt645Frame(wildcard, ControlCode.WRITE_ADDRESS,
            (short) 6, METER_ADDRESS));

        assertEquals(true, matches(readPending, new Dlt645Frame(wildcard, ControlCode.READ_ADDRESS_ERROR,
            (short) 1, new byte[]{0x02})));
        assertEquals(true, matches(writePending, new Dlt645Frame(wildcard, ControlCode.WRITE_ADDRESS_ERROR,
            (short) 1, new byte[]{0x02})));
        assertEquals(false, matches(readPending, new Dlt645Frame(wildcard, ControlCode.READ_ADDRESS_ERROR,
            (short) 0, new byte[0])));
    }

    @Test
    void readAddressErrorCompletesWithRemoteError() throws Exception {
        Dlt645MessageCodec codec = mock(Dlt645MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcReadRequest request = readRequest("addr", Dlt645CommandTag.of("cmd:read-address"));

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcReadResponse> future = (CompletableFuture<PlcReadResponse>) invoke(
            "executeReadAddress",
            new Class<?>[]{DefaultPlcReadRequest.class, String.class},
            request, "addr");

        invoke("handleIncomingMessage", new Class<?>[]{Dlt645Frame.class},
            new Dlt645Frame(new byte[6], ControlCode.READ_ADDRESS_ERROR, (short) 1, new byte[]{0x02}));

        PlcReadResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.REMOTE_ERROR, response.getResponseCode("addr"));
    }

    @Test
    void writeAddressErrorCompletesWithRemoteError() throws Exception {
        Dlt645MessageCodec codec = mock(Dlt645MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcWriteRequest request = mock(DefaultPlcWriteRequest.class);

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcWriteResponse> future = (CompletableFuture<PlcWriteResponse>) invoke(
            "executeWriteAddress",
            new Class<?>[]{DefaultPlcWriteRequest.class, String.class, PlcValue.class},
            request, "addr", new PlcSTRING("123456789012"));

        invoke("handleIncomingMessage", new Class<?>[]{Dlt645Frame.class},
            new Dlt645Frame(new byte[6], ControlCode.WRITE_ADDRESS_ERROR, (short) 1, new byte[]{0x01}));

        PlcWriteResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.REMOTE_ERROR, response.getResponseCode("addr"));
    }

    @Test
    void onReadSingleTagDoesNotDropTheValue() throws Exception {
        Dlt645MessageCodec codec = mock(Dlt645MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcReadRequest request = readRequest("energy", Dlt645Tag.of("00010000"));

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcReadResponse> future = (CompletableFuture<PlcReadResponse>) invoke(
            "onRead", new Class<?>[]{org.apache.plc4x.java.api.messages.PlcReadRequest.class}, request);

        ArgumentCaptor<Dlt645Frame> captor = ArgumentCaptor.forClass(Dlt645Frame.class);
        verify(codec).send(captor.capture());
        invoke("handleIncomingMessage", new Class<?>[]{Dlt645Frame.class},
            okReadResponse(captor.getValue(), new byte[]{0x56, 0x34, 0x12, 0x00}));

        PlcReadResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("energy"));
        assertNotNull(response.getPlcValue("energy"));
    }

    @Test
    void onReadMultipleTagsMergesPerTagResults() throws Exception {
        Dlt645MessageCodec codec = mock(Dlt645MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcReadRequest request = readRequest(
            "energy", Dlt645Tag.of("00010000"),
            "voltage", Dlt645Tag.of("02010100"));

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcReadResponse> future = (CompletableFuture<PlcReadResponse>) invoke(
            "onRead", new Class<?>[]{org.apache.plc4x.java.api.messages.PlcReadRequest.class}, request);

        ArgumentCaptor<Dlt645Frame> captor = ArgumentCaptor.forClass(Dlt645Frame.class);
        verify(codec).send(captor.capture());
        invoke("handleIncomingMessage", new Class<?>[]{Dlt645Frame.class},
            okReadResponse(captor.getValue(), new byte[]{0x56, 0x34, 0x12, 0x00}));

        verify(codec, timeout(1000).times(2)).send(captor.capture());
        Dlt645Frame second = captor.getAllValues().get(captor.getAllValues().size() - 1);
        invoke("handleIncomingMessage", new Class<?>[]{Dlt645Frame.class},
            okReadResponse(second, new byte[]{0x00, 0x22}));

        PlcReadResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("energy"));
        assertEquals(PlcResponseCode.OK, response.getResponseCode("voltage"));
    }

    @Test
    void timeoutDiscardsLeftoverBytesBeforeTheNextRequest() throws Exception {
        configuration.setRequestTimeout(80);
        var transport = new ScriptedTransport();
        var codec = new Dlt645MessageCodec(transport, frame -> {
            try {
                invoke("handleIncomingMessage", new Class<?>[]{Dlt645Frame.class}, frame);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        setField("messageCodec", codec);

        byte[] di = {0x00, 0x00, 0x01, 0x00};
        Dlt645Frame request = new Dlt645Frame(METER_ADDRESS, ControlCode.READ_DATA, (short) 4, di);
        CompletableFuture<Dlt645Frame> first = new CompletableFuture<>();
        invoke("dispatchRequest",
            new Class<?>[]{Dlt645Frame.class, long.class, CompletableFuture.class}, request, 1L, first);

        assertThrows(Exception.class, () -> first.get(1, TimeUnit.SECONDS));

        transport.deliver(wireBytes(okReadResponse(request, new byte[]{0x56, 0x34, 0x12, 0x00})));

        CompletableFuture<Dlt645Frame> second = new CompletableFuture<>();
        invoke("dispatchRequest",
            new Class<?>[]{Dlt645Frame.class, long.class, CompletableFuture.class}, request, 2L, second);

        codec.processIncomingData();
        assertFalse(second.isDone());
        assertEquals(0, transport.getNumBytesAvailable());
    }

    @Test
    void unexpectedSubsequentControlIsAnError() throws Exception {
        Dlt645MessageCodec codec = mock(Dlt645MessageCodec.class);
        setField("messageCodec", codec);
        Dlt645Tag tag = Dlt645Tag.of("00010000");
        DefaultPlcReadRequest request = readRequest("energy", tag);

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcReadResponse> future = (CompletableFuture<PlcReadResponse>) invoke(
            "accumulateSubsequentData",
            new Class<?>[]{DefaultPlcReadRequest.class, String.class, Dlt645Tag.class,
                byte[].class, byte[].class, int.class, int.class},
            request, "energy", tag, METER_ADDRESS, new byte[]{0, 0, 1, 0, 0x11, 0x22}, 1, 0);

        completePending(new Dlt645Frame(METER_ADDRESS, ControlCode.READ_DATA_RESPONSE,
            (short) 8, new byte[]{0, 0, 1, 0, 0x33, 0x44, 0x55, 0x66}));

        PlcReadResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.INTERNAL_ERROR, response.getResponseCode("energy"));
        assertNull(response.getPlcValue("energy"));
    }

    @Test
    void wildcardDecodingHonorsRequestedNumericType() throws Exception {
        byte[] wildcardDi = {0x02, 0x01, (byte) 0xFF, 0x00};
        var tag = new Dlt645WildcardTag(wildcardDi, List.of(
            new SubTagMapping("a", 0, "02010100", PlcValueType.REAL)));
        byte[] values = {0x00, 0x22, 0x00, 0x22, 0x00, 0x22};

        PlcStruct result = (PlcStruct) invoke("parseWildcardResponseData",
            new Class<?>[]{String.class, byte[].class, Dlt645Tag.class}, "0201FF00", values, tag);

        assertInstanceOf(PlcREAL.class, result.getValue("02010100"));
        assertInstanceOf(PlcLREAL.class, result.getValue("02010200"));
    }

    @Test
    void broadcastFreezeCompletesWithoutWaitingForAResponse() throws Exception {
        setField("meterAddress", Dlt645DriverContext.BROADCAST_ADDRESS);
        Dlt645MessageCodec codec = mock(Dlt645MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcWriteRequest request = mock(DefaultPlcWriteRequest.class);

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcWriteResponse> future = (CompletableFuture<PlcWriteResponse>) invoke(
            "executeFreeze",
            new Class<?>[]{DefaultPlcWriteRequest.class, String.class, Dlt645CommandTag.class, PlcValue.class},
            request, "freeze", Dlt645CommandTag.of("cmd:freeze"), new PlcSTRING("99999999"));

        PlcWriteResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("freeze"));
        verify(codec).send(argThat(frame ->
            frame.getControl() == ControlCode.FREEZE_DATA
                && Dlt645DriverContext.isBroadcastAddress(frame.getAddress())));
    }

    @Test
    void unicastFreezeWaitsForTheSlaveResponse() throws Exception {
        setField("meterAddress", Dlt645DriverContext.parseMeterAddress("123456789012"));
        Dlt645MessageCodec codec = mock(Dlt645MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcWriteRequest request = mock(DefaultPlcWriteRequest.class);

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcWriteResponse> future = (CompletableFuture<PlcWriteResponse>) invoke(
            "executeFreeze",
            new Class<?>[]{DefaultPlcWriteRequest.class, String.class, Dlt645CommandTag.class, PlcValue.class},
            request, "freeze", Dlt645CommandTag.of("cmd:freeze"), new PlcSTRING("99999999"));

        assertFalse(future.isDone());
        verify(codec).send(argThat(frame ->
            frame.getControl() == ControlCode.FREEZE_DATA
                && !Dlt645DriverContext.isBroadcastAddress(frame.getAddress())));
    }

    private Object invoke(String name, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = Dlt645Connection.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(connection, arguments);
    }

    private Object getField(String name) throws Exception {
        Field field = Dlt645Connection.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(connection);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = Dlt645Connection.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(connection, value);
    }

    private Object newPending(Dlt645Frame request) throws Exception {
        Class<?> pendingClass = Arrays.stream(Dlt645Connection.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("PendingRequest"))
            .findFirst().orElseThrow();
        Constructor<?> constructor = pendingClass.getDeclaredConstructor(long.class, Dlt645Frame.class,
            CompletableFuture.class);
        constructor.setAccessible(true);
        return constructor.newInstance(1L, request, new CompletableFuture<Dlt645Frame>());
    }

    private boolean matches(Object pending, Dlt645Frame response) throws Exception {
        Method matches = pending.getClass().getDeclaredMethod("matches", Dlt645Frame.class);
        matches.setAccessible(true);
        return (boolean) matches.invoke(pending, response);
    }

    private DefaultPlcReadRequest readRequest(Object... nameAndTag) {
        var tags = new LinkedHashMap<String, PlcTagItem<PlcTag>>();
        for (int i = 0; i < nameAndTag.length; i += 2) {
            tags.put((String) nameAndTag[i], new DefaultPlcTagItem<>((PlcTag) nameAndTag[i + 1]));
        }
        return new DefaultPlcReadRequest(mock(PlcReader.class), tags);
    }

    private static Dlt645Frame okReadResponse(Dlt645Frame request, byte[] value) {
        byte[] requestData = request.getDataPlain();
        byte[] data = new byte[4 + value.length];
        System.arraycopy(requestData, 0, data, 0, 4);
        System.arraycopy(value, 0, data, 4, value.length);
        return new Dlt645Frame(request.getAddress(), ControlCode.READ_DATA_RESPONSE,
            (short) data.length, data);
    }

    private void completePending(Dlt645Frame frame) throws Exception {
        Object pending = getField("pendingRequest");
        assertNotNull(pending);
        Field responseFutureField = pending.getClass().getDeclaredField("responseFuture");
        responseFutureField.setAccessible(true);
        @SuppressWarnings("unchecked")
        CompletableFuture<Dlt645Frame> responseFuture =
            (CompletableFuture<Dlt645Frame>) responseFutureField.get(pending);
        responseFuture.complete(frame);
    }

    private static byte[] wireBytes(Dlt645Frame frame) throws Exception {
        var buffer = new WriteBufferByteBased(new byte[frame.getLengthInBytes()]);
        frame.serialize(buffer);
        return buffer.getBytes();
    }

    private static final class ScriptedTransport implements TransportInstance<TransportConfiguration> {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int readPosition;
        private byte[] written;

        void deliver(byte[] bytes) {
            buffer.writeBytes(bytes);
        }

        @Override
        public TransportConfiguration getConfiguration() {
            return null;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public int getNumBytesAvailable() {
            return buffer.size() - readPosition;
        }

        @Override
        public byte[] peekReadableBytes(int count) throws TransportException {
            if (count > getNumBytesAvailable()) {
                throw new TransportException("peek beyond available");
            }
            return Arrays.copyOfRange(buffer.toByteArray(), readPosition, readPosition + count);
        }

        @Override
        public byte[] read(int count) throws TransportException {
            byte[] result = peekReadableBytes(count);
            readPosition += count;
            return result;
        }

        @Override
        public void write(byte[] bytes) {
            written = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public void close() {
        }
    }
}
