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
package org.apache.plc4x.java.transport.tcpserver.registration;

import java.util.Objects;
import java.util.Optional;

/**
 * Represents the outcome of parsing a device registration packet.
 *
 * <p>This immutable value object captures the result of a registration packet
 * parsing attempt, driving the state machine in the registration handler.</p>
 *
 * @see RegistrationPacketParser
 */
public final class RegistrationResult {

    /**
     * The parsing status indicating the outcome of a parse attempt.
     */
    public enum Status {
        /**
         * Parser successfully extracted a device identifier.
         * The input has been advanced by {@link #getConsumedBytes()} bytes.
         */
        COMPLETE,

        /**
         * Parser requires more data to complete.
         * The input remains unchanged.
         */
        NEED_MORE_DATA,

        /**
         * Parser detected invalid or malformed data.
         * The connection should be closed.
         */
        INVALID
    }

    private static final RegistrationResult NEED_MORE = new RegistrationResult(
        Status.NEED_MORE_DATA, null, 0, null);

    private final Status status;
    private final String deviceId;
    private final int consumedBytes;
    private final String failureReason;

    private RegistrationResult(Status status, String deviceId, int consumedBytes, String failureReason) {
        this.status = status;
        this.deviceId = deviceId;
        this.consumedBytes = consumedBytes;
        this.failureReason = failureReason;
    }

    /**
     * Creates a successful completion result with the extracted device ID.
     *
     * @param deviceId the extracted device identifier; must not be null or empty
     * @param consumedBytes the number of bytes consumed from the buffer; must be positive
     * @return a new result indicating successful parsing
     * @throws IllegalArgumentException if deviceId is null/empty or consumedBytes is non-positive
     */
    public static RegistrationResult complete(String deviceId, int consumedBytes) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("Device ID must not be null or blank");
        }
        if (consumedBytes <= 0) {
            throw new IllegalArgumentException("Consumed bytes must be positive: " + consumedBytes);
        }
        return new RegistrationResult(Status.COMPLETE, deviceId, consumedBytes, null);
    }

    /**
     * Returns a singleton result indicating more data is needed.
     *
     * @return a result indicating insufficient data
     */
    public static RegistrationResult needMoreData() {
        return NEED_MORE;
    }

    /**
     * Creates an invalid result with an explanatory reason.
     *
     * @param reason a human-readable description of why parsing failed; if null, "Unknown error" is used
     * @return a new result indicating invalid data
     */
    public static RegistrationResult invalid(String reason) {
        String effectiveReason = (reason == null || reason.isBlank()) ? "Unknown error" : reason;
        return new RegistrationResult(Status.INVALID, null, 0, effectiveReason);
    }

    /**
     * Returns the parsing status.
     *
     * @return the status indicating whether parsing completed, needs more data, or failed
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Returns the extracted device identifier.
     *
     * @return the device ID, or null if status is not COMPLETE
     */
    public String getDeviceId() {
        return deviceId;
    }

    /**
     * Returns the number of bytes consumed from the buffer.
     *
     * @return the consumed byte count; 0 if status is not COMPLETE
     */
    public int getConsumedBytes() {
        return consumedBytes;
    }

    /**
     * Returns the failure reason if parsing was invalid.
     *
     * @return an Optional containing the failure reason, or empty if not applicable
     */
    public Optional<String> getFailureReason() {
        return Optional.ofNullable(failureReason);
    }

    /**
     * Checks if this result represents successful completion.
     *
     * @return true if status is COMPLETE
     */
    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    /**
     * Checks if this result indicates more data is needed.
     *
     * @return true if status is NEED_MORE_DATA
     */
    public boolean needsMoreData() {
        return status == Status.NEED_MORE_DATA;
    }

    /**
     * Checks if this result indicates invalid data.
     *
     * @return true if status is INVALID
     */
    public boolean isInvalid() {
        return status == Status.INVALID;
    }

    @Override
    public String toString() {
        return switch (status) {
            case COMPLETE -> String.format("RegistrationResult[COMPLETE, deviceId=%s, consumed=%d]",
                deviceId, consumedBytes);
            case NEED_MORE_DATA -> "RegistrationResult[NEED_MORE_DATA]";
            case INVALID -> String.format("RegistrationResult[INVALID, reason=%s]", failureReason);
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RegistrationResult that)) return false;
        return consumedBytes == that.consumedBytes &&
            status == that.status &&
            Objects.equals(deviceId, that.deviceId) &&
            Objects.equals(failureReason, that.failureReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, deviceId, consumedBytes, failureReason);
    }
}
