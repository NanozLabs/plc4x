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
package org.apache.plc4x.java.cjt188.tag;

import org.apache.plc4x.java.api.exceptions.PlcInvalidTagException;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcValueType;

import java.util.regex.Pattern;

/**
 * CJ/T 188-2004 Command Tag for administrative operations.
 * <p>
 * Tag address format: {@code cmd:&lt;command-name&gt;}
 * <p>
 * Supported commands:
 * <ul>
 *   <li>{@code cmd:read-address} — Read meter communication address (0x03)</li>
 *   <li>{@code cmd:write-address} — Write meter communication address (0x15)</li>
 *   <li>{@code cmd:write-sync} — Write electromechanical sync data (0x16)</li>
 * </ul>
 */
public class Cjt188CommandTag implements PlcTag {

    public enum CommandType {
        READ_ADDRESS("read-address", true),
        WRITE_ADDRESS("write-address", false),
        WRITE_SYNC("write-sync", false);

        private final String keyword;
        private final boolean readable;

        CommandType(String keyword, boolean readable) {
            this.keyword = keyword;
            this.readable = readable;
        }

        public String getKeyword() {
            return keyword;
        }

        public boolean isReadable() {
            return readable;
        }

        public static CommandType fromKeyword(String keyword) {
            for (var ct : values()) {
                if (ct.keyword.equals(keyword)) {
                    return ct;
                }
            }
            return null;
        }
    }

    public static final Pattern COMMAND_PATTERN =
        Pattern.compile("^cmd:([a-z][a-z0-9-]*)(?:\\{.*})?$");

    private final CommandType commandType;
    private final String meterAddress;
    private final String meterType;

    public Cjt188CommandTag(CommandType commandType) {
        this(commandType, null, null);
    }

    public Cjt188CommandTag(CommandType commandType, String meterAddress, String meterType) {
        this.commandType = commandType;
        this.meterAddress = meterAddress == null || meterAddress.isBlank() ? null : meterAddress;
        this.meterType = meterType == null || meterType.isBlank() ? null : meterType;
    }

    public static boolean matches(String tagAddress) {
        if (tagAddress == null) {
            return false;
        }
        var matcher = COMMAND_PATTERN.matcher(tagAddress);
        if (!matcher.matches()) {
            return false;
        }
        return CommandType.fromKeyword(matcher.group(1)) != null;
    }

    public static Cjt188CommandTag of(String tagAddress) {
        var matcher = COMMAND_PATTERN.matcher(tagAddress);
        if (!matcher.matches()) {
            throw new PlcInvalidTagException(tagAddress, COMMAND_PATTERN,
                "cmd:<command-name>[{meter-address:...,meter-type:...}]");
        }
        var cmdType = CommandType.fromKeyword(matcher.group(1));
        if (cmdType == null) {
            throw new PlcInvalidTagException(tagAddress, COMMAND_PATTERN,
                "Unknown command. Supported: read-address, write-address, write-sync");
        }
        var config = org.apache.plc4x.java.spi.drivers.tags.TagConfigParser.parse(tagAddress);
        return new Cjt188CommandTag(cmdType, config.get("meter-address"), config.get("meter-type"));
    }

    public CommandType getCommandType() {
        return commandType;
    }

    public String getMeterAddress() {
        return meterAddress;
    }

    public String getMeterType() {
        return meterType;
    }

    @Override
    public String getAddressString() {
        var sb = new StringBuilder("cmd:").append(commandType.getKeyword());
        if (meterAddress != null || meterType != null) {
            sb.append('{');
            if (meterAddress != null) {
                sb.append("meter-address:").append(meterAddress);
            }
            if (meterType != null) {
                if (meterAddress != null) {
                    sb.append(',');
                }
                sb.append("meter-type:").append(meterType);
            }
            sb.append('}');
        }
        return sb.toString();
    }

    @Override
    public PlcValueType getPlcValueType() {
        return PlcValueType.STRING;
    }

    @Override
    public String toString() {
        return "Cjt188CommandTag{cmd=" + commandType.getKeyword() + '}';
    }
}
