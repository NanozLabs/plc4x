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
package org.apache.plc4x.java.cjt188.config;

import org.apache.plc4x.java.spi.config.Configuration;
import org.apache.plc4x.java.spi.config.annotations.ConfigurationParameter;
import org.apache.plc4x.java.spi.config.annotations.Description;
import org.apache.plc4x.java.spi.config.annotations.defaults.StringDefaultValue;

public class Cjt188Configuration implements Configuration {

    @ConfigurationParameter("request-timeout")
    @Description("Default timeout for all types of requests in milliseconds.")
    protected int requestTimeout = 5_000;

    @ConfigurationParameter("meter-address")
    @Description("Optional default printed 12-digit BCD meter address, or 14 hex digits including the leading meter-type byte. Used when a tag omits meter-address. Required for ping and for tags that do not name a meter.")
    private String meterAddress;

    @ConfigurationParameter("meter-type")
    @StringDefaultValue("10")
    @Description("Meter type byte as two hex digits (A0). Used when meter-address is 12 digits. 10=cold water, 11=hot water, 12=drinking water, 13=reclaimed water, 20=heat, 30=gas.")
    private String meterType;

    public int getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(int requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public String getMeterAddress() {
        return meterAddress;
    }

    public void setMeterAddress(String meterAddress) {
        this.meterAddress = meterAddress;
    }

    public String getMeterType() {
        return meterType;
    }

    public void setMeterType(String meterType) {
        this.meterType = meterType;
    }

    @Override
    public String toString() {
        return "Cjt188Configuration{" +
            "requestTimeout=" + requestTimeout +
            ", meterAddress='" + meterAddress + '\'' +
            ", meterType='" + meterType + '\'' +
            '}';
    }
}
