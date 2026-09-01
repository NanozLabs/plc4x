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

import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTag;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagBitString;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagBoolean;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagCharacterString;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagDate;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagDouble;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagEnumerated;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagNull;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagObjectIdentifier;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagOctetString;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagReal;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagSignedInteger;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagTime;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetApplicationTagUnsignedInteger;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetCharacterEncoding;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetConstructedData;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetConstructedDataElement;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetConstructedDataUnspecified;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetObjectType;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetPropertyIdentifier;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetTagHeader;
import org.apache.plc4x.java.bacnetip.readwrite.utils.StaticHelper;
import org.apache.plc4x.java.spi.values.PlcBOOL;
import org.apache.plc4x.java.spi.values.PlcLINT;
import org.apache.plc4x.java.spi.values.PlcLREAL;
import org.apache.plc4x.java.spi.values.PlcList;
import org.apache.plc4x.java.spi.values.PlcNull;
import org.apache.plc4x.java.spi.values.PlcRawByteArray;
import org.apache.plc4x.java.spi.values.PlcREAL;
import org.apache.plc4x.java.spi.values.PlcSTRING;
import org.apache.plc4x.java.spi.values.PlcUDINT;
import org.apache.plc4x.java.spi.values.PlcULINT;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps BACnet application tags / constructed data onto PLC4X {@link PlcValue}
 * and the inverse for writes. Mirrors plc4go {@code ValueDecoder}/{@code ValueEncoder}.
 */
final class BacNetValueCodec {

    private BacNetValueCodec() {
    }

    static PlcValue toPlcValue(BACnetConstructedData data) {
        if (data == null) {
            return new PlcNull();
        }
        if (data instanceof BACnetConstructedDataUnspecified unspecified) {
            return elementsToPlcValue(unspecified.getData());
        }
        Object actual = invokeNoArg(data, "getActualValue");
        if (actual instanceof BACnetApplicationTag tag) {
            return toPlcValue(tag);
        }
        if (actual instanceof BACnetConstructedData nested) {
            return toPlcValue(nested);
        }
        if (actual != null) {
            Object nestedActual = invokeNoArg(actual, "getActualValue");
            if (nestedActual instanceof BACnetApplicationTag tag) {
                return toPlcValue(tag);
            }
            Object value = invokeNoArg(actual, "getValue");
            if (value instanceof Enum<?> enumerated) {
                Object numeric = invokeNoArg(enumerated, "getValue");
                if (numeric instanceof Number number) {
                    return new PlcUDINT(number.longValue());
                }
                return new PlcSTRING(enumerated.name());
            }
            return new PlcSTRING(String.valueOf(actual));
        }
        return new PlcSTRING(data.getClass().getSimpleName());
    }

    static PlcValue toPlcValue(BACnetApplicationTag tag) {
        if (tag == null || tag instanceof BACnetApplicationTagNull) {
            return new PlcNull();
        }
        if (tag instanceof BACnetApplicationTagBoolean booleanTag) {
            return new PlcBOOL(booleanTag.getActualValue());
        }
        if (tag instanceof BACnetApplicationTagUnsignedInteger unsigned) {
            return PlcULINT.of(unsigned.getActualValue());
        }
        if (tag instanceof BACnetApplicationTagSignedInteger signed) {
            return new PlcLINT(signed.getActualValue());
        }
        if (tag instanceof BACnetApplicationTagReal real) {
            return new PlcREAL(real.getActualValue());
        }
        if (tag instanceof BACnetApplicationTagDouble dbl) {
            return new PlcLREAL(dbl.getActualValue());
        }
        if (tag instanceof BACnetApplicationTagCharacterString characterString) {
            return new PlcSTRING(characterString.getValue());
        }
        if (tag instanceof BACnetApplicationTagOctetString octetString) {
            return new PlcRawByteArray(octetString.getPayload().getOctets());
        }
        if (tag instanceof BACnetApplicationTagEnumerated enumerated) {
            return new PlcUDINT(enumerated.getActualValue());
        }
        if (tag instanceof BACnetApplicationTagBitString bitString) {
            List<Boolean> bits = bitString.getPayload().getData();
            return new PlcRawByteArray(bitsToBytes(bits));
        }
        if (tag instanceof BACnetApplicationTagDate date) {
            return new PlcSTRING(String.valueOf(date.getPayload()));
        }
        if (tag instanceof BACnetApplicationTagTime time) {
            return new PlcSTRING(String.valueOf(time.getPayload()));
        }
        if (tag instanceof BACnetApplicationTagObjectIdentifier objectIdentifier) {
            var payload = objectIdentifier.getPayload();
            return new PlcSTRING(payload.getObjectType() + "," + payload.getInstanceNumber());
        }
        return new PlcSTRING(tag.getClass().getSimpleName() + ":" + tag);
    }

    static BACnetApplicationTag toApplicationTag(PlcValue value, int objectType, int propertyId) {
        boolean enumeratedHint = isBinaryPresentValue(objectType, propertyId);
        if (value == null || value.isNull()) {
            return StaticHelper.createBACnetApplicationTagNull();
        }
        if (value.isBoolean()) {
            if (enumeratedHint) {
                return StaticHelper.createBACnetApplicationTagEnumerated(value.getBoolean() ? 1L : 0L);
            }
            return StaticHelper.createBACnetApplicationTagBoolean(value.getBoolean());
        }
        if (value.isString()) {
            return StaticHelper.createBACnetApplicationTagCharacterString(BACnetCharacterEncoding.ISO_10646_4, value.getString());
        }
        if (value.isFloat()) {
            return StaticHelper.createBACnetApplicationTagReal(value.getFloat());
        }
        if (value.isDouble()) {
            double number = value.getDouble();
            if (number == (float) number) {
                return StaticHelper.createBACnetApplicationTagReal((float) number);
            }
            return StaticHelper.createBACnetApplicationTagDouble(number);
        }
        if (value.isBigInteger()) {
            BigInteger big = value.getBigInteger();
            if (enumeratedHint) {
                return StaticHelper.createBACnetApplicationTagEnumerated(big.longValue());
            }
            return StaticHelper.createBACnetApplicationTagUnsignedInteger(big.longValue());
        }
        if (value.isLong() || value.isInteger() || value.isShort() || value.isByte()) {
            long number = value.getLong();
            if (enumeratedHint) {
                return StaticHelper.createBACnetApplicationTagEnumerated(number);
            }
            if (number < 0) {
                return StaticHelper.createBACnetApplicationTagSignedInteger(number);
            }
            return StaticHelper.createBACnetApplicationTagUnsignedInteger(number);
        }
        if (value.getPlcValueType() != null) {
            switch (value.getPlcValueType()) {
                case RAW_BYTE_ARRAY -> {
                    return StaticHelper.createBACnetApplicationTagOctetString(value.getRaw());
                }
                default -> {
                }
            }
        }
        throw new PlcRuntimeException("PlcValue type " + value.getPlcValueType() + " cannot be encoded as a BACnet ApplicationTag");
    }

    static BACnetConstructedData toConstructedData(PlcValue value, int objectType, int propertyId, short tagNumber) {
        BACnetApplicationTag appTag = toApplicationTag(value, objectType, propertyId);
        BACnetTagHeader header = StaticHelper.createBACnetTagHeaderBalanced(true, tagNumber, 0);
        BACnetConstructedDataElement element = new BACnetConstructedDataElement(header, appTag, null, null);
        return new BACnetConstructedDataUnspecified(
            StaticHelper.createBACnetOpeningTag(tagNumber),
            header,
            StaticHelper.createBACnetClosingTag(tagNumber),
            null,
            List.of(element)
        );
    }

    private static PlcValue elementsToPlcValue(List<BACnetConstructedDataElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return new PlcNull();
        }
        List<PlcValue> values = new ArrayList<>(elements.size());
        for (BACnetConstructedDataElement element : elements) {
            if (element.getApplicationTag() != null) {
                values.add(toPlcValue(element.getApplicationTag()));
            } else if (element.getConstructedData() != null) {
                values.add(toPlcValue(element.getConstructedData()));
            } else {
                values.add(new PlcNull());
            }
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        return new PlcList(values);
    }

    private static boolean isBinaryPresentValue(int objectType, int propertyId) {
        if (propertyId != (int) BACnetPropertyIdentifier.PRESENT_VALUE.getValue()) {
            return false;
        }
        return objectType == BACnetObjectType.BINARY_INPUT.getValue()
            || objectType == BACnetObjectType.BINARY_OUTPUT.getValue()
            || objectType == BACnetObjectType.BINARY_VALUE.getValue()
            || objectType == BACnetObjectType.BINARY_LIGHTING_OUTPUT.getValue();
    }

    private static byte[] bitsToBytes(List<Boolean> bits) {
        if (bits == null || bits.isEmpty()) {
            return new byte[0];
        }
        byte[] bytes = new byte[(bits.size() + 7) / 8];
        for (int i = 0; i < bits.size(); i++) {
            if (Boolean.TRUE.equals(bits.get(i))) {
                bytes[i / 8] |= (byte) (0x80 >> (i % 8));
            }
        }
        return bytes;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
