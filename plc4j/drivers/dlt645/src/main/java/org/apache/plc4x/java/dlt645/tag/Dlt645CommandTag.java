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
package org.apache.plc4x.java.dlt645.tag;

import org.apache.plc4x.java.api.exceptions.PlcInvalidTagException;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcValueType;
import org.apache.plc4x.java.dlt645.context.Dlt645DriverContext;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * DL/T 645-2007 Command Tag for administrative operations.
 * <p>
 * Tag address format: {@code [meter-address/]cmd:<command-name>}
 * <p>
 * Supported commands:
 * <ul>
 *   <li>{@code cmd:read-address} — Read meter communication address (0x13)</li>
 *   <li>{@code cmd:write-address} — Write meter communication address (0x15)</li>
 *   <li>{@code cmd:broadcast-time-sync} — Broadcast clock synchronization (0x08)</li>
 *   <li>{@code cmd:freeze} — Freeze data command (0x16)</li>
 *   <li>{@code cmd:change-baud-rate} — Change baud rate (0x17)</li>
 *   <li>{@code cmd:change-password} — Change password (0x18)</li>
 *   <li>{@code cmd:clear-max-demand} — Clear max demand (0x19)</li>
 *   <li>{@code cmd:clear-meter} — Clear meter (0x1A)</li>
 *   <li>{@code cmd:clear-event} — Clear event log (0x1B)</li>
 * </ul>
 */
public class Dlt645CommandTag implements PlcTag {

    public enum CommandType {
        READ_ADDRESS("read-address", true),
        WRITE_ADDRESS("write-address", false),
        BROADCAST_TIME_SYNC("broadcast-time-sync", false),
        FREEZE("freeze", false),
        CHANGE_BAUD_RATE("change-baud-rate", false),
        CHANGE_PASSWORD("change-password", false),
        CLEAR_MAX_DEMAND("clear-max-demand", false),
        CLEAR_METER("clear-meter", false),
        CLEAR_EVENT("clear-event", false);

        private final String keyword;
        private final boolean readable;

        CommandType(String keyword, boolean readable) {
            this.keyword = keyword;
            this.readable = readable;
        }

        public String getKeyword() {
            return keyword;
        }

        /** Whether this command is used in read requests. */
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
        Pattern.compile("^(?:(?<meter>\\d{1,12})/)?cmd:(?<cmd>[a-z][a-z0-9-]*)$");

    private final CommandType commandType;
    private final String meterAddress;
    private final byte[] meterAddressBytes;

    public Dlt645CommandTag(CommandType commandType) {
        this(commandType, null);
    }

    public Dlt645CommandTag(CommandType commandType, String meterAddress) {
        this.commandType = commandType;
        if (meterAddress == null || meterAddress.isEmpty()) {
            this.meterAddress = null;
            this.meterAddressBytes = null;
        } else {
            this.meterAddress = Dlt645DriverContext.formatPrintedAddress(meterAddress);
            this.meterAddressBytes = Dlt645DriverContext.parseMeterAddress(meterAddress);
        }
    }

    public static boolean matches(String tagAddress) {
        if (tagAddress == null) return false;
        var matcher = COMMAND_PATTERN.matcher(tagAddress);
        if (!matcher.matches()) return false;
        return CommandType.fromKeyword(matcher.group("cmd")) != null;
    }

    public static Dlt645CommandTag of(String tagAddress) {
        var matcher = COMMAND_PATTERN.matcher(tagAddress);
        if (!matcher.matches()) {
            throw new PlcInvalidTagException(tagAddress, COMMAND_PATTERN, "[meter-address/]cmd:<command-name>");
        }
        var cmdType = CommandType.fromKeyword(matcher.group("cmd"));
        if (cmdType == null) {
            throw new PlcInvalidTagException(tagAddress, COMMAND_PATTERN,
                "Unknown command. Supported: read-address, write-address, broadcast-time-sync, freeze, " +
                    "change-baud-rate, change-password, clear-max-demand, clear-meter, clear-event");
        }
        String meter = matcher.group("meter");
        return new Dlt645CommandTag(cmdType, meter == null || meter.isEmpty() ? null : meter);
    }

    public CommandType getCommandType() {
        return commandType;
    }

    public String getMeterAddress() {
        return meterAddress;
    }

    public byte[] getMeterAddressBytes() {
        return meterAddressBytes == null ? null : Arrays.copyOf(meterAddressBytes, meterAddressBytes.length);
    }

    @Override
    public String getAddressString() {
        if (meterAddress == null) {
            return "cmd:" + commandType.getKeyword();
        }
        return meterAddress + "/cmd:" + commandType.getKeyword();
    }

    @Override
    public PlcValueType getPlcValueType() {
        return PlcValueType.STRING;
    }

    @Override
    public String toString() {
        return "Dlt645CommandTag{cmd=" + commandType.getKeyword() + '}';
    }
}
