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

/**
 * Strategy interface for parsing device registration packets in server-side transports.
 *
 * <p>This interface enables the implementation of various registration packet formats
 * commonly used by DTU (Data Transfer Unit) devices when establishing connections
 * with industrial control systems.</p>
 *
 * <p>Implementations must handle partial data scenarios gracefully, as TCP may deliver
 * registration frames in multiple pieces due to segmentation.</p>
 *
 * <h3>Implementation Guidelines:</h3>
 * <ul>
 *   <li>Never mutate the input array unless parsing succeeds</li>
 *   <li>Return {@link RegistrationResult#needMoreData()} when insufficient bytes available</li>
 *   <li>Return {@link RegistrationResult#invalid(String)} on format violations</li>
 *   <li>Implementations should be stateless and thread-safe</li>
 * </ul>
 *
 * @see RegistrationResult
 * @see org.apache.plc4x.java.transport.tcpserver.registration.impl.FixedLengthParser
 * @see org.apache.plc4x.java.transport.tcpserver.registration.impl.RegexParser
 * @see org.apache.plc4x.java.transport.tcpserver.registration.impl.PrefixLengthParser
 * @see org.apache.plc4x.java.transport.tcpserver.registration.impl.DelimiterParser
 */
public interface RegistrationPacketParser {

    /**
     * Attempts to parse a device registration packet from the provided buffer.
     *
     * <p>The parser should examine the bytes in the buffer and attempt to extract a
     * device identifier according to its configured format.</p>
     *
     * @param buffer the inbound bytes positioned at the first byte of the expected
     *               registration packet; must not be null
     * @return a {@link RegistrationResult} indicating:
     *         <ul>
     *           <li>{@code COMPLETE} - successful parsing with device ID and consumed bytes</li>
     *           <li>{@code NEED_MORE_DATA} - insufficient data, await more bytes</li>
     *           <li>{@code INVALID} - malformed data, connection should be closed</li>
     *         </ul>
     * @throws NullPointerException if buffer is null
     */
    RegistrationResult parse(byte[] buffer);

    /**
     * Returns a human-readable description of this parser's expected format.
     *
     * <p>This is primarily used for logging and diagnostic purposes.</p>
     *
     * @return a description of the expected registration packet format
     */
    default String getFormatDescription() {
        return getClass().getSimpleName();
    }
}
