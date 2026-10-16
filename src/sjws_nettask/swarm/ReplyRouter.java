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
 * as soon as the envelope is built (before the ledger write), so the caller does not wait for the poller.
 * <p><b>Ordering: complete before produce (accepted, #219 review).</b> Replies are ASYNC, so completing after produce
 * would only wait on the page-cache hand-off and give the caller no durability. If produce then throws, the worker's
 * reply future fails, the task is not acked, its lease expires and it is redelivered; the second reply finds no pending
 * caller ({@link #completeLocal} returns false). Its ledger copy is the first the poller sees for that correlation key,
 * so it consumes the remembered direct delivery and is acked silently (it counts late only if that key was evicted). So: no double completion, no lost caller,
 * at-least-once handler execution (which swarm already assumes). The poller still
 * reads and acks every reply for retention; a reply already handed to its caller is not counted as late.
 */
public final class ReplyRouter implements AutoCloseable {

    public static final String GROUP = "router";
    public static final long DEFAULT_IDLE_CLOSE_MS = TimeUnit.MINUTES.toMillis(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Longest blocking poll; also bounds how late the idle check and {@link #close} are noticed. */
    private static final long POLL_WAIT_MS = 50;
    private static final long POLL_WAIT_NS = TimeUnit.MILLISECONDS.toNanos(POLL_WAIT_MS);

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
    /** S2c streaming calls whose final reply lands here ({@link #registerStream}); empty unless streaming is used. */
    private final java.util.concurrent.ConcurrentHashMap<Key, Long> streams = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Cap on registered streams ({@value #MAX_STREAMS_PROPERTY}, read at construction; an invalid or negative value
     * logs a WARN and falls back to the default, so it never breaks a router); past it registration is refused.
     */
    private final int maxStreams;
    /** Keys held in {@link #streams}, reserved by CAS before insertion so concurrent registrations never pass the cap. */
    private final java.util.concurrent.atomic.AtomicInteger streamCount = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicLong streamRejections = new AtomicLong();
    private final AtomicLong streamsExpired = new AtomicLong();
    /** Parses {@value #MAX_STREAMS_PROPERTY}: unset gives the default; invalid or negative logs a WARN and gives it too. */
    static int resolveMaxStreams(String v) {
        if (v == null || v.isBlank()) {
            return DEFAULT_MAX_STREAMS;
        }
        try {
            int n = Integer.parseInt(v.trim());
            if (n >= 0) {
                return n;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        System.getLogger(ReplyRouter.class.getName()).log(System.Logger.Level.WARNING,
                "invalid " + MAX_STREAMS_PROPERTY + "=" + v + "; using " + DEFAULT_MAX_STREAMS);
        return DEFAULT_MAX_STREAMS;
    }

    /** Property for {@link #maxStreams}; default {@value #DEFAULT_MAX_STREAMS}. */
    public static final String MAX_STREAMS_PROPERTY = "sjws.swarm.stream.maxRegistered";
    static final int DEFAULT_MAX_STREAMS = 65_536;
    /** Upper bound on remembered direct deliveries; past it the eldest is dropped (worst case: one late count). */
    static final int DELIVERED_LOCAL_MAX = 65_536;
    /**
     * Correlation keys completed by {@link #completeLocal} whose ledger copy the poller has not read yet. The poller
     * removes each key when it reads that reply, so this stays near empty; insertion order bounds it if the poller lags.
     */
    private final java.util.Map<Key, Boolean> deliveredLocal = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Key, Boolean> e) {
                    return size() > DELIVERED_LOCAL_MAX;
                }
            });

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
        this.maxStreams = resolveMaxStreams(System.getProperty(MAX_STREAMS_PROPERTY));
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
            this.deliveredLocal.put(key, Boolean.TRUE);
            return true;
        }
        return false;
    }

    /** Direct deliveries whose ledger copy the poller has not read yet (tests). */
    int deliveredLocalSize() {
        return this.deliveredLocal.size();
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
     * S2c: registers a streaming call submitted outside {@link #call} (e.g. {@code :stream}) with
     * {@code replyTo = }{@link #topic()} and correlation id {@code correlationId} (16 bytes, e.g. {@link #newId()}).
     * Its final {@code LAST} reply is then acked without counting late, and the key is cleared when that reply is
     * read. No pending future is held for the stream. Starts the poller, so the reply is read even when no unary
     * call is in flight, and keeps it from idle-closing while streams are registered.
     * <p>
     * Self-healing: the key expires at {@code deadlineMs} (pass the session deadline plus a grace period); the poller
     * sweeps expired keys at most once per poll slice, and only while any are registered. Callers should still
     * {@link #clearStream} on every exit path. At most {@value #MAX_STREAMS_PROPERTY} keys are held: past it the
     * registration is refused, counted in {@link #streamRejections()}, and that stream's reply would count late. The cap is
     * exact under concurrency (a slot is reserved by CAS before insertion); re-registering a held key only refreshes
     * its deadline and takes no new slot.
     *
     * @return false when refused by the cap
     */
    public boolean registerStream(byte[] correlationId, long deadlineMs) {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        var key = key(correlationId);
        if (this.streams.replace(key, deadlineMs) == null) { // a held key only refreshes its deadline
            // Reserve a slot first (CAS), so concurrent registrations never exceed the cap.
            int n;
            do {
                n = this.streamCount.get();
                if (n >= this.maxStreams) {
                    this.streamRejections.incrementAndGet();
                    return false;
                }
            } while (!this.streamCount.compareAndSet(n, n + 1));
            if (this.streams.put(key, deadlineMs) != null) {
                this.streamCount.decrementAndGet(); // raced with a registration of the same key: one slot
            }
        }
        ensurePolling();
        return true;
    }

    /** S2c: forgets a stream registered with {@link #registerStream}; a reply read afterwards counts late. */
    public void clearStream(byte[] correlationId) {
        removeStream(key(correlationId));
    }

    /** Removes {@code key} and releases its slot; true when it was held. */
    private boolean removeStream(Key key) {
        if (this.streams.remove(key) != null) {
            this.streamCount.decrementAndGet();
            return true;
        }
        return false;
    }

    /** Streams registered and not yet ended (tests, stats). */
    public int streams() {
        return this.streams.size();
    }

    /** Slots reserved against the cap (tests: always equals {@link #streams()} when quiescent). */
    int streamSlots() {
        return this.streamCount.get();
    }

    int maxStreams() {
        return this.maxStreams;
    }

    /** Registrations refused because {@value #MAX_STREAMS_PROPERTY} keys were already held. */
    public long streamRejections() {
        return this.streamRejections.get();
    }

    /** Stream keys dropped by the deadline sweep (a caller that never cleared them). */
    public long streamsExpired() {
        return this.streamsExpired.get();
    }

    /** Drops stream keys whose deadline passed at {@code now}. */
    void sweepStreams(long now) {
        for (var e : this.streams.entrySet()) {
            // remove(key, value): a key re-registered meanwhile (new deadline) is kept
            if (e.getValue() <= now && this.streams.remove(e.getKey(), e.getValue())) {
                this.streamCount.decrementAndGet();
                this.streamsExpired.incrementAndGet();
            }
        }
    }

    private static Key key(byte[] correlationId) {
        if (correlationId.length != 16) {
            throw new IllegalArgumentException("correlationId must be 16 bytes");
        }
        var b = ByteBuffer.wrap(correlationId);
        return new Key(b.getLong(0), b.getLong(8));
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
            } else if (this.deliveredLocal.remove(key) == null && !removeStream(key)) {
                this.late.incrementAndGet(); // timed out, or not an in-flight call or registered stream
            }
        }
        p.ack(GROUP, offs, atts, null); // one batched fenced ack (#100) for every reply read
        return got.size();
    }

    private void pollLoop() {
        long lastActive = this.topics.clock().getAsLong();
        long lastSweep = lastActive;
        while (!this.closed) {
            int n = pollOnce(POLL_WAIT_NS);
            long now = this.topics.clock().getAsLong();
            // Stream-key sweep: only while streams are registered, at most once per poll slice.
            if (!this.streams.isEmpty() && now - lastSweep >= POLL_WAIT_MS) {
                lastSweep = now;
                sweepStreams(now);
            }
            if (n > 0) {
                lastActive = now;
            } else if (this.pending.isEmpty() && this.streams.isEmpty() && now - lastActive >= this.idleCloseMs
                    && idleClose()) {
                return;
            }
        }
    }

    /** Stops the poller and closes the topic if still idle; a call racing in keeps it open. */
    private boolean idleClose() {
        this.lifecycle.lock();
        try {
            if (!this.pending.isEmpty() || !this.streams.isEmpty()) {
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
        for (var k : this.streams.keySet()) {
            removeStream(k);
        }
    }
}
