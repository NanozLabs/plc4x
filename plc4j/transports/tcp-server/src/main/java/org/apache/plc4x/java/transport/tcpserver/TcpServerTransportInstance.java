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
package org.apache.plc4x.java.transport.tcpserver;

import org.apache.plc4x.java.spi.transports.api.AsyncTransportInstance;
import org.apache.plc4x.java.spi.transports.api.BaseTransportInstance;
import org.apache.plc4x.java.spi.transports.api.RingBuffer;
import org.apache.plc4x.java.spi.transports.api.exceptions.TransportException;
import org.apache.plc4x.java.spi.utils.StaticHelper;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Server-side transport instance representing a single accepted DTU connection.
 *
 * <p>This class mirrors the official SPI3 {@code TcpTransportInstance} IO paradigm:
 * one virtual thread per connection doing blocking {@link SocketChannel} reads into a
 * {@link RingBuffer}, with a registered data listener invoked on each read. Unlike the
 * client-side transport, this instance does not connect anywhere — it wraps a
 * {@link SocketChannel} that was accepted by the {@link TcpServerTransport} listener.</p>
 *
 * <p>The channel is configured blocking, so a virtual thread parked in {@code read()}
 * releases its carrier. There is no Netty and no selector.</p>
 *
 * <p>Before the device registers, this instance is in <em>registration mode</em>:
 * incoming bytes are appended to a registration accumulator buffer and handed to the
 * {@link RegistrationHandler} for device-ID extraction. After a successful registration
 * the handler switches the instance to <em>transparent mode</em> where bytes flow
 * straight into the ring buffer.</p>
 */
public class TcpServerTransportInstance extends BaseTransportInstance<TcpServerTransportConfiguration>
        implements AsyncTransportInstance<TcpServerTransportConfiguration> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TcpServerTransportInstance.class);
    private static final int DEFAULT_BUFFER_SIZE = 81920;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final SocketChannel socketChannel;
    private final RingBuffer ringBuffer;
    private final ByteBuffer readBuffer;  // Reused per-connection direct buffer for channel reads (confined to the read thread)
    private final Lock readLock = new ReentrantLock();
    private final Lock writeLock = new ReentrantLock();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();

    // Async support
    private volatile Runnable dataListener;
    private volatile Consumer<Throwable> disconnectListener;
    private final Thread readThread;

    // Registration support
    private final RegistrationHandler registrationHandler;

    /**
     * Creates a server-side transport instance for an accepted socket.
     *
     * @param socketChannel the accepted, blocking-mode socket channel
     * @param configuration the transport configuration
     * @param auditLog the audit log
     * @param registrationHandler the registration handler that consumes bytes until registration completes
     */
    public TcpServerTransportInstance(SocketChannel socketChannel,
                                      TcpServerTransportConfiguration configuration,
                                      AuditLog auditLog,
                                      RegistrationHandler registrationHandler) throws TransportException {
        super(configuration, auditLog);
        LOGGER.debug("TcpServerTransportInstance");
        this.socketChannel = socketChannel;
        this.ringBuffer = new RingBuffer(configuration.getReceiveBufferSize());
        this.readBuffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE);
        this.registrationHandler = registrationHandler;

        try {
            socketChannel.socket().setTcpNoDelay(configuration.isTcpNoDelay());
            socketChannel.socket().setKeepAlive(configuration.isKeepAlive());
            if (configuration.getSendBufferSize() > 0) {
                socketChannel.socket().setSendBufferSize(configuration.getSendBufferSize());
            }
            if (configuration.getReceiveBufferSize() > 0) {
                socketChannel.socket().setReceiveBufferSize(configuration.getReceiveBufferSize());
            }
        } catch (IOException e) {
            try {
                socketChannel.close();
            } catch (IOException closeException) {
                e.addSuppressed(closeException);
            }
            getAuditLog().write(AuditLogEventType.ERROR, "Error in constructor: " + e.getMessage());
            throw new TransportException("Failed to configure server connection", e);
        }

        getAuditLog().write(AuditLogEventType.CONNECT, String.format(
            "Device connection accepted from: %s", getRemoteAddress()));

        // Start the per-connection read loop on a virtual thread (Java 21+).
        this.readThread = Thread.ofVirtual()
            .name("TCP-Server-Read-" + getRemoteAddress())
            .start(this::runReadLoop);
    }

    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) socketChannel.socket().getRemoteSocketAddress();
    }

    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) socketChannel.socket().getLocalSocketAddress();
    }

    /**
     * Returns the number of raw bytes received on this connection (for {@link TcpServerChannelRegistry.DeviceInfo}).
     *
     * @return bytes received
     */
    public long getBytesReceived() {
        return bytesReceived.get();
    }

    /**
     * Returns the number of raw bytes sent on this connection (for {@link TcpServerChannelRegistry.DeviceInfo}).
     *
     * @return bytes sent
     */
    public long getBytesSent() {
        return bytesSent.get();
    }

    @Override
    public boolean isOpen() {
        return open.get() && socketChannel.isConnected();
    }

    @Override
    public int getNumBytesAvailable() throws TransportException {
        readLock.lock();
        try {
            if (!isOpen()) {
                return 0;
            }

            return ringBuffer.availableForReading();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public byte[] peekReadableBytes(int numBytes) throws TransportException {
        if (numBytes <= 0) {
            return EMPTY_BYTES;
        }

        readLock.lock();
        try {
            ensureOpen();

            if (ringBuffer.availableForReading() < numBytes) {
                throw new TransportException(
                    String.format("Requested %d bytes but only %d available", numBytes, ringBuffer.availableForReading())
                );
            }

            // Peek without consuming
            return ringBuffer.peek(numBytes);
        } catch (TransportException e) {
            getAuditLog().write(AuditLogEventType.ERROR, "Error in peekReadableBytes: " + e.getMessage());
            throw e;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public byte[] read(int numBytes) throws TransportException {
        if (numBytes <= 0) {
            return EMPTY_BYTES;
        }

        readLock.lock();
        try {
            ensureOpen();

            if (ringBuffer.availableForReading() < numBytes) {
                throw new TransportException(
                    String.format("Requested %d bytes but only %d available", numBytes, ringBuffer.availableForReading())
                );
            }

            // Read and consume bytes
            byte[] bytes = ringBuffer.read(numBytes);

            // Log the bytes to the audit log
            if (getAuditLog().isEnabled()) {
                getAuditLog().write(AuditLogEventType.INCOMING_BYTES, StaticHelper.ENCODE_HEX(bytes));
            }

            return bytes;
        } catch (TransportException e) {
            getAuditLog().write(AuditLogEventType.ERROR, "Error in read: " + e.getMessage());
            throw e;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void write(byte[] bytes) throws TransportException {
        if (bytes == null || bytes.length == 0) {
            return;
        }

        writeLock.lock();
        try {
            ensureOpen();

            ByteBuffer writeBuffer = ByteBuffer.wrap(bytes);

            // Blocking write parks the virtual thread until the kernel send buffer accepts the
            // bytes — natural backpressure. (write() never returns -1; a broken/closed connection
            // surfaces as IOException/AsynchronousCloseException, both handled below.)
            while (writeBuffer.hasRemaining()) {
                socketChannel.write(writeBuffer);
            }

            bytesSent.addAndGet(bytes.length);
            LOGGER.trace("Wrote {} bytes", bytes.length);

            // Log the bytes to the audit log
            if (getAuditLog().isEnabled()) {
                getAuditLog().write(AuditLogEventType.OUTGOING_BYTES, "Write: " + StaticHelper.ENCODE_HEX(bytes));
            }
        } catch (AsynchronousCloseException e) {
            // A concurrent close() closed the channel while we were parked in write(): normal shutdown.
            if (!open.get()) {
                return;
            }
            getAuditLog().write(AuditLogEventType.ERROR, "Error in write: " + e.getMessage());
            throw new TransportException("Failed to write data", e);
        } catch (IOException e) {
            getAuditLog().write(AuditLogEventType.ERROR, "Error in write: " + e.getMessage());
            throw new TransportException("Failed to write data", e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close() throws TransportException {
        // CAS so concurrent/repeated close() calls run the shutdown exactly once.
        if (!open.compareAndSet(true, false)) {
            return;
        }

        // Intentionally takes NO locks: closing the channel is what unblocks a parked read()/write().
        // Acquiring writeLock first would deadlock against a writer parked in a blocking write().
        try {
            socketChannel.close();
            LOGGER.debug("TCP server connection closed");
            getAuditLog().write(AuditLogEventType.CLOSE, "Closed");
        } catch (IOException e) {
            getAuditLog().write(AuditLogEventType.ERROR, "Error in close: " + e.getMessage());
            throw new TransportException("Failed to close connection", e);
        } finally {
            // Always join the read loop, regardless of whether the channel closed cleanly.
            // Skip the self-join when close() runs on the read thread itself.
            if (readThread != null && Thread.currentThread() != readThread) {
                try {
                    readThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Ensures the connection is still open, throws exception otherwise.
     */
    private void ensureOpen() throws TransportException {
        if (!isOpen()) {
            throw new TransportException("Transport is closed");
        }
    }

    // ========== AsyncTransportInstance Implementation ==========

    @Override
    public void registerDataListener(Runnable listener) {
        this.dataListener = listener;
        LOGGER.debug("Data listener registered");
    }

    @Override
    public void removeDataListener() {
        this.dataListener = null;
        LOGGER.debug("Data listener removed");
    }

    @Override
    public void registerDisconnectListener(Consumer<Throwable> listener) {
        this.disconnectListener = listener;
        LOGGER.debug("Disconnect listener registered");
    }

    @Override
    public void removeDisconnectListener() {
        this.disconnectListener = null;
        LOGGER.debug("Disconnect listener removed");
    }

    /**
     * Notifies the disconnect listener if one is registered.
     *
     * @param cause the exception that caused the disconnect, or null for graceful close
     */
    private void notifyDisconnect(Throwable cause) {
        Consumer<Throwable> listener = disconnectListener;
        if (listener != null) {
            try {
                listener.accept(cause);
            } catch (Exception e) {
                LOGGER.error("Error in disconnect listener", e);
            }
        }
    }

    /**
     * Runs the listener on the read thread, guarding against a misbehaving listener so a thrown
     * exception cannot silently kill the read loop.
     */
    private void safeRun(Runnable listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.run();
        } catch (Throwable t) {
            LOGGER.error("Data listener failed", t);
        }
    }

    /**
     * Per-connection read loop on a virtual thread: blocking read into the ring buffer, then
     * notify the data listener. No selector, no polling.
     *
     * <p>While registration is in progress bytes are routed to the registration handler first;
     * once registration completes the remaining bytes are pushed into the ring buffer and the
     * connection becomes fully transparent.</p>
     */
    private void runReadLoop() {
        LOGGER.debug("Read loop started");
        try {
            while (open.get()) {
                int free = ringBuffer.remainingForWriting();
                if (free == 0) {
                    // Backpressure: the ring buffer is full and the consumer has not drained yet.
                    LockSupport.parkNanos(200_000L);
                    continue;
                }

                // Bound the read to free ring-buffer space so the buffer can never overflow.
                readBuffer.clear();
                readBuffer.limit(Math.min(readBuffer.capacity(), free));

                int bytesRead = socketChannel.read(readBuffer);  // parks vthread; releases carrier (JDK21)
                if (bytesRead == -1) {
                    // Connection closed gracefully by remote
                    LOGGER.info("Connection closed by remote");
                    open.set(false);
                    notifyDisconnect(null);
                    break;
                }
                if (bytesRead == 0) {
                    // A blocking read effectively never returns 0; harmless guard.
                    continue;
                }

                bytesReceived.addAndGet(bytesRead);
                readBuffer.flip();
                byte[] data = new byte[bytesRead];
                readBuffer.get(data);

                // While registration is pending the bytes belong to the registration handshake.
                if (registrationHandler != null && !registrationHandler.isComplete()) {
                    registrationHandler.onData(data);
                    if (registrationHandler.isComplete()) {
                        byte[] remaining = registrationHandler.getRemainingBytes();
                        if (remaining.length > 0) {
                            readLock.lock();
                            try {
                                ringBuffer.write(remaining);
                            } finally {
                                readLock.unlock();
                            }
                            safeRun(dataListener);
                        }
                    }
                    continue;
                }

                // Transparent mode: store bytes and notify the data listener.
                readLock.lock();
                try {
                    ringBuffer.write(data);
                } finally {
                    readLock.unlock();
                }

                // Notify OUTSIDE readLock — the data is already in the ring buffer.
                safeRun(dataListener);
            }
        } catch (IOException e) {
            // If open==false, an intentional close() closed the channel (AsynchronousCloseException) —
            // a normal shutdown, not a disconnect. Only a failure while still open is a real disconnect.
            if (open.get()) {
                getAuditLog().write(AuditLogEventType.ERROR, "Error in runReadLoop: " + e.getMessage());
                LOGGER.error("Error in read loop", e);
                open.set(false);
                notifyDisconnect(e);
            }
        }
        LOGGER.debug("Read loop stopped");
    }
}
