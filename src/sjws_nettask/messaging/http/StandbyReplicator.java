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
package sjws_nettask.messaging.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.RecordBatch;

/**
 * Standby side of replication (durable-log-plan §3): pulls committed RecordBatch frames from the primary with the
 * ordinary fetch API and re-appends each batch (same records, same timestamp, so the same bytes at the same offsets)
 * to the local log with {@link Durability#GROUP}. Each fetch reports, with the shared replica token, how far the local
 * log is durable, which is what a primary's {@code replicated} produce waits for. Stops on divergence (a local batch
 * would land at a different offset than on the primary): that needs an operator, never a silent overwrite.
 */
final class StandbyReplicator implements AutoCloseable {

    /** One fetch from the primary. */
    record Fetched(int status, long nextOffset, byte[] frames) { }

    /** Fetches {@code topic} from {@code offset}, reporting {@code acked} (local durable end). */
    interface Fetcher {
        Fetched fetch(String topic, long offset, long acked) throws IOException, InterruptedException;
    }

    static final String TOKEN_HEADER = "x-sjws-replica-token";
    static final int FETCH_BYTES = 1 << 20;

    private final LogTopics local;
    private final Fetcher fetcher;
    private final List<String> topics;
    private final long idleParkNanos;
    private final Thread thread;
    private volatile boolean stop;
    private volatile String failure;

    StandbyReplicator(LogTopics local, Fetcher fetcher, List<String> topics, long idleParkNanos) {
        this.local = local;
        this.fetcher = fetcher;
        this.topics = List.copyOf(topics);
        this.idleParkNanos = idleParkNanos;
        this.thread = Thread.ofPlatform().name("messaging-standby").daemon().unstarted(this::run);
    }

    StandbyReplicator start() {
        this.thread.start();
        return this;
    }

    /** Why replication stopped (divergence, a bad frame), or null while it runs. */
    String failure() {
        return this.failure;
    }

    /** One pass over every topic; true if any batch was copied. Exposed for deterministic tests. */
    boolean pass() throws IOException, InterruptedException {
        boolean any = false;
        for (var topic : this.topics) any |= copy(topic);
        return any;
    }

    private boolean copy(String topic) throws IOException, InterruptedException {
        var l = this.local.get(topic, true);
        long end = l.writer().snapshot().nextOffset(); // durable: every local append waited for GROUP
        var f = this.fetcher.fetch(topic, end, end);
        if (f.status() == 404) return false; // nothing produced on the primary yet
        if (f.status() != 200) throw new IOException("primary fetch " + topic + "@" + end + ": HTTP " + f.status());
        if (f.frames().length == 0) return false;
        var in = ByteBuffer.wrap(f.frames());
        var pending = new ArrayList<CompletableFuture<Long>>();
        var expect = new ArrayList<Long>();
        long next = end;
        while (in.hasRemaining()) {
            int len = in.getInt(in.position());
            RecordBatch b;
            try {
                b = RecordBatch.decode(in.slice(in.position(), 4 + len));
            } catch (RecordBatch.CorruptBatchException e) {
                throw new IOException("corrupt frame from primary for " + topic, e);
            }
            in.position(in.position() + 4 + len);
            // checked before anything is appended: a batch that does not start at our end means the logs differ
            if (b.baseOffset() != next) throw new Diverged(topic + ": primary batch " + b.baseOffset() + " does not start at local end " + next);
            next = b.nextOffset();
            pending.add(l.writer().append(b.records(), Durability.GROUP, b.timestampMs()));
            expect.add(b.baseOffset());
        }
        for (int i = 0; i < pending.size(); i++) {
            long got;
            try {
                got = pending.get(i).get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                throw new IOException("standby append " + topic + ": " + e);
            }
            if (got != expect.get(i)) {
                throw new Diverged(topic + ": primary batch " + expect.get(i) + " landed at local " + got);
            }
        }
        // report the new durable end right away, so a waiting replicated produce is released
        this.fetcher.fetch(topic, l.writer().snapshot().nextOffset(), l.writer().snapshot().nextOffset());
        return true;
    }

    static final class Diverged extends IOException {
        Diverged(String m) { super(m); }
    }

    private void run() {
        while (!this.stop) {
            try {
                if (!pass()) LockSupport.parkNanos(this.idleParkNanos);
            } catch (Diverged e) {
                this.failure = "diverged: " + e.getMessage();
                System.err.println("ERROR: messaging standby stopped, " + this.failure);
                return;
            } catch (InterruptedException e) {
                return;
            } catch (IOException | RuntimeException e) {
                System.err.println("WARN: messaging standby: " + e);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500)); // primary down: retry
            }
        }
    }

    @Override
    public void close() {
        this.stop = true;
        this.thread.interrupt();
        try {
            this.thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Fetcher over HTTP: {@code GET {primary}/messaging/v1/topics/{t}/records?offset=&maxBytes=&replicaAcked=}. */
    static Fetcher http(String primaryBase, String token) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var base = primaryBase.endsWith("/") ? primaryBase.substring(0, primaryBase.length() - 1) : primaryBase;
        return (topic, offset, acked) -> {
            var req = HttpRequest.newBuilder(URI.create(base + MessagingHttp.PREFIX + topic + "/records?offset=" + offset
                    + "&maxBytes=" + FETCH_BYTES + "&replicaAcked=" + acked))
                    .timeout(Duration.ofSeconds(30)).header(TOKEN_HEADER, token).GET().build();
            var r = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            long next = r.headers().firstValueAsLong("next-offset").orElse(offset);
            return new Fetched(r.statusCode(), next, r.body());
        };
    }
}
