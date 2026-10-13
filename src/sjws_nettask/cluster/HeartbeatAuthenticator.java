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
package sjws_nettask.cluster;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.Mac;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 on each HA UDP heartbeat. The membership payload stays the 64-byte
 * {@link HeartbeatCodec} body. The datagram appends a wall-clock timestamp, a monotonic
 * per-sender sequence, and a 32-byte tag:
 *
 * <pre>
 * [0, 64)   heartbeat body
 * [64, 72)  timestamp, epoch milliseconds, big-endian
 * [72, 80)  sequence, big-endian, starts at 1
 * [80, 112) HMAC-SHA256 over the first 80 bytes
 * </pre>
 *
 * A packet is dropped when the tag does not match the current or previous key, the timestamp
 * is outside {@link #WINDOW_MILLIS}, the body does not decode, or the sequence is not strictly
 * greater than the last accepted sequence from that sender. Dropped packets increment
 * {@link #rejected()} and are not delivered.
 */
public final class HeartbeatAuthenticator {

    public static final int BODY_BYTES = HeartbeatCodec.BYTES;
    public static final int TS_OFFSET = BODY_BYTES;
    public static final int SEQ_OFFSET = TS_OFFSET + Long.BYTES;
    public static final int MAC_OFFSET = SEQ_OFFSET + Long.BYTES;
    public static final int MAC_LENGTH = 32;
    public static final int PACKET_BYTES = MAC_OFFSET + MAC_LENGTH;
    /** Inclusive skew window. A packet at exactly ±5 seconds is accepted. */
    public static final long WINDOW_MILLIS = 5_000L;

    private static final String HMAC = "HmacSHA256";

    private final ReentrantLock lock = new ReentrantLock();
    private final Mac currentMac;
    private final Mac previousMac;
    private final byte[] macBuf = new byte[MAC_LENGTH];
    private final byte[] body = new byte[BODY_BYTES];
    private final Map<Integer, Long> lastSeq = new HashMap<>();
    private final AtomicInteger rejected = new AtomicInteger();
    private long sendSeq;

    public HeartbeatAuthenticator(ClusterKey key) {
        Objects.requireNonNull(key, "cluster key");
        this.currentMac = mac(key.current());
        this.previousMac = key.hasPrevious() ? mac(key.previous()) : null;
    }

    /** Packets whose tag, time, body, or sequence was refused. */
    public int rejected() {
        return this.rejected.get();
    }

    /**
     * Writes an authenticated datagram. The sequence is the next value for this sender.
     * {@code body} is the 64-byte membership payload. {@code dest} must hold {@link #PACKET_BYTES}.
     */
    public void seal(byte[] body, byte[] dest, long nowMillis) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(dest, "dest");
        if (body.length < BODY_BYTES || dest.length < PACKET_BYTES) {
            throw new IllegalArgumentException("heartbeat packet");
        }
        this.lock.lock();
        try {
            long seq = this.sendSeq + 1;
            this.sendSeq = seq;
            System.arraycopy(body, 0, dest, 0, BODY_BYTES);
            putLong(dest, TS_OFFSET, nowMillis);
            putLong(dest, SEQ_OFFSET, seq);
            writeMac(this.currentMac, dest);
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Checks the tag, the timestamp window, the body, and the per-sender sequence.
     *
     * @return the membership payload, or null when the packet is dropped
     */
    public Heartbeat open(byte[] packet, long nowMillis) {
        this.lock.lock();
        try {
            if (packet == null || packet.length != PACKET_BYTES || !tagOk(packet)) {
                this.rejected.incrementAndGet();
                return null;
            }
            long timestamp = getLong(packet, TS_OFFSET);
            long delta = nowMillis - timestamp;
            if (delta > WINDOW_MILLIS || -delta > WINDOW_MILLIS) {
                this.rejected.incrementAndGet();
                return null;
            }
            System.arraycopy(packet, 0, this.body, 0, BODY_BYTES);
            var beat = HeartbeatCodec.decode(this.body);
            if (beat == null) {
                this.rejected.incrementAndGet();
                return null;
            }
            long seq = getLong(packet, SEQ_OFFSET);
            long prev = this.lastSeq.getOrDefault(beat.fromWire(), 0L);
            if (seq <= prev) {
                this.rejected.incrementAndGet();
                return null;
            }
            this.lastSeq.put(beat.fromWire(), seq);
            return beat;
        } finally {
            this.lock.unlock();
        }
    }

    /** Test and rotation helper. Signs {@code body} with {@code key} at an explicit sequence. */
    static void seal(byte[] key, byte[] body, long nowMillis, long seq, byte[] dest) {
        Objects.requireNonNull(key, "key");
        if (body == null || body.length < BODY_BYTES || dest == null || dest.length < PACKET_BYTES || seq <= 0) {
            throw new IllegalArgumentException("heartbeat packet");
        }
        System.arraycopy(body, 0, dest, 0, BODY_BYTES);
        putLong(dest, TS_OFFSET, nowMillis);
        putLong(dest, SEQ_OFFSET, seq);
        writeMac(mac(key), dest);
    }

    private boolean tagOk(byte[] packet) {
        if (matches(this.currentMac, packet)) {
            return true;
        }
        return this.previousMac != null && matches(this.previousMac, packet);
    }

    private boolean matches(Mac mac, byte[] packet) {
        mac.reset();
        mac.update(packet, 0, MAC_OFFSET);
        try {
            mac.doFinal(this.macBuf, 0);
        } catch (ShortBufferException | IllegalStateException failed) {
            throw new IllegalStateException(failed);
        }
        int diff = 0;
        for (int i = 0; i < MAC_LENGTH; i++) {
            diff |= this.macBuf[i] ^ packet[MAC_OFFSET + i];
        }
        // Wipe the tag before returning so a later log of this buffer is not a reusable MAC.
        Arrays.fill(this.macBuf, (byte) 0);
        return diff == 0;
    }

    private static void writeMac(Mac mac, byte[] dest) {
        mac.reset();
        mac.update(dest, 0, MAC_OFFSET);
        try {
            mac.doFinal(dest, MAC_OFFSET);
        } catch (ShortBufferException | IllegalStateException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static Mac mac(byte[] key) {
        try {
            var mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac;
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static void putLong(byte[] dest, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            dest[offset + i] = (byte) value;
            value >>>= 8;
        }
    }

    private static long getLong(byte[] src, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (src[offset + i] & 0xFFL);
        }
        return value;
    }
}
