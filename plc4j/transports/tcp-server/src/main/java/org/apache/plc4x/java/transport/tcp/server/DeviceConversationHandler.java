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
import java.util.Queue;
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

    /** The single in-flight request waiting for a response (RTU is half-duplex) */
    private volatile PendingRequest<T> inFlightRequest;

    /** Queue of requests waiting to be sent */
    private final Queue<QueuedRequest<T>> requestQueue = new ConcurrentLinkedQueue<>();

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
        var ex = new TimeoutException("Channel closed before response received");
        synchronized (this) {
            if (inFlightRequest != null) {
                if (inFlightRequest.timeoutFuture != null) inFlightRequest.timeoutFuture.cancel(false);
                inFlightRequest.future.completeExceptionally(ex);
                inFlightRequest = null;
            }
            QueuedRequest<T> queued;
            while ((queued = requestQueue.poll()) != null) {
                queued.future.completeExceptionally(ex);
            }
        }
        super.handlerRemoved(ctx);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        logger.debug("[DeviceConversation] channelRead: msgType={}, queueSize={}", msg.getClass().getSimpleName(), requestQueue.size());

        PendingRequest<T> pending;
        synchronized (this) {
            pending = inFlightRequest;
        }

        if (pending != null) {
            try {
                T castMsg = (T) msg;
                if (pending.matcher.test(castMsg)) {
                    if (pending.timeoutFuture != null) pending.timeoutFuture.cancel(false);
                    pending.future.complete(castMsg);
                    logger.debug("[DeviceConversation] Response matched");
                    synchronized (this) {
                        inFlightRequest = null;
                    }
                    trySendNext();
                    return;
                }
            } catch (ClassCastException e) {
                logger.warn("[DeviceConversation] Type mismatch: {}", msg.getClass().getSimpleName());
            }
        }

        logger.debug("[DeviceConversation] No match, forwarding to next handler");
        super.channelRead(ctx, msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Exception in device conversation handler", cause);
        synchronized (this) {
            if (inFlightRequest != null) {
                if (inFlightRequest.timeoutFuture != null) inFlightRequest.timeoutFuture.cancel(false);
                inFlightRequest.future.completeExceptionally(cause);
                inFlightRequest = null;
            }
        }
        trySendNext();
        super.exceptionCaught(ctx, cause);
    }

    /**
     * Queues a request and sends it when no other request is in-flight.
     * RTU is half-duplex: only one request at a time.
     */
    public CompletableFuture<T> sendRequest(T request, Predicate<T> responseMatcher, Duration timeout) {
        if (channel == null || !channel.isActive()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Channel is not active"));
        }

        var future = new CompletableFuture<T>();
        requestQueue.offer(new QueuedRequest<>(request, responseMatcher, timeout, future));
        logger.debug("[DeviceConversation] Queued request, queueSize={}", requestQueue.size());
        trySendNext();
        return future;
    }

    /** Sends the next queued request if no request is currently in-flight. */
    private void trySendNext() {
        QueuedRequest<T> next;
        synchronized (this) {
            if (inFlightRequest != null) return;
            next = requestQueue.poll();
            if (next == null) return;
            inFlightRequest = new PendingRequest<>(next.future, next.matcher);
        }

        logger.debug("[DeviceConversation] Sending next request, remaining={}", requestQueue.size());

        // Schedule timeout - on timeout, clear in-flight and try next
        inFlightRequest.timeoutFuture = timeoutExecutor.schedule(() -> {
            synchronized (this) {
                if (inFlightRequest != null && inFlightRequest.future == next.future) {
                    logger.warn("[DeviceConversation] Request timed out");
                    inFlightRequest.future.completeExceptionally(
                        new TimeoutException("Request timed out after " + next.timeout.toMillis() + "ms"));
                    inFlightRequest = null;
                }
            }
            trySendNext();
        }, next.timeout.toMillis(), TimeUnit.MILLISECONDS);

        // Write to channel
        channel.writeAndFlush(next.request).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                synchronized (this) {
                    if (inFlightRequest != null && inFlightRequest.future == next.future) {
                        if (inFlightRequest.timeoutFuture != null) inFlightRequest.timeoutFuture.cancel(false);
                        inFlightRequest.future.completeExceptionally(writeFuture.cause());
                        inFlightRequest = null;
                    }
                }
                trySendNext();
            }
        });
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
        volatile ScheduledFuture<?> timeoutFuture;

        PendingRequest(CompletableFuture<T> future, Predicate<T> matcher) {
            this.future = future;
            this.matcher = matcher;
        }
    }

    /** A request waiting in the queue to be sent. */
    private static class QueuedRequest<T> {
        final T request;
        final Predicate<T> matcher;
        final Duration timeout;
        final CompletableFuture<T> future;

        QueuedRequest(T request, Predicate<T> matcher, Duration timeout, CompletableFuture<T> future) {
            this.request = request;
            this.matcher = matcher;
            this.timeout = timeout;
            this.future = future;
        }
    }
}
