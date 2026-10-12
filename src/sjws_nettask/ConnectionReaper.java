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
package sjws_nettask;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * One daemon thread enforcing every HTTP/1.1 connection deadline (replaces per-socket
 * SO_TIMEOUT, which is expensive on virtual threads). Each connection publishes its state in a
 * {@link Timer}; the reaper scans all timers every {@link #TICK} and acts on expired ones:
 * <ul>
 * <li>{@link State#IDLE} (keep-alive, waiting for the first byte of a request): idle timeout
 * from the end of the previous response; expiry ends the connection (see {@link #expire}).</li>
 * <li>{@link State#HEAD} (from the first byte of a request until its head and body are read):
 * header timeout, absolute; expiry shuts the input down so the connection answers 408 and
 * closes, and the channel is closed outright if that has not happened a write timeout later.</li>
 * <li>{@link State#PROCESSING}: no deadline (the handler is running).</li>
 * <li>{@link State#WRITING}: write timeout from the last write that made progress; a download
 * that keeps progressing is never cut, a client that stops reading is closed.</li>
 * </ul>
 * Connections update their timer with two volatile writes (deadline, then state); the reaper
 * reads the state first, so it never pairs a new state with an older deadline.
 */
final class ConnectionReaper implements AutoCloseable {

    static final Duration TICK = Duration.ofMillis(50);

    enum State { IDLE, HEAD, PROCESSING, WRITING }

    /** Deadline state of one connection; mutated by its own thread, read by the reaper. */
    final class Timer {
        private final SocketChannel channel;
        private volatile long deadline;
        private volatile State state = State.PROCESSING;
        private volatile boolean headExpired;
        private long headDeadline; // connection thread only
        private long forceCloseAt; // reaper thread only

        private Timer(SocketChannel channel) {
            this.channel = channel;
        }

        void idle() {
            this.deadline = System.nanoTime() + ConnectionReaper.this.idleNanos;
            this.state = State.IDLE;
        }

        void head() {
            this.headDeadline = System.nanoTime() + ConnectionReaper.this.headNanos;
            this.deadline = this.headDeadline;
            this.state = State.HEAD;
        }

        /**
         * Back to the head deadline set by {@link #head()} (same absolute instant) after an interim
         * write such as {@code 100 Continue}: the body that follows is still bounded by it.
         */
        void resumeHead() {
            this.deadline = this.headDeadline;
            this.state = State.HEAD;
        }

        void processing() {
            this.state = State.PROCESSING;
        }

        /** Called before each write and after each write that made progress. */
        void writing() {
            this.deadline = System.nanoTime() + ConnectionReaper.this.writeNanos;
            if (this.state != State.WRITING) {
                this.state = State.WRITING;
            }
        }

        /** @return true if the head deadline expired (the input was shut down by the reaper) */
        boolean headExpired() {
            return this.headExpired;
        }

        State state() {
            return this.state;
        }
    }

    private volatile long idleNanos;
    private volatile long headNanos;
    private volatile long writeNanos;
    private final Set<Timer> timers = ConcurrentHashMap.newKeySet();
    private final LongAdder idleExpired = new LongAdder();
    private final LongAdder headExpired = new LongAdder();
    private final LongAdder writeExpired = new LongAdder();
    private final Thread thread;
    private volatile boolean closed;
    /** Shutdown has begun: idle and half-read connections are closed; responses in progress are not. */
    private volatile boolean draining;

    ConnectionReaper(Duration idle, Duration head, Duration write) {
        this.idleNanos = idle.toNanos();
        this.headNanos = head.toNanos();
        this.writeNanos = write.toNanos();
        this.thread = Thread.ofPlatform().daemon().name("http1-reaper").unstarted(this::loop);
        this.thread.start();
    }

    /** Applies timeouts from a reload. Deadlines already published keep their old instant. */
    void update(Duration idle, Duration head, Duration write) {
        this.idleNanos = idle.toNanos();
        this.headNanos = head.toNanos();
        this.writeNanos = write.toNanos();
    }

    long idleNanos() {
        return this.idleNanos;
    }

    Timer register(SocketChannel channel) {
        var timer = new Timer(channel);
        this.timers.add(timer);
        return timer;
    }

    void deregister(Timer timer) {
        this.timers.remove(timer);
    }

    /** @return connections currently tracked (0 once every connection has finished) */
    int tracked() {
        return this.timers.size();
    }

    long idleExpirations() {
        return this.idleExpired.sum();
    }

    long headExpirations() {
        return this.headExpired.sum();
    }

    long writeExpirations() {
        return this.writeExpired.sum();
    }

    private void loop() {
        long tick = TICK.toMillis();
        while (!this.closed) {
            try {
                Thread.sleep(tick);
            } catch (InterruptedException e) {
                if (this.closed) {
                    return;
                }
            }
            try {
                sweep(System.nanoTime());
            } catch (RuntimeException e) { // never let the only timeout thread die
                System.err.println("reaper: " + e);
            }
        }
    }

    /** Ends idle keep-alives immediately. Connections in {@link State#PROCESSING} or writing are left alone. */
    void expireIdle() {
        for (var t : this.timers) {
            if (t.state == State.IDLE) {
                this.idleExpired.increment();
                expire(t);
            }
        }
    }

    /**
     * Ends idle and half-read connections immediately. A response already being handled
     * ({@link State#PROCESSING} or {@link State#WRITING}) is left alone until {@link #forceAll()}.
     * Later transitions back to idle are closed by {@link #sweep(long)}.
     */
    void beginDrain() {
        this.draining = true;
        for (var t : this.timers) {
            var state = t.state;
            if (state == State.IDLE || state == State.HEAD) {
                expire(t);
            }
        }
    }

    /** Closes every tracked connection. Called when the drain timeout has elapsed. */
    void forceAll() {
        for (var t : this.timers) {
            expire(t);
        }
    }

    /** @return true when any tracked connection is in {@code state} */
    boolean any(State state) {
        for (var t : this.timers) {
            if (t.state == state) {
                return true;
            }
        }
        return false;
    }

    void sweep(long now) {
        for (var t : this.timers) {
            var state = t.state; // read before the deadline (see class comment)
            if (this.draining && (state == State.IDLE || state == State.HEAD)) {
                expire(t);
                continue;
            }
            long deadline = t.deadline;
            switch (state) {
                case IDLE, WRITING -> {
                    if (now - deadline >= 0) {
                        (state == State.IDLE ? this.idleExpired : this.writeExpired).increment();
                        expire(t);
                    }
                }
                case HEAD -> {
                    if (!t.headExpired) {
                        if (now - deadline >= 0) {
                            this.headExpired.increment();
                            t.headExpired = true;
                            t.forceCloseAt = now + this.writeNanos;
                            try {
                                t.channel.shutdownInput(); // wakes the read: the connection answers 408
                            } catch (IOException | RuntimeException e) { // e.g. already closed
                                expire(t);
                            }
                        }
                    } else if (now - t.forceCloseAt >= 0) {
                        expire(t);
                    }
                }
                case PROCESSING -> {
                    // no deadline while the handler runs
                }
            }
        }
    }

    /**
     * Ends a connection without ever blocking the reaper: {@code SocketChannel.close()} waits for a
     * virtual thread parked in a read or write to run and release the channel, so under carrier
     * pressure it could stall every other deadline (and no FIN would go out meanwhile). shutdown(2)
     * on both directions is immediate: the peer gets its FIN, the parked read sees EOF and a parked
     * write fails (EPIPE). The close itself then happens on the connection's own thread
     * (try-with-resources), with a virtual-thread close as a backstop.
     */
    private void expire(Timer t) {
        this.timers.remove(t); // the connection's own deregister() is then a no-op
        var channel = t.channel;
        try {
            channel.shutdownOutput();
        } catch (IOException | RuntimeException e) {
            // already closed or reset
        }
        try {
            channel.shutdownInput();
        } catch (IOException | RuntimeException e) {
            // already closed or reset
        }
        Thread.startVirtualThread(() -> {
            try {
                channel.close();
            } catch (IOException e) {
                // already closed
            }
        });
    }

    @Override
    public void close() {
        this.closed = true;
        this.thread.interrupt();
    }
}
