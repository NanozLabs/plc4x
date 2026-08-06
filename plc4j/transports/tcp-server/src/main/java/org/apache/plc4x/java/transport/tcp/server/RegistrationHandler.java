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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import org.apache.plc4x.java.transport.tcp.server.registration.RegistrationPacketParser;
import org.apache.plc4x.java.transport.tcp.server.registration.RegistrationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Netty channel handler that manages the device registration handshake.
 *
 * <p>This handler sits at the front of the pipeline when a new connection
 * is accepted. It enforces a registration timeout and uses the configured
 * {@link RegistrationPacketParser} to extract the device identifier from
 * the initial data sent by the DTU device.</p>
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>Connection established → Start registration timeout</li>
 *   <li>Data received → Accumulate and parse registration packet</li>
 *   <li>Registration success → Register device, remove handler, fire remaining data</li>
 *   <li>Timeout or invalid data → Close connection</li>
 * </ol>
 *
 * <h3>Buffer Management:</h3>
 * <p>This handler accumulates incoming data in a composite buffer until
 * the registration packet is complete. Any remaining data after the
 * registration packet is forwarded to subsequent handlers.</p>
 *
 * @since 0.14.0
 */
public class RegistrationHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationHandler.class);

    private final long timeoutMillis;
    private final RegistrationPacketParser parser;
    private final DeviceChannelRegistry registry;
    private final BiConsumer<ChannelHandlerContext, String> onRegistrationComplete;

    private ScheduledFuture<?> timeoutFuture;
    private ByteBuf accumulatedBuffer;
    private boolean registrationComplete = false;

    /**
     * Creates a new registration handler.
     *
     * @param timeoutMillis the registration timeout in milliseconds
     * @param parser the parser for extracting device ID
     * @param registry the device channel registry
     * @param onRegistrationComplete callback when registration succeeds (context, deviceId)
     */
    public RegistrationHandler(long timeoutMillis,
                               RegistrationPacketParser parser,
                               DeviceChannelRegistry registry,
                               BiConsumer<ChannelHandlerContext, String> onRegistrationComplete) {
        this.timeoutMillis = timeoutMillis;
        this.parser = parser;
        this.registry = registry;
        this.onRegistrationComplete = onRegistrationComplete;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        logger.debug("New connection from {}, starting registration with {} timeout",
            remote, timeoutMillis + "ms");

        // Initialize accumulation buffer
        accumulatedBuffer = ctx.alloc().buffer();

        // Schedule registration timeout
        timeoutFuture = ctx.executor().schedule(() -> {
            if (!registrationComplete && ctx.channel().isActive()) {
                logger.warn("Registration timeout for connection from {}", remote);
                ctx.close();
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);

        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (registrationComplete) {
            // Already registered, forward to next handler
            ctx.fireChannelRead(msg);
            return;
        }

        if (!(msg instanceof ByteBuf)) {
            // Forward non-ByteBuf messages to allow protocol handlers to receive them
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf data = (ByteBuf) msg;
        try {
            accumulatedBuffer.writeBytes(data);
        } finally {
            data.release();
        }

        // Try to parse registration packet
        attemptRegistration(ctx);
    }

    /**
     * Attempts to parse the accumulated buffer for a registration packet.
     */
    private void attemptRegistration(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive()) {
            return;
        }

        RegistrationResult result = parser.parse(accumulatedBuffer);

        switch (result.getStatus()) {
            case COMPLETE:
                handleRegistrationSuccess(ctx, result);
                break;

            case NEED_MORE_DATA:
                // Wait for more data
                logger.trace("Registration parser needs more data, accumulated {} bytes",
                    accumulatedBuffer.readableBytes());
                break;

            case INVALID:
                handleRegistrationFailure(ctx, result);
                break;
        }
    }

    /**
     * Handles successful registration.
     */
    private void handleRegistrationSuccess(ChannelHandlerContext ctx, RegistrationResult result) {
        // Cancel timeout
        cancelTimeout();

        String deviceId = result.getDeviceId();
        Channel channel = ctx.channel();
        InetSocketAddress remote = (InetSocketAddress) channel.remoteAddress();

        logger.info("Device '{}' registration successful from {}", deviceId, remote);

        // Register in device registry
        DeviceChannelRegistry.RegistrationResult regResult = registry.register(deviceId, channel);

        switch (regResult) {
            case SUCCESS:
            case REPLACED:
                completeRegistration(ctx, deviceId);
                break;

            case REJECTED:
                logger.warn("Device '{}' registration rejected (duplicate)", deviceId);
                ctx.close();
                break;

            case LIMIT_EXCEEDED:
                logger.warn("Device '{}' registration rejected (limit exceeded)", deviceId);
                ctx.close();
                break;
        }
    }

    /**
     * Completes the registration process.
     */
    private void completeRegistration(ChannelHandlerContext ctx, String deviceId) {
        registrationComplete = true;

        // Store device ID as channel attribute for later reference
        ctx.channel().attr(TcpServerChannelFactory.DEVICE_ID_KEY).set(deviceId);

        // Notify completion callback before removing handler
        if (onRegistrationComplete != null) {
            onRegistrationComplete.accept(ctx, deviceId);
        }

        // Note: parser already advanced readerIndex by consumedBytes via readBytes(),
        // so no skipBytes() needed here.

        // Forward any remaining data in the buffer before removing handler
        ByteBuf remaining = null;
        if (accumulatedBuffer.readableBytes() > 0) {
            remaining = accumulatedBuffer.retainedSlice();
        }

        // Release the accumulated buffer
        accumulatedBuffer.release();
        accumulatedBuffer = null;

        // Remove this handler from the pipeline
        ctx.pipeline().remove(this);

        // Fire remaining data after handler removal
        if (remaining != null) {
            ctx.fireChannelRead(remaining);
        }
    }

    /**
     * Handles registration failure.
     */
    private void handleRegistrationFailure(ChannelHandlerContext ctx, RegistrationResult result) {
        cancelTimeout();
        releaseBuffer();

        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        logger.warn("Registration failed for connection from {}: {}",
            remote, result.getFailureReason().orElse("Invalid registration packet"));

        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelTimeout();
        releaseBuffer();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        logger.error("Exception during registration from {}", remote, cause);
        cancelTimeout();
        releaseBuffer();
        ctx.close();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cancelTimeout();
        releaseBuffer();
        super.handlerRemoved(ctx);
    }

    /**
     * Cancels the registration timeout.
     */
    private void cancelTimeout() {
        if (timeoutFuture != null && !timeoutFuture.isDone()) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    /**
     * Releases the accumulated buffer if present.
     */
    private void releaseBuffer() {
        if (accumulatedBuffer != null && accumulatedBuffer.refCnt() > 0) {
            accumulatedBuffer.release();
            accumulatedBuffer = null;
        }
    }
}
