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
 * The poller blocks in {@code Partition.poll(..., waitNanos)} (#99), woken by the reply's append: no spinning.
 * <p>Same-process replies are completed by {@link #completeLocal} from {@link TaskService#reply}/{@link TaskService#replyAll}
 * as soon as the envelope is built, so the caller does not wait for the poller (or the ledger write). The poller still
 * reads and acks every reply for retention; a reply already handed to its caller is not counted as late.
 */
public final class ReplyRouter implements AutoCloseable {

    public static final String GROUP = "router";
    public static final long DEFAULT_IDLE_CLOSE_MS = TimeUnit.MINUTES.toMillis(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Longest blocking poll; also bounds how late the idle check and {@link #close} are noticed. */
    private static final long POLL_WAIT_NS = TimeUnit.MILLISECONDS.toNanos(50);

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
    /** Correlation keys completed by {@link #completeLocal}; the poller removes them so they are not counted late. */
    private final java.util.Set<Key> deliveredLocal = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record Key(long hi, long lo) { }

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

    /**
     * Completes a same-process caller whose {@code replyTo} is this router's topic, without waiting for the poller.
     * Returns {@code true} when a pending call was completed. The envelope is still written to the reply topic for
     * durability and ack; the poller then acks it and does not count it as late.
     */
    public boolean completeLocal(TaskEnvelope env) {
        if (env == null || this.closed) {
            return false;
        }
        var key = new Key(env.correlationHi(), env.correlationLo());
        var f = this.pending.remove(key);
        if (f == null) {
            return false;
        }
        if (f.complete(env)) {
            this.deliveredLocal.add(key);
            // Bound the set if the poller lags (should stay near empty under load).
            if (this.deliveredLocal.size() > 65_536) {
                this.deliveredLocal.clear();
            }
            return true;
        }
        return false;
    }

    public int pending() {
        return this.pending.size();
    }

    public boolean polling() {
        return this.poller != null;
    }

    /** New random 16-byte id (SecureRandom; for one-off ids such as HTTP submits). */
    public static byte[] newId() {
        var id = new byte[16];
        RANDOM.nextBytes(id);
        return id;
    }

    /** Per-router random high half: ids are unique across processes without a SecureRandom call per call. */
    private final long idPrefix = RANDOM.nextLong();
    private final AtomicLong idCounter = new AtomicLong(RANDOM.nextLong());

    private byte[] nextId() {
        return ByteBuffer.allocate(16).putLong(this.idPrefix).putLong(this.idCounter.incrementAndGet()).array();
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
        var id = nextId();
        var env = TaskEnvelope.owned(id, id, this.topic, deadlineMs, traceparent, contentType, body.clone());
        var key = new Key(this.idPrefix, ByteBuffer.wrap(id).getLong(8));
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
        return pollOnce(0);
    }

    /** As {@link #pollOnce()}, blocking up to {@code waitNanos} for a reply (#99); no spin, no park loop. */
    int pollOnce(long waitNanos) {
        var p = this.topics.partition(this.topic);
        var got = waitNanos > 0 ? p.poll(GROUP, "router", 256, 30_000, waitNanos) : p.poll(GROUP, "router", 256, 30_000);
        int n = got.size();
        if (n == 0) {
            return 0;
        }
        var offs = new long[n];
        var atts = new int[n];
        for (int i = 0; i < n; i++) {
            var d = got.get(i);
            offs[i] = d.offset();
            atts[i] = d.attempt();
            TaskEnvelope env = null;
            try {
                env = TaskEnvelope.decode(d.payload());
            } catch (IllegalArgumentException e) {
                // not an envelope: acked and dropped below
            }
            if (env == null) {
                this.late.incrementAndGet(); // not an envelope: acked and dropped
                continue;
            }
            var key = new Key(env.correlationHi(), env.correlationLo());
            var f = this.pending.remove(key);
            if (f != null) {
                if (!f.complete(env)) {
                    this.late.incrementAndGet(); // raced with the caller's timeout
                }
            } else if (!this.deliveredLocal.remove(key)) {
                this.late.incrementAndGet(); // timed out, or not an in-flight call
            }
        }
        p.ack(GROUP, offs, atts, null); // one batched fenced ack (#100) for every reply read
        return got.size();
    }

    private void pollLoop() {
        long lastActive = this.topics.clock().getAsLong();
        while (!this.closed) {
            int n = pollOnce(POLL_WAIT_NS);
            long now = this.topics.clock().getAsLong();
            if (n > 0) {
                lastActive = now;
            } else if (this.pending.isEmpty() && now - lastActive >= this.idleCloseMs && idleClose()) {
                return;
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
            t.interrupt(); // ends a blocking poll at once; the loop then sees closed
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
