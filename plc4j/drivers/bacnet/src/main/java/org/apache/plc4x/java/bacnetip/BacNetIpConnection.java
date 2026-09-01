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
package org.apache.plc4x.java.bacnetip;

import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcSubscriptionEvent;
import org.apache.plc4x.java.api.messages.PlcSubscriptionRequest;
import org.apache.plc4x.java.api.messages.PlcSubscriptionResponse;
import org.apache.plc4x.java.api.messages.PlcUnsubscriptionRequest;
import org.apache.plc4x.java.api.messages.PlcUnsubscriptionResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.model.PlcConsumerRegistration;
import org.apache.plc4x.java.api.model.PlcSubscriptionHandle;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.bacnetip.configuration.BacNetIpConfiguration;
import org.apache.plc4x.java.bacnetip.ede.EdeParser;
import org.apache.plc4x.java.bacnetip.ede.model.Datapoint;
import org.apache.plc4x.java.bacnetip.ede.model.EdeModel;
import org.apache.plc4x.java.bacnetip.readwrite.APDU;
import org.apache.plc4x.java.bacnetip.readwrite.APDUAbort;
import org.apache.plc4x.java.bacnetip.readwrite.APDUComplexAck;
import org.apache.plc4x.java.bacnetip.readwrite.APDUConfirmedRequest;
import org.apache.plc4x.java.bacnetip.readwrite.APDUError;
import org.apache.plc4x.java.bacnetip.readwrite.APDUReject;
import org.apache.plc4x.java.bacnetip.readwrite.APDUSimpleAck;
import org.apache.plc4x.java.bacnetip.readwrite.APDUUnconfirmedRequest;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTag;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetConfirmedServiceRequest;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetConfirmedServiceRequestConfirmedCOVNotification;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetConfirmedServiceRequestReadProperty;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetConfirmedServiceRequestReadPropertyMultiple;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetConfirmedServiceRequestWriteProperty;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetConstructedData;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetConstructedDataElement;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetConstructedDataUnspecified;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetContextTagObjectIdentifier;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetContextTagUnsignedInteger;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetPropertyIdentifier;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetPropertyIdentifierTagged;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetPropertyReference;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetPropertyValue;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetReadAccessProperty;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetReadAccessResult;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetReadAccessSpecification;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetServiceAck;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetServiceAckReadProperty;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetServiceAckReadPropertyMultiple;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetUnconfirmedServiceRequest;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetUnconfirmedServiceRequestUnconfirmedCOVNotification;
import org.apache.plc4x.java.bacnetip.readwrite.BVLC;
import org.apache.plc4x.java.bacnetip.readwrite.BVLCForwardedNPDU;
import org.apache.plc4x.java.bacnetip.readwrite.BVLCOriginalBroadcastNPDU;
import org.apache.plc4x.java.bacnetip.readwrite.BVLCOriginalUnicastNPDU;
import org.apache.plc4x.java.bacnetip.readwrite.MaxApduLengthAccepted;
import org.apache.plc4x.java.bacnetip.readwrite.MaxSegmentsAccepted;
import org.apache.plc4x.java.bacnetip.readwrite.NPDU;
import org.apache.plc4x.java.bacnetip.readwrite.NPDUControl;
import org.apache.plc4x.java.bacnetip.readwrite.NPDUNetworkPriority;
import org.apache.plc4x.java.bacnetip.readwrite.utils.StaticHelper;
import org.apache.plc4x.java.bacnetip.tag.BacNetIpTag;
import org.apache.plc4x.java.bacnetip.tag.BacNetIpTagHandler;
import org.apache.plc4x.java.spi.drivers.ConnectionBase;
import org.apache.plc4x.java.spi.drivers.exceptions.MessageCodecException;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcConsumerRegistration;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcReadResponse;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcSubscriptionEvent;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcSubscriptionResponse;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcUnsubscriptionResponse;
import org.apache.plc4x.java.spi.drivers.messages.DefaultPlcWriteResponse;
import org.apache.plc4x.java.spi.drivers.messages.items.DefaultPlcResponseItem;
import org.apache.plc4x.java.spi.drivers.messages.items.PlcResponseItem;
import org.apache.plc4x.java.spi.drivers.tags.PlcTagHandler;
import org.apache.plc4x.java.spi.transports.api.TransportInstance;
import org.apache.plc4x.java.spi.values.DefaultPlcValueHandler;
import org.apache.plc4x.java.spi.values.PlcDINT;
import org.apache.plc4x.java.spi.values.PlcList;
import org.apache.plc4x.java.spi.values.PlcNull;
import org.apache.plc4x.java.spi.values.PlcSTRING;
import org.apache.plc4x.java.spi.values.PlcStruct;
import org.apache.plc4x.java.spi.values.PlcUDINT;
import org.apache.plc4x.java.spi.values.PlcUSINT;
import org.apache.plc4x.java.spi.values.PlcValueHandler;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * BACnet/IP connection.
 *
 * <p>Active client: confirmed {@code ReadProperty} / {@code ReadPropertyMultiple} /
 * {@code WriteProperty} against the UDP peer in the connection URL.</p>
 *
 * <p>Passive path is unchanged: incoming COV notifications are still broadcast
 * to every registered subscriber.</p>
 */
public class BacNetIpConnection extends ConnectionBase<BacNetIpConfiguration> {

    private static final Logger LOGGER = LoggerFactory.getLogger(BacNetIpConnection.class);

    private BacNetIpMessageCodec messageCodec;
    private volatile boolean connected = false;
    private EdeModel edeModel;

    private final Map<DefaultPlcConsumerRegistration, Consumer<PlcSubscriptionEvent>> consumers = new ConcurrentHashMap<>();
    private final Map<Short, CompletableFuture<APDU>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger invokeIds = new AtomicInteger(0);

    public BacNetIpConnection(BacNetIpConfiguration configuration,
                              TransportInstance<?> transportInstance,
                              AuditLog auditLog) {
        super(configuration, transportInstance, auditLog);
    }

    @Override
    protected PlcTagHandler getTagHandler() {
        return new BacNetIpTagHandler();
    }

    @Override
    protected PlcValueHandler getValueHandler() {
        return new DefaultPlcValueHandler();
    }

    @Override
    public boolean isConnected() {
        return connected && messageCodec != null && messageCodec.isOpen();
    }

    @Override
    protected void onConnect() throws PlcConnectionException {
        if (configuration.getEdeFile() != null) {
            File edeFile = configuration.getEdeFile();
            if (!edeFile.exists() || !edeFile.isFile()) {
                throw new PlcConnectionException("File specified with 'ede-file-path' does not exist or is not a file: '"
                    + edeFile + "'");
            }
            edeModel = new EdeParser().parseFile(edeFile);
        } else if (configuration.getEdeDirectory() != null) {
            File edeDirectory = configuration.getEdeDirectory();
            if (!edeDirectory.exists() || !edeDirectory.isDirectory()) {
                throw new PlcConnectionException("File specified with 'ede-directory-path' does not exist or is not a directory: '"
                    + edeDirectory + "'");
            }
            edeModel = new EdeParser().parseDirectory(edeDirectory);
        }

        messageCodec = new BacNetIpMessageCodec(transportInstance, this::handleIncomingMessage);
        startReceiving(() -> {
            try {
                messageCodec.processIncomingData();
            } catch (MessageCodecException e) {
                LOGGER.error("Error processing incoming BACnet/IP data", e);
            }
        });
        connected = true;
    }

    @Override
    public void close() throws Exception {
        connected = false;
        pending.values().forEach(future -> future.completeExceptionally(new PlcConnectionException("Connection closed")));
        pending.clear();
        stopReceiving();
        if (messageCodec != null) {
            messageCodec.close();
        }
        consumers.clear();
        super.close();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Read / Write
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected CompletableFuture<PlcReadResponse> onRead(PlcReadRequest readRequest) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new PlcConnectionException("not connected"));
        }
        List<String> tagNames = new ArrayList<>(readRequest.getTagNames());
        if (tagNames.isEmpty()) {
            return CompletableFuture.failedFuture(new PlcRuntimeException("at least one tag required"));
        }
        // Sequential ReadProperty. ReadPropertyMultiple is optional and many
        // simulators/field devices encode the ACK differently.
        Map<String, PlcResponseItem<PlcValue>> values = new LinkedHashMap<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String tagName : tagNames) {
            chain = chain.thenCompose(ignored -> readOne(readRequest, List.of(tagName))
                .thenAccept(item -> values.put(tagName, item)));
        }
        return chain.thenApply(ignored -> new DefaultPlcReadResponse(readRequest, values));
    }

    private CompletableFuture<PlcResponseItem<PlcValue>> readOne(PlcReadRequest readRequest, List<String> tagNames) {
        try {
            BACnetConfirmedServiceRequest serviceRequest = buildReadServiceRequest(readRequest, tagNames);
            return sendConfirmed(serviceRequest).thenApply(apdu -> {
                PlcReadResponse response = toReadResponse(apdu, readRequest, tagNames);
                String tagName = tagNames.get(0);
                return new DefaultPlcResponseItem<>(response.getResponseCode(tagName), response.getPlcValue(tagName));
            });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    protected CompletableFuture<PlcWriteResponse> onWrite(PlcWriteRequest writeRequest) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new PlcConnectionException("not connected"));
        }
        List<String> tagNames = new ArrayList<>(writeRequest.getTagNames());
        if (tagNames.isEmpty()) {
            return CompletableFuture.failedFuture(new PlcRuntimeException("at least one tag required"));
        }
        // WriteProperty is the widely supported BIBB (DS-WP-B). Multiple tags
        // are issued sequentially rather than WritePropertyMultiple, which many
        // field devices do not implement.
        Map<String, PlcResponseCode> codes = new LinkedHashMap<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String tagName : tagNames) {
            chain = chain.thenCompose(ignored -> writeOne(writeRequest, tagName)
                .thenAccept(code -> codes.put(tagName, code)));
        }
        return chain.thenApply(ignored -> new DefaultPlcWriteResponse(writeRequest, codes));
    }

    private CompletableFuture<PlcResponseCode> writeOne(PlcWriteRequest writeRequest, String tagName) {
        try {
            BacNetIpTag tag = requireBacNetTag(writeRequest.getTag(tagName), tagName);
            BacNetIpTag.Property property = tag.firstProperty();
            BACnetConfirmedServiceRequest serviceRequest = new BACnetConfirmedServiceRequestWriteProperty(
                0L,
                objectIdentifier(tag),
                StaticHelper.createBACnetPropertyIdentifierTagged((byte) 1, property.getPropertyIdentifier()),
                arrayIndexTag((byte) 2, property.getArrayIndex()),
                BacNetValueCodec.toConstructedData(writeRequest.getPlcValue(tagName), tag.getObjectType(), property.getPropertyIdentifier(), (short) 3),
                unsignedContext((byte) 4, property.getWritePriority())
            );
            return sendConfirmed(serviceRequest).thenApply(apdu -> writeResponseCode(apdu));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private BACnetConfirmedServiceRequest buildReadServiceRequest(PlcReadRequest readRequest, List<String> tagNames) {
        if (tagNames.size() == 1) {
            BacNetIpTag tag = requireBacNetTag(readRequest.getTag(tagNames.get(0)), tagNames.get(0));
            if (tag.getProperties().size() == 1) {
                BacNetIpTag.Property property = tag.firstProperty();
                return new BACnetConfirmedServiceRequestReadProperty(
                    0L,
                    objectIdentifier(tag),
                    StaticHelper.createBACnetPropertyIdentifierTagged((byte) 1, property.getPropertyIdentifier()),
                    arrayIndexTag((byte) 2, property.getArrayIndex())
                );
            }
        }
        List<BACnetReadAccessSpecification> specs = new ArrayList<>();
        for (String tagName : tagNames) {
            BacNetIpTag tag = requireBacNetTag(readRequest.getTag(tagName), tagName);
            List<BACnetPropertyReference> references = new ArrayList<>();
            for (BacNetIpTag.Property property : tag.getProperties()) {
                references.add(new BACnetPropertyReference(
                    StaticHelper.createBACnetPropertyIdentifierTagged((byte) 0, property.getPropertyIdentifier()),
                    arrayIndexTag((byte) 1, property.getArrayIndex())
                ));
            }
            specs.add(new BACnetReadAccessSpecification(
                objectIdentifier(tag),
                StaticHelper.createBACnetOpeningTag((short) 1),
                references,
                StaticHelper.createBACnetClosingTag((short) 1)
            ));
        }
        return new BACnetConfirmedServiceRequestReadPropertyMultiple(0L, specs);
    }

    private CompletableFuture<APDU> sendConfirmed(BACnetConfirmedServiceRequest serviceRequest) {
        short invokeId = nextInvokeId();
        APDUConfirmedRequest apdu = new APDUConfirmedRequest(
            false,
            false,
            false,
            MaxSegmentsAccepted.UNSPECIFIED,
            MaxApduLengthAccepted.NUM_OCTETS_1476,
            invokeId,
            null,
            null,
            serviceRequest,
            null,
            null
        );
        NPDUControl control = new NPDUControl(false, false, false, true, NPDUNetworkPriority.NORMAL_MESSAGE);
        // Generated NPDU.getLengthInBits() calls destinationAddress.size()/sourceAddress.size()
        // unconditionally, so local unicast must pass empty lists rather than null.
        NPDU npdu = new NPDU((short) 1, control, null, null, List.of(), null, null, List.of(), null, null, apdu);
        BVLCOriginalUnicastNPDU bvlc = new BVLCOriginalUnicastNPDU(npdu);

        CompletableFuture<APDU> future = new CompletableFuture<>();
        pending.put(invokeId, future);
        try {
            messageCodec.send(bvlc);
        } catch (MessageCodecException e) {
            pending.remove(invokeId);
            future.completeExceptionally(e);
            return future;
        }
        int timeout = Math.max(100, configuration.getApduTimeout());
        return future.orTimeout(timeout, TimeUnit.MILLISECONDS).whenComplete((ignored, error) -> pending.remove(invokeId));
    }

    private PlcReadResponse toReadResponse(APDU apdu, PlcReadRequest request, List<String> tagNames) {
        Map<String, PlcResponseItem<PlcValue>> values = new LinkedHashMap<>();
        if (apdu instanceof APDUError || apdu instanceof APDUReject || apdu instanceof APDUAbort) {
            PlcResponseCode code = apdu instanceof APDUAbort ? PlcResponseCode.UNSUPPORTED : PlcResponseCode.REMOTE_ERROR;
            for (String tagName : tagNames) {
                values.put(tagName, new DefaultPlcResponseItem<>(code, new PlcNull()));
            }
            return new DefaultPlcReadResponse(request, values);
        }
        if (!(apdu instanceof APDUComplexAck complexAck) || complexAck.getServiceAck() == null) {
            for (String tagName : tagNames) {
                values.put(tagName, new DefaultPlcResponseItem<>(PlcResponseCode.INTERNAL_ERROR, new PlcNull()));
            }
            return new DefaultPlcReadResponse(request, values);
        }
        BACnetServiceAck serviceAck = complexAck.getServiceAck();
        if (serviceAck instanceof BACnetServiceAckReadProperty readProperty) {
            values.put(tagNames.get(0), new DefaultPlcResponseItem<>(PlcResponseCode.OK, BacNetValueCodec.toPlcValue(readProperty.getValues())));
            for (int i = 1; i < tagNames.size(); i++) {
                values.put(tagNames.get(i), new DefaultPlcResponseItem<>(PlcResponseCode.NOT_FOUND, new PlcNull()));
            }
            return new DefaultPlcReadResponse(request, values);
        }
        if (serviceAck instanceof BACnetServiceAckReadPropertyMultiple multiple) {
            List<BACnetReadAccessResult> data = multiple.getData();
            for (int i = 0; i < tagNames.size(); i++) {
                String tagName = tagNames.get(i);
                if (data == null || i >= data.size() || data.get(i).getListOfResults() == null) {
                    values.put(tagName, new DefaultPlcResponseItem<>(PlcResponseCode.NOT_FOUND, new PlcNull()));
                    continue;
                }
                List<BACnetReadAccessProperty> results = data.get(i).getListOfResults().getListOfReadAccessProperty();
                values.put(tagName, collapseReadResults(results));
            }
            return new DefaultPlcReadResponse(request, values);
        }
        for (String tagName : tagNames) {
            values.put(tagName, new DefaultPlcResponseItem<>(PlcResponseCode.INTERNAL_ERROR, new PlcNull()));
        }
        return new DefaultPlcReadResponse(request, values);
    }

    private PlcResponseItem<PlcValue> collapseReadResults(List<BACnetReadAccessProperty> results) {
        if (results == null || results.isEmpty()) {
            return new DefaultPlcResponseItem<>(PlcResponseCode.NOT_FOUND, new PlcNull());
        }
        List<PlcValue> collected = new ArrayList<>();
        for (BACnetReadAccessProperty result : results) {
            if (result.getReadResult() == null) {
                collected.add(new PlcNull());
                continue;
            }
            if (result.getReadResult().getPropertyAccessError() != null) {
                return new DefaultPlcResponseItem<>(PlcResponseCode.REMOTE_ERROR, new PlcNull());
            }
            collected.add(BacNetValueCodec.toPlcValue(result.getReadResult().getPropertyValue()));
        }
        if (collected.size() == 1) {
            return new DefaultPlcResponseItem<>(PlcResponseCode.OK, collected.get(0));
        }
        return new DefaultPlcResponseItem<>(PlcResponseCode.OK, new PlcList(collected));
    }

    private static PlcResponseCode writeResponseCode(APDU apdu) {
        if (apdu instanceof APDUSimpleAck) {
            return PlcResponseCode.OK;
        }
        if (apdu instanceof APDUError || apdu instanceof APDUReject) {
            return PlcResponseCode.REMOTE_ERROR;
        }
        if (apdu instanceof APDUAbort) {
            return PlcResponseCode.UNSUPPORTED;
        }
        return PlcResponseCode.INTERNAL_ERROR;
    }

    private short nextInvokeId() {
        return (short) (invokeIds.getAndUpdate(value -> (value + 1) & 0xFF) & 0xFF);
    }

    private static BacNetIpTag requireBacNetTag(PlcTag tag, String tagName) {
        if (tag instanceof BacNetIpTag bacNetIpTag) {
            return bacNetIpTag;
        }
        throw new PlcRuntimeException("tag " + tagName + " is not a BACnet tag");
    }

    private static BACnetContextTagObjectIdentifier objectIdentifier(BacNetIpTag tag) {
        return StaticHelper.createBACnetContextTagObjectIdentifier((byte) 0, (short) tag.getObjectType(), (int) tag.getObjectInstance());
    }

    private static BACnetContextTagUnsignedInteger arrayIndexTag(byte tagNumber, Integer arrayIndex) {
        return unsignedContext(tagNumber, arrayIndex);
    }

    private static BACnetContextTagUnsignedInteger unsignedContext(byte tagNumber, Integer value) {
        if (value == null) {
            return null;
        }
        return StaticHelper.createBACnetContextTagUnsignedInteger(tagNumber, value.longValue());
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Decoding pipeline
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void handleIncomingMessage(BVLC msg) {
        NPDU npdu = extractNpdu(msg);
        if (npdu == null) {
            LOGGER.warn("Unmapped BVLC {}", msg);
            return;
        }
        APDU apdu = npdu.getApdu();
        Short invokeId = extractResponseInvokeId(apdu);
        if (invokeId != null) {
            CompletableFuture<APDU> future = pending.remove(invokeId);
            if (future != null) {
                future.complete(apdu);
                return;
            }
        }

        if (apdu instanceof APDUConfirmedRequest apduConfirmedRequest) {
            decodeConfirmedRequest(apduConfirmedRequest);
        } else if (apdu instanceof APDUUnconfirmedRequest apduUnconfirmedRequest) {
            decodeUnconfirmedRequest(apduUnconfirmedRequest);
        } else if (apdu instanceof APDUError) {
            // Ignore unmatched errors.
        } else if (apdu == null && npdu.getNlm() != null) {
            // "Who is router?" / "I am router" messages — ignore.
        } else {
            LOGGER.debug("Unexpected NPDU APDU type: {}", apdu == null ? "null" : apdu.getClass().getName());
        }
    }

    private static NPDU extractNpdu(BVLC msg) {
        if (msg instanceof BVLCOriginalUnicastNPDU bvlcUnicast) {
            return bvlcUnicast.getNpdu();
        }
        if (msg instanceof BVLCForwardedNPDU bvlcForwarded) {
            return bvlcForwarded.getNpdu();
        }
        if (msg instanceof BVLCOriginalBroadcastNPDU bvlcBroadcast) {
            return bvlcBroadcast.getNpdu();
        }
        return null;
    }

    private static Short extractResponseInvokeId(APDU apdu) {
        if (apdu instanceof APDUComplexAck complexAck) {
            return complexAck.getOriginalInvokeId();
        }
        if (apdu instanceof APDUSimpleAck simpleAck) {
            return simpleAck.getOriginalInvokeId();
        }
        if (apdu instanceof APDUError error) {
            return error.getOriginalInvokeId();
        }
        if (apdu instanceof APDUReject reject) {
            return reject.getOriginalInvokeId();
        }
        if (apdu instanceof APDUAbort abort) {
            return abort.getOriginalInvokeId();
        }
        return null;
    }

    private void decodeConfirmedRequest(APDUConfirmedRequest apduConfirmedRequest) {
        BACnetConfirmedServiceRequest serviceRequest = apduConfirmedRequest.getServiceRequest();
        if (!(serviceRequest instanceof BACnetConfirmedServiceRequestConfirmedCOVNotification valueChange)) {
            return;
        }
        long deviceIdentifier = valueChange.getMonitoredObjectIdentifier().getPayload().getInstanceNumber();
        int objectType = valueChange.getMonitoredObjectIdentifier().getPayload().getObjectType().getValue();
        long objectInstance = valueChange.getMonitoredObjectIdentifier().getPayload().getInstanceNumber();
        BacNetIpTag curTag = new BacNetIpTag(deviceIdentifier, objectType, objectInstance);

        for (BACnetPropertyValue baCnetPropertyValue : valueChange.getListOfValues().getData()) {
            if (baCnetPropertyValue.getPropertyIdentifier().getValue() != BACnetPropertyIdentifier.PRESENT_VALUE) {
                continue;
            }
            BACnetConstructedDataElement propertyValue = baCnetPropertyValue.getPropertyValue();
            Map<String, PlcValue> enriched = baseFields(curTag, deviceIdentifier, objectType, objectInstance);
            enriched.put("tagNumber", new PlcUSINT(propertyValue.getPeekedTagHeader().getActualTagNumber()));
            enriched.put("lengthValueType", new PlcUSINT(propertyValue.getPeekedTagHeader().getActualLength()));
            applyEdeEnrichment(curTag, enriched);
            publishEvent(curTag, new PlcStruct(enriched));
        }
    }

    private void decodeUnconfirmedRequest(APDUUnconfirmedRequest unconfirmedRequest) {
        BACnetUnconfirmedServiceRequest serviceRequest = unconfirmedRequest.getServiceRequest();
        if (!(serviceRequest instanceof BACnetUnconfirmedServiceRequestUnconfirmedCOVNotification valueChange)) {
            return;
        }
        long deviceIdentifier = valueChange.getMonitoredObjectIdentifier().getPayload().getInstanceNumber();
        int objectType = valueChange.getMonitoredObjectIdentifier().getPayload().getObjectType().getValue();
        long objectInstance = valueChange.getMonitoredObjectIdentifier().getPayload().getInstanceNumber();
        BacNetIpTag curTag = new BacNetIpTag(deviceIdentifier, objectType, objectInstance);

        for (BACnetPropertyValue baCnetPropertyValue : valueChange.getListOfValues().getData()) {
            if (baCnetPropertyValue.getPropertyIdentifier().getValue() != BACnetPropertyIdentifier.PRESENT_VALUE) {
                continue;
            }
            BACnetApplicationTag baCnetTag = ((BACnetConstructedDataUnspecified) baCnetPropertyValue.getPropertyValue().getConstructedData())
                .getData().get(0).getApplicationTag();
            Map<String, PlcValue> enriched = baseFields(curTag, deviceIdentifier, objectType, objectInstance);
            enriched.put("tagNumber", new PlcUSINT(baCnetTag.getActualTagNumber()));
            enriched.put("lengthValueType", new PlcUSINT(baCnetTag.getActualLength()));
            applyEdeEnrichment(curTag, enriched);
            publishEvent(curTag, new PlcStruct(enriched));
        }
    }

    private Map<String, PlcValue> baseFields(BacNetIpTag tag, long deviceIdentifier, int objectType, long objectInstance) {
        Map<String, PlcValue> enriched = new LinkedHashMap<>();
        enriched.put("deviceIdentifier", new PlcUDINT(deviceIdentifier));
        enriched.put("objectType", new PlcDINT(objectType));
        enriched.put("objectInstance", new PlcUDINT(objectInstance));
        enriched.put("address", new PlcSTRING(tag.getDeviceIdentifier() + "/" + tag.getObjectType() + "/" + tag.getObjectInstance()));
        return enriched;
    }

    private void applyEdeEnrichment(BacNetIpTag tag, Map<String, PlcValue> enriched) {
        if (edeModel == null) {
            return;
        }
        Datapoint datapoint = edeModel.getDatapoint(tag);
        if (datapoint != null) {
            enriched.putAll(datapoint.toPlcValues());
        }
    }

    private void publishEvent(BacNetIpTag tag, PlcValue plcValue) {
        DefaultPlcSubscriptionEvent event = new DefaultPlcSubscriptionEvent(Instant.now(),
            Collections.singletonMap("event", new DefaultPlcResponseItem<>(PlcResponseCode.OK, plcValue)));
        for (Consumer<PlcSubscriptionEvent> consumer : consumers.values()) {
            // No tag-level filtering yet — the address pattern is still a TODO.
            consumer.accept(event);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Subscribe / consumer registry
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected CompletableFuture<PlcSubscriptionResponse> onSubscribe(PlcSubscriptionRequest request) {
        Map<String, PlcResponseItem<PlcSubscriptionHandle>> values = new HashMap<>();
        for (String tagName : request.getTagNames()) {
            values.put(tagName, new DefaultPlcResponseItem<>(PlcResponseCode.OK, new BacNetIpSubscriptionHandle(this)));
        }
        return CompletableFuture.completedFuture(new DefaultPlcSubscriptionResponse(request, values));
    }

    @Override
    protected CompletableFuture<PlcUnsubscriptionResponse> onUnsubscribe(PlcUnsubscriptionRequest request) {
        return CompletableFuture.completedFuture(new DefaultPlcUnsubscriptionResponse(request));
    }

    @Override
    protected PlcConsumerRegistration onRegisterConsumer(Consumer<PlcSubscriptionEvent> consumer,
                                                        Collection<PlcSubscriptionHandle> handles) {
        DefaultPlcConsumerRegistration registration = new DefaultPlcConsumerRegistration(this, consumer,
            handles.toArray(new PlcSubscriptionHandle[0]));
        consumers.put(registration, consumer);
        return registration;
    }

    @Override
    protected void onUnregisterConsumer(PlcConsumerRegistration registration) {
        if (registration instanceof DefaultPlcConsumerRegistration r) {
            consumers.remove(r);
        }
    }

}
