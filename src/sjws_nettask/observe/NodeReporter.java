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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Builds a {@link NodeReport} for the control-plane heartbeat. Call {@link #report(long)} from
 * that thread, not from a request. The default cadence is {@link #INTERVAL_NANOS} (5 s): a faster
 * membership loop (the HA design uses 500 ms) reuses the previous report instead of copying
 * counters again. There is no per-request hook here.
 * <p>
 * One caller. The cached report is published with a volatile write.
 */
public final class NodeReporter {

    /** Control payload interval. Not the membership heartbeat interval. */
    public static final long INTERVAL_NANOS = 5_000_000_000L;

    private final String nodeId;
    private final ServerMetrics metrics;
    private final LongSupplier appliedVersion;
    private final LongSupplier contentHash;
    private final BooleanSupplier alive;
    private final BooleanSupplier ready;
    private final BooleanSupplier draining;
    private final LongSupplier wallClockMillis;
    private final LabelSource labels;
    private final long intervalNanos;
    private final AtomicLong sequence = new AtomicLong();
    private volatile NodeReport cached;
    private long notBefore;

    private NodeReporter(Builder builder) {
        this.nodeId = builder.nodeId;
        this.metrics = builder.metrics;
        this.appliedVersion = builder.appliedVersion;
        this.contentHash = builder.contentHash;
        this.alive = builder.alive;
        this.ready = builder.ready;
        this.draining = builder.draining;
        this.wallClockMillis = builder.wallClockMillis;
        this.labels = builder.labels;
        this.intervalNanos = builder.intervalNanos;
    }

    public static Builder builder(String nodeId, ServerMetrics metrics) {
        return new Builder(nodeId, metrics);
    }

    /**
     * @param nowNanos {@link System#nanoTime()} on the heartbeat thread
     * @return the cached report when the cadence has not elapsed
     */
    public NodeReport report(long nowNanos) {
        var current = this.cached;
        if (current != null && nowNanos < this.notBefore) {
            return current;
        }
        var taken = take();
        this.notBefore = nowNanos + this.intervalNanos;
        this.cached = taken;
        return taken;
    }

    public NodeReport report() {
        return report(System.nanoTime());
    }

    private NodeReport take() {
        var snap = this.metrics.snapshot();
        long clientErrors = 0;
        long errors = 0;
        for (int status = 400; status < 500; status++) {
            clientErrors += snap.status(status);
        }
        for (int status = 500; status < 600; status++) {
            errors += snap.status(status);
        }
        return new NodeReport(this.nodeId, this.sequence.incrementAndGet(), this.wallClockMillis.getAsLong(),
                this.alive.getAsBoolean(), this.ready.getAsBoolean(), this.draining.getAsBoolean(),
                this.appliedVersion.getAsLong(), this.contentHash.getAsLong(), snap.requests(), errors, clientErrors,
                snap.bytesSent(), snap.latency().sumNanos(), snap.latency().counts(), this.labels.routes(),
                this.labels.apiKeys());
    }

    public static final class Builder {

        private final String nodeId;
        private final ServerMetrics metrics;
        private LongSupplier appliedVersion = () -> 0;
        private LongSupplier contentHash = () -> 0;
        private BooleanSupplier alive = () -> true;
        private BooleanSupplier ready = () -> true;
        private BooleanSupplier draining = () -> false;
        private LongSupplier wallClockMillis = System::currentTimeMillis;
        private LabelSource labels = LabelSource.NONE;
        private long intervalNanos = INTERVAL_NANOS;

        private Builder(String nodeId, ServerMetrics metrics) {
            if (nodeId == null || nodeId.isEmpty()) {
                throw new IllegalArgumentException("nodeId is empty");
            }
            this.nodeId = nodeId;
            this.metrics = Objects.requireNonNull(metrics, "metrics");
        }

        public Builder appliedVersion(LongSupplier version) {
            this.appliedVersion = Objects.requireNonNull(version, "version");
            return this;
        }

        public Builder contentHash(LongSupplier hash) {
            this.contentHash = Objects.requireNonNull(hash, "hash");
            return this;
        }

        public Builder alive(BooleanSupplier alive) {
            this.alive = Objects.requireNonNull(alive, "alive");
            return this;
        }

        public Builder ready(BooleanSupplier ready) {
            this.ready = Objects.requireNonNull(ready, "ready");
            return this;
        }

        public Builder draining(BooleanSupplier draining) {
            this.draining = Objects.requireNonNull(draining, "draining");
            return this;
        }

        public Builder wallClockMillis(LongSupplier clock) {
            this.wallClockMillis = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /** Per-route and per-API-key series. Null is the same as {@link LabelSource#NONE}. */
        public Builder labels(LabelSource labels) {
            this.labels = labels == null ? LabelSource.NONE : labels;
            return this;
        }

        /** @param nanos non-negative; 0 takes a fresh report on every call */
        public Builder intervalNanos(long nanos) {
            if (nanos < 0) {
                throw new IllegalArgumentException("interval is negative: " + nanos);
            }
            this.intervalNanos = nanos;
            return this;
        }

        public NodeReporter build() {
            return new NodeReporter(this);
        }
    }
}
