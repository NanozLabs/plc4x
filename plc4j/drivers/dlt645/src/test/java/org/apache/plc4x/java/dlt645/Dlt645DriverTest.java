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
package org.apache.plc4x.java.dlt645;

import org.apache.plc4x.java.api.PlcDriver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Dlt645DriverTest {

    @Test
    void serviceLoaderFindsTheClientDriver() {
        assertTrue(ServiceLoader.load(PlcDriver.class).stream()
            .map(ServiceLoader.Provider::get)
            .anyMatch(Dlt645Driver.class::isInstance));
    }

    @Test
    void serialDefaultsAreInjectedWithoutOverwritingExplicitSettings() throws Exception {
        assertEquals(
            "dlt645:serial:///dev/ttyUSB0?meter-address=123456789012&serial.baud-rate=2400" +
                "&serial.data-bits=8&serial.stop-bits=1&serial.parity=even",
            withSerialDefaults("dlt645:serial:///dev/ttyUSB0?meter-address=123456789012"));
        assertEquals(
            "dlt645:serial:///dev/ttyUSB0?serial.baud-rate=9600&serial.parity=odd" +
                "&serial.data-bits=8&serial.stop-bits=1",
            withSerialDefaults("dlt645:serial:///dev/ttyUSB0?serial.baud-rate=9600&serial.parity=odd"));
        assertEquals("dlt645:tcp://localhost:8899", withSerialDefaults("dlt645:tcp://localhost:8899"));
    }

    private static String withSerialDefaults(String connectionString) throws Exception {
        Method method = Dlt645Driver.class.getDeclaredMethod("withSerialDefaults", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, connectionString);
    }
}
