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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Predicate;

/**
 * Manages request-response conversations for a specific device channel.
 *
 * <p>This handler intercepts inbound messages and correlates them with pending
 * requests based on a matcher predicate. It enables the server-side protocol
 * logic to send requests to specific device channels and receive responses.</p>
 *
 * <h3>Usage Pattern:</h3>
 * <ol>
 *   <li>Get handler from channel: {@code DeviceConversationHandler.getFromChannel(channel)}</li>
 *   <li>Send request and wait: {@code handler.sendRequest(request, matcher, timeout)}</li>
 *   <li>Response is automatically matched and returned via CompletableFuture</li>
 * </ol>
 *
 * @param <T> the message type handled by this conversation
 * @since 0.14.0
 */
public class DeviceConversationHandler<T> extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DeviceConversationHandler.class);

    /** Attribute key for storing the handler on the channel */
    public static final AttributeKey<DeviceConversationHandler<?>> HANDLER_KEY =
        AttributeKey.valueOf("plc4x.deviceConversationHandler");

    /** Pending requests waiting for responses, keyed by a unique request ID */
    private final Map<Integer, PendingRequest<T>> pendingRequests = new ConcurrentHashMap<>();

    private final ScheduledExecutorService timeoutExecutor;
    private Channel channel;

    public DeviceConversationHandler() {
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "device-conversation-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.channel = ctx.channel();
        // Store reference on channel for easy access
        ctx.channel().attr(HANDLER_KEY).set(this);
        super.handlerAdded(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        // Cancel all pending requests
        for (PendingRequest<T> pending : pendingRequests.values()) {
            pending.future.completeExceptionally(
                new TimeoutException("Channel closed before response received"));
            if (pending.timeoutFuture != null) {
                pending.timeoutFuture.cancel(false);
            }
        }
        pendingRequests.clear();
        super.handlerRemoved(ctx);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Try to match against pending requests
        boolean matched = false;

        for (Map.Entry<Integer, PendingRequest<T>> entry : pendingRequests.entrySet()) {
            PendingRequest<T> pending = entry.getValue();
            try {
                if (pending.matcher.test((T) msg)) {
                    pendingRequests.remove(entry.getKey());
                    if (pending.timeoutFuture != null) {
                        pending.timeoutFuture.cancel(false);
                    }
                    pending.future.complete((T) msg);
                    matched = true;
                    logger.trace("Response matched for request ID {}", entry.getKey());
                    break;
                }
            } catch (ClassCastException e) {
                // Message type doesn't match, continue
            }
        }

        if (!matched) {
            // Forward unmatched messages to next handler
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Exception in device conversation handler", cause);
        // Fail all pending requests
        for (PendingRequest<T> pending : pendingRequests.values()) {
            pending.future.completeExceptionally(cause);
            if (pending.timeoutFuture != null) {
                pending.timeoutFuture.cancel(false);
            }
        }
        pendingRequests.clear();
        super.exceptionCaught(ctx, cause);
    }

    /**
     * Sends a request to the device and waits for a matching response.
     *
     * @param request the request message to send
     * @param responseMatcher predicate to match the expected response
     * @param timeout maximum time to wait for response
     * @return a CompletableFuture that completes with the response
     */
    public CompletableFuture<T> sendRequest(T request, Predicate<T> responseMatcher, Duration timeout) {
        if (channel == null || !channel.isActive()) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Channel is not active"));
            return future;
        }

        int requestId = System.identityHashCode(request);
        CompletableFuture<T> future = new CompletableFuture<>();

        PendingRequest<T> pending = new PendingRequest<>(future, responseMatcher);
        pendingRequests.put(requestId, pending);

        // Schedule timeout
        pending.timeoutFuture = timeoutExecutor.schedule(() -> {
            PendingRequest<T> removed = pendingRequests.remove(requestId);
            if (removed != null) {
                removed.future.completeExceptionally(
                    new TimeoutException("Request timed out after " + timeout.toMillis() + "ms"));
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        // Send the request
        channel.writeAndFlush(request).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                PendingRequest<T> removed = pendingRequests.remove(requestId);
                if (removed != null) {
                    removed.timeoutFuture.cancel(false);
                    removed.future.completeExceptionally(writeFuture.cause());
                }
            }
        });

        return future;
    }

    /**
     * Gets the device ID associated with this channel.
     *
     * @return the device ID, or null if not set
     */
    public String getDeviceId() {
        if (channel != null) {
            return channel.attr(TcpServerChannelFactory.DEVICE_ID_KEY).get();
        }
        return null;
    }

    /**
     * Gets the DeviceConversationHandler from a channel.
     *
     * @param channel the channel
     * @param <T> the message type
     * @return the handler, or null if not found
     */
    @SuppressWarnings("unchecked")
    public static <T> DeviceConversationHandler<T> getFromChannel(Channel channel) {
        return (DeviceConversationHandler<T>) channel.attr(HANDLER_KEY).get();
    }

    /**
     * Shuts down the timeout executor.
     */
    public void shutdown() {
        timeoutExecutor.shutdownNow();
    }

    /**
     * Holds information about a pending request.
     */
    private static class PendingRequest<T> {
        final CompletableFuture<T> future;
        final Predicate<T> matcher;
        ScheduledFuture<?> timeoutFuture;

        PendingRequest(CompletableFuture<T> future, Predicate<T> matcher) {
            this.future = future;
            this.matcher = matcher;
        }
    }
}
