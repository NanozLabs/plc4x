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
package org.apache.plc4x.java.cjt188.protocol;

import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.cjt188.config.Cjt188Configuration;
import org.apache.plc4x.java.cjt188.context.Cjt188DriverContext;
import org.apache.plc4x.java.cjt188.readwrite.Cjt188Frame;
import org.apache.plc4x.java.cjt188.readwrite.ControlCode;
import org.apache.plc4x.java.cjt188.tag.Cjt188CommandTag;
import org.apache.plc4x.java.cjt188.tag.Cjt188Tag;
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
import org.apache.plc4x.java.spi.values.PlcSTRING;
import org.apache.plc4x.java.spi.values.PlcStruct;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Cjt188ConnectionProtocolTest {

    private static final byte[] METER_ADDRESS = Cjt188DriverContext.parseMeterAddress("123456789012", "10");

    private Cjt188Configuration configuration;
    private Cjt188Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        configuration = new Cjt188Configuration();
        configuration.setMeterAddress("123456789012");
        configuration.setMeterType("10");
        TransportInstance<?> transport = mock(TransportInstance.class);
        AuditLog auditLog = mock(AuditLog.class);
        when(auditLog.isEnabled()).thenReturn(false);
        connection = new Cjt188Connection(configuration, transport, auditLog);
        setField("meterAddress", METER_ADDRESS);
    }

    @Test
    void configurationRoundTrip() {
        configuration.setRequestTimeout(3000);
        assertEquals(3000, configuration.getRequestTimeout());
        assertEquals("123456789012", configuration.getMeterAddress());
        assertEquals("10", configuration.getMeterType());
        assertTrue(configuration.toString().contains("123456789012"));
    }

    @Test
    void mergesSubsequentDataAfterDiAndSer() throws Exception {
        byte[] initial = {0x1F, (byte) 0x90, 0x00, 0x11, 0x22};
        byte[] next = {0x1F, (byte) 0x90, 0x01, 0x33, 0x44};

        assertArrayEquals(new byte[]{0x1F, (byte) 0x90, 0x00, 0x11, 0x22, 0x33, 0x44},
            (byte[]) invoke("mergeSubsequentData", new Class<?>[]{byte[].class, byte[].class}, initial, next));
    }

    @Test
    void numericWritesAreFixedLengthAndScaled() throws Exception {
        Cjt188Tag tag = Cjt188Tag.of("901F");
        assertArrayEquals(new byte[]{0x56, 0x34, 0x12, 0x00},
            (byte[]) invoke("serializeValue", new Class<?>[]{Cjt188Tag.class, PlcValue.class},
                tag, new PlcLREAL(1234.56)));
    }

    @Test
    void dateTimeWritesUseLowUnitFirstBcd() throws Exception {
        Cjt188Tag tag = Cjt188Tag.of("D120");
        assertArrayEquals(new byte[]{0x05, 0x04, 0x03, 0x02, 0x01, 0x24},
            (byte[]) invoke("serializeValue", new Class<?>[]{Cjt188Tag.class, PlcValue.class},
                tag, new PlcSTRING("240102030405")));
    }

    @Test
    void trailingStatusBytesAreIgnoredForKnownDi() throws Exception {
        Cjt188Tag tag = Cjt188Tag.of("901F");
        byte[] data = {0x1F, (byte) 0x90, 0x00, 0x56, 0x34, 0x12, 0x00, 0x00, 0x00};
        PlcValue value = (PlcValue) invoke("parseResponseData",
            new Class<?>[]{byte[].class, Cjt188Tag.class}, data, tag);
        assertEquals(1234.56, value.getDouble(), 0.00001);
    }

    @Test
    void sendFailureClearsPendingRequest() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        Cjt188Frame frame = new Cjt188Frame(new byte[7], ControlCode.READ_DATA, (short) 3, new byte[3]);
        doThrow(new MessageCodecException("send failed")).when(codec).send(frame);
        setField("messageCodec", codec);
        CompletableFuture<Cjt188Frame> response = new CompletableFuture<>();

        invoke("dispatchRequest",
            new Class<?>[]{Cjt188Frame.class, long.class, CompletableFuture.class}, frame, 1L, response);

        assertTrue(response.isCompletedExceptionally());
        assertNull(getField("pendingRequest"));
    }

    @Test
    void ignoresFramesWithUnknownControlCodes() {
        Cjt188Frame frame = new Cjt188Frame(new byte[7], null, (short) 0, new byte[0]);
        assertDoesNotThrow(() -> invoke("handleIncomingMessage",
            new Class<?>[]{Cjt188Frame.class}, frame));
    }

    @Test
    void onConnectAllowsMissingMeterAddressForBusMode() throws Exception {
        configuration.setMeterAddress(null);
        assertDoesNotThrow(() -> invoke("onConnect", new Class<?>[0]));
        assertNull(getField("meterAddress"));
    }

    @Test
    void pendingMatcherRejectsWrongDi() throws Exception {
        byte[] requestData = {0x1F, (byte) 0x90, 0x00};
        Cjt188Frame request = new Cjt188Frame(METER_ADDRESS, ControlCode.READ_DATA,
            (short) requestData.length, requestData);
        Object pending = newPending(request);

        Cjt188Frame correct = new Cjt188Frame(METER_ADDRESS, ControlCode.READ_DATA_RESPONSE,
            (short) 7, new byte[]{0x1F, (byte) 0x90, 0x00, 0x56, 0x34, 0x12, 0x00});
        Cjt188Frame wrongDi = new Cjt188Frame(METER_ADDRESS, ControlCode.READ_DATA_RESPONSE,
            (short) 7, new byte[]{0x10, (byte) 0x90, 0x00, 0x56, 0x34, 0x12, 0x00});

        assertEquals(true, matches(pending, correct));
        assertEquals(false, matches(pending, wrongDi));
    }

    @Test
    void readAddressErrorCompletesWithRemoteError() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcReadRequest request = readRequest("addr", Cjt188CommandTag.of("cmd:read-address"));

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcReadResponse> future = (CompletableFuture<PlcReadResponse>) invoke(
            "executeReadAddress",
            new Class<?>[]{DefaultPlcReadRequest.class, String.class},
            request, "addr");

        invoke("handleIncomingMessage", new Class<?>[]{Cjt188Frame.class},
            new Cjt188Frame(new byte[7], ControlCode.READ_ADDRESS_ERROR, (short) 1, new byte[]{0x02}));

        PlcReadResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.REMOTE_ERROR, response.getResponseCode("addr"));
    }

    @Test
    void onReadSingleTagDoesNotDropTheValue() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcReadRequest request = readRequest("volume", Cjt188Tag.of("901F"));

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcReadResponse> future = (CompletableFuture<PlcReadResponse>) invoke(
            "onRead", new Class<?>[]{org.apache.plc4x.java.api.messages.PlcReadRequest.class}, request);

        ArgumentCaptor<Cjt188Frame> captor = ArgumentCaptor.forClass(Cjt188Frame.class);
        verify(codec).send(captor.capture());
        invoke("handleIncomingMessage", new Class<?>[]{Cjt188Frame.class},
            okReadResponse(captor.getValue(), new byte[]{0x56, 0x34, 0x12, 0x00}));

        PlcReadResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("volume"));
        assertEquals(1234.56, response.getPlcValue("volume").getDouble(), 0.00001);
    }

    @Test
    void tagMeterAddressOverridesConnectionDefaultOnTheWire() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcReadRequest request = readRequest("heat", Cjt188Tag.of("20987654321098/901F"));

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcReadResponse> future = (CompletableFuture<PlcReadResponse>) invoke(
            "onRead", new Class<?>[]{org.apache.plc4x.java.api.messages.PlcReadRequest.class}, request);

        ArgumentCaptor<Cjt188Frame> captor = ArgumentCaptor.forClass(Cjt188Frame.class);
        verify(codec).send(captor.capture());
        assertArrayEquals(Cjt188DriverContext.parseMeterAddress("20987654321098", "10"),
            captor.getValue().getAddress());
        invoke("handleIncomingMessage", new Class<?>[]{Cjt188Frame.class},
            okReadResponse(captor.getValue(), new byte[]{0x56, 0x34, 0x12, 0x00}));
        assertEquals(PlcResponseCode.OK, future.get(1, TimeUnit.SECONDS).getResponseCode("heat"));
    }

    @Test
    void oneConnectionReadsTwoMetersSequentially() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcReadRequest request = readRequest(
            "cold", Cjt188Tag.of("10123456789012/901F"),
            "heat", Cjt188Tag.of("20987654321098/901F"));

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcReadResponse> future = (CompletableFuture<PlcReadResponse>) invoke(
            "onRead", new Class<?>[]{org.apache.plc4x.java.api.messages.PlcReadRequest.class}, request);

        ArgumentCaptor<Cjt188Frame> captor = ArgumentCaptor.forClass(Cjt188Frame.class);
        verify(codec, org.mockito.Mockito.timeout(1000)).send(captor.capture());
        invoke("handleIncomingMessage", new Class<?>[]{Cjt188Frame.class},
            okReadResponse(captor.getValue(), new byte[]{0x56, 0x34, 0x12, 0x00}));

        verify(codec, org.mockito.Mockito.timeout(1000).times(2)).send(org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<Cjt188Frame> all = ArgumentCaptor.forClass(Cjt188Frame.class);
        verify(codec, org.mockito.Mockito.times(2)).send(all.capture());
        var frames = all.getAllValues();
        assertArrayEquals(Cjt188DriverContext.parseMeterAddress("10123456789012", "10"),
            frames.get(0).getAddress());
        assertArrayEquals(Cjt188DriverContext.parseMeterAddress("20987654321098", "10"),
            frames.get(1).getAddress());
        invoke("handleIncomingMessage", new Class<?>[]{Cjt188Frame.class},
            okReadResponse(frames.get(1), new byte[]{0x00, 0x00, 0x00, 0x00}));

        PlcReadResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("cold"));
        assertEquals(PlcResponseCode.OK, response.getResponseCode("heat"));
    }

    @Test
    void writeDataCompletesOn84h() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcWriteRequest request = mock(DefaultPlcWriteRequest.class);

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcWriteResponse> future = (CompletableFuture<PlcWriteResponse>) invoke(
            "executeWriteData",
            new Class<?>[]{DefaultPlcWriteRequest.class, String.class, Cjt188Tag.class, PlcValue.class,
                ControlCode.class, ControlCode.class, ControlCode.class},
            request, "valve", Cjt188Tag.of("A017:RAW_BYTE_ARRAY"),
            new org.apache.plc4x.java.spi.values.PlcRawByteArray(new byte[]{(byte) 0x99}),
            ControlCode.WRITE_DATA, ControlCode.WRITE_DATA_RESPONSE, ControlCode.WRITE_DATA_ERROR);

        ArgumentCaptor<Cjt188Frame> captor = ArgumentCaptor.forClass(Cjt188Frame.class);
        verify(codec).send(captor.capture());
        invoke("handleIncomingMessage", new Class<?>[]{Cjt188Frame.class},
            new Cjt188Frame(captor.getValue().getAddress(), ControlCode.WRITE_DATA_RESPONSE,
                (short) 0, new byte[0]));

        PlcWriteResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("valve"));
    }

    @Test
    void writeAddressErrorCompletesWithRemoteError() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcWriteRequest request = mock(DefaultPlcWriteRequest.class);

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcWriteResponse> future = (CompletableFuture<PlcWriteResponse>) invoke(
            "executeWriteAddress",
            new Class<?>[]{DefaultPlcWriteRequest.class, String.class, PlcValue.class},
            request, "addr", new PlcSTRING("123456789012"));

        invoke("handleIncomingMessage", new Class<?>[]{Cjt188Frame.class},
            new Cjt188Frame(new byte[7], ControlCode.WRITE_ADDRESS_ERROR, (short) 1, new byte[]{0x01}));

        PlcWriteResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.REMOTE_ERROR, response.getResponseCode("addr"));
    }

    @Test
    void wildcardDecodingSplitsConcatenatedValues() throws Exception {
        byte[] wildcardDi = {(byte) 0x80, (byte) 0xAA};
        // not a wildcard - use 80FF which matches 80A0/80AA/80AB/80AC/80B0 mixed.
        // Use parseWildcardResponseData with 9011+9012 which are same length.
        byte[] values = {0x56, 0x34, 0x12, 0x00, 0x00, 0x00, 0x00, 0x00};
        PlcStruct result = (PlcStruct) invoke("parseWildcardResponseData",
            new Class<?>[]{String.class, byte[].class, Cjt188Tag.class}, "90FF", values,
            Cjt188Tag.of("90FF:RAW_BYTE_ARRAY"));
        assertNotNull(result);
        assertTrue(result.getKeys().contains("9010") || result.getKeys().contains("9011")
            || result.getKeys().contains("901F"));
    }

    @Test
    void dateTimeResponseDecodesToPlcDateAndTime() throws Exception {
        Cjt188Tag tag = Cjt188Tag.of("D120");
        byte[] data = {0x20, (byte) 0xD1, 0x00, 0x05, 0x04, 0x03, 0x02, 0x01, 0x24};
        PlcValue value = (PlcValue) invoke("parseResponseData",
            new Class<?>[]{byte[].class, Cjt188Tag.class}, data, tag);
        assertInstanceOf(PlcDATE_AND_TIME.class, value);
    }

    @Test
    void unexpectedSubsequentControlIsAnError() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        Cjt188Tag tag = Cjt188Tag.of("901F");
        DefaultPlcReadRequest request = readRequest("volume", tag);

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcReadResponse> future = (CompletableFuture<PlcReadResponse>) invoke(
            "accumulateSubsequentData",
            new Class<?>[]{DefaultPlcReadRequest.class, String.class, Cjt188Tag.class,
                byte[].class, int.class, int.class},
            request, "volume", tag, new byte[]{0x1F, (byte) 0x90, 0x00, 0x11, 0x22}, 1, 0);

        completePending(new Cjt188Frame(METER_ADDRESS, ControlCode.WRITE_DATA_RESPONSE,
            (short) 0, new byte[0]));

        PlcReadResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.INTERNAL_ERROR, response.getResponseCode("volume"));
        assertNull(response.getPlcValue("volume"));
    }

    @Test
    void handlersAndConcurrencyAreWired() throws Exception {
        assertNotNull(invoke("getTagHandler", new Class<?>[0]));
        assertNotNull(invoke("getValueHandler", new Class<?>[0]));
        assertEquals(1, invoke("getMaxConcurrentRequests", new Class<?>[0]));
    }

    @Test
    void pingCompletesWhenTheMeterAnswers() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        when(codec.isOpen()).thenReturn(true);
        assertTrue(connection.isConnected());

        @SuppressWarnings("unchecked")
        CompletableFuture<org.apache.plc4x.java.api.messages.PlcPingResponse> future =
            (CompletableFuture<org.apache.plc4x.java.api.messages.PlcPingResponse>) invoke(
                "onPing", new Class<?>[]{org.apache.plc4x.java.api.messages.PlcPingRequest.class},
                mock(org.apache.plc4x.java.api.messages.PlcPingRequest.class));

        ArgumentCaptor<Cjt188Frame> captor = ArgumentCaptor.forClass(Cjt188Frame.class);
        verify(codec).send(captor.capture());
        invoke("handleIncomingMessage", new Class<?>[]{Cjt188Frame.class},
            okReadResponse(captor.getValue(), new byte[]{0x56, 0x34, 0x12, 0x00}));

        assertEquals(PlcResponseCode.OK, future.get(1, TimeUnit.SECONDS).getResponseCode());
    }

    @Test
    void writeSyncDispatchesControl16h() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcWriteRequest request = mock(DefaultPlcWriteRequest.class);

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcWriteResponse> future = (CompletableFuture<PlcWriteResponse>) invoke(
            "executeWriteSync",
            new Class<?>[]{DefaultPlcWriteRequest.class, String.class, Cjt188CommandTag.class, PlcValue.class},
            request, "sync", Cjt188CommandTag.of("cmd:write-sync"), new PlcSTRING("901F:12.34"));

        ArgumentCaptor<Cjt188Frame> captor = ArgumentCaptor.forClass(Cjt188Frame.class);
        verify(codec).send(captor.capture());
        assertEquals(ControlCode.WRITE_SYNC, captor.getValue().getControl());
        invoke("handleIncomingMessage", new Class<?>[]{Cjt188Frame.class},
            new Cjt188Frame(captor.getValue().getAddress(), ControlCode.WRITE_SYNC_RESPONSE,
                (short) 0, new byte[0]));

        assertEquals(PlcResponseCode.OK, future.get(1, TimeUnit.SECONDS).getResponseCode("sync"));
    }

    @Test
    void onWriteRejectsMultiTagRequests() {
        DefaultPlcWriteRequest request = mock(DefaultPlcWriteRequest.class);
        when(request.getTagNames()).thenReturn(new java.util.LinkedHashSet<>(java.util.List.of("a", "b")));
        assertTrue(((CompletableFuture<?>) invokeQuiet("onWrite",
            new Class<?>[]{org.apache.plc4x.java.api.messages.PlcWriteRequest.class}, request))
            .isCompletedExceptionally());
    }

    @Test
    void subsequentFrameIsMergedThenDecoded() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        Cjt188Tag tag = Cjt188Tag.of("901F");
        DefaultPlcReadRequest request = readRequest("volume", tag);
        byte[] first = {0x1F, (byte) 0x90, 0x00, 0x56, 0x34};

        @SuppressWarnings("unchecked")
        CompletableFuture<PlcReadResponse> future = (CompletableFuture<PlcReadResponse>) invoke(
            "accumulateSubsequentData",
            new Class<?>[]{DefaultPlcReadRequest.class, String.class, Cjt188Tag.class,
                byte[].class, int.class, int.class},
            request, "volume", tag, first, 1, 0);

        ArgumentCaptor<Cjt188Frame> captor = ArgumentCaptor.forClass(Cjt188Frame.class);
        verify(codec).send(captor.capture());
        invoke("handleIncomingMessage", new Class<?>[]{Cjt188Frame.class},
            new Cjt188Frame(METER_ADDRESS, ControlCode.READ_DATA_RESPONSE, (short) 5,
                new byte[]{0x1F, (byte) 0x90, 0x01, 0x12, 0x00}));

        PlcReadResponse response = future.get(1, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("volume"));
        assertEquals(1234.56, response.getPlcValue("volume").getDouble(), 0.00001);
    }

    @Test
    void closeAndDisconnectFailPendingRequests() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        when(codec.isOpen()).thenReturn(true);
        CompletableFuture<Cjt188Frame> pending = new CompletableFuture<>();
        java.util.Set<CompletableFuture<?>> active =
            (java.util.Set<CompletableFuture<?>>) getField("activeRequests");
        active.add(pending);

        invoke("onTransportDisconnected", new Class<?>[]{Throwable.class}, new RuntimeException("lost"));
        assertTrue(pending.isCompletedExceptionally());

        CompletableFuture<Cjt188Frame> pending2 = new CompletableFuture<>();
        active.add(pending2);
        connection.close();
        assertTrue(pending2.isCompletedExceptionally());
        assertFalse(connection.isConnected());
    }

    @Test
    void formatAddressResponseAcceptsSerPrefixAndBareAddress() throws Exception {
        byte[] bare = METER_ADDRESS;
        assertEquals("10123456789012", invoke("formatAddressResponse",
            new Class<?>[]{byte[].class}, (Object) bare));
        byte[] withSer = new byte[8];
        withSer[0] = 0x01;
        System.arraycopy(METER_ADDRESS, 0, withSer, 1, 7);
        assertEquals("10123456789012", invoke("formatAddressResponse",
            new Class<?>[]{byte[].class}, (Object) withSer));
        byte[] serLooksLikeType = new byte[8];
        serLooksLikeType[0] = 0x10;
        System.arraycopy(METER_ADDRESS, 0, serLooksLikeType, 1, 7);
        assertEquals("10123456789012", invoke("formatAddressResponse",
            new Class<?>[]{byte[].class}, (Object) serLooksLikeType));
    }

    @Test
    void readAddressRequestHasEmptyData() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcReadRequest request = readRequest("addr", Cjt188CommandTag.of("cmd:read-address"));
        invoke("executeReadAddress",
            new Class<?>[]{DefaultPlcReadRequest.class, String.class}, request, "addr");
        ArgumentCaptor<Cjt188Frame> captor = ArgumentCaptor.forClass(Cjt188Frame.class);
        verify(codec).send(captor.capture());
        assertEquals(ControlCode.READ_ADDRESS, captor.getValue().getControl());
        assertEquals(0, captor.getValue().getLength());
        assertEquals(0, captor.getValue().getDataPlain().length);
    }

    @Test
    void writeAddressRequestDataIsSevenByteAddress() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcWriteRequest request = mock(DefaultPlcWriteRequest.class);
        invoke("executeWriteAddress",
            new Class<?>[]{DefaultPlcWriteRequest.class, String.class, PlcValue.class},
            request, "addr", new PlcSTRING("123456789012"));
        ArgumentCaptor<Cjt188Frame> captor = ArgumentCaptor.forClass(Cjt188Frame.class);
        verify(codec).send(captor.capture());
        assertEquals(ControlCode.WRITE_ADDRESS, captor.getValue().getControl());
        assertArrayEquals(METER_ADDRESS, captor.getValue().getDataPlain());
    }

    @Test
    void missingMeterAddressDoesNotThrowFromReadOrWrite() throws Exception {
        configuration.setMeterAddress(null);
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcReadRequest read = readRequest("volume", Cjt188Tag.of("901F"));
        @SuppressWarnings("unchecked")
        CompletableFuture<PlcReadResponse> readFuture = (CompletableFuture<PlcReadResponse>) invoke(
            "executeReadData",
            new Class<?>[]{DefaultPlcReadRequest.class, String.class, Cjt188Tag.class},
            read, "volume", Cjt188Tag.of("901F"));
        assertEquals(PlcResponseCode.INVALID_ADDRESS,
            readFuture.get(1, TimeUnit.SECONDS).getResponseCode("volume"));

        DefaultPlcWriteRequest write = mock(DefaultPlcWriteRequest.class);
        @SuppressWarnings("unchecked")
        CompletableFuture<PlcWriteResponse> writeFuture = (CompletableFuture<PlcWriteResponse>) invoke(
            "executeWriteData",
            new Class<?>[]{DefaultPlcWriteRequest.class, String.class, Cjt188Tag.class, PlcValue.class,
                ControlCode.class, ControlCode.class, ControlCode.class},
            write, "valve", Cjt188Tag.of("A017:RAW_BYTE_ARRAY"),
            new org.apache.plc4x.java.spi.values.PlcRawByteArray(new byte[]{(byte) 0x99}),
            ControlCode.WRITE_DATA, ControlCode.WRITE_DATA_RESPONSE, ControlCode.WRITE_DATA_ERROR);
        assertFalse(writeFuture.isCompletedExceptionally());
        assertEquals(PlcResponseCode.INVALID_ADDRESS,
            writeFuture.get(1, TimeUnit.SECONDS).getResponseCode("valve"));
    }

    @Test
    void invalidTagMeterAndMalformedWriteSyncCompleteTheFuture() throws Exception {
        Cjt188MessageCodec codec = mock(Cjt188MessageCodec.class);
        setField("messageCodec", codec);
        DefaultPlcReadRequest read = readRequest("volume", Cjt188Tag.of("ABCDEFABCDEF/901F"));
        @SuppressWarnings("unchecked")
        CompletableFuture<PlcReadResponse> readFuture = (CompletableFuture<PlcReadResponse>) invoke(
            "executeReadData",
            new Class<?>[]{DefaultPlcReadRequest.class, String.class, Cjt188Tag.class},
            read, "volume", Cjt188Tag.of("ABCDEFABCDEF/901F"));
        assertEquals(PlcResponseCode.INVALID_ADDRESS,
            readFuture.get(1, TimeUnit.SECONDS).getResponseCode("volume"));

        DefaultPlcWriteRequest write = mock(DefaultPlcWriteRequest.class);
        @SuppressWarnings("unchecked")
        CompletableFuture<PlcWriteResponse> syncFuture = (CompletableFuture<PlcWriteResponse>) invoke(
            "executeWriteSync",
            new Class<?>[]{DefaultPlcWriteRequest.class, String.class, Cjt188CommandTag.class, PlcValue.class},
            write, "sync", Cjt188CommandTag.of("cmd:write-sync"), new PlcSTRING("not-a-payload"));
        assertEquals(PlcResponseCode.INVALID_DATA,
            syncFuture.get(1, TimeUnit.SECONDS).getResponseCode("sync"));

        assertEquals("", invoke("formatAddressResponse", new Class<?>[]{byte[].class}, (Object) null));
        assertEquals("", invoke("formatAddressResponse", new Class<?>[]{byte[].class}, (Object) new byte[3]));
    }

    @Test
    void addressCommandResponsesMustMatchTheMeterOnTheWire() throws Exception {
        Cjt188Frame writeReq = new Cjt188Frame(Cjt188DriverContext.WILDCARD_ADDRESS,
            ControlCode.WRITE_ADDRESS, (short) METER_ADDRESS.length, METER_ADDRESS);
        Object writePending = newPending(writeReq);
        assertTrue(matches(writePending, new Cjt188Frame(METER_ADDRESS,
            ControlCode.WRITE_ADDRESS_RESPONSE, (short) 0, new byte[0])));
        assertFalse(matches(writePending, new Cjt188Frame(
            Cjt188DriverContext.parseMeterAddress("20987654321098", "10"),
            ControlCode.WRITE_ADDRESS_RESPONSE, (short) 0, new byte[0])));

        Cjt188Frame readReq = new Cjt188Frame(Cjt188DriverContext.WILDCARD_ADDRESS,
            ControlCode.READ_ADDRESS, (short) 0, new byte[0]);
        Object readPending = newPending(readReq);
        assertTrue(matches(readPending, new Cjt188Frame(METER_ADDRESS,
            ControlCode.READ_ADDRESS_RESPONSE, (short) 7, METER_ADDRESS)));
        assertFalse(matches(readPending, new Cjt188Frame(new byte[7],
            ControlCode.READ_ADDRESS_RESPONSE, (short) 7, METER_ADDRESS)));
    }

    @Test
    void writeAddressRejectsUnparseableValue() throws Exception {
        DefaultPlcWriteRequest request = mock(DefaultPlcWriteRequest.class);
        @SuppressWarnings("unchecked")
        CompletableFuture<PlcWriteResponse> future = (CompletableFuture<PlcWriteResponse>) invoke(
            "executeWriteAddress",
            new Class<?>[]{DefaultPlcWriteRequest.class, String.class, PlcValue.class},
            request, "addr", new PlcSTRING("not-an-address"));
        assertEquals(PlcResponseCode.INVALID_ADDRESS,
            future.get(1, TimeUnit.SECONDS).getResponseCode("addr"));
    }

    @Test
    void timeoutDiscardsLeftoverBytesBeforeTheNextRequest() throws Exception {
        configuration.setRequestTimeout(80);
        var transport = new ScriptedTransport();
        var codec = new Cjt188MessageCodec(transport, frame -> {
            try {
                invoke("handleIncomingMessage", new Class<?>[]{Cjt188Frame.class}, frame);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        setField("messageCodec", codec);

        byte[] di = {0x1F, (byte) 0x90, 0x00};
        Cjt188Frame request = new Cjt188Frame(METER_ADDRESS, ControlCode.READ_DATA, (short) 3, di);
        CompletableFuture<Cjt188Frame> first = new CompletableFuture<>();
        invoke("dispatchRequest",
            new Class<?>[]{Cjt188Frame.class, long.class, CompletableFuture.class}, request, 1L, first);

        assertThrows(Exception.class, () -> first.get(1, TimeUnit.SECONDS));

        transport.deliver(wireBytes(okReadResponse(request, new byte[]{0x56, 0x34, 0x12, 0x00})));

        CompletableFuture<Cjt188Frame> second = new CompletableFuture<>();
        invoke("dispatchRequest",
            new Class<?>[]{Cjt188Frame.class, long.class, CompletableFuture.class}, request, 2L, second);

        codec.processIncomingData();
        assertFalse(second.isDone());
        assertEquals(0, transport.getNumBytesAvailable());
    }

    private Object invoke(String name, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = Cjt188Connection.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(connection, arguments);
    }

    private Object invokeQuiet(String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            return invoke(name, parameterTypes, arguments);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Object getField(String name) throws Exception {
        Field field = Cjt188Connection.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(connection);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = Cjt188Connection.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(connection, value);
    }

    private Object newPending(Cjt188Frame request) throws Exception {
        Class<?> pendingClass = Arrays.stream(Cjt188Connection.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("PendingRequest"))
            .findFirst().orElseThrow();
        Constructor<?> constructor = pendingClass.getDeclaredConstructor(long.class, Cjt188Frame.class,
            CompletableFuture.class);
        constructor.setAccessible(true);
        return constructor.newInstance(1L, request, new CompletableFuture<Cjt188Frame>());
    }

    private boolean matches(Object pending, Cjt188Frame response) throws Exception {
        Method matches = pending.getClass().getDeclaredMethod("matches", Cjt188Frame.class);
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

    private static Cjt188Frame okReadResponse(Cjt188Frame request, byte[] value) {
        byte[] requestData = request.getDataPlain();
        byte[] data = new byte[3 + value.length];
        System.arraycopy(requestData, 0, data, 0, 3);
        System.arraycopy(value, 0, data, 3, value.length);
        return new Cjt188Frame(request.getAddress(), ControlCode.READ_DATA_RESPONSE,
            (short) data.length, data);
    }

    private void completePending(Cjt188Frame frame) throws Exception {
        Object pending = getField("pendingRequest");
        assertNotNull(pending);
        Field responseFutureField = pending.getClass().getDeclaredField("responseFuture");
        responseFutureField.setAccessible(true);
        @SuppressWarnings("unchecked")
        CompletableFuture<Cjt188Frame> responseFuture =
            (CompletableFuture<Cjt188Frame>) responseFutureField.get(pending);
        responseFuture.complete(frame);
    }

    private static byte[] wireBytes(Cjt188Frame frame) throws Exception {
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
