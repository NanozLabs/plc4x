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
package org.apache.plc4x.java.cjt188;

import org.apache.plc4x.java.api.PlcDriver;
import org.apache.plc4x.java.cjt188.tag.Cjt188CommandTag;
import org.apache.plc4x.java.cjt188.tag.Cjt188Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Cjt188DriverTest {

    @Test
    void serviceLoaderFindsTheClientDriver() {
        assertTrue(ServiceLoader.load(PlcDriver.class).stream()
            .map(ServiceLoader.Provider::get)
            .anyMatch(Cjt188Driver.class::isInstance));
    }

    @Test
    void protocolMetadataAndCapabilities() {
        var driver = new Cjt188Driver();
        assertEquals("cjt188", driver.getProtocolCode());
        assertEquals("CJ/T 188-2004", driver.getProtocolName());
        assertEquals("serial", driver.getDefaultTransportCode().orElseThrow());
        assertTrue(driver.getSupportedTransportCodes().contains("tcp"));
        assertTrue(driver.defaultPorts("tcp").contains(8899));
        assertInstanceOf(Cjt188Tag.class, driver.prepareTag("901F"));
        assertInstanceOf(Cjt188CommandTag.class, driver.prepareTag("cmd:read-address"));
        assertTrue((Boolean) invoke(driver, "canPing"));
        assertTrue((Boolean) invoke(driver, "canRead"));
        assertTrue((Boolean) invoke(driver, "canWrite"));
        assertEquals(org.apache.plc4x.java.cjt188.config.Cjt188Configuration.class,
            invoke(driver, "getConfigurationClass"));
    }

    private static Object invoke(Cjt188Driver driver, String name) {
        try {
            Method method = Cjt188Driver.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(driver);
        } catch (ReflectiveOperationException e) {
            try {
                Method method = driver.getClass().getSuperclass().getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(driver);
            } catch (ReflectiveOperationException e2) {
                throw new RuntimeException(e2);
            }
        }
    }

    @Test
    void serialDefaultsAreInjectedWithoutOverwritingExplicitSettings() throws Exception {
        assertEquals(
            "cjt188:serial:///dev/ttyUSB0?meter-address=123456789012&serial.baud-rate=2400" +
                "&serial.data-bits=8&serial.stop-bits=1&serial.parity=even",
            withSerialDefaults("cjt188:serial:///dev/ttyUSB0?meter-address=123456789012"));
        assertEquals(
            "cjt188:serial:///dev/ttyUSB0?serial.baud-rate=9600&serial.parity=odd" +
                "&serial.data-bits=8&serial.stop-bits=1",
            withSerialDefaults("cjt188:serial:///dev/ttyUSB0?serial.baud-rate=9600&serial.parity=odd"));
        assertEquals("cjt188:tcp://localhost:8899", withSerialDefaults("cjt188:tcp://localhost:8899"));
    }

    private static String withSerialDefaults(String connectionString) throws Exception {
        Method method = Cjt188Driver.class.getDeclaredMethod("withSerialDefaults", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, connectionString);
    }
}
