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
package org.apache.plc4x.java.bacnetip.tag;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.plc4x.java.api.exceptions.PlcInvalidTagException;
import org.apache.plc4x.java.api.model.ArrayInfo;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcValueType;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetObjectType;
import org.apache.plc4x.java.bacnetip.readwrite.BACnetPropertyIdentifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BACnet/IP tag.
 *
 * <p>Polling / read-write address (aligned with plc4go):
 * {@code OBJECT_TYPE,instance/PROPERTY} with optional {@code [arrayIndex]} and
 * {@code {writePriority}}, multiple properties joined by {@code &}.
 *
 * <pre>
 *   ANALOG_INPUT,1/PRESENT_VALUE
 *   ANALOG_VALUE,10/PRESENT_VALUE{8}
 *   BINARY_INPUT,1/PRESENT_VALUE
 *   ANALOG_INPUT,1/PRIORITY_ARRAY[1]
 *   DEVICE,12001/OBJECT_LIST
 *   0,1/85
 * </pre>
 *
 * <p>The 3-arg constructor {@code (deviceIdentifier, objectType, objectInstance)}
 * is retained for EDE lookup and COV matching; those tags have an empty property
 * list.
 */
public class BacNetIpTag implements PlcTag {

    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
        "^(?<objectType>[\\d\\w]+),(?<objectInstance>\\d+)/(?<propertyIdentifiers>[\\d\\w]+(?:\\[\\d+])?(?:\\{\\d+})?(?:&[\\d\\w]+(?:\\[\\d+])?(?:\\{\\d+})?)*)$");

    private static final Pattern PROPERTY_PATTERN = Pattern.compile(
        "^(?<propertyIdentifier>[\\d\\w]+)(?:\\[(?<arrayIndex>\\d+)])?(?:\\{(?<writePriority>\\d+)})?$");

    public static final int INT_WILDCARD = -1;
    public static final long LONG_WILDCARD = -1;

    private final long deviceIdentifier;
    private final int objectType;
    private final long objectInstance;
    private final List<Property> properties;

    public static boolean matches(String tagString) {
        return tagString != null && ADDRESS_PATTERN.matcher(tagString.trim()).matches();
    }

    public static BacNetIpTag of(String tagString) {
        if (tagString == null) {
            throw new PlcInvalidTagException("Unable to parse address: null");
        }
        Matcher matcher = ADDRESS_PATTERN.matcher(tagString.trim());
        if (!matcher.matches()) {
            throw new PlcInvalidTagException("Unable to parse address: " + tagString);
        }
        int objectType = parseObjectType(matcher.group("objectType"));
        long objectInstance = Long.parseLong(matcher.group("objectInstance"));
        if (objectInstance < 0 || objectInstance > 4_194_302L) {
            throw new PlcInvalidTagException("Object instance out of range (0..4194302): " + objectInstance);
        }
        List<Property> properties = new ArrayList<>();
        for (String propertyString : matcher.group("propertyIdentifiers").split("&")) {
            properties.add(parseProperty(propertyString));
        }
        if (properties.isEmpty()) {
            throw new PlcInvalidTagException("At least one property is required: " + tagString);
        }
        return new BacNetIpTag(LONG_WILDCARD, objectType, objectInstance, properties);
    }

    public BacNetIpTag(long deviceIdentifier, int objectType, long objectInstance) {
        this(deviceIdentifier, objectType, objectInstance, List.of());
    }

    public BacNetIpTag(long deviceIdentifier, int objectType, long objectInstance, List<Property> properties) {
        this.deviceIdentifier = deviceIdentifier;
        this.objectType = objectType;
        this.objectInstance = objectInstance;
        this.properties = properties == null ? List.of() : List.copyOf(properties);
    }

    @Override
    public String getAddressString() {
        if (properties.isEmpty()) {
            return deviceIdentifier + "/" + objectType + "/" + objectInstance;
        }
        StringBuilder propertiesString = new StringBuilder();
        for (int i = 0; i < properties.size(); i++) {
            if (i > 0) {
                propertiesString.append('&');
            }
            propertiesString.append(properties.get(i));
        }
        return objectTypeName() + "," + objectInstance + "/" + propertiesString;
    }

    @Override
    public PlcValueType getPlcValueType() {
        return PlcTag.super.getPlcValueType();
    }

    @Override
    public List<ArrayInfo> getArrayInfo() {
        return PlcTag.super.getArrayInfo();
    }

    public long getDeviceIdentifier() {
        return deviceIdentifier;
    }

    public int getObjectType() {
        return objectType;
    }

    public long getObjectInstance() {
        return objectInstance;
    }

    public List<Property> getProperties() {
        return properties;
    }

    public Property firstProperty() {
        if (properties.isEmpty()) {
            throw new PlcInvalidTagException("Tag has no properties: " + getAddressString());
        }
        return properties.get(0);
    }

    public boolean matches(BacNetIpTag otherTag) {
        return ((deviceIdentifier == LONG_WILDCARD) || (deviceIdentifier == otherTag.deviceIdentifier)) &&
            ((objectType == INT_WILDCARD) || (objectType == otherTag.objectType)) &&
            ((objectInstance == LONG_WILDCARD) || (objectInstance == otherTag.objectInstance));
    }

    public String objectTypeName() {
        BACnetObjectType type = BACnetObjectType.enumForValue((short) objectType);
        if (type != null && type != BACnetObjectType.VENDOR_PROPRIETARY_VALUE) {
            return type.name();
        }
        return Integer.toString(objectType);
    }

    private static int parseObjectType(String raw) {
        String token = raw.trim();
        try {
            int numeric = Integer.parseInt(token);
            if (numeric < 0 || numeric > 1023) {
                throw new PlcInvalidTagException("Object type out of range (0..1023): " + numeric);
            }
            return numeric;
        } catch (NumberFormatException ignored) {
            try {
                BACnetObjectType type = BACnetObjectType.valueOf(token.toUpperCase(Locale.ROOT));
                return type.getValue();
            } catch (IllegalArgumentException e) {
                throw new PlcInvalidTagException("Unknown object type " + token);
            }
        }
    }

    private static Property parseProperty(String raw) {
        Matcher matcher = PROPERTY_PATTERN.matcher(raw);
        if (!matcher.matches()) {
            throw new PlcInvalidTagException("Unable to parse property: " + raw);
        }
        int propertyId = parsePropertyIdentifier(matcher.group("propertyIdentifier"));
        Integer arrayIndex = null;
        String arrayIndexMatch = matcher.group("arrayIndex");
        if (arrayIndexMatch != null && !arrayIndexMatch.isBlank()) {
            arrayIndex = Integer.parseInt(arrayIndexMatch);
        }
        Integer writePriority = null;
        String writePriorityMatch = matcher.group("writePriority");
        if (writePriorityMatch != null && !writePriorityMatch.isBlank()) {
            int parsed = Integer.parseInt(writePriorityMatch);
            if (parsed < 1 || parsed > 16) {
                throw new PlcInvalidTagException("write priority " + parsed + " out of range (1..16)");
            }
            writePriority = parsed;
        }
        return new Property(propertyId, arrayIndex, writePriority);
    }

    private static int parsePropertyIdentifier(String raw) {
        String token = raw.trim();
        try {
            long numeric = Long.parseLong(token);
            if (numeric < 0 || numeric > Integer.MAX_VALUE) {
                throw new PlcInvalidTagException("Property identifier out of range: " + numeric);
            }
            return (int) numeric;
        } catch (NumberFormatException ignored) {
            try {
                BACnetPropertyIdentifier identifier = BACnetPropertyIdentifier.valueOf(token.toUpperCase(Locale.ROOT));
                return (int) identifier.getValue();
            } catch (IllegalArgumentException e) {
                throw new PlcInvalidTagException("Unknown property type " + token);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BacNetIpTag)) {
            return false;
        }
        BacNetIpTag that = (BacNetIpTag) o;
        return new EqualsBuilder()
            .append(getDeviceIdentifier(), that.getDeviceIdentifier())
            .append(getObjectType(), that.getObjectType())
            .append(getObjectInstance(), that.getObjectInstance())
            .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37)
            .append(getDeviceIdentifier())
            .append(getObjectType())
            .append(getObjectInstance())
            .toHashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
            .append("deviceIdentifier", deviceIdentifier)
            .append("objectType", objectType)
            .append("objectInstance", objectInstance)
            .append("properties", properties)
            .toString();
    }

    public static final class Property {
        private final int propertyIdentifier;
        private final Integer arrayIndex;
        private final Integer writePriority;

        public Property(int propertyIdentifier, Integer arrayIndex, Integer writePriority) {
            this.propertyIdentifier = propertyIdentifier;
            this.arrayIndex = arrayIndex;
            this.writePriority = writePriority;
        }

        public int getPropertyIdentifier() {
            return propertyIdentifier;
        }

        public Integer getArrayIndex() {
            return arrayIndex;
        }

        public Integer getWritePriority() {
            return writePriority;
        }

        public String name() {
            BACnetPropertyIdentifier identifier = BACnetPropertyIdentifier.enumForValue(propertyIdentifier);
            if (identifier != null && identifier != BACnetPropertyIdentifier.VENDOR_PROPRIETARY_VALUE) {
                return identifier.name();
            }
            return Integer.toString(propertyIdentifier);
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder(name());
            if (arrayIndex != null) {
                result.append('[').append(arrayIndex).append(']');
            }
            if (writePriority != null) {
                result.append('{').append(writePriority).append('}');
            }
            return result.toString();
        }
    }
}
