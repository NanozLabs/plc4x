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
package org.apache.plc4x.java.transport.tcp.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.spi.configuration.HasConfiguration;
import org.apache.plc4x.java.spi.connection.ChannelFactory;
import org.apache.plc4x.java.transport.tcp.server.registration.RegistrationPacketParser;
import org.apache.plc4x.java.transport.tcp.server.registration.RegistrationParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Netty-based channel factory for TCP Server transport.
 *
 * <p>This factory creates a server that listens for incoming TCP connections
 * from DTU devices. Unlike the standard TCP transport which initiates outbound
 * connections, this factory operates in passive/server mode.</p>
 *
 * <h3>Architecture:</h3>
 * <pre>
 *                        +------------------+
 *                        | ServerBootstrap  |
 *                        +--------+---------+
 *                                 |
 *              +------------------+------------------+
 *              |                  |                  |
 *        +-----------+      +-----------+      +-----------+
 *        | Device 1  |      | Device 2  |      | Device N  |
 *        +-----------+      +-----------+      +-----------+
 *              |                  |                  |
 *    [RegistrationHandler] [RegistrationHandler] [RegistrationHandler]
 *              |                  |                  |
 *    [Protocol Pipeline]  [Protocol Pipeline]  [Protocol Pipeline]
 * </pre>
 *
 * <h3>Connection Flow:</h3>
 * <ol>
 *   <li>Server binds to configured address and port</li>
 *   <li>DTU device connects and sends registration packet</li>
 *   <li>RegistrationHandler parses device ID and registers in DeviceChannelRegistry</li>
 *   <li>Protocol handlers are installed for Modbus communication</li>
 *   <li>Transparent channel is established for normal protocol operations</li>
 * </ol>
 *
 * @since 0.14.0
 */
public class TcpServerChannelFactory implements ChannelFactory, HasConfiguration<TcpServerTransportConfiguration> {

    private static final AtomicInteger counter = new AtomicInteger(0);

    private static final Logger logger = LoggerFactory.getLogger(TcpServerChannelFactory.class);

    /** Attribute key for storing device ID on the channel */
    public static final AttributeKey<String> DEVICE_ID_KEY = AttributeKey.valueOf("plc4x.deviceId");

    /** Attribute key for storing registration time */
    public static final AttributeKey<Long> REGISTRATION_TIME_KEY = AttributeKey.valueOf("plc4x.registrationTime");

    /** Attribute key for storing device registry on the server channel */
    public static final AttributeKey<DeviceChannelRegistry> DEVICE_REGISTRY_KEY = AttributeKey.valueOf("plc4x.deviceRegistry");

    private final SocketAddress bindAddress;
    private TcpServerTransportConfiguration configuration;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private DeviceChannelRegistry deviceRegistry;
    private RegistrationPacketParser registrationParser;

    private ChannelHandler protocolHandlerProvider;
    private Consumer<Channel> onDeviceConnected;
    private Consumer<Channel> onDeviceDisconnected;

    /**
     * Creates a new TCP server channel factory.
     *
     * @param bindAddress the address to bind the server socket to
     */
    public TcpServerChannelFactory(SocketAddress bindAddress) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "Bind address must not be null");
    }

    @Override
    public void setConfiguration(TcpServerTransportConfiguration configuration) {
        this.configuration = configuration;
        initializeFromConfiguration();
    }

    /**
     * Initializes components from configuration.
     */
    private void initializeFromConfiguration() {
        if (configuration == null) {
            return;
        }

        // Create device registry with configured policy
        DeviceChannelRegistry.DuplicatePolicy policy = parseDuplicatePolicy(
            configuration.getDuplicateHandling());
        this.deviceRegistry = new DeviceChannelRegistry(policy, configuration.getMaxConnections());

        // Create registration parser from configuration
        this.registrationParser = createRegistrationParser();
    }

    /**
     * Creates a registration parser based on configuration.
     */
    private RegistrationPacketParser createRegistrationParser() {
        Map<String, String> parserConfig = new HashMap<>();
        parserConfig.put(RegistrationParserFactory.KEY_TYPE, configuration.getRegistrationType());
        parserConfig.put(RegistrationParserFactory.KEY_LENGTH,
            String.valueOf(configuration.getRegistrationLength()));
        parserConfig.put(RegistrationParserFactory.KEY_CHARSET, configuration.getRegistrationCharset());
        parserConfig.put(RegistrationParserFactory.KEY_MAX_LENGTH,
            String.valueOf(configuration.getRegistrationMaxLength()));

        if (configuration.getRegistrationPattern() != null) {
            parserConfig.put(RegistrationParserFactory.KEY_PATTERN, configuration.getRegistrationPattern());
        }

        parserConfig.put(RegistrationParserFactory.KEY_PREFIX_BYTES,
            String.valueOf(configuration.getRegistrationPrefixBytes()));
        parserConfig.put(RegistrationParserFactory.KEY_BYTE_ORDER, configuration.getRegistrationByteOrder());
        parserConfig.put(RegistrationParserFactory.KEY_DELIMITER, configuration.getRegistrationDelimiter());

        return RegistrationParserFactory.create(parserConfig);
    }

    /**
     * Parses the duplicate handling policy from string.
     */
    private DeviceChannelRegistry.DuplicatePolicy parseDuplicatePolicy(String policy) {
        return switch (policy.toLowerCase()) {
            case "replace" -> DeviceChannelRegistry.DuplicatePolicy.REPLACE;
            case "reject" -> DeviceChannelRegistry.DuplicatePolicy.REJECT;
            case "allow" -> DeviceChannelRegistry.DuplicatePolicy.ALLOW;
            default -> DeviceChannelRegistry.DuplicatePolicy.REPLACE;
        };
    }

    @Override
    public Channel createChannel(ChannelHandler channelHandler) throws PlcConnectionException {
        Objects.requireNonNull(channelHandler, "Channel handler must not be null");

        logger.info("第{}次执行该代码", counter.incrementAndGet());

        // Store the protocol handler for use in child channel initialization
        this.protocolHandlerProvider = channelHandler;

        // Ensure configuration is initialized
        if (configuration == null) {
            configuration = new TcpServerTransportConfiguration();
            initializeFromConfiguration();
        }

        try {
            // Create event loop groups
            bossGroup = new NioEventLoopGroup(1,
                new DefaultThreadFactory("plc4x-tcp-server-boss"));
            workerGroup = new NioEventLoopGroup(configuration.getWorkerThreads(),
                new DefaultThreadFactory("plc4x-tcp-server-worker"));

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, configuration.getBacklog())
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, configuration.isKeepAlive())
                .childOption(ChannelOption.TCP_NODELAY, configuration.isTcpNoDelay())
                .childOption(ChannelOption.SO_RCVBUF, configuration.getReceiveBufferSize())
                .childOption(ChannelOption.SO_SNDBUF, configuration.getSendBufferSize())
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        initializeChildChannel(ch);
                    }
                });

            // Bind and start accepting connections
            // ChannelFuture bindFuture = bootstrap.bind(502).sync();
            ChannelFuture bindFuture = bootstrap.bind(bindAddress).sync();
            serverChannel = bindFuture.channel();
            serverChannel.attr(DEVICE_REGISTRY_KEY).set(deviceRegistry);

            logger.info("TCP Server started on {}", bindAddress);

            return serverChannel;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
            throw new PlcConnectionException("Interrupted while starting server", e);
        } catch (Exception e) {
            logger.error(e.getMessage());
            shutdown();
            throw new PlcConnectionException("Failed to start TCP server on " + bindAddress, e);
        }
    }

    /**
     * Initializes a child channel (incoming connection).
     */
    private void initializeChildChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        logger.debug("Initializing child channel from {}", ch.remoteAddress());

        // 字节统计 handler（必须在最前面，统计原始字节）
        var bytesTracker = new BytesTrackingHandler();
        pipeline.addLast("bytesTracker", bytesTracker);
        ch.attr(BytesTrackingHandler.BYTES_TRACKER_KEY).set(bytesTracker);

        // Add idle state handler if configured
        if (configuration.getIdleTimeout() > 0) {
            pipeline.addLast("idleHandler",
                new IdleStateHandler(configuration.getIdleTimeout(), 0, 0, TimeUnit.MILLISECONDS));
            pipeline.addLast("idleEventHandler", new IdleStateEventHandler());
        }

        // Add registration handler
        pipeline.addLast("registration", new RegistrationHandler(
            configuration.getRegistrationTimeout(),
            registrationParser,
            deviceRegistry,
            this::onDeviceRegistered
        ));
    }

    /**
     * Called when a device completes registration.
     */
    private void onDeviceRegistered(ChannelHandlerContext ctx, String deviceId) {
        Channel channel = ctx.channel();
        channel.attr(REGISTRATION_TIME_KEY).set(System.currentTimeMillis());

        logger.info("Device '{}' registered, installing protocol handlers. Pipeline before: {}", deviceId, ctx.pipeline().names());

        // Install the protocol handler first (ChannelInitializer adds codec + Plc4xNettyWrapper)
        if (protocolHandlerProvider != null) {
            ctx.pipeline().addLast(protocolHandlerProvider);
        }

        logger.info("Device '{}' pipeline after protocol install: {}", deviceId, ctx.pipeline().names());

        // Insert DeviceConversationHandler AFTER the codec so it receives decoded messages,
        // not raw ByteBuf. Find the codec by type and insert after it.
        String codecHandlerName = findCodecHandlerName(ctx.pipeline());
        if (codecHandlerName != null) {
            ctx.pipeline().addAfter(codecHandlerName, "deviceConversation", new DeviceConversationHandler<>());
            logger.info("Device '{}' DeviceConversationHandler inserted after codec '{}'. Final pipeline: {}", deviceId, codecHandlerName, ctx.pipeline().names());
        } else {
            // Fallback: add at end (may not work correctly but avoids NPE)
            logger.warn("Could not find codec handler in pipeline for device '{}', adding DeviceConversationHandler at end. Pipeline: {}", deviceId, ctx.pipeline().names());
            ctx.pipeline().addLast("deviceConversation", new DeviceConversationHandler<>());
        }

        // Notify callback
        if (onDeviceConnected != null) {
            onDeviceConnected.accept(channel);
        }
    }

    /**
     * Finds the name of the byte-to-message codec handler in the pipeline.
     */
    private String findCodecHandlerName(ChannelPipeline pipeline) {
        for (Map.Entry<String, ChannelHandler> entry : pipeline) {
            if (entry.getValue().getClass().getSimpleName().contains("GeneratedProtocolMessageCodec")
                || entry.getValue().getClass().getSimpleName().contains("GeneratedDriverByteToMessageCodec")) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public boolean isPassive() {
        return true;
    }

    @Override
    public void closeEventLoopForChannel(Channel channel) {
        // For server mode, when closing the server channel, perform full shutdown
        if (channel == serverChannel) {
            shutdown();
        }
        // For child channels (device connections), the registry handles cleanup
    }

    /**
     * Shuts down the server and all connections.
     */
    public void shutdown() {
        logger.info("Shutting down TCP server");

        if (deviceRegistry != null) {
            deviceRegistry.shutdown();
        }

        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }

        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }

        logger.info("TCP server shutdown complete");
    }

    /**
     * Gets the device channel registry.
     *
     * @return the device registry
     */
    public DeviceChannelRegistry getDeviceRegistry() {
        return deviceRegistry;
    }

    /**
     * Gets the channel for a specific device.
     *
     * @param deviceId the device identifier
     * @return the channel, or null if not connected
     */
    public Channel getDeviceChannel(String deviceId) {
        return deviceRegistry != null ? deviceRegistry.getChannel(deviceId) : null;
    }

    /**
     * Sets a callback for when devices connect.
     *
     * @param callback the callback
     */
    public void setOnDeviceConnected(Consumer<Channel> callback) {
        this.onDeviceConnected = callback;
    }

    /**
     * Sets a callback for when devices disconnect.
     *
     * @param callback the callback
     */
    public void setOnDeviceDisconnected(Consumer<Channel> callback) {
        this.onDeviceDisconnected = callback;
        if (deviceRegistry != null) {
            deviceRegistry.addDisconnectionListener(info -> {
                if (callback != null) {
                    callback.accept(info.getChannel());
                }
            });
        }
    }

    /**
     * Handler for idle state events.
     */
    private static class IdleStateEventHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
            throws Exception {
            if (evt instanceof io.netty.handler.timeout.IdleStateEvent) {
                logger.info("Closing idle connection from {}", ctx.channel().remoteAddress());
                ctx.close();
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }
    }
}
