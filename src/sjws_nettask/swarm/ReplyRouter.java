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
package sjws_nettask.swarm;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Request/reply (agent-swarm.md §5.1). This node or process has one reply topic, {@code reply.<id>}. Replies
 * are matched to callers in memory by {@code correlationId}.
 * <ul>
 * <li>One poller thread reads the reply topic as group {@value #GROUP} and acks every reply. A late reply,
 * whose caller already timed out, is acked and discarded.</li>
 * <li>A call that gets no reply by its deadline fails with {@link TimeoutException}. The HTTP layer maps that to
 * a 504-class error.</li>
 * <li>With no call pending and nothing read for {@code idleCloseMs} (default 10 min), the poller stops and the
 * topic is closed; its files are kept. The next {@link #call} reopens it.</li>
 * </ul>
 * Polling is adaptive: the poller spins briefly while replies are due, then parks for up to 20 us while a call is pending and up to 1 ms when none is. Messaging has
 * no blocking poll; see the Messaging Core note in the S1 PR.
 */
public final class ReplyRouter implements AutoCloseable {

    public static final String GROUP = "router";
    public static final long DEFAULT_IDLE_CLOSE_MS = TimeUnit.MINUTES.toMillis(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SPINS = 2_000;
    private static final long MAX_PARK_NS = 1_000_000;
    /** Longest park while a caller waits, so a reply is noticed within ~20 us after the spin phase. */
    private static final long WAITING_PARK_NS = 20_000;

    private final Topics topics;
    private final TaskService tasks;
    private final String topic;
    private final long producerId;
    private final long idleCloseMs;
    private final AtomicLong seq = new AtomicLong();
    private final ConcurrentHashMap<Key, CompletableFuture<TaskEnvelope>> pending = new ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock lifecycle = new java.util.concurrent.locks.ReentrantLock();
    private volatile Thread poller;
    private volatile boolean closed;
    private final AtomicLong late = new AtomicLong();

    private record Key(long hi, long lo) {
        static Key of(byte[] id) {
            var b = ByteBuffer.wrap(id);
            return new Key(b.getLong(), b.getLong());
        }
    }

    /**
     * @param id         node or process id; the reply topic is {@code reply.<id>}
     * @param producerId this process's producer id for submits (§5.2: the agent's own id, never derived per stream)
     */
    public ReplyRouter(Topics topics, TaskService tasks, String id, long producerId, long idleCloseMs) {
        if (idleCloseMs < 1) {
            throw new IllegalArgumentException("idleCloseMs");
        }
        this.topics = topics;
        this.tasks = tasks;
        this.topic = "reply." + id;
        this.producerId = producerId;
        this.idleCloseMs = idleCloseMs;
    }

    public String topic() {
        return this.topic;
    }

    /** Replies that arrived after their caller gave up; acked and discarded. */
    public long lateReplies() {
        return this.late.get();
    }

    public int pending() {
        return this.pending.size();
    }

    public boolean polling() {
        return this.poller != null;
    }

    /** New 16-byte id for a task or a correlation. */
    public static byte[] newId() {
        var id = new byte[16];
        RANDOM.nextBytes(id);
        return id;
    }

    /**
     * Submits {@code body} to {@code pool} and completes with the reply envelope, or with {@link TimeoutException}
     * at {@code deadlineMs} (absolute, the topics' clock). The task id is the correlation id.
     */
    public CompletableFuture<TaskEnvelope> call(String pool, String contentType, byte[] body, long deadlineMs,
            String traceparent) {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        var id = newId();
        var env = new TaskEnvelope(id, id, this.topic, deadlineMs, traceparent, "", "", contentType, body);
        var key = Key.of(id);
        var f = new CompletableFuture<TaskEnvelope>();
        this.pending.put(key, f);
        ensurePolling();
        long wait = deadlineMs - this.topics.clock().getAsLong();
        f.orTimeout(Math.max(0, wait), TimeUnit.MILLISECONDS).whenComplete((r, e) -> this.pending.remove(key, f));
        try {
            this.tasks.submit(pool, this.producerId, this.seq.incrementAndGet(), env);
        } catch (RuntimeException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    /** Always under the lock, so a call never races an idle close into a stranded reply. */
    private void ensurePolling() {
        this.lifecycle.lock();
        try {
            if (this.poller == null && !this.closed) {
                var p = this.topics.partition(this.topic);
                p.subscribe(GROUP, false);
                this.poller = Thread.ofPlatform().daemon().name("swarm-" + this.topic).start(this::pollLoop);
            }
        } finally {
            this.lifecycle.unlock();
        }
    }

    /** One poll of the reply topic: completes waiting callers and acks every reply. Returns replies read. */
    int pollOnce() {
        var p = this.topics.partition(this.topic);
        var got = p.poll(GROUP, "router", 256, 30_000);
        for (var d : got) {
            TaskEnvelope env = null;
            try {
                env = TaskEnvelope.decode(d.payload());
            } catch (IllegalArgumentException e) {
                // not an envelope: ack and drop below
            }
            var f = env == null ? null : this.pending.remove(Key.of(env.correlationId()));
            if (f == null || !f.complete(env)) {
                this.late.incrementAndGet();
            }
            p.ack(GROUP, d.offset(), d.attempt());
        }
        return got.size();
    }

    private void pollLoop() {
        long lastActive = this.topics.clock().getAsLong();
        int idleSpins = 0;
        long park = 1_000;
        while (!this.closed) {
            int n = pollOnce();
            long now = this.topics.clock().getAsLong();
            if (n > 0) {
                lastActive = now;
                idleSpins = 0;
                park = 1_000;
                continue;
            }
            if (this.pending.isEmpty() && now - lastActive >= this.idleCloseMs && idleClose()) {
                return;
            }
            boolean waiting = !this.pending.isEmpty();
            if (waiting && ++idleSpins < SPINS) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(park);
                park = Math.min(waiting ? WAITING_PARK_NS : MAX_PARK_NS, park * 2);
            }
        }
    }

    /** Stops the poller and closes the topic if still idle; a call racing in keeps it open. */
    private boolean idleClose() {
        this.lifecycle.lock();
        try {
            if (!this.pending.isEmpty()) {
                return false;
            }
            this.poller = null;
            this.topics.close(this.topic);
            return true;
        } finally {
            this.lifecycle.unlock();
        }
    }

    @Override
    public void close() {
        Thread t;
        this.lifecycle.lock();
        try {
            this.closed = true;
            t = this.poller;
            this.poller = null;
        } finally {
            this.lifecycle.unlock();
        }
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (var f : this.pending.values()) {
            f.completeExceptionally(new IllegalStateException("closed"));
        }
    }
}
