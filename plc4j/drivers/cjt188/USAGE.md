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
# PLC4X CJ/T 188-2004 Driver

Client driver for **CJ/T 188-2004** 《户用计量仪表数据传输技术条件》
(household water, heat and gas meters). It supports direct serial RS-485 and
TCP transparent gateways. Server/listener mode is not part of this module.

The frame layout follows the standard: 7-byte address (A0 = meter type,
A1..A6 = 12-digit BCD, low byte first), 2-byte data identifier, 1-byte
sequence number, and DATA bytes transmitted as `(plain + 33H) mod 256`.
Meter types and the data-identifier map are both specified by the standard
(see [Fixed data identifiers](#fixed-data-identifiers-annexes)); a compliant
vendor does not invent its own point addresses.

## Connection URLs

A connection is the **serial port / TCP gateway** (the RS-485 bus), not a
single meter. Several household meters may share one 485 pair; the OS will
not let two processes (or two PLC4X connections) open the same UART, so
those meters must be read through **one** connection. The driver already
sends only one frame at a time.

```text
cjt188:serial:///dev/ttyUSB0
cjt188:serial:///dev/ttyUSB0?meter-address=123456789012&meter-type=10
cjt188:tcp://192.168.1.100:8899?meter-address=123456789012&meter-type=20
cjt188:tcp://192.168.1.100:8899?meter-address=10123456789012
```

`meter-address` on the URL is an **optional default**. Tags that omit a meter
use it; tags that name a meter ignore it. Ping still needs the URL default.

The 14-digit form `meter-address=TTAAAAAAAAAAAA` folds the meter-type byte
into the address (`TT` is A0). When the address is 12 digits, `meter-type`
supplies A0.

For `serial`, the driver supplies the standard defaults `2400 baud, 8E1`
unless they are explicitly overridden:

```text
cjt188:serial:///dev/ttyUSB0?serial.baud-rate=9600&serial.parity=even
```

| Driver option | Default | Description |
|---|---:|---|
| `request-timeout` | `5000` | Wire response timeout in milliseconds |
| `meter-address` | *(optional)* | Default printed 12-digit BCD address, or 14 hex digits including meter type. Used only when the tag does not name a meter |
| `meter-type` | `10` | Default A0 meter-type byte as two hex digits. Ignored when the chosen address is 14 digits |

### Meter type (A0)

A0 is **defined by the standard**, not by the vendor and not by this driver.
CJ/T 188-2004 puts it in the 7-byte communication address so that mixed
water / heat / gas meters on one RS-485 bus can be distinguished.

| Code | Type | Annex |
|---|---|---|
| `10` | Cold water meter (冷水水表) | A |
| `11` | Domestic hot water (生活热水水表) | A |
| `12` | Drinking water (直饮水水表) | A |
| `13` | Reclaimed water (中水水表) | A |
| `20` | Heat meter (热量表) | B |
| `30` | Gas meter (燃气表) | C |

`AA` × 7 is the wildcard address (read/write communication address).
`99` × 7 is broadcast.

Example: printed address `123456789012`, cold-water type `10` is transmitted as
wire bytes `10 12 90 78 56 34 12`.

The driver accepts any two hex digits for `meter-type` so a non-standard A0
still reaches the wire. The six codes above are the official table.

## Tag Address Format

Tags use the displayed `DI1 DI0` order (4 hex digits). The meter identity is
optional when the connection URL already has a default:

```text
[meter-address/]<4-hex-digit-DI>[:<PlcValueType>][{meter-address:...,meter-type:...}]
```

| Form | Meaning |
|---|---|
| `901F` | DI only; uses the URL default meter |
| `123456789012/901F` | 12-digit printed address + DI; A0 from URL `meter-type` (or `10`) |
| `10123456789012/901F` | 14 hex digits (type `10` + printed address) + DI |
| `901F{meter-address:123456789012,meter-type:20}` | Same as prefix, Modbus-style options |
| `123456789012/901F{meter-address:999999999999}` | Curly `meter-address` **wins** over the prefix and over the URL |

CJ/T 188 meter identity is **not** a one-byte Modbus `unit-id`. It is the
7-byte frame address: A0 (meter type, two hex digits such as `10` / `20` /
`30`) plus a 12-digit BCD serial. On the tag it is written as those 12 or
14 digits, not as a long opaque hex blob.

A tag-level meter address always overrides the connection URL. That is the
form to use when two meters share one 485 line:

```java
try (PlcConnection connection = PlcDriverManager.getDefault()
        .getConnectionManager()
        .getConnection("cjt188:serial:///dev/ttyUSB0")) {
    PlcReadResponse response = connection.readRequestBuilder()
        .addTagAddress("cold", "10123456789012/901F")
        .addTagAddress("heat", "20987654321098/901F")
        .build().execute().get();
    double coldM3 = response.getDouble("cold");
    double heatKwh = response.getDouble("heat");
}
```

Single-meter shortcut (address on the URL, omitted on the tag):

```java
try (PlcConnection connection = PlcDriverManager.getDefault()
        .getConnectionManager()
        .getConnection("cjt188:serial:///dev/ttyUSB0?meter-address=123456789012&meter-type=10")) {
    PlcReadResponse response = connection.readRequestBuilder()
        .addTagAddress("volume", "901F")
        .build().execute().get();
    double cubicMetres = response.getDouble("volume");
}
```

Known numeric DIs default to `LREAL`. `REAL`, integer types, and `STRING` may be
requested explicitly. Unknown or structured DIs must use `RAW_BYTE_ARRAY`.
A data read/write that names neither a tag meter nor a URL default fails.

Known BCD writes are encoded to the DI's fixed length and decimal scale. Raw
data writes require an explicit `:RAW_BYTE_ARRAY` tag. Some meters append a
2-byte status word after the value; the driver keeps the DI's registered
length and ignores the trailing status.

### Fixed data identifiers (annexes)

The standard does two things: it enumerates meter types (A0), and it assigns
a **fixed 2-byte data identifier** to each measurement for that type
(Annex A water, Annex B heat, Annex C gas).

A vendor that implements CJ/T 188 must use those codes. Current accumulated
quantity is always tag `901F`; settlement-day history is always `9010`…
`901C`; the encoding (BCD length and decimal places) is also fixed. This is
unlike Modbus, where each manufacturer invents its own register map.

Meter type and DI live in different parts of the frame:

| | Meter type A0 | Data identifier DI |
|---|---|---|
| Where | 7-byte address field | DATA field |
| Role | Which device | Which quantity |
| Example | `10` = cold water | `901F` = current accumulated |

The DI **code space is shared**. The same four hex digits can mean a
different physical quantity depending on A0:

| DI | Water (A0 `10`–`13`) / gas (`30`) | Heat (A0 `20`) |
|---|---|---|
| `901F` | Current volume, m³ | Current heat energy, kWh (sometimes GJ) |
| `9010` | Settlement-day volume, m³ | Settlement-day heat energy |
| `9011` … `901C` | History, volume | History, heat energy |

The driver does not rewrite the tag when A0 changes. Tag `901F` is always
sent as DI `1F 90`; A0 only selects the meter on the bus. The engineering
unit is implied by the meter type on the connection.

Not every meter implements every row in its annex. A cheap water meter may
only answer `901F`, clock and status; heat power and temperatures exist on
heat meters. A missing DI comes back as an abnormal response (no requested
data).

### Shared identifiers (Annex A / B / C)

| DI | Description | Format |
|---|---|---|
| `901F` | Current accumulated quantity | 4-byte BCD, 2 decimals |
| `9010` | Settlement-day accumulated quantity | 4-byte BCD, 2 decimals |
| `9011` … `901C` | Settlement-day −1 … −12 | 4-byte BCD, 2 decimals |
| `D120` | Real-time clock `YYMMDDhhmmss` | `DATE_AND_TIME` |
| `8102` | Status ST | `RAW_BYTE_ARRAY`, 2 bytes |
| `8103` | Remaining amount | 4-byte BCD, 2 decimals |
| `8104` | Remaining volume | 4-byte BCD, 2 decimals |
| `A017` | Valve status / control | 1 byte: `99` open, `55` close |

### Heat-meter identifiers (Annex B)

| DI | Description | Format |
|---|---|---|
| `9020` | Current heat power | 3-byte BCD, 2 decimals, kW |
| `90A0` | Instantaneous flow | 4-byte BCD, 2 decimals, m³/h |
| `80A0` | Accumulated flow | 4-byte BCD, 2 decimals, m³ |
| `80AA` / `8110` | Supply temperature | 2-byte BCD, 2 decimals, °C |
| `80AB` / `8111` | Return temperature | 2-byte BCD, 2 decimals, °C |
| `80AC` / `8112` | Accumulated operating time | 3-byte BCD, hours |
| `80B0` | Real-time clock | `DATE_AND_TIME` |

`8110` / `8111` / `8112` are common vendor aliases of `80AA` / `80AB` /
`80AC`. The driver registers both.

The registry is the commonly used annex subset, not every row of the
standard. Vendors may add private DIs; those must not replace the standard
codes. Unknown DIs:

```text
ABCD:RAW_BYTE_ARRAY
```

A DI containing `FF` (for example `90FF`) is treated as a wildcard block read.
The response is a struct keyed by each concrete sub-item DI. Use
`90FF:RAW_BYTE_ARRAY` if the concatenated payload should be returned as bytes.

## Administrative Commands

| Tag | Control | Value |
|---|---:|---|
| `cmd:read-address` | `03H` | Read request; uses address `AA` x 7 |
| `cmd:write-address` | `15H` | New printed 12-digit address, or 14 hex digits including type |
| `cmd:write-sync` | `16H` | `DI1DI0:decimal`, e.g. `901F:1234.56` (electromechanical sync) |

```java
PlcReadResponse address = connection.readRequestBuilder()
    .addTagAddress("addr", "cmd:read-address")
    .build().execute().get();
String printed = address.getString("addr"); // e.g. 10123456789012

connection.writeRequestBuilder()
    .addTagAddress("valve", "A017:RAW_BYTE_ARRAY", new byte[]{(byte) 0x99})
    .build().execute().get();
```

## Wire Protocol

```text
FE FE FE FE | 68 | A0..A6 | 68 | C | L | DATA | CS | 16
```

- The four `FE` bytes wake the receiver. The codec sends them and skips any
  leading wake/noise bytes while receiving.
- Address is **7 bytes**. A0 is the meter type. A1..A6 are the 12-digit BCD
  address, low byte first. For printed address `123456789012` and type `10`,
  wire bytes are `10 12 90 78 56 34 12`.
- Data identifier is **2 bytes**, transmitted `DI0` then `DI1`. Tag addresses
  use the displayed `DI1 DI0` order: tag `901F` is sent as `1F 90`.
- Every DATA byte is transmitted as `(plain + 33H) mod 256`.
- `CS` is the modulo-256 sum from the first `68` through the last DATA byte.
- Control bit D7 is direction, D6 is abnormal response, D5 announces more data,
  and D0-D4 contain the function number.
- Read/write **data** (`01H` / `04H`) and write-sync (`16H`) carry a 1-byte
  sequence number `SER` after the DI. Request layout: `DI0 DI1 SER [payload]`.
- Read address (`03H`) has empty DATA (`L=0`). Write address (`15H`) DATA is
  the 7-byte wire address only. Those commands have no DI and no SER.
- Subsequent frames reuse control `01H` with an incremented `SER` (there is no
  separate "read subsequent" function as in DL/T 645).
- Abnormal responses contain one `ERR` byte, optionally preceded by `SER`.

CJ/T 188 has no transaction identifier. The driver therefore permits exactly
one request on the wire and validates response address, control and DI before completing it.

### Control codes (CJ/T 188-2004)

| Function | Master | Slave OK | Slave more | Slave error |
|---|---:|---:|---:|---:|
| Read data | `01H` | `81H` | `A1H` | `C1H` |
| Read address | `03H` | `83H` | — | `C3H` |
| Write data | `04H` | `84H` | — | `C4H` |
| Write address | `15H` | `95H` | — | `D5H` |
| Write electromechanical sync | `16H` | `96H` | — | `D6H` |

## Build

```bash
./mvnw -pl protocols/cjt188,plc4j/drivers/cjt188 -am \
  -P with-java test -DskipCheckstyle -Drat.skip=true
```
