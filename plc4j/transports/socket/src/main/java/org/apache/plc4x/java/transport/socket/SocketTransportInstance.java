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
package org.apache.plc4x.java.transport.socket;

import org.apache.plc4x.java.spi.transports.api.AsyncTransportInstance;
import org.apache.plc4x.java.spi.transports.api.BaseTransportInstance;
import org.apache.plc4x.java.spi.transports.api.RingBuffer;
import org.apache.plc4x.java.spi.transports.api.exceptions.TransportException;
import org.apache.plc4x.java.spi.utils.StaticHelper;
import org.apache.plc4x.java.transport.socket.config.SocketTransportConfiguration;
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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Socket transport instance for one externally-injected, already-established
 * {@link SocketChannel} (one device in DTU/gateway server mode).
 *
 * <p>This mirrors the official SPI3 {@code TcpTransportInstance} IO paradigm: one virtual
 * thread per connection doing blocking {@link SocketChannel} reads into a {@link RingBuffer},
 * with a registered data listener invoked on each read. Unlike the client-side TCP transport
 * it does not connect anywhere — the channel was accepted and handed in by the user (who also
 * performed any registration handshake and consumed the handshake bytes before injecting).</p>
 */
public class SocketTransportInstance extends BaseTransportInstance<SocketTransportConfiguration>
        implements AsyncTransportInstance<SocketTransportConfiguration> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketTransportInstance.class);
    private static final int DEFAULT_BUFFER_SIZE = 81920;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final SocketChannel socketChannel;
    private final RingBuffer ringBuffer;
    private final ByteBuffer readBuffer;  // Reused per-connection direct buffer for channel reads (confined to the read thread)
    private final Lock readLock = new ReentrantLock();
    private final Lock writeLock = new ReentrantLock();
    private final AtomicBoolean open = new AtomicBoolean(true);

    // Async support
    private volatile Runnable dataListener;
    private volatile Consumer<Throwable> disconnectListener;
    private final Thread readThread;

    public SocketTransportInstance(SocketChannel socketChannel,
                                   SocketTransportConfiguration configuration,
                                   AuditLog auditLog) throws TransportException {
        super(configuration, auditLog);
        LOGGER.debug("SocketTransportInstance");
        this.socketChannel = socketChannel;
        this.ringBuffer = new RingBuffer(configuration.receiveBufferSize > 0 ? configuration.receiveBufferSize : DEFAULT_BUFFER_SIZE);
        this.readBuffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE);

        try {
            socketChannel.configureBlocking(true);
            socketChannel.socket().setTcpNoDelay(configuration.tcpNoDelay);
            socketChannel.socket().setKeepAlive(configuration.keepAlive);

            if (configuration.sendBufferSize > 0) {
                socketChannel.socket().setSendBufferSize(configuration.sendBufferSize);
            }
            if (configuration.receiveBufferSize > 0) {
                socketChannel.socket().setReceiveBufferSize(configuration.receiveBufferSize);
            }
        } catch (IOException e) {
            try {
                socketChannel.close();
            } catch (IOException closeException) {
                e.addSuppressed(closeException);
            }
            getAuditLog().write(AuditLogEventType.ERROR, "Error in constructor: " + e.getMessage());
            throw new TransportException("Failed to configure injected socket channel", e);
        }

        getAuditLog().write(AuditLogEventType.CONNECT, String.format(
            "Injected socket connection from: %s", getRemoteAddress()));

        // Start the per-connection read loop on a virtual thread (Java 21+) LAST, so a throw
        // from the logging/audit above cannot leak an already-running thread.
        this.readThread = Thread.ofVirtual()
            .name("Socket-Read-" + getRemoteAddress())
            .start(this::runReadLoop);
    }

    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) socketChannel.socket().getRemoteSocketAddress();
    }

    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) socketChannel.socket().getLocalSocketAddress();
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
            LOGGER.debug("Socket connection closed");
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
     */
    private void runReadLoop() {
        LOGGER.debug("Read loop started");
        try {
            while (open.get()) {
                int free = ringBuffer.remainingForWriting();
                if (free == 0) {
                    // Backpressure: the ring buffer is full and the consumer has not drained yet.
                    // Park briefly and re-check; never disconnect (only the codec knows frame
                    // boundaries, and cross-thread consumers like COTP can legitimately lag).
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

                readBuffer.flip();
                readLock.lock();
                try {
                    ringBuffer.write(readBuffer);
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
