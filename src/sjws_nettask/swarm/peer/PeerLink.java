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
package sjws_nettask.swarm.peer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.cluster.ClusterRpc;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.TcpTransport;
import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.ledger.Durability;

/**
 * One node's end of the swarm peer links (#236 option (a), PR 1): a {@link TcpTransport} in its own
 * {@link #DOMAIN} (frames of the Raft, HA or CP domains fail the tag check here, and these fail there), with
 * direct delivery on each connection's reader thread, plus the forwarded produce in both roles.
 *
 * <p><b>Forwarder.</b> {@link #produce} sends a write to the partition's owner and completes when the owner
 * answers. Writes to one {@code (owner, topic)} form a stream with its own random 64-bit producerId and seqs
 * 1, 2, 3, ...; at most {@link Limits#inflightPerProducer} are unanswered (beyond that, {@link Outcome#BUSY} at once,
 * well under the owner's 4096-seq window, so an unapplied seq cannot age out while it is retried). Go-back-N:
 * after {@link Outcome#OUT_OF_ORDER}, a {@code BUSY} from the owner, or no answer for
 * {@link Limits#requestTimeoutMs}, every unanswered write is resent in seq order (jittered exponential backoff,
 * 10 ms to 1 s); the owner's {@code (origin, producerId)} dedup turns resends of applied seqs into
 * {@code DUPLICATE} with the original offset. A write unanswered past its budget fails as {@link Outcome#TIMEOUT}
 * (outcome unknown: it may have been applied). Because the owner applies a stream strictly in order, any failure
 * of a write retires its stream and fails every write queued behind it; the next write starts a new stream.
 * {@link Limits#breakerTimeouts} consecutive timeouts to one owner open its breaker for
 * {@link Limits#breakerOpenMs}: writes fail at once with {@link Outcome#UNAVAILABLE}.
 *
 * <p><b>Owner.</b> A forwarded produce is applied with {@link Partition#produceFrom} under the sender's
 * <em>authenticated</em> wire id, the one its connection's certificate pins (never a payload field), to the
 * partition {@link Owner#partition} returns for that sender and topic. Nothing is served unless an {@link Owner}
 * allows it: this PR registers no swarm family (stream, reply and task forwarding come in later PRs, each with
 * its own lease and grant checks). At most {@link Limits#inboundInflight} writes per peer await durability;
 * beyond that, and for a producer over the owner's live-producer cap, the answer is a retryable {@code BUSY}.
 * Error answers carry a fixed outcome code only: no exception text, address or internal id crosses the wire.
 *
 * <p>Wire (inside the MACed frame; the frame group is the topic): PRODUCE {@code producerId:i64 seq:i64
 * durability:u8 payload}; ACK {@code producerId:i64 seq:i64 outcome:u8 offset:i64 expectedSeq:i64}.
 */
public final class PeerLink implements AutoCloseable {

    /** Domain-separated frames: label and HKDF info of the swarm peer links. */
    public static final TcpTransport.Domain DOMAIN = new TcpTransport.Domain("swarm-peer", "sjws-swarm-peer-frame\0",
            "sjws-swarm-peer-frame-v1", Limits.MAX_RECORD_BYTES + 1024);
    static final int PRODUCE = 1;
    static final int ACK = 2;
    private static final int PRODUCE_HEADER = 8 + 8 + 1;
    private static final int ACK_BYTES = 8 + 8 + 1 + 8 + 8;

    /**
     * What a forwarded write did. The first five are the owner's {@link Partition.ForwardOutcome}s; APPENDED through
     * OUTCOME_UNKNOWN are wire codes, the rest are decided by the sender.
     */
    public enum Outcome {
        APPENDED, DUPLICATE, TOO_OLD, OUT_OF_ORDER, BUSY,
        /** The owner does not serve this topic to this node. */
        FORBIDDEN,
        /**
         * Not appended: the owner could not apply it (opening the partition failed, or its ledger writer refused it
         * before apply). Fixed code; details stay in the owner's log. Nothing landed, so it may be retried.
         */
        FAILED,
        /**
         * The owner applied it but could not make it durable (its ledger writer failed after apply): it may or may
         * not survive, so it must not be resent as a new write. Every write behind it on the stream gets the same
         * answer (#258's outcome-unknown, never a refusal).
         */
        OUTCOME_UNKNOWN,
        /** Local: no answer within the write's budget; it may or may not have been applied. */
        TIMEOUT,
        /** Local: the owner's breaker is open, the owner is not a pinned peer, or the link is closed. */
        UNAVAILABLE,
        /** Local: the owner no longer has seqs it already acknowledged (it restarted without them). */
        LOST
    }

    /** {@code offset} is the partition offset for APPENDED and DUPLICATE, else -1. */
    public record Result(Outcome outcome, long offset) {
        public boolean ok() {
            return outcome == Outcome.APPENDED || outcome == Outcome.DUPLICATE;
        }

        /** The client-facing answer for this result; null when {@link #ok()}. See {@link Problem#of}. */
        public Problem problem() {
            return Problem.of(outcome);
        }
    }

    /**
     * How a failed forwarded write is reported to the client at the edge (#258's problem types). A write whose fate
     * is unknown is a typed 503 outcome-unknown and never a redirect, refusal or not-leader; a write that certainly
     * did not land is not-appended, 503 when it may be retried and 400 when it will never be accepted. No forwarded
     * outcome is a not-leader: the forward hop has no leader (the owner is chosen by the sender), so an edge keeps
     * its own not-leader 307/503 for its own leadership.
     */
    public record Problem(int status, String type, boolean retryable) {
        public static final String OUTCOME_UNKNOWN_TYPE = "https://sjws.dev/problems/outcome-unknown";
        public static final String NOT_APPENDED_TYPE = "https://sjws.dev/problems/not-appended";

        public static Problem of(Outcome outcome) {
            return switch (outcome) {
                case APPENDED, DUPLICATE -> null;
                // applied-not-durable, no answer in the budget, or past the owner's dedup window: may have landed
                case OUTCOME_UNKNOWN, TIMEOUT, TOO_OLD -> new Problem(503, OUTCOME_UNKNOWN_TYPE, false);
                // nothing landed and a later attempt can succeed (OUT_OF_ORDER only surfaces once rewinds ran out)
                case FAILED, BUSY, UNAVAILABLE, LOST, OUT_OF_ORDER -> new Problem(503, NOT_APPENDED_TYPE, true);
                // the owner will never take this write here (topic not served to this node, or over the size limit)
                case FORBIDDEN -> new Problem(400, NOT_APPENDED_TYPE, false);
            };
        }
    }

    /** Owner side: the partition {@code from} may write {@code topic} to, or null to refuse it ({@code FORBIDDEN}). */
    @FunctionalInterface
    public interface Owner {
        Partition partition(NodeId from, String topic) throws IOException;

        Owner NONE = (from, topic) -> null;
    }

    /**
     * Defaults per #236 R7: {@code sjws.swarm.peer.*}.
     *
     * @param inflightPerProducer unanswered writes per stream (256, at most 1024)
     * @param inboundInflight writes per peer awaiting durability on the owner (2048)
     * @param liveProducersPerPeer live forwarded producers per peer per partition on the owner (1024)
     * @param producerIdleMs idle time before such a producer may be evicted (1 h)
     * @param requestTimeoutMs no answer for this long triggers a go-back-N resend (2000)
     * @param maxRecordBytes largest forwarded payload, checked before sending and before applying
     * @param queue outbound frames per peer (1024)
     * @param breakerTimeouts consecutive timeouts that open an owner's breaker (5)
     * @param breakerOpenMs how long it stays open before writes are tried again (1000)
     */
    public record Limits(int inflightPerProducer, int inboundInflight, int liveProducersPerPeer, long producerIdleMs,
            long requestTimeoutMs, int maxRecordBytes, int queue, int breakerTimeouts, long breakerOpenMs) {
        public static final int MAX_RECORD_BYTES = 4 << 20;
        public static final Limits DEFAULT = new Limits(256, 2048, Partition.DEFAULT_LIVE_PRODUCERS,
                Partition.DEFAULT_PRODUCER_IDLE_MS, 2_000, 1 << 20, 1024, 5, 1_000);

        public Limits {
            if (inflightPerProducer < 1 || inflightPerProducer > 1024 || inboundInflight < 1 || liveProducersPerPeer < 1
                    || producerIdleMs < 0 || requestTimeoutMs < 1 || maxRecordBytes < 0
                    || maxRecordBytes > MAX_RECORD_BYTES || queue < 1 || breakerTimeouts < 1 || breakerOpenMs < 0) {
                throw new IllegalArgumentException("swarm peer limits");
            }
        }
    }

    private final PeerConfig config;
    private final Limits limits;
    private final Owner owner;
    private final TcpTransport transport;
    private final ClusterRpc rpc;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<StreamKey, Stream> streams = new ConcurrentHashMap<>();
    /** Per peer wire, fixed at open (the transport admits only pinned members). */
    private final Map<Integer, Breaker> breakers;
    private final Map<Integer, AtomicInteger> inbound;
    private final ConcurrentHashMap<Unsynced, CompletableFuture<Long>> unsynced = new ConcurrentHashMap<>();
    private final Thread ticker;
    private volatile boolean open = true;
    final AtomicLong forwarded = new AtomicLong();
    final AtomicLong resent = new AtomicLong();
    final AtomicLong rewinds = new AtomicLong();
    final AtomicLong timeouts = new AtomicLong();
    final AtomicLong inlineFailures = new AtomicLong();
    final AtomicLong served = new AtomicLong();
    final AtomicLong refused = new AtomicLong();
    final AtomicLong malformed = new AtomicLong();
    final AtomicLong[] answered = new AtomicLong[Outcome.values().length];

    private PeerLink(PeerConfig config, ClusterKey key, TcpTransport.SocketFactory sockets, Owner owner, Limits limits,
            long writeStallMillis) throws IOException {
        this.config = Objects.requireNonNull(config, "config");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.owner = Objects.requireNonNull(owner, "owner");
        for (int i = 0; i < answered.length; i++) {
            answered[i] = new AtomicLong();
        }
        var b = new java.util.HashMap<Integer, Breaker>();
        var in = new java.util.HashMap<Integer, AtomicInteger>();
        for (var n : config.nodes()) {
            if (!n.equals(config.self())) {
                b.put(n.wire(), new Breaker());
                in.put(n.wire(), new AtomicInteger());
            }
        }
        this.breakers = Map.copyOf(b);
        this.inbound = Map.copyOf(in);
        this.transport = TcpTransport.open(config.self().id(), config.members(), key, sjws_nettask.cluster.Clock.SYSTEM,
                limits.queue(), sockets, DOMAIN.withWriteStallMillis(writeStallMillis), true);
        this.rpc = transport.attach(config.self().id(), this::onFrame);
        this.ticker = Thread.ofVirtual().name("swarm-peer-tick-" + config.self().name()).start(this::tick);
    }

    /**
     * Opens this node's links. {@code sockets} must be {@link PeerSockets} (mTLS) whenever the map lists more than
     * one node; {@link PeerStartup} enforces that for configured deployments. Plain sockets are for in-process
     * tests and the loopback hop bench only.
     */
    public static PeerLink open(PeerConfig config, ClusterKey key, TcpTransport.SocketFactory sockets, Owner owner,
            Limits limits) throws IOException {
        return new PeerLink(config, key, sockets, owner, limits, limits.requestTimeoutMs());
    }

    /** Tests: {@code writeStallMillis} 0 turns the transport's write-stall bound off. */
    static PeerLink open(PeerConfig config, ClusterKey key, TcpTransport.SocketFactory sockets, Owner owner,
            Limits limits, long writeStallMillis) throws IOException {
        return new PeerLink(config, key, sockets, owner, limits, writeStallMillis);
    }

    public PeerConfig config() {
        return config;
    }

    /** The underlying transport, for its auth and drop counters. */
    public TcpTransport transport() {
        return transport;
    }

    /** Answers received (forwarder side) or sent (owner side) with {@code outcome}. */
    public long answered(Outcome outcome) {
        return answered[outcome.ordinal()].get();
    }

    // ---- forwarder ----

    /**
     * Forwards one write to {@code ownerNode}'s partition {@code topic}. Completes with the owner's answer, or a
     * local {@link Outcome}; never exceptionally.
     *
     * @param durability what the owner waits for before answering; null for the partition's default
     * @param budgetMs how long to keep resending before {@link Outcome#TIMEOUT} (at least one request timeout)
     */
    public CompletableFuture<Result> produce(String ownerNode, String topic, byte[] payload, Durability durability,
            long budgetMs) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(payload, "payload");
        var to = config.byName(ownerNode);
        if (!open || to == null || to.equals(config.self())) {
            return done(Outcome.UNAVAILABLE);
        }
        if (payload.length > limits.maxRecordBytes() || topic.isEmpty()
                || topic.getBytes(StandardCharsets.UTF_8).length > 255) {
            return done(Outcome.FORBIDDEN);
        }
        var breaker = breakers.get(to.wire());
        if (breaker.isOpen()) {
            return done(Outcome.UNAVAILABLE);
        }
        long now = System.nanoTime();
        long deadline = now + Math.max(budgetMs, limits.requestTimeoutMs()) * 1_000_000L;
        while (true) {
            var key = new StreamKey(to.wire(), topic);
            var stream = streams.get(key);
            if (stream == null) {
                var fresh = new Stream(to.id(), topic, nextProducerId());
                stream = streams.putIfAbsent(key, fresh);
                if (stream == null) {
                    stream = fresh;
                }
            }
            Pending p;
            stream.lock.lock();
            try {
                if (stream.retired) {
                    continue; // lost a race with retirement: the next pass makes a fresh stream
                }
                if (stream.pending.size() >= limits.inflightPerProducer()) {
                    return done(Outcome.BUSY);
                }
                p = new Pending(stream.nextSeq++, payload, durability, deadline);
                p.round = stream.round;
                stream.pending.put(p.seq, p);
                if (stream.pending.size() == 1) {
                    stream.progressNanos = now;
                }
                forwarded.incrementAndGet();
            } finally {
                stream.lock.unlock();
            }
            // B1: the inline write may block on this owner's socket, so it runs with no lock held. onAck (the
            // owner's reader thread) and the ticker take stream.lock, and blocking under it could stall every stream
            // or deadlock two nodes forwarding to each other. The socket write is bounded by the transport's
            // write-stall watchdog (requestTimeoutMs). Two producers racing on one stream may now put their frames
            // on the wire out of seq order; the owner answers OUT_OF_ORDER and the go-back-N rewind resends in order.
            // A frame sent after its stream retired is harmless: every retirement leaves the owner refusing it
            // (FORBIDDEN, OUT_OF_ORDER after LOST or FAILED), or the caller already has an outcome-unknown TIMEOUT.
            if (!stream.retired && !send(stream, p, true)) {
                failedFast(stream, p);
            }
            return p.future;
        }
    }

    /** N4: an inline write the transport dropped is resent after a short backoff, not after requestTimeoutMs. */
    private void failedFast(Stream stream, Pending p) {
        stream.lock.lock();
        try {
            if (!stream.retired && stream.pending.get(p.seq) == p && stream.retryAtNanos == 0) {
                long now = System.nanoTime();
                stream.retryAtNanos = now + backoffNanos(++stream.busy);
                stream.round++;
                stream.progressNanos = now;
                inlineFailures.incrementAndGet();
            }
        } finally {
            stream.lock.unlock();
        }
    }

    private long nextProducerId() {
        long id;
        do {
            id = random.nextLong();
        } while (id == 0);
        return id;
    }

    /** False when the transport dropped the frame (inline sends only report it; queued sends fail on timeout). */
    private boolean send(Stream stream, Pending p, boolean inline) {
        var body = ByteBuffer.allocate(PRODUCE_HEADER + p.payload.length).putLong(stream.producerId).putLong(p.seq)
                .put((byte) (p.durability == null ? 0 : p.durability.ordinal() + 1)).put(p.payload).array();
        var frame = new SimTransport.Frame(new GroupId(stream.topic), PRODUCE, body);
        if (inline) {
            return transport.sendNow(stream.owner, frame);
        }
        rpc.send(stream.owner, frame);
        return true;
    }

    private void onAck(NodeId from, String topic, byte[] body) {
        if (body.length != ACK_BYTES) {
            malformed.incrementAndGet();
            return;
        }
        var b = ByteBuffer.wrap(body);
        long producerId = b.getLong();
        long seq = b.getLong();
        int code = b.get() & 0xFF;
        long offset = b.getLong();
        long expected = b.getLong();
        if (code > Outcome.OUTCOME_UNKNOWN.ordinal()) {
            malformed.incrementAndGet();
            return;
        }
        var outcome = Outcome.values()[code];
        answered[code].incrementAndGet();
        var stream = streams.get(new StreamKey(from.wire(), topic));
        if (stream == null || stream.producerId != producerId) {
            return; // a retired stream's late answer
        }
        var answeredBy = breakers.get(from.wire());
        if (answeredBy != null) {
            answeredBy.success();
        }
        List<Pending> failed = null;
        stream.lock.lock();
        try {
            var p = stream.pending.get(seq);
            switch (outcome) {
                case APPENDED, DUPLICATE -> {
                    if (p != null) {
                        stream.pending.remove(seq);
                        stream.progressNanos = System.nanoTime();
                        p.future.complete(new Result(outcome, offset));
                    }
                }
                case OUT_OF_ORDER -> {
                    if (p != null && p.round == stream.round) {
                        if (expected < stream.pending.firstKey()) {
                            failed = retire(stream); // the owner forgot seqs it acknowledged
                            outcome = Outcome.LOST;
                        } else {
                            rewind(stream);
                        }
                    }
                }
                case BUSY -> {
                    if (p != null && p.round == stream.round) {
                        stream.retryAtNanos = System.nanoTime() + backoffNanos(++stream.busy);
                        stream.round++; // later answers of this round are stale; the ticker resends
                    }
                }
                default -> {
                    if (p != null) {
                        // TOO_OLD, FORBIDDEN, FAILED, OUTCOME_UNKNOWN: retire; everything behind gets the same answer
                        failed = retire(stream);
                    }
                }
            }
        } finally {
            stream.lock.unlock();
        }
        if (failed != null) {
            for (var f : failed) {
                f.future.complete(new Result(outcome, -1));
            }
        }
    }

    /** Lock held. Resends every unanswered write in seq order, as one new round. */
    private void rewind(Stream stream) {
        stream.round++;
        rewinds.incrementAndGet();
        for (var p : stream.pending.values()) {
            p.round = stream.round;
            resent.incrementAndGet();
            send(stream, p, false); // reader or ticker thread: never block on a socket
        }
        stream.progressNanos = System.nanoTime();
    }

    /** Lock held. Removes the stream; returns its unanswered writes for the caller to fail outside the lock. */
    private List<Pending> retire(Stream stream) {
        stream.retired = true;
        streams.remove(new StreamKey(stream.owner.wire(), stream.topic), stream);
        var out = new ArrayList<>(stream.pending.values());
        stream.pending.clear();
        return out;
    }

    private static long backoffNanos(int attempt) {
        long ms = Math.min(1_000L, 10L << Math.min(attempt - 1, 7));
        return (ms / 2 + ThreadLocalRandom.current().nextLong(ms / 2 + 1)) * 1_000_000L;
    }

    /** Timeouts, BUSY retries and budgets, checked every 10 ms. */
    private void tick() {
        while (open) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException closing) {
                return;
            }
            long now = System.nanoTime();
            long timeout = limits.requestTimeoutMs() * 1_000_000L;
            for (var stream : streams.values()) {
                List<Pending> expired = null;
                stream.lock.lock();
                try {
                    if (stream.retired || stream.pending.isEmpty()) {
                        continue;
                    }
                    if (stream.pending.firstEntry().getValue().deadlineNanos - now <= 0) {
                        timeouts.incrementAndGet();
                        breakers.get(stream.owner.wire()).timeout();
                        expired = retire(stream);
                    } else if (stream.retryAtNanos != 0 && now - stream.retryAtNanos >= 0) {
                        stream.retryAtNanos = 0;
                        rewind(stream);
                    } else if (stream.retryAtNanos == 0 && now - stream.progressNanos >= timeout) {
                        timeouts.incrementAndGet();
                        breakers.get(stream.owner.wire()).timeout();
                        stream.retryAtNanos = now + backoffNanos(++stream.busy);
                        stream.round++;
                        stream.progressNanos = now;
                    }
                } finally {
                    stream.lock.unlock();
                }
                if (expired != null) {
                    for (var f : expired) {
                        f.future.complete(new Result(Outcome.TIMEOUT, -1));
                    }
                }
            }
        }
    }

    // ---- owner ----

    private void onFrame(NodeId from, SimTransport.Frame frame) {
        switch (frame.type()) {
            case PRODUCE -> serve(from, frame.group().value(), frame.body());
            case ACK -> onAck(from, frame.group().value(), frame.body());
            default -> malformed.incrementAndGet();
        }
    }

    private void serve(NodeId from, String topic, byte[] body) {
        if (body.length < PRODUCE_HEADER) {
            malformed.incrementAndGet();
            return;
        }
        var b = ByteBuffer.wrap(body);
        long producerId = b.getLong();
        long seq = b.getLong();
        int d = b.get() & 0xFF;
        if (producerId == 0 || seq < 1 || d > Durability.values().length) {
            malformed.incrementAndGet();
            return;
        }
        var durability = d == 0 ? null : Durability.values()[d - 1];
        if (body.length - PRODUCE_HEADER > limits.maxRecordBytes()) {
            answer(from, topic, producerId, seq, Outcome.FORBIDDEN, -1, 0);
            return;
        }
        Partition partition;
        try {
            partition = owner.partition(from, topic);
        } catch (IOException | RuntimeException failed) {
            System.getLogger(PeerLink.class.getName()).log(System.Logger.Level.WARNING,
                    "swarm peer: opening {0} for {1} failed: {2}", topic, from.name(), failed.toString());
            answer(from, topic, producerId, seq, Outcome.FAILED, -1, 0);
            return;
        }
        if (partition == null) {
            refused.incrementAndGet();
            answer(from, topic, producerId, seq, Outcome.FORBIDDEN, -1, 0);
            return;
        }
        var waiting = inbound.get(from.wire());
        if (waiting == null || waiting.get() >= limits.inboundInflight()) {
            answer(from, topic, producerId, seq, Outcome.BUSY, -1, 0);
            return;
        }
        var payload = new byte[body.length - PRODUCE_HEADER];
        b.get(payload);
        Partition.ForwardedResult r;
        try {
            r = partition.produceFrom(from.wire(), producerId, seq, payload, durability, limits.liveProducersPerPeer(),
                    limits.producerIdleMs());
        } catch (RuntimeException failed) {
            System.getLogger(PeerLink.class.getName()).log(System.Logger.Level.WARNING,
                    "swarm peer: produce to {0} from {1} failed: {2}", topic, from.name(), failed.toString());
            answer(from, topic, producerId, seq, Outcome.FAILED, -1, 0);
            return;
        }
        served.incrementAndGet();
        var outcome = Outcome.values()[r.outcome().ordinal()];
        // A resend can arrive while the original append still waits for its durability: its DUPLICATE answer
        // must wait for the same future, or the sender would take a not-yet-durable write as durable.
        var key = new Unsynced(from.wire(), topic, producerId, seq);
        var durable = r.durable();
        if (r.outcome() == Partition.ForwardOutcome.DUPLICATE) {
            var original = unsynced.get(key);
            if (original != null) {
                durable = original;
            }
        }
        if (durable.isDone()) {
            answerDurable(from, topic, producerId, seq, outcome, r, durable);
            return;
        }
        waiting.incrementAndGet();
        boolean appended = r.outcome() == Partition.ForwardOutcome.APPENDED;
        if (appended) {
            unsynced.put(key, durable);
        }
        var wait = durable;
        durable.whenComplete((o, e) -> {
            waiting.decrementAndGet();
            if (appended) {
                unsynced.remove(key, wait);
            }
            answerDurable(from, topic, producerId, seq, outcome, r, wait);
        });
    }

    /**
     * The answer when a write's durability failed. An APPENDED or DUPLICATE write is already applied on the owner
     * (and visible to its consumers), so its fate is unknown, never a refusal (#258). Any other outcome never waited.
     */
    static Outcome afterDurabilityFailure(Partition.ForwardOutcome outcome) {
        return outcome == Partition.ForwardOutcome.APPENDED || outcome == Partition.ForwardOutcome.DUPLICATE
                ? Outcome.OUTCOME_UNKNOWN : Outcome.FAILED;
    }

    /** A forwarded append still waiting for its durability (bounded by {@link Limits#inboundInflight} per peer). */
    private record Unsynced(int origin, String topic, long producerId, long seq) { }

    private void answerDurable(NodeId from, String topic, long producerId, long seq, Outcome outcome,
            Partition.ForwardedResult r, CompletableFuture<Long> durable) {
        if (durable.isCompletedExceptionally()) {
            answer(from, topic, producerId, seq, afterDurabilityFailure(r.outcome()), -1, 0);
        } else {
            answer(from, topic, producerId, seq, outcome, r.offset(), r.expectedSeq());
        }
    }

    private void answer(NodeId to, String topic, long producerId, long seq, Outcome outcome, long offset, long expected) {
        answered[outcome.ordinal()].incrementAndGet();
        var body = ByteBuffer.allocate(ACK_BYTES).putLong(producerId).putLong(seq).put((byte) outcome.ordinal())
                .putLong(offset).putLong(expected).array();
        rpc.send(to, new SimTransport.Frame(new GroupId(topic), ACK, body));
    }

    private static CompletableFuture<Result> done(Outcome outcome) {
        return CompletableFuture.completedFuture(new Result(outcome, -1));
    }

    /** Unanswered writes across all streams (a gauge). */
    public int pending() {
        int n = 0;
        for (var s : streams.values()) {
            n += s.pending.size();
        }
        return n;
    }

    /** True while {@code node}'s breaker is open. */
    public boolean breakerOpen(String node) {
        var n = config.byName(node);
        var b = n == null ? null : breakers.get(n.wire());
        return b != null && b.isOpen();
    }

    @Override
    public void close() {
        open = false;
        ticker.interrupt();
        transport.close();
        for (var stream : streams.values()) {
            List<Pending> failed;
            stream.lock.lock();
            try {
                failed = retire(stream);
            } finally {
                stream.lock.unlock();
            }
            for (var f : failed) {
                f.future.complete(new Result(Outcome.UNAVAILABLE, -1));
            }
        }
    }

    private record StreamKey(int owner, String topic) {
    }

    private static final class Pending {
        final long seq;
        final byte[] payload;
        final Durability durability;
        final long deadlineNanos;
        final CompletableFuture<Result> future = new CompletableFuture<>();
        long round;

        Pending(long seq, byte[] payload, Durability durability, long deadlineNanos) {
            this.seq = seq;
            this.payload = payload;
            this.durability = durability;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private static final class Stream {
        final ReentrantLock lock = new ReentrantLock();
        final NodeId owner;
        final String topic;
        final long producerId;
        final TreeMap<Long, Pending> pending = new TreeMap<>();
        long nextSeq = 1;
        long round;
        long progressNanos;
        long retryAtNanos;
        int busy;
        volatile boolean retired; // read without the lock before an inline send (B1)

        Stream(NodeId owner, String topic, long producerId) {
            this.owner = owner;
            this.topic = topic;
            this.producerId = producerId;
        }
    }

    /** Opens after {@link Limits#breakerTimeouts} consecutive timeouts, for {@link Limits#breakerOpenMs}. */
    private final class Breaker {
        private final AtomicInteger consecutive = new AtomicInteger();
        private volatile long openUntilNanos;
        private volatile boolean opened;

        boolean isOpen() {
            return opened && System.nanoTime() - openUntilNanos < 0;
        }

        void timeout() {
            if (consecutive.incrementAndGet() >= limits.breakerTimeouts()) {
                openUntilNanos = System.nanoTime() + limits.breakerOpenMs() * 1_000_000L;
                opened = true;
                consecutive.set(0);
            }
        }

        void success() {
            consecutive.set(0);
        }
    }

    /** Exposed for {@link PeerStartup}'s summary line. */
    static Map<String, Long> counters(PeerLink link) {
        return Map.of("forwarded", link.forwarded.get(), "resent", link.resent.get(), "timeouts", link.timeouts.get(),
                "served", link.served.get(), "refused", link.refused.get());
    }
}
