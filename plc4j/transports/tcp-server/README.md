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
# Modbus TCP Server Mode - 用户指南

## 概述

Modbus TCP Server 模式是 Apache PLC4X 的扩展功能，支持 DTU（数据传输单元）设备主动发起 TCP 连接到服务端。这种模式适用于以下场景：

- DTU 设备位于 NAT 后面，无法从外部主动连接
- 需要集中管理多个远程 Modbus 设备
- 设备通过 4G/5G 网络连接，IP 地址不固定

## 核心特性

- **被动监听模式**：服务端监听指定端口，等待 DTU 设备连接
- **设备注册机制**：连接后设备发送注册包，包含设备唯一标识
- **灵活的注册包格式**：支持固定长度、正则表达式、前缀长度、分隔符等多种格式
- **多设备管理**：支持同时管理多个设备连接
- **设备路由**：读写请求可指定目标设备，支持 Tag 级别或全局配置
- **透明通道**：注册完成后，后续通信与标准 Modbus TCP 协议完全一致

## 连接 URL 格式

```
modbus-tcp-server:tcp-server://[bind-address]:port[?options]
```

### 示例

```java
// 监听所有接口的 502 端口
String url = "modbus-tcp-server:tcp-server://0.0.0.0:502";

// 监听特定 IP
String url = "modbus-tcp-server:tcp-server://192.168.1.100:8502";

// 使用固定长度注册包（16 字节）
String url = "modbus-tcp-server:tcp-server://0.0.0.0:502?registration-type=fixed&registration-length=16";

// 使用正则表达式注册包
String url = "modbus-tcp-server:tcp-server://0.0.0.0:502?registration-type=regex&registration-pattern=REG:(.+)\\r\\n";

// 使用前缀长度注册包（2 字节长度头）
String url = "modbus-tcp-server:tcp-server://0.0.0.0:502?registration-type=prefix&registration-prefix-bytes=2";

// 使用分隔符注册包（CRLF 结尾）
String url = "modbus-tcp-server:tcp-server://0.0.0.0:502?registration-type=delimiter&registration-delimiter=CRLF";
```

## 配置参数

### 网络配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `bind-address` | 0.0.0.0 | 服务端绑定地址 |
| `worker-threads` | CPU 核心数 | Worker 线程数 |
| `backlog` | 128 | TCP 连接队列长度 |
| `max-connections` | 1000 | 最大并发连接数 |

### TCP 选项

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `keep-alive` | true | 启用 TCP Keep-Alive |
| `tcp-no-delay` | true | 禁用 Nagle 算法 |
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

### Modbus 协议配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `default-unit-identifier` | 1 | 默认从站 ID |
| `request-timeout` | 5000 | 请求超时（毫秒） |
| `target-device-id` | - | 默认目标设备 ID（当 Tag 中未指定时使用） |

## 设备路由机制

在服务端模式下，多个 DTU 设备会连接到服务器。读写请求需要指定目标设备 ID，以确定将请求发送到哪个设备。

### 设备 ID 指定方式

支持两种方式指定目标设备：

#### 1. Tag 地址中指定（推荐）

在 Tag 地址中使用 `device-id` 配置参数：

```java
// 读取 DEVICE001 的保持寄存器
String tagAddress = "holding-register:1:INT{device-id:\"DEVICE001\"}";

// 读取 DEVICE002 的线圈
String tagAddress = "coil:100:BOOL{device-id:\"DEVICE002\"}";

// 同时指定设备 ID 和单元 ID
String tagAddress = "holding-register:1:INT{device-id:\"DEVICE001\",unit-id:2}";
```

#### 2. 连接 URL 中配置默认设备

在连接 URL 中设置 `target-device-id` 作为默认值（当 Tag 中未指定时使用）：

```java
String url = "modbus-tcp-server:tcp-server://0.0.0.0:502" +
    "?registration-type=fixed" +
    "&registration-length=16" +
    "&target-device-id=DEVICE001";
```

### 优先级规则

1. **Tag 级别** > **URL 配置**
2. 如果两者都未指定，将抛出异常

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

配置：
```
registration-type=fixed
registration-length=16
registration-charset=UTF-8
```

### 2. 正则表达式 (regex)

设备发送文本格式注册包，使用正则表达式提取设备 ID。

```
+-------------------+
|  REG:DEVICE001\r\n |
+-------------------+

模式：REG:(?<deviceId>.+)\r\n
```

配置：
```
registration-type=regex
registration-pattern=REG:(?<deviceId>.+)\\r\\n
registration-max-length=64
```

### 3. 前缀长度 (prefix)

设备发送带长度头的注册包。

```
+----------------+---------------------+
| Length (2 bytes) | Device ID (N bytes) |
+----------------+---------------------+

示例（大端）：
00 0A | D E V I C E 0 0 0 1
```

配置：
```
registration-type=prefix
registration-prefix-bytes=2
registration-byte-order=BIG_ENDIAN
```

### 4. 分隔符 (delimiter)

设备发送以特定分隔符结尾的注册包。

```
+------------------------+--------+
|    Device ID           | \r\n   |
+------------------------+--------+

示例：
"DEVICE001\r\n"
```

配置：
```
registration-type=delimiter
registration-delimiter=CRLF
registration-max-length=128
```

## 使用示例

### 基本使用（单设备）

```java
import org.apache.plc4x.java.PlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;

public class ModbusTcpServerExample {
    public static void main(String[] args) throws Exception {
        // 创建连接（启动服务端），设置默认目标设备
        String connectionUrl = "modbus-tcp-server:tcp-server://0.0.0.0:502" +
            "?registration-type=fixed" +
            "&registration-length=16" +
            "&target-device-id=DEVICE001";

        try (PlcConnection connection = PlcDriverManager.getConnection(connectionUrl)) {
            // 等待设备连接...
            Thread.sleep(10000);

            // 读取保持寄存器（使用 URL 中配置的默认设备）
            PlcReadRequest readRequest = connection.readRequestBuilder()
                .addTagAddress("holding", "holding-register:1:INT")
                .build();

            PlcReadResponse response = readRequest.execute().get();
            System.out.println("Value: " + response.getInteger("holding"));
        }
    }
}
```

### 多设备操作（推荐方式）

```java
import org.apache.plc4x.java.PlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;

public class MultiDeviceExample {
    public static void main(String[] args) throws Exception {
        // 创建连接（不设置默认设备，在 Tag 中指定）
        String connectionUrl = "modbus-tcp-server:tcp-server://0.0.0.0:502" +
            "?registration-type=fixed" +
            "&registration-length=16";

        try (PlcConnection connection = PlcDriverManager.getConnection(connectionUrl)) {
            // 等待设备连接...
            Thread.sleep(10000);

            // 从 DEVICE001 读取数据
            PlcReadRequest request1 = connection.readRequestBuilder()
                .addTagAddress("dev1_temp", "holding-register:1:INT{device-id:\"DEVICE001\"}")
                .build();
            PlcReadResponse response1 = request1.execute().get();
            System.out.println("DEVICE001 Temperature: " + response1.getInteger("dev1_temp"));

            // 从 DEVICE002 读取数据
            PlcReadRequest request2 = connection.readRequestBuilder()
                .addTagAddress("dev2_temp", "holding-register:1:INT{device-id:\"DEVICE002\"}")
                .build();
            PlcReadResponse response2 = request2.execute().get();
            System.out.println("DEVICE002 Temperature: " + response2.getInteger("dev2_temp"));

            // 向 DEVICE003 写入数据
            connection.writeRequestBuilder()
                .addTagAddress("setpoint", "holding-register:100:INT{device-id:\"DEVICE003\"}", 500)
                .build()
                .execute()
                .get();
            System.out.println("Setpoint written to DEVICE003");
        }
    }
}
```

### 设备管理与监控

使用 `ModbusTcpServerConnection` 工具类可以方便地管理设备连接和订阅事件：

```java
import org.apache.plc4x.java.PlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.modbus.tcpserver.ModbusTcpServerConnection;

public class DeviceManagementExample {
    public static void main(String[] args) throws Exception {
        String url = "modbus-tcp-server:tcp-server://0.0.0.0:502" +
            "?registration-type=fixed&registration-length=16";

        try (PlcConnection connection = PlcDriverManager.getConnection(url)) {

            // 包装为 ModbusTcpServerConnection
            ModbusTcpServerConnection serverConn = ModbusTcpServerConnection.of(connection);

            // ========================================
            // 订阅设备连接事件
            // ========================================
            serverConn.onDeviceConnected(event -> {
                System.out.println("【设备上线】");
                System.out.println("  设备ID: " + event.getDeviceId());
                System.out.println("  远程IP: " + event.getRemoteIp());
                System.out.println("  远程端口: " + event.getRemotePort());
                System.out.println("  连接时间: " + event.getTimestamp());
            });

            // ========================================
            // 订阅设备断开事件
            // ========================================
            serverConn.onDeviceDisconnected(event -> {
                System.out.println("【设备离线】");
                System.out.println("  设备ID: " + event.getDeviceId());
            });

            System.out.println("服务已启动，等待设备连接...");

            // 保持运行，模拟业务逻辑
            while (true) {
                Thread.sleep(5000);

                // 获取当前连接设备数
                int count = serverConn.getConnectedDeviceCount();
                System.out.println("当前在线设备: " + count + " 台");

                // 遍历所有设备
                for (String deviceId : serverConn.getConnectedDevices()) {
                    ModbusTcpServerConnection.DeviceInfo info = serverConn.getDeviceInfo(deviceId);
                    System.out.println("  - " + deviceId + " (" + info.getRemoteIp() + ")");
                }
            }
        }
    }
}
```

### API 速查表

#### ModbusTcpServerConnection

| 方法 | 说明 |
|------|------|
| `of(PlcConnection)` | 从 PlcConnection 创建包装实例 |
| `onDeviceConnected(Consumer<DeviceEvent>)` | 订阅设备连接事件 |
| `onDeviceDisconnected(Consumer<DeviceEvent>)` | 订阅设备断开事件 |
| `getConnectedDevices()` | 获取所有已连接设备ID |
| `getConnectedDeviceCount()` | 获取已连接设备数量 |
| `isDeviceConnected(String)` | 检查设备是否在线 |
| `getDeviceInfo(String)` | 获取设备详细信息 |
| `disconnectDevice(String)` | 主动断开指定设备 |

#### DeviceEvent

| 属性 | 说明 |
|------|------|
| `getDeviceId()` | 设备唯一标识 |
| `getRemoteIp()` | 远程IP地址 |
| `getRemotePort()` | 远程端口 |
| `getRemoteAddress()` | InetSocketAddress 对象 |
| `getTimestamp()` | 事件时间戳 |
| `getType()` | 事件类型 (CONNECTED/DISCONNECTED) |

#### DeviceInfo

| 属性 | 说明 |
|------|------|
| `getDeviceId()` | 设备唯一标识 |
| `getRemoteIp()` | 远程IP地址 |
| `getRemotePort()` | 远程端口 |
| `getConnectedAt()` | 连接建立时间 |
| `isActive()` | 连接是否活跃 |

### 完整业务示例

```java
import org.apache.plc4x.java.PlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.modbus.tcpserver.ModbusTcpServerConnection;

public class CompleteExample {
    public static void main(String[] args) throws Exception {
        String url = "modbus-tcp-server:tcp-server://0.0.0.0:502" +
            "?registration-type=fixed&registration-length=16";

        try (PlcConnection connection = PlcDriverManager.getConnection(url)) {
            ModbusTcpServerConnection serverConn = ModbusTcpServerConnection.of(connection);

            // 设备上线时自动读取数据
            serverConn.onDeviceConnected(event -> {
                System.out.println("新设备上线: " + event.getDeviceId());

                // 异步读取该设备的数据
                try {
                    var response = connection.readRequestBuilder()
                        .addTagAddress("temp", "holding-register:1:INT{device-id:\"" + event.getDeviceId() + "\"}")
                        .build()
                        .execute()
                        .get();
                    System.out.println("初始温度: " + response.getInteger("temp"));
                } catch (Exception e) {
                    System.err.println("读取失败: " + e.getMessage());
                }
            });

            // 设备离线时记录日志
            serverConn.onDeviceDisconnected(event -> {
                System.out.println("设备离线: " + event.getDeviceId() +
                    ", 在线时长: " + java.time.Duration.between(event.getTimestamp(), java.time.Instant.now()));
            });

            // 定时轮询所有设备
            while (true) {
                Thread.sleep(10000);

                for (String deviceId : serverConn.getConnectedDevices()) {
                    try {
                        var response = connection.readRequestBuilder()
                            .addTagAddress("temp", "holding-register:1:INT{device-id:\"" + deviceId + "\"}")
                            .build()
                            .execute()
                            .get();
                        System.out.println(deviceId + " 温度: " + response.getInteger("temp"));
                    } catch (Exception e) {
                        System.err.println(deviceId + " 读取失败");
                    }
                }
            }
        }
    }
}
```

## DTU 设备配置指南

### 通用配置步骤

1. **配置服务器地址**：设置 DTU 连接的服务器 IP 和端口
2. **配置注册包**：按照服务端配置的格式设置注册包内容
3. **配置心跳**：建议配置心跳包保持连接活跃
4. **配置透传模式**：确保 DTU 工作在透传模式

### 常见 DTU 配置示例

#### USR-TCP232 系列
```
工作模式：TCP Client
服务器地址：your-server-ip
服务器端口：502
注册包：DEVICE001（16字节，UTF-8）
心跳包：间隔 60 秒
```

#### 有人 4G DTU
```
工作模式：透传
服务器地址：your-server-ip:502
注册包：固定发送 "IMEI号"
```

## 架构图

```
                     ┌─────────────────────────────────────────┐
                     │         Modbus TCP Server               │
                     │    (modbus-tcp-server:tcp-server://)    │
                     └────────────────────┬────────────────────┘
                                          │
                     ┌────────────────────┴────────────────────┐
                     │           TCP Server Socket             │
                     │         (ServerBootstrap/Netty)         │
                     └────────────────────┬────────────────────┘
                                          │
          ┌───────────────┬───────────────┼───────────────┬───────────────┐
          │               │               │               │               │
     ┌────┴────┐     ┌────┴────┐     ┌────┴────┐     ┌────┴────┐     ┌────┴────┐
     │  DTU 1  │     │  DTU 2  │     │  DTU 3  │     │  DTU 4  │     │  DTU N  │
     │ (IMEI1) │     │ (IMEI2) │     │ (IMEI3) │     │ (IMEI4) │     │ (IMEIN) │
     └────┬────┘     └────┬────┘     └────┬────┘     └────┬────┘     └────┬────┘
          │               │               │               │               │
     ┌────┴────┐     ┌────┴────┐     ┌────┴────┐     ┌────┴────┐     ┌────┴────┐
     │ Modbus  │     │ Modbus  │     │ Modbus  │     │ Modbus  │     │ Modbus  │
     │  Slave  │     │  Slave  │     │  Slave  │     │  Slave  │     │  Slave  │
     │  (PLC)  │     │  (PLC)  │     │  (PLC)  │     │  (PLC)  │     │  (PLC)  │
     └─────────┘     └─────────┘     └─────────┘     └─────────┘     └─────────┘

连接流程：
1. DTU 主动连接服务器（TCP Client）
2. DTU 发送注册包（包含设备标识，如 IMEI）
3. 服务器解析注册包，注册设备
4. 建立透明通道，后续为标准 Modbus TCP 协议
```

## 注意事项

1. **防火墙配置**：确保服务器端口对外开放
2. **连接数限制**：根据服务器资源合理设置 `max-connections`
3. **超时配置**：根据网络质量调整 `registration-timeout` 和 `idle-timeout`
4. **安全考虑**：生产环境建议在前端部署防火墙或 VPN
5. **日志监控**：建议开启 DEBUG 日志监控设备连接状态

## 故障排查

### 设备无法连接

1. 检查服务器防火墙配置
2. 确认 DTU 网络连通性（ping 测试）
3. 检查端口是否被占用
4. 查看服务端日志中的连接错误

### 注册失败

1. 检查注册包格式是否与配置匹配
2. 使用 Wireshark 抓包分析实际发送的注册包
3. 检查字符编码设置是否正确
4. 调整 `registration-timeout` 值

### 连接中断

1. 检查 DTU 心跳配置
2. 调整 `idle-timeout` 参数
3. 检查网络稳定性
4. 查看是否有重复设备 ID 导致被踢出

## 构建

```bash
# 构建 TCP Server Transport 模块
mvn clean install -pl plc4j/transports/tcp-server -am

# 构建 Modbus 驱动（包含 Server 模式）
mvn clean install -pl plc4j/drivers/modbus -am
```

## 版本信息

- **首次发布版本**: 0.14.0
- **需要 Java 版本**: 21+
- **依赖 Netty 版本**: 4.1.x
