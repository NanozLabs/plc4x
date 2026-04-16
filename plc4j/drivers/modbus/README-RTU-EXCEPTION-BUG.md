<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License. You may obtain a copy of the License at

   https://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied. See the License for the
 specific language governing permissions and limitations
 under the License.
-->

# Modbus RTU exception response CRC bug

## Summary

This fork fixes a PLC4X Modbus RTU bug where valid exception responses, such as `0f8602a262`, were not parsed successfully.

## Root cause

In RTU mode, `ModbusPDUError` discarded the original Modbus function code and always returned `0` from `getFunctionFlag()`.

When `ModbusRtuADU` verified the CRC during parsing, it recomputed the CRC from the parsed PDU object. Because the error PDU serialized back out as `0x80 0x02` instead of the original `0x86 0x02`, the CRC check failed even though the wire frame was valid.

That caused PLC4X to drop the exception response before it reached `ModbusRtuProtocolLogic.handle(...)`, which then led to request timeout behavior.

## Impact

- Valid RTU exception responses were dropped
- Client operations timed out even when the server replied correctly
- In RTU mode, the timeout path could block subsequent requests because the transaction slot was not released

## Fixes in this fork

1. Preserve the original function code in `ModbusPDUError`
2. Parse RTU exception frames correctly so CRC verification succeeds
3. Release the RTU transaction on timeout and on error to avoid queue blockage
4. Add a parser/serializer regression test for `0f8602a262`

## Reproduction frame

Request:

```text
0f06000200036925
```

Expected exception response:

```text
0f8602a262
```

## Files touched

- `plc4j/drivers/modbus/src/main/generated/org/apache/plc4x/java/modbus/readwrite/ModbusPDU.java`
- `plc4j/drivers/modbus/src/main/generated/org/apache/plc4x/java/modbus/readwrite/ModbusPDUError.java`
- `plc4j/drivers/modbus/src/main/java/org/apache/plc4x/java/modbus/rtu/protocol/ModbusRtuProtocolLogic.java`
- `protocols/modbus/src/test/resources/protocols/modbus/rtu/ParserSerializerTestsuite.xml`
