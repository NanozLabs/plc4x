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

Apache PLC4X driver for DL/T 645-2007 China Smart Meter Communication Protocol.

## Features

- Client mode: RS-485 (serial) and TCP transport
- Server mode: Reverse TCP connection (meters connect to PLC4X)
- +33H data encoding/decoding per DL/T 645-2007 specification
- Standard-compliant checksum calculation (full frame sum mod 256)
- 25+ common Data Identifier (DI) semantic decoding (energy, voltage, current, power, etc.)
- BCD data format parsing with automatic decimal scaling

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>org.apache.plc4x</groupId>
    <artifactId>plc4j-driver-dlt645</artifactId>
    <version>0.14.0-SNAPSHOT</version>
</dependency>
```

### Client Mode - Serial (RS-485)

```java
// Connect to meter via RS-485
try (PlcConnection connection = PlcDriverManager.getDefault()
        .getConnectionManager()
        .getConnection("dlt645:serial:///dev/ttyUSB0?meter-address=123456789012")) {

    // Read total positive active energy (kWh)
    PlcReadRequest readRequest = connection.readRequestBuilder()
        .addTagAddress("energy", "00010000")
        .build();

    PlcReadResponse response = readRequest.execute().get();
    double totalEnergy = response.getDouble("energy");
    System.out.println("Total active energy: " + totalEnergy + " kWh");
}
```

### Client Mode - TCP

```java
// Connect to meter via TCP (e.g., DTU transparent transmission)
try (PlcConnection connection = PlcDriverManager.getDefault()
        .getConnectionManager()
        .getConnection("dlt645:tcp://192.168.1.100:8899?meter-address=123456789012")) {

    // Read A-phase voltage
    PlcReadRequest readRequest = connection.readRequestBuilder()
        .addTagAddress("voltageA", "02010100")
        .build();

    PlcReadResponse response = readRequest.execute().get();
    double voltage = response.getDouble("voltageA");
    System.out.println("A-phase voltage: " + voltage + " V");
}
```

### Server Mode - Reverse TCP Connection

```java
// Listen for inbound meter connections with fixed-length registration (16 bytes IMEI)
try (PlcConnection connection = PlcDriverManager.getDefault()
        .getConnectionManager()
        .getConnection("dlt645-server:tcpserver://0.0.0.0:8899"
            + "?registration-type=fixed&registration-length=16"
            + "&password=12345678&operator-code=00000000")) {

    // Wrap as Dlt645ServerConnection
    Dlt645ServerConnection server = Dlt645ServerConnection.of(connection);

    // Event-driven device discovery
    server.onDeviceConnected(event ->
        System.out.println("Device connected: " + event.getDeviceId()));
    server.onDeviceDisconnected(event ->
        System.out.println("Device disconnected: " + event.getDeviceId()));

    // Get a device sub-connection — works like a standard PlcConnection
    Dlt645DeviceConnection device = server.getDeviceConnection("123456789012");

    // Read from device — clean tag format, no device-id in tags
    PlcReadResponse response = device.readRequestBuilder()
        .addTagAddress("power", "02030000")
        .build().execute().get();
    double totalPower = response.getDouble("power");
    System.out.println("Total active power: " + totalPower + " kW");

    // Write to device
    device.writeRequestBuilder()
        .addTagAddress("data", "00010000", PlcValues.of("123456"))
        .build().execute().get();

    // Administrative commands
    device.readRequestBuilder()
        .addTagAddress("addr", "cmd:read-address")
        .build().execute().get();
}
```

#### Multiple Devices

```java
Dlt645ServerConnection server = Dlt645ServerConnection.of(connection);

// Each device gets its own sub-connection
Dlt645DeviceConnection meter1 = server.getDeviceConnection("DTU_001");
Dlt645DeviceConnection meter2 = server.getDeviceConnection("DTU_002");

// Use independently — like two separate client connections
meter1.readRequestBuilder().addTagAddress("energy", "00010000").build().execute();
meter2.readRequestBuilder().addTagAddress("energy", "00010000").build().execute();

// Query connected devices
Set<String> devices = server.getConnectedDevices();
for (String deviceId : devices) {
    Dlt645DeviceConnection dev = server.getDeviceConnection(deviceId);
    // Read all devices...
}
```

#### Server Mode with Regex Registration

```java
// DTU sends "REG:IMEI123456\r\n" as registration packet
try (PlcConnection connection = PlcDriverManager.getDefault()
        .getConnectionManager()
        .getConnection("dlt645-server:tcpserver://0.0.0.0:8899"
            + "?registration-type=regex&registration-pattern=REG:(.+)\\r\\n")) {
    // ...
}
```

## Connection URL Format

### Client Mode

```
dlt645:serial://<serial-port>?<options>
dlt645:tcp://<host>:<port>?<options>
```

### Server Mode

```
dlt645-server:tcpserver://<bind-address>:<port>?<options>

# Examples:
dlt645-server:tcpserver://0.0.0.0:8899?registration-type=fixed&registration-length=16
dlt645-server:tcpserver://0.0.0.0:8899?registration-type=regex&registration-pattern=REG:(.+)\r\n
dlt645-server:tcpserver://0.0.0.0:8899?registration-type=prefix&registration-prefix-bytes=2
dlt645-server:tcpserver://0.0.0.0:8899?registration-type=delimiter&registration-delimiter=CRLF
```

## Configuration Parameters

### Client Mode (`dlt645`)

| Parameter | Default | Description |
|-----------|---------|-------------|
| `request-timeout` | `5000` | Request timeout in milliseconds |
| `meter-address` | `999999999999` | Meter address (12 hex digits, BCD). Use `999999999999` for broadcast |
| `password` | (empty) | Password for write operations (8 hex digits, PA field per Section 8.2.3) |
| `operator-code` | (empty) | Operator code for write operations (8 hex digits, P0 field per Section 8.2.3) |

### Server Mode (`dlt645-server`)

| Parameter | Default | Description |
|-----------|---------|-------------|
| `request-timeout` | `10000` | Request timeout in milliseconds |
| `target-device-id` | (empty) | Target meter address for operations |
| `device-timeout` | `30000` | Timeout to wait for device connection |
| `password` | (empty) | Password for write/admin operations (8 hex digits, PA field) |
| `operator-code` | (empty) | Operator code for write/admin operations (8 hex digits, P0 field) |

### Registration Packet Configuration (Server Mode Transport)

These parameters configure how the server identifies connecting DTU devices.
DTU devices typically send a registration packet (e.g., IMEI) upon connection.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `registration-type` | `fixed` | Parser type: `fixed`, `regex`, `prefix`, `delimiter` |
| `registration-timeout` | `5000` | Timeout (ms) waiting for registration packet |
| `registration-length` | `16` | For `fixed` type: exact byte count to read |
| `registration-pattern` | (empty) | For `regex` type: pattern with capturing group for device ID |
| `registration-prefix-bytes` | `2` | For `prefix` type: length prefix size (1, 2, or 4 bytes) |
| `registration-delimiter` | `CRLF` | For `delimiter` type: `CRLF`, `LF`, `NULL`, or hex (e.g. `0D0A`) |
| `registration-max-length` | `256` | Max length for variable-length registration packets |
| `registration-charset` | `UTF-8` | Character encoding for registration packet |
| `registration-byte-order` | `BIG_ENDIAN` | For `prefix` type: `BIG_ENDIAN` or `LITTLE_ENDIAN` |
| `max-connections` | `1000` | Maximum concurrent device connections |
| `idle-timeout` | `300000` | Idle timeout (ms), 0 to disable |
| `duplicate-handling` | `replace` | Duplicate device: `replace` (close old), `reject`, `allow` |
| `keep-alive` | `true` | Enable TCP keep-alive |

#### Registration Type Details

**`fixed`** - Fixed-length registration frame (e.g., 16-byte IMEI):
```
dlt645-server:tcpserver://0.0.0.0:8899?registration-type=fixed&registration-length=16
```

**`regex`** - Pattern-based extraction (e.g., `REG:IMEI\r\n`):
```
dlt645-server:tcpserver://0.0.0.0:8899?registration-type=regex&registration-pattern=REG:(.+)\r\n
```

**`prefix`** - Variable-length with length prefix:
```
dlt645-server:tcpserver://0.0.0.0:8899?registration-type=prefix&registration-prefix-bytes=2
```

**`delimiter`** - Delimiter-separated (CRLF, LF, NULL):
```
dlt645-server:tcpserver://0.0.0.0:8899?registration-type=delimiter&registration-delimiter=CRLF
```

## Tag Address Format

### Data Identifier Tags

```
<DI_hex_8>[:<dataType>]
```

- `DI_hex_8`: 8-character hex string representing 4-byte Data Identifier (DI3 DI2 DI1 DI0)
- `dataType`: Optional, defaults to `REAL`. Supported: `REAL`, `STRING`, `UINT`, `INT`

### Command Tags

```
cmd:<command-name>
```

Command tags are used for administrative operations beyond standard read/write data.

| Command Tag | Direction | Control Code | Description |
|-------------|-----------|-------------|-------------|
| `cmd:read-address` | Read | `0x13` | Read meter communication address |
| `cmd:write-address` | Write | `0x15` | Write meter communication address |
| `cmd:freeze` | Write | `0x16` | Freeze data command |
| `cmd:change-baud-rate` | Write | `0x17` | Change baud rate |
| `cmd:change-password` | Write | `0x18` | Change password |
| `cmd:clear-max-demand` | Write | `0x19` | Clear max demand |
| `cmd:clear-meter` | Write | `0x1A` | Clear meter |
| `cmd:clear-event` | Write | `0x1B` | Clear event log |

#### Command Tag Examples

```java
// Read meter communication address
PlcReadRequest readAddr = connection.readRequestBuilder()
    .addTagAddress("addr", "cmd:read-address")
    .build();
PlcReadResponse addrResp = readAddr.execute().get();
String meterAddress = addrResp.getString("addr");  // e.g., "123456789012"

// Write meter communication address (uses broadcast address automatically)
PlcWriteRequest writeAddr = connection.writeRequestBuilder()
    .addTagAddress("addr", "cmd:write-address", PlcValues.of("123456789012"))
    .build();
writeAddr.execute().get();

// Change baud rate (value: hex baud rate code, e.g., "04"=2400, "08"=9600)
PlcWriteRequest changeBaud = connection.writeRequestBuilder()
    .addTagAddress("baud", "cmd:change-baud-rate", PlcValues.of("08"))
    .build();
changeBaud.execute().get();

// Change password (value: "old_password:new_password", each 8 hex digits)
PlcWriteRequest changePwd = connection.writeRequestBuilder()
    .addTagAddress("pwd", "cmd:change-password", PlcValues.of("00000000:12345678"))
    .build();
changePwd.execute().get();

// Freeze data (value: hex freeze parameters)
PlcWriteRequest freeze = connection.writeRequestBuilder()
    .addTagAddress("freeze", "cmd:freeze", PlcValues.of("0115"))
    .build();
freeze.execute().get();

// Clear max demand (uses configured password/operator-code)
PlcWriteRequest clear = connection.writeRequestBuilder()
    .addTagAddress("clear", "cmd:clear-max-demand", PlcValues.of(""))
    .build();
clear.execute().get();
```

**Note**: Write-type commands require `password` and `operator-code` connection parameters for authentication.

### Common Data Identifiers

| DI | Description | Unit | Example |
|----|-------------|------|---------|
| `00010000` | Total positive active energy | kWh | `1234.56` |
| `00010100` | Rate 1 positive active energy | kWh | |
| `00010200` | Rate 2 positive active energy | kWh | |
| `00020000` | Total negative active energy | kWh | |
| `02010100` | A-phase voltage | V | `220.1` |
| `02010200` | B-phase voltage | V | |
| `02010300` | C-phase voltage | V | |
| `02020100` | A-phase current | A | `5.123` |
| `02020200` | B-phase current | A | |
| `02020300` | C-phase current | A | |
| `02030000` | Total active power | kW | `1.2345` |
| `02030100` | A-phase active power | kW | |
| `02040000` | Total reactive power | kvar | |
| `02050000` | Total apparent power | kVA | |
| `02060000` | Total power factor | - | `0.987` |
| `02800002` | Grid frequency | Hz | `50.00` |
| `04000101` | Date and time | - | |

## Protocol Details

### Frame Structure

```
┌──────────┬─────────┬──────────┬─────────┬─────────┬────────┬──────────┬────┬─────────┐
│ Preamble │ Start   │ Address  │ Start   │ Control │ Length │ Data     │ CS │ End     │
│ (FE×N)   │ 0x68    │ (6 bytes)│ 0x68    │ (1 byte)│ (1)    │ (+0x33)  │    │ 0x16    │
└──────────┴─────────┴──────────┴─────────┴─────────┴────────┴──────────┴────┴─────────┘
```

### Preamble Bytes (0xFE)

Per DL/T 645-2007, the master may send 1-4 bytes of `0xFE` before each frame as a wake-up
signal for RS-485 half-duplex communication. This driver handles preamble as follows:

- **Receiving**: Fully supported. The frame sync algorithm (`findFrameStart`) automatically
  skips any `0xFE` preamble bytes before locating the `0x68` start marker.
- **Sending**: Not currently sent by the driver. For TCP connections, preamble is unnecessary.
  For RS-485 serial connections, most DTU/serial-to-TCP converters handle preamble at the
  hardware level. If direct RS-485 requires preamble, configure the serial adapter accordingly.

### Address Field Byte Order

The 6-byte address field uses BCD encoding with **LSB-first** wire order per DL/T 645-2007.
The `meter-address` parameter accepts the address as printed on the meter label (MSB first),
and the driver automatically reverses it to wire order.

Example: `meter-address=123456789012` → wire bytes `[0x12, 0x90, 0x78, 0x56, 0x34, 0x12]`

### Control Codes

| Code | Direction | Description |
|------|-----------|-------------|
| `0x11` | Master→Slave | Read data (读数据) |
| `0x91` | Slave→Master | Read data response |
| `0xD1` | Slave→Master | Read data error |
| `0x12` | Master→Slave | Read subsequent data (读后续数据) |
| `0x13` | Master→Slave | Read communication address (读通信地址) |
| `0x14` | Master→Slave | Write data (写数据) |
| `0x94` | Slave→Master | Write data response |
| `0xD4` | Slave→Master | Write data error |
| `0x15` | Master→Slave | Write communication address (写通信地址) |
| `0x16` | Master→Slave | Freeze data (冻结命令) |
| `0x17` | Master→Slave | Change baud rate (更改通信速率) |
| `0x18` | Master→Slave | Change password (修改密码) |
| `0x08` | Master→All | Broadcast time sync (广播校时) |

### Error Response

Error responses have bit D6 set in the control code (e.g., `0xD1` for read error).
The data field contains DI echo (4 bytes) followed by an ERR byte with bit-mapped error codes:

| Bit | Error Description |
|-----|-------------------|
| 0 | Other error (其他错误) |
| 1 | No requested data (无请求数据) |
| 2 | Password error / unauthorized (密码错/未授权) |
| 3 | Baud rate cannot change (通信速率不能更改) |
| 4 | Year time zone overflow (年时区数超) |
| 5 | Day period overflow (日时段数超) |
| 6 | Rate number overflow (费率数超) |
| 7 | Reserved (保留) |

The driver automatically parses and logs these error codes.

### +0x33 Encoding

All data bytes on wire are encoded by adding 0x33 to each byte (mod 256).
The driver handles this transparently - tag values are in plain format.

### Checksum

Per DL/T 645-2007 Section 4.2: CS = sum of all bytes from first 0x68 to before CS, mod 256.

### Multi-Frame Read (Read Subsequent Data)

When a read response has D5=1 in the control code (0xB1 for READ_DATA, 0xB2 for READ_SUBSEQUENT_DATA),
more data frames follow. The driver automatically:

1. Detects the D5 flag in the response control code
2. Sends READ_SUBSEQUENT_DATA (0x12) with the original DI and an incrementing sequence number
3. Accumulates data from all frames
4. Completes the read response when D5=0 (no more data)

This is transparent to the caller — large data reads are automatically handled.

## Module Structure

```
plc4j/drivers/dlt645/
├── src/main/java/org/apache/plc4x/java/dlt645/
│   ├── Dlt645Driver.java                          # Client driver
│   ├── config/Dlt645Configuration.java             # Client config
│   ├── context/Dlt645DriverContext.java             # Client context
│   ├── protocol/Dlt645ProtocolLogic.java            # Client protocol logic
│   ├── readwrite/utils/
│   │   ├── StaticHelper.java                       # Frame helpers (+33H, checksum)
│   │   └── DataIdentifiers.java                    # DI registry & semantic decoder
│   ├── server/
│   │   ├── Dlt645ServerDriver.java                 # Server driver
│   │   ├── config/Dlt645ServerConfiguration.java    # Server config
│   │   ├── context/Dlt645ServerDriverContext.java   # Server context
│   │   └── protocol/Dlt645ServerProtocolLogic.java  # Server protocol logic
│   └── tag/
│       ├── Dlt645Tag.java                          # DI tag definition
│       ├── Dlt645CommandTag.java                   # Command tag (cmd:xxx)
│       └── Dlt645TagHandler.java                   # Tag parser
├── src/main/generated/                             # Auto-generated from mspec
│   └── org/apache/plc4x/java/dlt645/readwrite/
│       ├── Constants.java
│       ├── ControlCode.java
│       └── Dlt645Frame.java
└── pom.xml

protocols/dlt645/
├── src/main/resources/protocols/dlt645/dlt645.mspec  # Protocol definition
└── src/main/java/.../Dlt645Protocol.java             # Code-gen protocol
```

## Build

```bash
# Build protocol module
mvn clean install -pl protocols/dlt645 -am -DskipTests -Drat.skip=true

# Regenerate code from mspec (after mspec changes)
mvn generate-sources -pl plc4j/drivers/dlt645 -am \
    -P update-generated-code,with-java -DskipTests -Drat.skip=true

# Build driver
mvn clean install -pl plc4j/drivers/dlt645 -am -P with-java -DskipTests -Drat.skip=true
```
