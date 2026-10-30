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
package sjws_nettask.routing;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Global backstop for proxied body bytes held in memory (security finding F5), with an optional
 * per-peer share so one client address cannot hold the whole budget (#78).
 *
 * <p>Every proxy exchange counts the bytes it read from an upstream and has not yet handed to
 * the client writer. Each exchange is already bounded by its read-ahead window
 * ({@link UpstreamPool#READ_AHEAD}, 256 KiB). On top of that, while {@link #exhausted(int)} is
 * true for that exchange's peer stripe, event loops stop reading ahead: autoRead is off and they
 * do not call {@code read()}. An exchange then reads again only once its own queue is empty, so
 * slow clients wait (TCP backpressure to the upstream) instead of growing the heap. Other peers
 * keep reading against the remaining global budget.
 *
 * <p><b>Worst-case overshoot</b> (the budget is soft). A {@code read()} is one socket read
 * ({@code maxMessagesPerRead = 1}) of at most {@link UpstreamPool#MAX_READ} (128 KiB), or
 * {@link UpstreamPool#SMALL_READ} (16 KiB) once the budget is {@link #pressured()} (at least 3/4
 * used). Steady state under pressure is therefore at most {@code limit + active × 16 KiB}. The
 * transient bound, for reads already issued when pressure began, is
 * {@code limit + active × 128 KiB}. "Active" means proxied responses being streamed: HTTP/1
 * connections plus h2/h3 streams. At 2048 of them, that is 64 MiB + 32 MiB steady and
 * 64 MiB + 256 MiB transient.
 *
 * <p>A streamed body that is dropped without being written is closed by the filter chain
 * ({@link sjws_nettask.core.Body#discard}). As a backstop, the idle timer abandons a paused
 * exchange whose consumer has not taken a chunk for twice {@code idle_read_timeout}.
 *
 * <p>The default global limit is 64 MiB, or {@code -Dsjws.proxy.maxInflightBytes=<bytes>}. The
 * per-peer share defaults to one eighth of that, or
 * {@code -Dsjws.proxy.maxInflightBytesPerPeer=<bytes>} ({@code 0} disables the table). Peers are
 * indexed by a fixed-size striped {@link AtomicLongArray} hashed from the remote address (not
 * {@code X-Forwarded-For}); collisions only make the cap stricter. One instance is shared by all
 * upstream pools in the process ({@link #shared()}). Tests make their own; the single-argument
 * constructor leaves the per-peer table off.
 */
public final class ByteBudget {
    private static final ByteBudget SHARED = sharedFromProperties();

    /** Stripes for per-peer counts; power of two. One extra slot is the keyless/unknown peer. */
    static final int PEER_BUCKETS = 4096;

    private final long limit;
    private final long peerLimit;
    private final AtomicLong inFlight = new AtomicLong();
    /** Null when per-peer accounting is off (zero cost on the add/release path). */
    private final AtomicLongArray peers;
    private final int peerMask;

    public ByteBudget(long limit) {
        this(limit, 0L);
    }

    /**
     * @param peerLimit max in-flight bytes per peer stripe, or {@code 0} to disable per-peer
     *        accounting (no table, no second charge)
     */
    public ByteBudget(long limit, long peerLimit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (peerLimit < 0) {
            throw new IllegalArgumentException("peerLimit must be >= 0");
        }
        this.limit = limit;
        this.peerLimit = peerLimit;
        if (peerLimit > 0) {
            this.peers = new AtomicLongArray(PEER_BUCKETS + 1);
            this.peerMask = PEER_BUCKETS - 1;
        } else {
            this.peers = null;
            this.peerMask = 0;
        }
    }

    private static ByteBudget sharedFromProperties() {
        long limit = Long.getLong("sjws.proxy.maxInflightBytes", 64L << 20);
        Long peerProp = Long.getLong("sjws.proxy.maxInflightBytesPerPeer");
        long peer = peerProp == null ? Math.max(1L, limit / 8) : peerProp;
        return new ByteBudget(limit, peer);
    }

    public static ByteBudget shared() {
        return SHARED;
    }

    public long limit() {
        return this.limit;
    }

    /** Per-peer share, or {@code 0} when the table is off. */
    public long peerLimit() {
        return this.peerLimit;
    }

    /** Bytes read from upstreams and not yet taken by a client writer (the F5 regression-test hook). */
    public long inFlight() {
        return this.inFlight.get();
    }

    /** In-flight bytes on one peer stripe (test hook); {@code 0} when per-peer is off. */
    public long peerInFlight(int stripe) {
        return this.peers == null ? 0L : this.peers.get(stripe);
    }

    /** Stripe for a client address; the keyless slot when the peer is unknown. */
    public int peerStripe(InetSocketAddress peer) {
        if (this.peers == null) {
            return 0;
        }
        if (peer == null || peer.getAddress() == null) {
            return keylessStripe();
        }
        return peerStripe(peer.getAddress());
    }

    /** Stripe for a resolved address. */
    public int peerStripe(InetAddress address) {
        if (this.peers == null) {
            return 0;
        }
        if (address == null) {
            return keylessStripe();
        }
        return (int) mix(hash(address.getAddress())) & this.peerMask;
    }

    /** Slot shared by exchanges with no peer address. */
    public int keylessStripe() {
        return this.peers == null ? 0 : this.peerMask + 1;
    }

    /** At least three quarters of the global budget used: upstream reads shrink to {@link UpstreamPool#SMALL_READ}. */
    boolean pressured() {
        return this.inFlight.get() >= this.limit - (this.limit >> 2);
    }

    /** Global pressure, or this peer stripe is at least three quarters of its share. */
    boolean pressured(int stripe) {
        if (pressured()) {
            return true;
        }
        return this.peers != null && this.peers.get(stripe) >= this.peerLimit - (this.peerLimit >> 2);
    }

    boolean exhausted() {
        return this.inFlight.get() >= this.limit;
    }

    /** Global budget full, or this peer stripe is at its share. */
    boolean exhausted(int stripe) {
        if (exhausted()) {
            return true;
        }
        return this.peers != null && this.peers.get(stripe) >= this.peerLimit;
    }

    void add(long bytes) {
        add(bytes, keylessStripe());
    }

    void add(long bytes, int stripe) {
        this.inFlight.addAndGet(bytes);
        if (this.peers != null) {
            this.peers.addAndGet(stripe, bytes);
        }
    }

    void release(long bytes) {
        release(bytes, keylessStripe());
    }

    void release(long bytes, int stripe) {
        this.inFlight.addAndGet(-bytes);
        if (this.peers != null) {
            this.peers.addAndGet(stripe, -bytes);
        }
    }

    /** SplitMix64 finalizer: spreads adjacent addresses over the table. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static long hash(byte[] address) {
        long h = 0xcbf29ce484222325L;
        for (byte b : address) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }
}
