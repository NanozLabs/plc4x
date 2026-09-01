<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

      https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->
# PLC4X DL/T 645-2007 Driver

Client driver for the DL/T 645-2007 multifunction electricity-meter protocol.
It supports direct serial RS-485 and TCP transparent gateways. Server/listener
mode is not part of this module.

## Connection URLs

```text
dlt645:serial:///dev/ttyUSB0
dlt645:tcp://192.168.1.100:8899
dlt645:serial:///dev/ttyUSB0?meter-address=123456789012
```

The connection is the physical channel (one serial port or one TCP gateway).
Meter address belongs on the tag so several meters on the same RS-485 bus can
share one connection. `meter-address` on the URL is an optional default for
single-meter sessions and for tags that omit the prefix.

For `serial`, the driver supplies the standard defaults `2400 baud, 8E1` unless
they are explicitly overridden:

```text
dlt645:serial:///dev/ttyUSB0?serial.baud-rate=9600&serial.parity=even
```

| Driver option | Default | Description |
|---|---:|---|
| `request-timeout` | `5000` | Wire response timeout in milliseconds |
| `meter-address` | empty | Optional printed 12-digit BCD default. Prefer the tag prefix. `999999999999` is broadcast: slaves do not answer ordinary reads/writes; broadcast freeze is one-way. |
| `password` | empty | Four-byte `PA P0 P1 P2` field as 8 hex digits |
| `operator-code` | empty | Four-byte operator code as 8 hex digits |

## Reading And Writing DI Values

Tags use the displayed `DI3 DI2 DI1 DI0` order. An optional printed meter
address selects which slave on a shared bus the request is for:

```text
[<12-digit-meter>/]<8-hex-digit-DI>[:<PlcValueType>]
```

Known numeric DIs default to `LREAL`. `REAL`, integer types, and `STRING` may be
requested explicitly. Unknown or structured DIs must use `RAW_BYTE_ARRAY`; the
driver will not guess their semantic layout.

```java
try (PlcConnection connection = PlcDriverManager.getDefault()
        .getConnectionManager()
        .getConnection("dlt645:serial:///dev/ttyUSB0")) {
    PlcReadResponse response = connection.readRequestBuilder()
        .addTagAddress("energy", "123456789012/00010000")
        .build().execute().get();
    double kWh = response.getDouble("energy");
}
```

Known BCD writes are encoded to the DI's fixed length and decimal scale. Raw
data writes require an explicit `:RAW_BYTE_ARRAY` tag.

Common registered DIs include:

| DI | Description | Format |
|---|---|---|
| `00010000` | Total positive active energy | 4-byte BCD, 2 decimals |
| `02010100` | A-phase voltage | 2-byte BCD, 1 decimal |
| `02020100` | A-phase current | signed 3-byte BCD, 3 decimals |
| `02030000` | Total active power | signed 3-byte BCD, 4 decimals |
| `02060000` | Total power factor | signed 2-byte BCD, 3 decimals |
| `02800002` | Grid frequency | 2-byte BCD, 2 decimals |
| `04000101` | Meter date and time | `DATE_AND_TIME` |

The registry covers common energy and instantaneous variables, not every item
in Annex A. Event, frozen, demand, parameter and load-record structures should
be read as raw bytes unless their DI has a descriptor.

## Administrative Commands

| Tag | Control | Value |
|---|---:|---|
| `cmd:read-address` | `13H` | Read request; uses address `AA` x 6 |
| `cmd:write-address` | `15H` | New printed 12-digit address |
| `cmd:broadcast-time-sync` | `08H` | `YYMMDDhhmmss` or `yyyy-MM-dd HH:mm:ss` |
| `cmd:freeze` | `16H` | Eight BCD digits `MMDDhhmm`; `99999999` means immediate. Broadcast address `999999999999` is one-way (no slave response). |
| `cmd:change-baud-rate` | `17H` | One-hot feature byte: `02/04/08/10/20/40` |
| `cmd:change-password` | `18H` | `DI3DI2DI1DI0:old-password:new-password` |
| `cmd:clear-max-demand` | `19H` | Empty value; uses configured auth fields |
| `cmd:clear-meter` | `1AH` | Empty value; uses configured auth fields |
| `cmd:clear-event` | `1BH` | `FFFFFFFF` or an event DI ending in `FF` |

Broadcast time synchronization is one-way as required by the standard. Address
and programming commands may also require the meter's physical programming key.

## Wire Protocol

```text
FE FE FE FE | 68 | A0..A5 | 68 | C | L | DATA | CS | 16
```

- The four `FE` bytes wake the receiver. The codec sends them and skips any
  leading wake/noise bytes while receiving.
- A six-byte BCD address is transmitted low byte first. For printed address
  `123456789012`, wire bytes are `12 90 78 56 34 12`; bytes are not bit-reversed.
- Every DATA byte is transmitted as `(plain + 33H) mod 256`.
- `CS` is the modulo-256 sum from the first `68` through the last DATA byte.
- Control bit D7 is direction, D6 is abnormal response, D5 announces more data,
  and D0-D4 contain the function number.
- Abnormal responses contain one decoded `ERR` byte.
- Subsequent read requests and responses carry a sequence number from 1 to 255.
  A response layout is `DI0..DI3 + data + SEQ`.

DL/T 645 has no transaction identifier. The driver therefore permits exactly
one request on the wire and validates response address, control, DI and sequence
before completing it.

## Build

```bash
./mvnw -pl protocols/dlt645,plc4j/drivers/dlt645 -am \
  -P with-java test -DskipCheckstyle -Drat.skip=true
```
