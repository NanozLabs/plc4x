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
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Apache PLC4X is the Industrial IoT adapter - a universal library for accessing industrial programmable logic controllers (PLCs) using a variety of protocols with a uniform API. The project supports multiple languages: **Java** (production-ready), **Go** (production-ready), **C** (WIP), **Python** (WIP), and **C#/.Net** (abandoned).

- **Current Version**: 0.14.0-SNAPSHOT
- **License**: Apache License 2.0
- **Minimum Java**: 21 (for build and runtime)

## Build Commands

```bash
# Build Java modules only
./mvnw -P with-java install

# Build Go modules
./mvnw -P with-go install

# Build with code generation update (required after modifying .mspec files)
./mvnw -P update-generated-code install

# Build specific driver with dependencies
./mvnw clean install -pl plc4j/drivers/modbus -am

# Run tests for a specific driver
./mvnw test -pl plc4j/drivers/s7 -am

# Full build (all languages and checks)
./mvnw -P with-c,with-dotnet,with-go,with-java,with-python,enable-all-checks install

# Docker build (requires 12GB+ RAM)
docker compose up
```

**Important**: When building submodules directly, set JVM args in your IDE:
```
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
```
总是使用中文跟我对话
这个项目我只关注Java部分，go和c部分我不关心

## Architecture

### Code Generation Pipeline

```
protocols/*.mspec → code-generation (ANTLR4 + FreeMarker) → language-specific drivers
```

The **mspec** DSL defines binary protocol specifications, which are then transformed into serialization/deserialization code for each supported language.

### Module Structure

```
plc4x/
├── code-generation/          # Code generation framework (ANTLR4 parser + FreeMarker templates)
├── protocols/                # Protocol specifications (.mspec format)
│   ├── modbus/              # Modbus TCP/RTU/ASCII
│   ├── s7/                  # Siemens S7
│   ├── opcua/               # OPC UA
│   └── ...                  # 22+ protocol definitions
├── plc4j/                    # Java implementation
│   ├── api/                 # Public API (PlcConnection, PlcDriver, PlcTag)
│   ├── spi/                 # Service Provider Interface (GeneratedDriverBase)
│   ├── drivers/             # Protocol drivers (21+)
│   ├── transports/          # Transport layers (TCP/UDP/Serial/CAN/PCAP)
│   └── tools/               # Utilities (connection-cache, opm, scraper)
├── plc4go/                   # Go implementation
├── plc4c/                    # C implementation
├── plc4py/                   # Python implementation
└── plc4net/                  # .NET implementation
```

### Key Architectural Concepts

1. **Driver Registration**: Uses Java ServiceLoader (`META-INF/services/org.apache.plc4x.java.api.PlcDriver`)
2. **Transport Decoupling**: Drivers are independent from transports; same driver can work over TCP, Serial, or CAN
3. **Connection URI Format**: `protocol[:transport]://host:port?options`
   - `modbus-tcp://192.168.1.1:502`
   - `s7://192.168.1.1:102`
   - `modbus-rtu:serial:///dev/ttyUSB0`

### PLC4J Driver Pattern

```java
public class ModbusTcpDriver extends GeneratedDriverBase<ModbusTcpADU> {
    @Override
    public String getProtocolCode() { return "modbus-tcp"; }

    @Override
    protected Optional<String> getDefaultTransportCode() {
        return Optional.of("tcp");
    }
}
```

## Available Drivers (plc4j)

| Protocol | Driver | Description |
|----------|--------|-------------|
| Modbus | modbus | Modbus TCP/RTU/ASCII |
| S7 | s7 | Siemens S7-300/400/1200/1500 |
| OPC UA | opcua | OPC Unified Architecture |
| EtherNet/IP | eip | Allen-Bradley/Rockwell |
| BACnet | bacnet | Building Automation |
| KNX | knxnetip | Building Automation |
| PROFINET | profinet, profinet-ng | Industrial Ethernet |
| CANopen | canopen | CAN-based automation |
| ADS | ads | Beckhoff TwinCAT |
| IEC 60870-5-104 | iec-60870 | SCADA |
| Firmata | firmata | Arduino |
| C-Bus | c-bus | Clipsal |
| ctrlX | ctrlx | Bosch Rexroth |
| Mock/Simulated | mock, simulated | Testing |

## Transports

TCP, UDP, Serial (RS-232/RS-485), CAN, SocketCAN, Raw Socket, PCAP Replay, Virtual CAN, SSL/TLS

## Tech Stack

| Component | Technology | Version |
|-----------|------------|---------|
| Build | Maven | 3.6+ |
| Java | OpenJDK | 21+ |
| Code Gen | ANTLR | 4.13.2 |
| Templates | FreeMarker | 2.3.34 |
| Network | Netty | 4.1.123.Final |
| JSON | Jackson | 2.20.0 |
| Logging | SLF4J + Logback | 2.0.17 + 1.5.20 |
| Testing | JUnit 5 | 6.0.0 |
| Async | Vavr (Either) | 0.10.7 |

## Code Style

- **Encoding**: UTF-8
- **Indent**: 4 spaces (Java), 2 spaces (XML/YAML), tabs (Go)
- **Package**: `org.apache.plc4x.*`
- **Star imports**: 5+ for regular, 3+ for static (IntelliJ default)
- **Import order**: others → javax.* → java.* → static imports
- **Code coverage**: Java build requires 90% coverage

## Key Files

- **Protocol specs**: `protocols/<name>/src/main/resources/protocols/<name>.mspec`
- **Java API**: `plc4j/api/src/main/java/org/apache/plc4x/java/api/`
- **Java SPI**: `plc4j/spi/src/main/java/org/apache/plc4x/java/spi/`
- **Drivers**: `plc4j/drivers/<name>/src/main/java/`
- **Version properties**: Root `pom.xml` `<properties>` section
