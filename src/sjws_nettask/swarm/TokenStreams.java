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
 * <li><b>Shards:</b> chunks go to the <b>submitter's</b> lazy stream shards {@code stream.<submitter>#i}, opened
 * without a DLQ (#225), shard {@code TokenStream.shardOf(streamId, shards)}. The submitter is the node named by the
 * task's {@code replyTo} ({@code reply.<submitter>}), the same metadata the unary reply is routed by; so a worker on
 * any node writes where the submitter reads (#234 routing fix). {@code shards} ({@code sjws.swarm.stream.shards},
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
 * <li><b>Shard reader (#234 routing fix):</b> while any sink is open, one daemon thread ({@code sjws-stream-<node>})
 * consumes this node's own {@code stream.<node>#i} shards (group {@value #READER_GROUP}, subscribed at the first
 * {@link #open}, before the stream's task is submitted) and offers each chunk to its sink. That is how chunks and
 * {@code RESET} from a worker on another node reach the reader; chunks already pushed in-process are recognised as
 * duplicates and skipped without touching the sink's capacity. The thread stops when no sink has been open for
 * {@link #READER_IDLE_MS} or when the topics close.</li>
 * </ul>
 * Chunks are written with the agent's own {@code (producerId, seq)} (§5.2), {@link Durability#NONE}; {@code LAST}
 * with {@link Durability#ASYNC}, and the task is acked only after both {@code LAST} and the reply are durable (§4.4).
 */
public final class TokenStreams {

    static final long FENCE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    static final int FENCE_PURGE_AT = 65_536;
    /** Consumer group of the shard reader on the submitter's node. */
    static final String READER_GROUP = "stream-reader";
    static final long READER_IDLE_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long READER_WAIT_NS = TimeUnit.MILLISECONDS.toNanos(50);

    /**
     * Startup settings; {@code sjws.swarm.stream.*} system properties or {@code SJWS_SWARM_STREAM_*} env. {@code credit}
     * is {@code sjws.swarm.stream.credit.enabled} (S2c PR 6, off by default): with it off no credit state exists.
     */
    public record Config(int shards, int window, int sinkChunks, boolean credit) {
        /** Credit off. */
        public Config(int shards, int window, int sinkChunks) {
            this(shards, window, sinkChunks, false);
        }

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
            if (credit && window > sinkChunks) { // #257 R2: held chunks (≤ window − 1) and the filler must fit the sink
                // Refused, not IllegalArgumentException: the process must not start (a plain failure only skips the module)
                throw new sjws_nettask.OptionalModule.Refused("sjws.swarm.stream.window (" + window
                        + ") must be at most sjws.swarm.stream.sinkChunks (" + sinkChunks
                        + ") when sjws.swarm.stream.credit.enabled is on: out-of-order chunks held for reordering would"
                        + " fill the sink and stall the stream; lower the window or raise sinkChunks");
            }
        }

        public static final Config DEFAULT = new Config(1, TokenStream.Reorder.DEFAULT_WINDOW, 256);

        /** {@code get} maps a property name ({@code sjws.swarm.stream.shards}) to its value or null. */
        public static Config from(Function<String, String> get) {
            return new Config(intSetting(get, "shards", 1), intSetting(get, "window", TokenStream.Reorder.DEFAULT_WINDOW),
                    intSetting(get, "sinkChunks", 256), boolSetting(get, "credit.enabled"));
        }

        /**
         * {@code true} or {@code false} (any case; blank or unset is false); anything else stops the process
         * ({@link sjws_nettask.OptionalModule.Refused}, as the window rule above): a typo must neither turn
         * backpressure off silently nor start the node without the swarm module (#257 N-c).
         */
        private static boolean boolSetting(Function<String, String> get, String name) {
            var v = get.apply("sjws.swarm.stream." + name);
            if (v == null || v.isBlank() || v.trim().equalsIgnoreCase("false")) {
                return false;
            }
            if (v.trim().equalsIgnoreCase("true")) {
                return true;
            }
            throw new sjws_nettask.OptionalModule.Refused("sjws.swarm.stream." + name + " must be true or false (any case),"
                    + " got \"" + v + "\"");
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
        int floor = Integer.MIN_VALUE; // guarded by lock: attempts below are refused (a LAST whose ack failed)
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

    /** S2c PR 5 cancel state: cancelled and deadline-bound stream tasks (swarm memory only, never in messaging). */
    private final CancelSet cancels;

    public TokenStreams(Topics topics, String node, Config config) {
        this.topics = topics;
        this.node = node;
        this.config = config;
        this.cancels = new CancelSet(topics.clock());
    }

    public CancelSet cancels() {
        return this.cancels;
    }

    public Config config() {
        return this.config;
    }

    /** This node's shard {@code shard}: {@code stream.<node>#shard}. */
    public String shardTopic(int shard) {
        return shardTopic(this.node, shard);
    }

    /** Shard {@code shard} of {@code node} (local or remote): {@code stream.<node>#shard}. */
    static String shardTopic(String node, int shard) {
        return "stream." + node + "#" + shard;
    }

    /** This node's shard topic for a stream id (where streams submitted here are read). */
    public String topicFor(long streamHi, long streamLo) {
        return topicFor(this.node, streamHi, streamLo);
    }

    /** {@code node}'s shard topic for a stream id, {@code hash(streamId) % shards}. */
    String topicFor(String node, long streamHi, long streamLo) {
        return shardTopic(node, TokenStream.shardOf(streamHi, streamLo, this.config.shards()));
    }

    /**
     * The submitting node of {@code env}: its {@code replyTo} is {@code reply.<submitter>} (set by the submitter, as
     * for unary replies). A task without such a reply topic falls back to this node.
     */
    String submitterOf(TaskEnvelope env) {
        var to = env.replyTo();
        if (to == null || !to.startsWith(REPLY_PREFIX) || to.length() == REPLY_PREFIX.length()) {
            return this.node;
        }
        var name = to.substring(REPLY_PREFIX.length());
        if (!SAFE_NODE.matcher(name).matches()) { // #234 review: never build a topic name from an unsafe string
            this.invalidSubmitters.incrementAndGet();
            return this.node;
        }
        return name;
    }

    private static final String REPLY_PREFIX = "reply.";
    /** Node ids, as {@code cp.JoinTokens} validates them. */
    static final java.util.regex.Pattern SAFE_NODE = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    /** W3C {@code tracestate} member the submitter stamps with its shard count (#234 review 2). */
    static final String SHARDS_MEMBER = "sjws-stream";
    private static final String SHARDS_VALUE = "shards:";

    private final AtomicLong invalidSubmitters = new AtomicLong();
    private final AtomicLong invalidShardStamps = new AtomicLong();

    /** Tasks whose {@code replyTo} named an unsafe node; their chunks went to this node's shards. */
    public long invalidSubmitters() {
        return this.invalidSubmitters.get();
    }

    /** Tasks whose shard stamp was malformed or out of 1..64; their chunks were refused. */
    public long invalidShardStamps() {
        return this.invalidShardStamps.get();
    }

    /**
     * True when {@code env} carries the {@value #SHARDS_MEMBER} {@code tracestate} member, which every {@code :stream}
     * submit stamps (#261 Q1). The one stream-task predicate, shared with {@link #submitterShards}: it matches the member
     * name only, so a malformed value still marks a stream task (its chunks are refused there). Unary HTTP submits always
     * have an empty {@code tracestate}, the fast path.
     */
    static boolean isStreamTask(TaskEnvelope env) {
        return shardsMember(env.tracestate()) != null;
    }

    /** The value of the {@value #SHARDS_MEMBER} member of {@code tracestate}, or null when it has none. */
    private static String shardsMember(String ts) {
        if (ts == null || ts.isEmpty()) {
            return null;
        }
        for (var member : ts.split(",")) {
            var m = member.trim();
            if (m.indexOf('=') == SHARDS_MEMBER.length() && m.startsWith(SHARDS_MEMBER)) {
                return m.substring(SHARDS_MEMBER.length() + 1);
            }
        }
        return null;
    }

    /** The {@code tracestate} a submitter puts on a stream task: its own {@code sjws.swarm.stream.shards}. */
    String shardsTracestate() {
        return SHARDS_MEMBER + "=" + SHARDS_VALUE + this.config.shards();
    }

    /**
     * The submitter's shard count for {@code env}, from the {@value #SHARDS_MEMBER} member of its {@code tracestate};
     * this node's own count only when the member is absent (a task from a submitter without the stamp). A present but
     * malformed or out-of-range value is refused, never clamped: a guessed count would pick a shard no reader polls.
     */
    int submitterShards(TaskEnvelope env) {
        var v = shardsMember(env.tracestate());
        if (v == null) {
            return this.config.shards();
        }
        int n = -1;
        if (v.startsWith(SHARDS_VALUE) && v.length() > SHARDS_VALUE.length() && v.length() <= SHARDS_VALUE.length() + 2) {
            try {
                n = Integer.parseInt(v.substring(SHARDS_VALUE.length()));
            } catch (NumberFormatException e) {
                n = -1;
            }
        }
        if (n < 1 || n > 64) {
            this.invalidShardStamps.incrementAndGet();
            throw new IllegalArgumentException("stream task has an invalid shard stamp: " + v);
        }
        return n;
    }

    /** The submitter's shard partition for the chunks of {@code env}'s stream, opened lazily (no DLQ). */
    Partition partitionFor(TaskEnvelope env, long streamHi, long streamLo) {
        var topic = shardTopic(submitterOf(env), TokenStream.shardOf(streamHi, streamLo, submitterShards(env)));
        return this.topics.partitionWithoutDlq(topic);
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
        if (c.reset()) {
            throw new IllegalArgumentException("RESET is consumer-only");
        }
        c = c.withAttempt(t.attempt() & StreamChunk.MAX_ATTEMPT); // the lease's attempt, not the client's (§12 rule 3)
        long now = this.topics.clock().getAsLong();
        // S2c PR 5: a cancelled (or past-deadline) task fails fast on its next chunk; LAST still goes through so
        // the worker can end the stream (LAST|CANCELLED) and ack (§12 Q3)
        if (!c.last()) {
            var why = this.cancels.reason(TaskService.topic(t.pool(), t.shard()), t.offset(), now);
            if (why != null) {
                return new Admitted(LeaseResult.CANCELLED, null);
            }
        }
        // the submitter's shard, resolved before the fence moves: a refused stamp leaves no trace
        var part = partitionFor(env, c.streamHi(), c.streamLo());
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
            if (f.settled || t.attempt() < f.attempt || t.attempt() < f.floor) {
                return new Admitted(LeaseResult.STALE_ATTEMPT, null);
            }
            f.attempt = t.attempt();
            f.touchedMs = now;
            var hook = this.beforeWrite;
            if (hook != null) {
                hook.run();
            }
            deliverLocal(c); // before produce, as completeLocal: latency must not wait on the ledger hand-off
            var r = part.produce(producerId, seq, c.encode(),
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

    /**
     * After a final ack that was refused: that attempt lost its lease, so it may not produce again (#230 review
     * nit); a later attempt still can.
     */
    void retire(Task t) {
        var f = this.fences.get(new FenceKey(t.pool(), t.shard(), t.offset()));
        if (f != null) {
            f.lock.lock();
            try {
                if (t.attempt() != Integer.MAX_VALUE) {
                    f.floor = Math.max(f.floor, t.attempt() + 1);
                }
                f.touchedMs = this.topics.clock().getAsLong();
            } finally {
                f.lock.unlock();
            }
        }
    }

    /** The periodic sweep's fence purge (stream flag on); the inline purge at {@link #FENCE_PURGE_AT} is a backstop. */
    void purgeFences(long now) {
        purge(now);
    }

    private void purge(long now) {
        this.fences.entrySet().removeIf(e -> {
            var f = e.getValue();
            if (now - f.touchedMs <= FENCE_TTL_MS || !f.lock.tryLock()) {
                return false;
            }
            try { // #234 re-review: a writer may have touched it between the read above and the lock
                return now - f.touchedMs > FENCE_TTL_MS;
            } finally {
                f.lock.unlock();
            }
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
        try {
            ensureReading(); // subscribed before this returns, so before the caller submits the task
        } catch (RuntimeException e) {
            this.sinks.remove(key, s);
            throw e;
        }
        return s;
    }

    private final java.util.concurrent.locks.ReentrantLock readerLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile Thread reader;
    private final AtomicLong ledgerChunks = new AtomicLong();
    private final AtomicLong ledgerUndecodable = new AtomicLong();

    /** Chunks the shard reader handed to a sink (a remote worker's, or not yet pushed in-process). */
    public long ledgerChunks() {
        return this.ledgerChunks.get();
    }

    boolean reading() {
        return this.reader != null;
    }

    /** Starts the shard reader if it is not running; subscribes every local shard first. */
    private void ensureReading() {
        this.readerLock.lock();
        try {
            if (this.reader == null && !this.topics.closed()) {
                for (int i = 0; i < this.config.shards(); i++) {
                    this.topics.partitionWithoutDlq(shardTopic(i)).subscribe(READER_GROUP, false);
                }
                this.reader = Thread.ofPlatform().daemon().name("sjws-stream-" + this.node).start(this::readLoop);
            }
        } finally {
            this.readerLock.unlock();
        }
    }

    private void readLoop() {
        long lastActive = this.topics.clock().getAsLong();
        int shards = this.config.shards();
        long wait = Math.max(1, READER_WAIT_NS / shards);
        while (!this.topics.closed()) {
            int n = 0;
            try {
                for (int i = 0; i < shards; i++) {
                    n += readOnce(i, wait);
                }
            } catch (RuntimeException e) {
                if (this.topics.closed() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                System.err.println("WARN: swarm: stream shard reader: " + e);
                java.util.concurrent.locks.LockSupport.parkNanos(READER_WAIT_NS);
            }
            long now = this.topics.clock().getAsLong();
            if (n > 0 || !this.sinks.isEmpty()) {
                lastActive = now;
            } else if (now - lastActive >= READER_IDLE_MS && idleStop()) {
                return;
            }
        }
        this.readerLock.lock();
        try {
            if (this.reader == Thread.currentThread()) {
                this.reader = null;
            }
        } finally {
            this.readerLock.unlock();
        }
    }

    /** Stops the reader if still idle; an {@link #open} racing in keeps it running. */
    private boolean idleStop() {
        this.readerLock.lock();
        try {
            if (!this.sinks.isEmpty()) {
                return false;
            }
            this.reader = null;
            return true;
        } finally {
            this.readerLock.unlock();
        }
    }

    /** One poll of local shard {@code shard}: offers every chunk to its sink and acks the batch. Returns chunks read. */
    int readOnce(int shard, long waitNanos) {
        var p = this.topics.partitionWithoutDlq(shardTopic(shard));
        var got = waitNanos > 0 ? p.poll(READER_GROUP, this.node, 256, 30_000, waitNanos)
                : p.poll(READER_GROUP, this.node, 256, 30_000);
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
            StreamChunk c;
            try {
                c = StreamChunk.decode(d.payload());
            } catch (RuntimeException e) {
                this.ledgerUndecodable.incrementAndGet();
                continue;
            }
            var s = this.sinks.get(new Key(c.streamHi(), c.streamLo()));
            if (s != null) {
                var credit = s.credit; // S2c PR 6: null unless the credit sub-flag is on
                long lower = s.offerFromLedger(c, credit);
                if (lower != Sink.SKIPPED) {
                    this.ledgerChunks.incrementAndGet();
                    try {
                        var l = s.listener;
                        if (l != null) {
                            l.run();
                        }
                    } finally {
                        if (credit != null) {
                            credit.lowerHeld(lower); // only after the drain published the flushed chunks (#248 r4 B3)
                        }
                    }
                }
            }
        }
        p.ack(READER_GROUP, offs, atts, null);
        return n;
    }

    int openSinks() {
        return this.sinks.size();
    }

    private void deliverLocal(StreamChunk c) {
        var s = this.sinks.get(new Key(c.streamHi(), c.streamLo()));
        if (s != null) {
            var credit = s.credit; // S2c PR 6: null unless the credit sub-flag is on
            long lower = s.offer(c, credit);
            if (lower < 0) {
                this.sinkDrops.incrementAndGet();
                s.dropped = true;
            }
            if (credit == null) {
                var l = s.listener;
                if (l != null) {
                    l.run(); // e.g. the HTTP leg drains into its session before the first GET
                }
                return;
            }
            try {
                var l = s.listener;
                if (l != null) {
                    l.run();
                }
            } finally {
                credit.lowerHeld(lower & ~Sink.DROPPED); // only after the drain published them (#248 r4 B3)
            }
        }
    }

    /**
     * A bounded, in-order, deduplicated queue of one stream's chunks for a same-process caller. Producers are the
     * worker threads of that stream (one at a time per attempt); one consumer calls {@link #poll}.
     */
    public static final class Sink implements AutoCloseable {
        private final TokenStreams owner;
        private final Key key;
        private final Config config;
        private TokenStream.Reorder reorder; // guarded by lock; replaced on RESET
        /** The attempt being delivered, -1 before the first chunk; guarded by lock (§12 reset rule). */
        private long attempt = -1;
        private final ArrayBlockingQueue<StreamChunk> queue;
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        private final AtomicLong conflicts = new AtomicLong();
        private final AtomicLong stale = new AtomicLong();
        private final AtomicLong resets = new AtomicLong();
        /** Set once a chunk for this stream could not be queued: the stream has a gap. */
        volatile boolean dropped;
        /** Run on the producer thread after every offer (set once by the consumer). */
        volatile Runnable listener;
        /**
         * S2c PR 6 credit state of the HTTP session reading this sink (set once by {@link StreamHttp} before the task
         * is submitted); null with the credit sub-flag off, and for in-process consumers.
         */
        volatile StreamCredit.State credit;
        /** {@link #offer(StreamChunk, StreamCredit.State)}: the chunk was not queued (sink full or reorder overflow). */
        static final long DROPPED = Long.MIN_VALUE;
        /** {@link #offerFromLedger(StreamChunk, StreamCredit.State)}: the chunk was skipped, not offered. */
        static final long SKIPPED = -1L;

        Sink(TokenStreams owner, Key key, Config config) {
            this.owner = owner;
            this.key = key;
            this.config = config;
            this.reorder = new TokenStream.Reorder(key.hi(), key.lo(), config.window());
            this.queue = new ArrayBlockingQueue<>(config.sinkChunks());
        }

        /**
         * False when the chunk could not be queued (sink full); duplicates, conflicts and stale attempts are not
         * failures. Reorder state is per {@code (streamId, attempt)} (§12 reset rule): a chunk of a newer attempt
         * discards everything buffered or queued for the older one, queues {@code RESET(attempt)} and restarts at
         * {@code chunkSeq} 0; a chunk of an older attempt is dropped and counted in {@link #staleChunks()}.
         */
        boolean offer(StreamChunk c) {
            return offer(c, null) >= 0;
        }

        /**
         * {@link #offer(StreamChunk)}, also keeping {@code credit}'s held counters when it is not null (S2c PR 6, #248 r4
         * B3, r5 C1): a {@code BUFFERED} chunk raises them here, under the sink lock; chunks that leave the reorder
         * buffer (a flush, or the discard of an older attempt on RESET) are not lowered here: their count and bytes,
         * captured under this lock, are returned packed ({@code chunks << 40 | bytes}) for the caller to subtract
         * after its listener (the session drain) returned. Never a store: a concurrent {@code BUFFERED} of the new
         * attempt is never wiped out. {@link #DROPPED} is or-ed in when the chunk was not queued.
         */
        long offer(StreamChunk c, StreamCredit.State credit) {
            this.lock.lock();
            try {
                long lower = 0;
                if (c.streamHi() == this.key.hi() && c.streamLo() == this.key.lo()) {
                    if (this.attempt < 0) {
                        this.attempt = c.attempt();
                    } else if (c.attempt() < this.attempt) {
                        this.stale.incrementAndGet();
                        return 0;
                    } else if (c.attempt() > this.attempt) {
                        if (credit != null) { // the discarded attempt's held chunks, subtracted after the drain
                            lower = (long) this.reorder.held() << 40 | credit.heldBytesLocked;
                            credit.heldBytesLocked = 0;
                        }
                        this.queue.clear();
                        this.reorder = new TokenStream.Reorder(this.key.hi(), this.key.lo(), this.config.window());
                        this.attempt = c.attempt();
                        this.dropped = false; // a gap of the discarded attempt no longer matters
                        this.queue.add(StreamChunk.reset(this.key.hi(), this.key.lo(), c.attempt()));
                        this.resets.incrementAndGet();
                    }
                }
                if (this.queue.remainingCapacity() < 1 + this.reorder.held()) {
                    return lower | DROPPED;
                }
                var out = new ArrayList<StreamChunk>(2);
                var r = this.reorder.offer(c, out);
                if (r == TokenStream.Reorder.Offer.CONFLICT) {
                    this.conflicts.incrementAndGet();
                } else if (r == TokenStream.Reorder.Offer.OVERFLOW) {
                    return lower | DROPPED;
                }
                if (credit != null) {
                    if (r == TokenStream.Reorder.Offer.BUFFERED) {
                        credit.heldChunks.incrementAndGet();
                        credit.heldBytes.addAndGet(c.size());
                        credit.heldBytesLocked += c.size();
                    } else if (out.size() > 1) { // out[0] is c; the rest left the reorder buffer
                        long b = 0;
                        for (int i = 1; i < out.size(); i++) {
                            b += out.get(i).size();
                        }
                        credit.heldBytesLocked -= b;
                        lower += (long) (out.size() - 1) << 40 | b;
                    }
                }
                this.queue.addAll(out);
                return lower;
            } finally {
                this.lock.unlock();
            }
        }

        /**
         * The shard reader's offer: a chunk this sink already has (pushed in-process by a local worker, same attempt and
         * {@code chunkSeq} already delivered or buffered) or of an older attempt is skipped silently, without counting
         * and without the capacity check; anything else goes through {@link #offer}. Returns true when it was offered
         * (the caller then runs the listener); a full sink is recorded by {@link #offer} as a drop.
         */
        boolean offerFromLedger(StreamChunk c) {
            return offerFromLedger(c, null) != SKIPPED;
        }

        /** {@link #offerFromLedger(StreamChunk)} with credit counters: {@link #SKIPPED}, or the held delta to lower. */
        long offerFromLedger(StreamChunk c, StreamCredit.State credit) {
            this.lock.lock();
            try {
                if (c.streamHi() != this.key.hi() || c.streamLo() != this.key.lo()) {
                    return SKIPPED;
                }
                if (this.attempt >= 0 && (c.attempt() < this.attempt
                        || c.attempt() == this.attempt && this.reorder.has(c.chunkSeq()))) {
                    return SKIPPED;
                }
                long lower = offer(c, credit);
                if (lower < 0) {
                    this.owner.sinkDrops.incrementAndGet();
                    this.dropped = true;
                }
                return lower & ~DROPPED;
            } finally {
                this.lock.unlock();
            }
        }

        /** The next in-order chunk, waiting up to {@code timeout}; null on timeout. */
        public StreamChunk poll(long timeout, TimeUnit unit) throws InterruptedException {
            return this.queue.poll(timeout, unit);
        }

        /** The next in-order chunk if one is queued, else null; never blocks. */
        public StreamChunk poll0() {
            return this.queue.poll();
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

        /** True once a chunk was dropped (sink full or reorder overflow): the consumer must report a gap. */
        public boolean dropped() {
            return this.dropped;
        }

        /** Called after every offer, on the producer thread; must be cheap and must not block. */
        public void onOffer(Runnable listener) {
            this.listener = listener;
        }

        public long conflicts() {
            return this.conflicts.get();
        }

        /** Chunks of an attempt older than the one being delivered, dropped (§12 reset rule). */
        public long staleChunks() {
            return this.stale.get();
        }

        /** {@code RESET} markers queued: times a newer attempt replaced the buffered one. */
        public long resets() {
            return this.resets.get();
        }

        /** The attempt being delivered, or -1 before the first chunk. */
        public long attempt() {
            this.lock.lock();
            try {
                return this.attempt;
            } finally {
                this.lock.unlock();
            }
        }

        @Override
        public void close() {
            this.owner.sinks.remove(this.key, this);
        }
    }
}
