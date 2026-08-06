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
# TCP Server Transport（服务端传输层）用户指南

## 概述

TCP Server 传输层是 Apache PLC4X SPI3 架构下的服务端传输，支持 DTU（数据传输单元）
设备**主动发起 TCP 连接**到服务端。适用于以下场景：

- DTU 设备位于 NAT 后面，无法从外部主动连接
- 需要集中管理多个远程设备
- 设备通过 4G/5G 网络连接，IP 地址不固定

本模块基于官方 SPI3 transport API（`plc4j/transports/api`），**无 Netty 依赖**，
采用虚拟线程 + `RingBuffer` 的阻塞 IO 范式（与官方 `TcpTransportInstance` 一致）。

## 核心特性

- **被动监听模式**：服务端监听指定地址和端口，等待 DTU 设备连接
- **设备注册机制**：连接后设备发送注册包，包含设备唯一标识
- **灵活的注册包格式**：支持固定长度、正则表达式、前缀长度、分隔符等多种格式
- **多设备管理**：支持同时管理多个设备连接
- **设备路由**：读写请求可指定目标设备，支持 Tag 级别或全局配置
- **透明通道**：注册完成后，后续通信与标准协议完全一致

## 连接 URL 格式

```
protocol:tcp-server://[bind-address]:port[?options]
```

### 示例

```
// 监听所有接口的 502 端口
modbus-tcp-server:tcp-server://0.0.0.0:502

// 监听特定 IP
modbus-tcp-server:tcp-server://192.168.1.100:8502

// 使用固定长度注册包（16 字节）
modbus-tcp-server:tcp-server://0.0.0.0:502?registration-type=fixed&registration-length=16

// 使用正则表达式注册包
modbus-tcp-server:tcp-server://0.0.0.0:502?registration-type=regex&registration-pattern=REG:(.+)\\r\\n

// 使用前缀长度注册包（2 字节长度头）
modbus-tcp-server:tcp-server://0.0.0.0:502?registration-type=prefix&registration-prefix-bytes=2

// 使用分隔符注册包（CRLF 结尾）
modbus-tcp-server:tcp-server://0.0.0.0:502?registration-type=delimiter&registration-delimiter=CRLF
```

## 配置参数

### 网络配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `bind-address` | 0.0.0.0 | 服务端绑定地址 |
| `port` | 0（取 URL 端口） | 服务端监听端口；URL 中指定 `:0` 时使用系统分配端口 |
| `backlog` | 128 | TCP 连接队列长度 |
| `max-connections` | 1000 | 最大并发连接数（0 为不限制） |

### TCP 选项

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `keep-alive` | true | 启用 TCP Keep-Alive |
| `tcp-no-delay` | true | 禁用 Nagle 算法 |
| `receive-buffer-size` | 65536 | TCP 接收缓冲区大小（字节） |
| `send-buffer-size` | 65536 | TCP 发送缓冲区大小（字节） |
| `idle-timeout` | 300000 | 空闲超时（毫秒），0 禁用 |

### 注册配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `registration-timeout` | 5000 | 注册超时（毫秒） |
| `registration-type` | fixed | 解析器类型：fixed/regex/prefix/delimiter |
| `registration-length` | 16 | 固定长度解析器的字节数 |
| `registration-pattern` | - | 正则表达式模式 |
| `registration-prefix-bytes` | 2 | 长度前缀字节数（1/2/4） |
| `registration-byte-order` | BIG_ENDIAN | 字节序：BIG_ENDIAN/LITTLE_ENDIAN |
| `registration-delimiter` | CRLF | 分隔符：CRLF/LF/NULL/十六进制 |
| `registration-max-length` | 256 | 变长解析器最大长度 |
| `registration-charset` | UTF-8 | 字符编码 |
| `duplicate-handling` | replace | 重复设备处理：replace/reject/allow |

### 路由配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `target-device-id` | - | 默认目标设备 ID（当 Tag 中未指定时使用） |

## 设备路由机制

在服务端模式下，多个 DTU 设备会连接到服务器。读写请求需要指定目标设备 ID，以确定
将请求发送到哪个设备。因为 SPI3 的 driver ↔ transport-instance 是 1:1，本传输层通过
`RoutingTransportInstance` 对 driver 呈现单一连接，内部将 IO 路由到当前目标设备。

### 设备 ID 指定方式

1. **Tag 地址中指定（推荐）**：在 Tag 地址中使用 `device-id` 配置参数
   （driver 层在协议逻辑中解析该参数，并调用 `setTargetDevice()` 设置路由目标）：

```
holding-register:1:INT{device-id:"DEVICE001"}
coil:100:BOOL{device-id:"DEVICE002"}
holding-register:1:INT{device-id:"DEVICE001",unit-id:2}
```

2. **连接 URL 中配置默认设备**：设置 `target-device-id` 作为默认值
   （当 Tag 中未指定时使用）。

### 路由优先级

1. Tag 级别（driver 调 `setTargetDevice`）> URL 配置（`target-device-id`）
2. 若两者都未指定且当前恰好只有一个设备在线，路由到该唯一设备
3. 若目标设备不在线，读写操作抛出 `TransportException`，提示指定 `device-id`

## 注册包格式说明

### 1. 固定长度 (fixed)

设备发送固定字节数的注册包，内容为设备 ID 字符串。

```
+--------------------------------+
|   Device ID (16 bytes UTF-8)   |
+--------------------------------+

示例：
"DEVICE0001      " (16 字节，右侧填充空格)
```

配置：`registration-type=fixed&registration-length=16&registration-charset=UTF-8`

### 2. 正则表达式 (regex)

设备发送文本格式注册包，使用正则表达式提取设备 ID。支持命名分组 `deviceId`
（优先）或第一个捕获组。

```
+-------------------+
|  REG:DEVICE001\r\n |
+-------------------+

模式：REG:(?<deviceId>.+)\r\n
```

配置：`registration-type=regex&registration-pattern=REG:(?<deviceId>.+)\\r\\n&registration-max-length=64`

### 3. 前缀长度 (prefix)

设备发送带长度头的注册包。

```
+----------------+---------------------+
| Length (2 bytes) | Device ID (N bytes) |
+----------------+---------------------+

示例（大端）：
00 0A | D E V I C E 0 0 0 1
```

配置：`registration-type=prefix&registration-prefix-bytes=2&registration-byte-order=BIG_ENDIAN`

### 4. 分隔符 (delimiter)

设备发送以特定分隔符结尾的注册包。

```
+------------------------+--------+
|    Device ID           | \r\n   |
+------------------------+--------+

示例："DEVICE001\r\n"
```

配置：`registration-type=delimiter&registration-delimiter=CRLF&registration-max-length=128`

## 架构

```
                     ┌─────────────────────────────────────────┐
                     │        TcpServerTransport (SPI3)        │
                     │   (protocol:tcp-server://bind:port)     │
                     └───────────────────┬─────────────────────┘
                                         │
                     ┌───────────────────┴─────────────────────┐
                     │     ServerSocketChannel (accept loop)   │
                     │        (single virtual thread)          │
                     └───────────────────┬─────────────────────┘
                                         │
          ┌──────────────┬───────────────┼───────────────┬───────────────┐
          │              │               │               │               │
     ┌────┴────┐    ┌────┴────┐     ┌────┴────┐     ┌────┴────┐     ┌────┴────┐
     │ DTU 1   │    │ DTU 2   │     │ DTU 3   │     │ DTU 4   │     │ DTU N   │
     └────┬────┘    └────┬────┘     └────┬────┘     └────┬────┘     └────┬────┘
          │              │               │               │               │
     ┌────┴────┐    ┌────┴────┐     ┌────┴────┐     ┌────┴────┐     ┌────┴────┐
     │ TcpServerTransportInstance (每设备一个，虚拟线程读循环 + RingBuffer)
     │ + RegistrationHandler（注册握手）
     └──────────────────────────────────────────────────────────────────┘
                     │
     ┌───────────────┴──────────────────────────────┐
     │ TcpServerChannelRegistry (deviceId → 连接)    │
     └───────────────┬──────────────────────────────┘
                     │
     ┌───────────────┴──────────────────────────────┐
     │ RoutingTransportInstance (driver 拿到的实例)   │
     │  · setTargetDevice(deviceId)                  │
     │  · write/read/peek 路由到目标设备              │
     └──────────────────────────────────────────────┘
```

连接流程：
1. DTU 主动连接服务器（TCP Client）
2. DTU 发送注册包（包含设备标识，如 IMEI）
3. `RegistrationHandler` 解析注册包，`TcpServerChannelRegistry` 注册设备
4. 建立透明通道，后续为标准协议通信

## 模块结构

```
plc4j/transports/tcp-server/
├── pom.xml
├── README.md
└── src/main/java/org/apache/plc4x/java/transport/tcpserver/
    ├── TcpServerTransport.java            # 实现 Transport：解析 URL、起 accept 循环
    ├── TcpServerTransportConfiguration.java
    ├── TcpServerTransportInstance.java    # 每设备的连接实例（虚拟线程 + RingBuffer）
    ├── RoutingTransportInstance.java      # driver 拿到的虚拟路由实例
    ├── TcpServerChannelRegistry.java      # 多设备注册表（duplicate-handling 等）
    ├── RegistrationHandler.java           # 注册包接收/解析/识别
    ├── DeviceConnectionHandler.java       # 设备连接生命周期（accept→注册→路由）
    └── registration/
        ├── RegistrationPacketParser.java  # 解析策略接口
        ├── RegistrationParserFactory.java # 工厂
        ├── RegistrationResult.java        # 解析结果
        └── impl/                          # fixed / regex / prefix / delimiter
```

## 构建

```bash
# 构建 TCP Server Transport 模块
./mvnw -P with-java -pl plc4j/transports/tcp-server -am install
```

## 自检

本模块包含两个可手动运行的自检（无测试框架）：

```bash
java -cp <classes>:<test-classes>:<deps> \
  org.apache.plc4x.java.transport.tcpserver.registration.RegistrationParserSelfCheck

java -cp <classes>:<test-classes>:<deps> \
  org.apache.plc4x.java.transport.tcpserver.TcpServerTransportSelfCheck
```

第二个自检会起真实 ServerSocket、模拟 DTU 注册并验证路由读写。

## 注意事项

1. **防火墙配置**：确保服务器端口对外开放
2. **连接数限制**：根据服务器资源合理设置 `max-connections`
3. **超时配置**：根据网络质量调整 `registration-timeout` 和 `idle-timeout`
4. **安全考虑**：生产环境建议在前端部署防火墙或 VPN
5. **日志监控**：建议开启 DEBUG 日志监控设备连接状态
