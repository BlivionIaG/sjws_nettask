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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import sjws_nettask.messaging.delivery.LeaseResult;
import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.swarm.wire.StreamChunk;

/**
 * S2c worker produce path (docs/design/agent-swarm.md §5.2, §12; S2c PR 3). Exists only when
 * {@code sjws.swarm.stream.enabled} is on: {@link SwarmModule} creates it and binds it to {@link TaskService}; with
 * the flag off this class is never loaded and nothing below runs.
 * <ul>
 * <li><b>Shards:</b> chunks go to lazy per-node stream shards {@code stream.<node>#i}, opened without a DLQ
 * (#225), shard {@code TokenStream.shardOf(streamId, shards)}. {@code shards} ({@code sjws.swarm.stream.shards},
 * default 1) is read once at startup: a resume must land on the same shard. {@code reply.<node>} keeps unary
 * replies and the final result.</li>
 * <li><b>Stream id:</b> the task's {@code correlationId} (an internal routing key; the caller-facing session id
 * of §12 Q2 is minted by the HTTP leg, PR 4).</li>
 * <li><b>Fence:</b> per task (pool, shard, offset), the highest attempt that has produced a chunk on this node.
 * The check and the write are atomic under the task's fence lock: a chunk from a lower attempt is
 * {@code STALE_ATTEMPT} and is not written, and no older attempt's chunk lands after a newer one's. After a
 * successful final ack the entry becomes a tombstone (every later chunk is stale); entries are purged after
 * {@link #FENCE_TTL_MS} idle. The fence is node-local; the cross-node rule (attempt in the chunk header, consumer
 * reset) is recorded in §12 for PR 4/5. The final ack is the messaging fence (#87).</li>
 * <li><b>Local delivery:</b> a same-process caller that {@link #open opened} the stream gets each chunk pushed to a
 * bounded {@link Sink} (in order, deduplicated by {@code TokenStream.Reorder}) before the ledger write, as
 * {@code ReplyRouter.completeLocal} does for unary replies. A full sink drops the chunk and counts it; the
 * ledger copy stays the source for resume (PR 4).</li>
 * </ul>
 * Chunks are written with the agent's own {@code (producerId, seq)} (§5.2), {@link Durability#NONE}; {@code LAST}
 * with {@link Durability#ASYNC}, and the task is acked only after both {@code LAST} and the reply are durable (§4.4).
 */
public final class TokenStreams {

    static final long FENCE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    static final int FENCE_PURGE_AT = 65_536;

    /** Startup settings; {@code sjws.swarm.stream.*} system properties or {@code SJWS_SWARM_STREAM_*} env. */
    public record Config(int shards, int window, int sinkChunks) {
        public Config {
            if (shards < 1 || shards > 64) {
                throw new IllegalArgumentException("sjws.swarm.stream.shards 1..64: " + shards);
            }
            if (window < 1 || window > 65_536) {
                throw new IllegalArgumentException("sjws.swarm.stream.window 1..65536: " + window);
            }
            if (sinkChunks < 1 || sinkChunks > 65_536) {
                throw new IllegalArgumentException("sjws.swarm.stream.sinkChunks 1..65536: " + sinkChunks);
            }
        }

        public static final Config DEFAULT = new Config(1, TokenStream.Reorder.DEFAULT_WINDOW, 256);

        /** {@code get} maps a property name ({@code sjws.swarm.stream.shards}) to its value or null. */
        public static Config from(Function<String, String> get) {
            return new Config(intSetting(get, "shards", 1), intSetting(get, "window", TokenStream.Reorder.DEFAULT_WINDOW),
                    intSetting(get, "sinkChunks", 256));
        }

        private static int intSetting(Function<String, String> get, String name, int dflt) {
            var v = get.apply("sjws.swarm.stream." + name);
            if (v == null || v.isBlank()) {
                return dflt;
            }
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("sjws.swarm.stream." + name + ": " + v, e);
            }
        }
    }

    private record FenceKey(String pool, int shard, long offset) { }

    private record Key(long hi, long lo) { }

    /** Per-task fence: the highest admitted attempt; its lock makes the attempt check and the chunk write atomic. */
    private static final class Fence {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        int attempt; // guarded by lock
        boolean settled; // guarded by lock: tombstone after a successful final ack
        volatile long touchedMs;

        Fence(long now) {
            this.attempt = Integer.MIN_VALUE;
            this.touchedMs = now;
        }
    }

    private final Topics topics;
    private final String node;
    private final Config config;
    private final ConcurrentHashMap<FenceKey, Fence> fences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Sink> sinks = new ConcurrentHashMap<>();
    private final AtomicLong sinkDrops = new AtomicLong();

    public TokenStreams(Topics topics, String node, Config config) {
        this.topics = topics;
        this.node = node;
        this.config = config;
    }

    public Config config() {
        return this.config;
    }

    public String shardTopic(int shard) {
        return "stream." + this.node + "#" + shard;
    }

    /** The shard topic for a stream id. */
    public String topicFor(long streamHi, long streamLo) {
        return shardTopic(TokenStream.shardOf(streamHi, streamLo, this.config.shards()));
    }

    Partition partitionFor(long streamHi, long streamLo) {
        return this.topics.partitionWithoutDlq(topicFor(streamHi, streamLo));
    }

    /** Chunks dropped because a local sink was full (resume from the ledger is PR 4). */
    public long sinkDrops() {
        return this.sinkDrops.get();
    }

    int fenceEntries() {
        return this.fences.size();
    }

    /** Test hook, run inside the fence lock just before the produce hand-off. */
    volatile Runnable beforeWrite;
    private final AtomicLong lastFailures = new AtomicLong();

    /** Final writes ({@code LAST} or reply) that failed before the ack; the fence then lives until its TTL. */
    public long lastFailures() {
        return this.lastFailures.get();
    }

    void lastFailed(Throwable e) {
        this.lastFailures.incrementAndGet();
        System.err.println("WARN: swarm: stream LAST/reply write failed, task not acked: " + e);
    }

    /** The outcome of {@link #admitAndWrite}: {@code result} OK with the produce result, or a refusal. */
    record Admitted(LeaseResult result, Partition.ProduceResult produced) { }

    /**
     * Atomically checks the fence and writes {@code c}: under the task's fence lock, a chunk is refused with
     * {@code STALE_ATTEMPT} if a later attempt was admitted or the stream was settled; otherwise its attempt becomes
     * the fence and the chunk is handed to the shard <b>before</b> the lock is released, so no older attempt's chunk
     * can land after a newer attempt's on this node.
     */
    Admitted admitAndWrite(Task t, long producerId, long seq, StreamChunk c) {
        var env = t.envelope();
        if (c.streamHi() != env.correlationHi() || c.streamLo() != env.correlationLo()) {
            throw new IllegalArgumentException("chunk stream id is not the task's correlation id");
        }
        long now = this.topics.clock().getAsLong();
        var key = new FenceKey(t.pool(), t.shard(), t.offset());
        var f = this.fences.get(key);
        if (f == null) {
            if (this.fences.size() >= FENCE_PURGE_AT) {
                purge(now);
            }
            var fresh = new Fence(now);
            var prior = this.fences.putIfAbsent(key, fresh);
            f = prior != null ? prior : fresh;
        }
        f.lock.lock();
        try {
            if (this.fences.get(key) != f) { // purged between get and lock: retry on the live entry
                return admitAndWrite(t, producerId, seq, c);
            }
            if (f.settled || t.attempt() < f.attempt) {
                return new Admitted(LeaseResult.STALE_ATTEMPT, null);
            }
            f.attempt = t.attempt();
            f.touchedMs = now;
            var hook = this.beforeWrite;
            if (hook != null) {
                hook.run();
            }
            deliverLocal(c); // before produce, as completeLocal: latency must not wait on the ledger hand-off
            var r = partitionFor(c.streamHi(), c.streamLo()).produce(producerId, seq, c.encode(),
                    c.last() ? Durability.ASYNC : Durability.NONE);
            return new Admitted(LeaseResult.OK, r);
        } finally {
            f.lock.unlock();
        }
    }

    /** After a successful final ack: tombstones the fence so no attempt of this task can produce again. */
    void settle(Task t) {
        var f = this.fences.get(new FenceKey(t.pool(), t.shard(), t.offset()));
        if (f != null) {
            f.lock.lock();
            try {
                f.settled = true;
                f.touchedMs = this.topics.clock().getAsLong();
            } finally {
                f.lock.unlock();
            }
        }
    }

    private void purge(long now) {
        this.fences.entrySet().removeIf(e -> {
            var f = e.getValue();
            if (now - f.touchedMs <= FENCE_TTL_MS || !f.lock.tryLock()) {
                return false;
            }
            f.lock.unlock();
            return true;
        });
    }

    /** Opens a same-process consumer for the stream of {@code correlationId} (16 bytes); close it when done. */
    public Sink open(byte[] correlationId) {
        if (correlationId.length != 16) {
            throw new IllegalArgumentException("correlationId is 16 bytes");
        }
        var b = java.nio.ByteBuffer.wrap(correlationId);
        var key = new Key(b.getLong(), b.getLong());
        var s = new Sink(this, key, this.config);
        if (this.sinks.putIfAbsent(key, s) != null) {
            throw new IllegalStateException("stream already open");
        }
        return s;
    }

    int openSinks() {
        return this.sinks.size();
    }

    private void deliverLocal(StreamChunk c) {
        var s = this.sinks.get(new Key(c.streamHi(), c.streamLo()));
        if (s != null && !s.offer(c)) {
            this.sinkDrops.incrementAndGet();
        }
    }

    /**
     * A bounded, in-order, deduplicated queue of one stream's chunks for a same-process caller. Producers are the
     * worker threads of that stream (one at a time per attempt); one consumer calls {@link #poll}.
     */
    public static final class Sink implements AutoCloseable {
        private final TokenStreams owner;
        private final Key key;
        private final TokenStream.Reorder reorder;
        private final ArrayBlockingQueue<StreamChunk> queue;
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        private final AtomicLong conflicts = new AtomicLong();

        Sink(TokenStreams owner, Key key, Config config) {
            this.owner = owner;
            this.key = key;
            this.reorder = new TokenStream.Reorder(key.hi(), key.lo(), config.window());
            this.queue = new ArrayBlockingQueue<>(config.sinkChunks());
        }

        /** False when the chunk could not be queued (sink full); duplicates and conflicts are not failures. */
        boolean offer(StreamChunk c) {
            this.lock.lock();
            try {
                if (this.queue.remainingCapacity() < 1 + this.reorder.held()) {
                    return false;
                }
                var out = new ArrayList<StreamChunk>(2);
                var r = this.reorder.offer(c, out);
                if (r == TokenStream.Reorder.Offer.CONFLICT) {
                    this.conflicts.incrementAndGet();
                } else if (r == TokenStream.Reorder.Offer.OVERFLOW) {
                    return false;
                }
                this.queue.addAll(out);
                return true;
            } finally {
                this.lock.unlock();
            }
        }

        /** The next in-order chunk, waiting up to {@code timeout}; null on timeout. */
        public StreamChunk poll(long timeout, TimeUnit unit) throws InterruptedException {
            return this.queue.poll(timeout, unit);
        }

        /** Drains whatever is queued, in order. */
        public List<StreamChunk> drain() {
            var out = new ArrayList<StreamChunk>();
            this.queue.drainTo(out);
            return out;
        }

        /** True once {@code LAST} and every chunk before it have been queued. */
        public boolean complete() {
            this.lock.lock();
            try {
                return this.reorder.complete();
            } finally {
                this.lock.unlock();
            }
        }

        public long conflicts() {
            return this.conflicts.get();
        }

        @Override
        public void close() {
            this.owner.sinks.remove(this.key, this);
        }
    }
}
