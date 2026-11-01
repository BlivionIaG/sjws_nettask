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
package sjws_nettask.cp;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded Idempotency-Key used-key map for {@link CpStore} (#259). A deterministic function of the applied
 * {@code cp-config} log: every replica, and a node that restores a snapshot then applies the suffix, holds the same
 * table and therefore the same duplicate verdict.
 *
 * <p><b>Key.</b> The Idempotency-Key string (≤ {@link #MAX_KEY_BYTES} UTF-8 bytes). Value: the request fingerprint, the
 * recorded outcome, and the Raft index that first applied it.
 *
 * <p><b>Eviction.</b> Driven only by log state. On {@link #remember} at applied index {@code i}:
 * <ol>
 * <li>Drop every entry whose {@code appliedIndex <= i - }{@link #WINDOW} (a retry of an evicted key is unknown again
 * and is applied again; the table cannot tell an evicted key from a new one, so there is no {@code KeyExpired}).</li>
 * <li>If the map still exceeds {@link #MAX_KEYS}, drop the eldest by applied index (LinkedHashMap insertion order =
 * apply order). Ties at one index break by key bytes because one Raft index carries at most one IDEM wrapper.</li>
 * </ol>
 * Both {@link #WINDOW} and {@link #MAX_KEYS} are constants of the replicated state machine, not local startup
 * properties: they are written into every snapshot and {@link #read} refuses another window or cap. IDEM log entries
 * carry no principal, so the cap is global (a large default); a per-principal cap needs the principal on the wire.
 *
 * <p><b>Snapshot.</b> {@link #write} at index {@code s} emits exactly the entries that survive eviction at {@code s}
 * (appliedIndex &gt; s − WINDOW, then the cap), plus WINDOW, MAX_KEYS and the eviction counter. {@link #read} replaces
 * the caller's table; it does not merge.
 *
 * <p>Not thread-safe: owned by the group's delivery thread.
 */
public final class IdempotencyTable {

    /**
     * Replay window in log indexes: an entry applied at {@code i} is dropped once the applied index reaches
     * {@code i + WINDOW}. Generous so client retries inside a long outage still dedup; see docs/design/control-plane.md.
     */
    public static final long WINDOW = 100_000L;
    /**
     * Hard count cap. Global because IDEM entries carry no principal (#259 noisy-neighbour). Large enough that a
     * well-behaved operator stays inside the index window first.
     */
    public static final int MAX_KEYS = 100_000;
    /** Admission bound on the Idempotency-Key (UTF-8 byte length). */
    public static final int MAX_KEY_BYTES = 128;
    /**
     * Max UTF-8 bytes for a fingerprint on snapshot decode (#341 N3). Fingerprints store method/path/body
     * (#258); 512 KiB covers large YAML proposals while refusing a hostile 1 MiB-per-string image.
     */
    public static final int MAX_FINGERPRINT_BYTES = 512 * 1024;
    /** Max UTF-8 bytes for a recorded outcome body on snapshot decode (#341 N3). */
    public static final int MAX_BODY_BYTES = 512 * 1024;
    /** Encoding version; bump when the on-disk layout changes. */
    static final int FORMAT = 1;

    /** One remembered key: fingerprint and outcome as applied at {@code appliedIndex}. */
    public record Entry(String fingerprint, int status, String body, long appliedIndex) {
    }

    private final long window;
    private final int maxKeys;
    /** Insertion order = first-apply order; the eldest is the lowest appliedIndex. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private long evictions;
    /** Times {@link #remember} saw a key over {@link #MAX_KEY_BYTES} (#341 N2). Local; not in the snapshot. */
    private long oversizeSkipped;

    public IdempotencyTable() {
        this(WINDOW, MAX_KEYS);
    }

    /** Test and snapshot-restore sizes; production {@link CpStore} uses {@link #WINDOW} / {@link #MAX_KEYS}. */
    IdempotencyTable(long window, int maxKeys) {
        if (window < 1) {
            throw new IllegalArgumentException("window " + window);
        }
        if (maxKeys < 1) {
            throw new IllegalArgumentException("maxKeys " + maxKeys);
        }
        this.window = window;
        this.maxKeys = maxKeys;
    }

    long window() {
        return window;
    }

    int maxKeys() {
        return maxKeys;
    }

    int size() {
        return entries.size();
    }

    long evictions() {
        return evictions;
    }

    /** Oversize keys skipped by {@link #remember} (#341 N2). Not snapshotted. */
    long oversizeSkipped() {
        return oversizeSkipped;
    }

    /** The remembered entry for {@code key}, or null when unknown (never seen or evicted). */
    public Entry get(String key) {
        return entries.get(key);
    }

    public boolean contains(String key) {
        return entries.containsKey(key);
    }

    /**
     * Records {@code key} as first applied at {@code appliedIndex}. No-op when the key is already present (a second
     * IDEM wrapper for the same key: first applied wins). Keys over {@link #MAX_KEY_BYTES} UTF-8 are skipped
     * (counted, first logged) and are <b>not</b> deduplicated (#341 N2). Then evicts by window and by cap.
     */
    public void remember(String key, String fingerprint, int status, String body, long appliedIndex) {
        if (key == null) {
            throw new IllegalArgumentException("key");
        }
        if (appliedIndex < 1) {
            throw new IllegalArgumentException("appliedIndex " + appliedIndex);
        }
        // #341 N2: agree with admission and snapshot read — oversize keys are not stored, but eviction still runs.
        // Deterministic: every replica that applies the same log entry takes the same branch. The key is not
        // deduplicated (a later retry with a legal key is a new first apply). Counted; first skip is logged.
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            oversizeSkipped++;
            if (oversizeSkipped == 1L) {
                System.getLogger(IdempotencyTable.class.getName()).log(System.Logger.Level.WARNING,
                        "cp idempotency: skipped Idempotency-Key over " + MAX_KEY_BYTES
                                + " UTF-8 bytes at apply (not stored, not deduplicated); further skips are counted only");
            }
            evictThrough(appliedIndex);
            return;
        }
        if (entries.containsKey(key)) {
            evictThrough(appliedIndex);
            return;
        }
        entries.put(key, new Entry(fingerprint == null ? "" : fingerprint, status, body == null ? "" : body,
                appliedIndex));
        evictThrough(appliedIndex);
    }

    /** Drops entries with {@code appliedIndex <= appliedIndex - window}, then eldest until {@code size <= maxKeys}. */
    void evictThrough(long appliedIndex) {
        long floor = appliedIndex - window;
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().appliedIndex() <= floor) {
                it.remove();
                evictions++;
            } else {
                break; // LinkedHashMap eldest-first: later entries have higher-or-equal indexes
            }
        }
        while (entries.size() > maxKeys) {
            it = entries.entrySet().iterator();
            it.next();
            it.remove();
            evictions++;
        }
    }

    /** Replaces this table's contents with {@code other}'s (install). Window and cap must match. */
    void replaceWith(IdempotencyTable other) {
        if (other.window != window || other.maxKeys != maxKeys) {
            throw new IllegalArgumentException("idempotency table: window/cap " + other.window + "/" + other.maxKeys
                    + " != " + window + "/" + maxKeys);
        }
        entries.clear();
        entries.putAll(other.entries);
        evictions = other.evictions;
    }

    /** Writes format {@link #FORMAT}, eldest first. */
    public void write(DataOutput out) throws IOException {
        out.writeInt(FORMAT);
        out.writeLong(window);
        out.writeInt(maxKeys);
        out.writeLong(evictions);
        out.writeInt(entries.size());
        for (var e : entries.entrySet()) {
            writeString(out, e.getKey());
            writeString(out, e.getValue().fingerprint());
            out.writeInt(e.getValue().status());
            writeString(out, e.getValue().body());
            out.writeLong(e.getValue().appliedIndex());
        }
    }

    /** {@link #read} expecting production {@link #WINDOW} / {@link #MAX_KEYS}. */
    public static IdempotencyTable read(DataInput in) throws IOException {
        return read(in, WINDOW, MAX_KEYS);
    }

    /**
     * Reads a table written by {@link #write}. Refuses another format, window or cap (a table with another limit would
     * evict differently from its peers).
     */
    static IdempotencyTable read(DataInput in, long expectedWindow, int expectedMax) throws IOException {
        int format = in.readInt();
        long window = in.readLong();
        int max = in.readInt();
        if (format != FORMAT || window != expectedWindow || max != expectedMax) {
            throw new IOException("idempotency table: format " + format + " window " + window + " max " + max
                    + ", this build reads format " + FORMAT + " window " + expectedWindow + " max " + expectedMax);
        }
        var t = new IdempotencyTable(window, max);
        t.evictions = in.readLong();
        if (t.evictions < 0) {
            throw new IOException("idempotency table: negative evictions");
        }
        int size = in.readInt();
        if (size < 0 || size > max) {
            throw new IOException("idempotency table: " + size + " keys over the limit " + max);
        }
        long prevIndex = 0;
        for (int k = 0; k < size; k++) {
            var key = readString(in, MAX_KEY_BYTES);
            var fp = readString(in, MAX_FINGERPRINT_BYTES);
            int status = in.readInt();
            var body = readString(in, MAX_BODY_BYTES);
            long applied = in.readLong();
            if (key.isEmpty() || key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
                throw new IOException("idempotency table: bad key");
            }
            if (applied < 1 || applied < prevIndex) {
                throw new IOException("idempotency table: appliedIndex out of order");
            }
            if (t.entries.put(key, new Entry(fp, status, body, applied)) != null) {
                throw new IOException("idempotency table: duplicate key");
            }
            prevIndex = applied;
        }
        return t;
    }

    private static void writeString(DataOutput out, String s) throws IOException {
        var raw = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(raw.length);
        out.write(raw);
    }

    private static String readString(DataInput in, int maxBytes) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > maxBytes) {
            throw new IOException("idempotency table: string length " + len + " (max " + maxBytes + ")");
        }
        var raw = new byte[len];
        in.readFully(raw);
        return new String(raw, StandardCharsets.UTF_8);
    }
}
