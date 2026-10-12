/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.observe;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import sjws_nettask.ServerConfig.AccessLogFormat;

/**
 * Access log written by one background thread. Request threads format a line and hand the bytes to
 * a lock-free queue; they never write the stream and never wait on it. A full queue or a failed
 * stream drops the line and counts it.
 */
final class AccessLog implements Runnable, AutoCloseable {

    static final int CAPACITY = 8192;
    private static final long FLUSH_NANOS = 20_000_000L;
    private static final int FLUSH_BYTES = 32 * 1024;

    private final LogQueue queue;
    private final OutputStream out;
    private final boolean closeOut;
    private final AccessLogFormat format;
    private final LongAdder dropped = new LongAdder();
    private final Thread thread;
    private final Clock clock = new Clock();
    private volatile boolean stopped;
    private volatile boolean failed;

    static AccessLog start(String target, AccessLogFormat format) throws IOException {
        if ("-".equals(target)) {
            return new AccessLog(unclosable(System.out), false, format, CAPACITY);
        }
        return new AccessLog(new FileOutputStream(target, true), true, format, CAPACITY);
    }

    /** @param capacity power of two; tests use a tiny queue to force drops */
    AccessLog(OutputStream out, boolean closeOut, AccessLogFormat format, int capacity) {
        this.queue = new LogQueue(capacity);
        this.out = out;
        this.closeOut = closeOut;
        this.format = format;
        this.thread = Thread.ofPlatform().daemon().name("access-log").unstarted(this);
        this.thread.start();
    }

    boolean headers() {
        return this.format != AccessLogFormat.COMMON;
    }

    long dropped() {
        return this.dropped.sum();
    }

    /**
     * Formats and enqueues one line. Returns immediately. Null strings are logged as {@code -}.
     * The caller still records metrics when this drops the line.
     */
    void offer(String remote, String method, String target, String protocol, int status, long bytes, long durationNanos,
            String referer, String userAgent) {
        if (this.stopped || this.failed || this.queue.isFull()) {
            this.dropped.increment();
            return;
        }
        byte[] line = AccessLine.format(this.format, System.currentTimeMillis(), this.clock.bytes(), remote, method,
                target, protocol, status, bytes, durationNanos, referer, userAgent);
        if (!this.queue.offer(line)) {
            this.dropped.increment();
        }
    }

    @Override
    public void run() {
        var buffered = new BufferedOutputStream(this.out, FLUSH_BYTES);
        long pending = 0;
        long flushedAt = System.nanoTime();
        try {
            while (true) {
                this.clock.refresh(System.currentTimeMillis());
                byte[] line = this.queue.poll();
                if (line == null) {
                    if (pending > 0 && System.nanoTime() - flushedAt >= FLUSH_NANOS) {
                        buffered.flush();
                        pending = 0;
                        flushedAt = System.nanoTime();
                    }
                    if (this.stopped) {
                        break;
                    }
                    LockSupport.parkNanos(1_000_000L);
                    continue;
                }
                buffered.write(line);
                pending += line.length;
                if (pending >= FLUSH_BYTES || System.nanoTime() - flushedAt >= FLUSH_NANOS) {
                    buffered.flush();
                    pending = 0;
                    flushedAt = System.nanoTime();
                }
            }
            byte[] rest;
            while ((rest = this.queue.poll()) != null) {
                buffered.write(rest);
            }
            buffered.flush();
        } catch (IOException | RuntimeException e) {
            this.failed = true;
            System.err.println("WARN: access log write failed: " + e);
        } finally {
            if (this.closeOut) {
                try {
                    this.out.close();
                } catch (IOException e) {
                    System.err.println("WARN: closing access log: " + e);
                }
            }
        }
    }

    @Override
    public void close() {
        this.stopped = true;
        this.thread.interrupt();
        try {
            this.thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** CLF timestamp shared by every request thread; replaced once a second by the writer. */
    private static final class Clock {

        private volatile byte[] bytes = AccessLine.clfTime(System.currentTimeMillis());
        private long second = Long.MIN_VALUE;

        void refresh(long epochMillis) {
            long sec = Math.floorDiv(epochMillis, 1000L);
            if (sec != this.second) {
                this.bytes = AccessLine.clfTime(epochMillis);
                this.second = sec;
            }
        }

        byte[] bytes() {
            return this.bytes;
        }
    }

    /** Writes through to {@code out} and never closes it (stdout). */
    private static OutputStream unclosable(OutputStream out) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                out.flush();
            }
        };
    }
}
