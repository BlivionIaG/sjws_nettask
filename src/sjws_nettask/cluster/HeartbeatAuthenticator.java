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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.KDF;
import javax.crypto.Mac;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 on each HA UDP heartbeat. The membership payload stays the 64-byte
 * {@link HeartbeatCodec} body. The datagram also carries a wall-clock timestamp, a monotonic
 * per-sender sequence, a per-boot epoch, a version, a key id, and the destination wire id:
 *
 * <pre>
 * [0, 1)     version
 * [1, 2)     key id (0 current, 1 previous)
 * [2, 66)    heartbeat body
 * [66, 74)   timestamp, epoch milliseconds, big-endian
 * [74, 82)   sequence, big-endian, starts at 1
 * [82, 90)   boot epoch, big-endian
 * [90, 92)   destination wire id, big-endian
 * [92, 124)  HMAC-SHA256
 * </pre>
 *
 * The MAC input is {@code "sjws-ha-hb\\0" || version || keyId || body || ts || seq || bootEpoch || dest}.
 * The HMAC key is HKDF-SHA256 of the cluster key with info {@code sjws-ha-heartbeat-v1}, so this
 * tag is not a tag for any other use of the same secret. The key id selects one key; a packet is
 * not tried against both.
 *
 * <p>Replay state is {@code (sender, bootEpoch, sequence, lastAcceptedMillis)}. A newer boot epoch
 * resets that sender's high-water mark, so a restarted node is accepted. An epoch later than the
 * packet timestamp, or later than the receiver's now, by more than {@link #WINDOW_MILLIS} is
 * dropped and does not update that state: a boot time cannot sit in the future, so one forged
 * epoch cannot lock a member out. A lower epoch is accepted only after the sender has been
 * silent for at least the membership dead interval, which is a clock step backwards across a
 * restart of a member the receiver already treats as dead. A timestamp older than this
 * process's boot is dropped, so a capture from before this process started is not replayed.
 * A destination other than this node is dropped. A sender that is not in the membership list
 * is dropped and is not inserted into the map. The map is then bounded by the membership list.
 */
public final class HeartbeatAuthenticator {

    public static final int VERSION = 1;
    public static final int KEY_CURRENT = 0;
    public static final int KEY_PREVIOUS = 1;
    public static final int BODY_BYTES = HeartbeatCodec.BYTES;
    public static final int BODY_OFFSET = 2;
    public static final int TS_OFFSET = BODY_OFFSET + BODY_BYTES;
    public static final int SEQ_OFFSET = TS_OFFSET + Long.BYTES;
    public static final int EPOCH_OFFSET = SEQ_OFFSET + Long.BYTES;
    public static final int DEST_OFFSET = EPOCH_OFFSET + Long.BYTES;
    public static final int MAC_OFFSET = DEST_OFFSET + Short.BYTES;
    public static final int MAC_LENGTH = 32;
    public static final int PACKET_BYTES = MAC_OFFSET + MAC_LENGTH;
    /** Inclusive skew window. A packet at exactly ±5 seconds is accepted. */
    public static final long WINDOW_MILLIS = 5_000L;

    private static final String HMAC = "HmacSHA256";
    private static final byte[] LABEL = "sjws-ha-hb\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HKDF_INFO = "sjws-ha-heartbeat-v1".getBytes(StandardCharsets.US_ASCII);
    private static final long WARN_EVERY_MILLIS = 5_000L;

    /** Why a datagram was dropped. {@link #rejected()} is the sum. */
    public enum Reason {
        MALFORMED,
        TAG,
        TIME,
        BODY,
        SEQ,
        EPOCH,
        DEST,
        MEMBER
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Mac currentMac;
    private final Mac previousMac;
    private final long bootEpoch;
    private final long deadAfterMillis;
    private final byte[] macBuf = new byte[MAC_LENGTH];
    private final byte[] tagBuf = new byte[MAC_LENGTH];
    private final byte[] body = new byte[BODY_BYTES];
    private final Map<Integer, Sender> last = new HashMap<>();
    private final AtomicInteger[] rejected = new AtomicInteger[Reason.values().length];
    private long sendSeq;
    private long lastTimeWarnMillis = Long.MIN_VALUE;

    public HeartbeatAuthenticator(ClusterKey key, Clock clock) {
        this(key, clock, 0L);
    }

    /**
     * @param deadAfterMillis silence after which a lower boot epoch is accepted. Zero never
     *        accepts a lower epoch. This is the membership dead interval.
     */
    public HeartbeatAuthenticator(ClusterKey key, Clock clock, long deadAfterMillis) {
        Objects.requireNonNull(key, "cluster key");
        Objects.requireNonNull(clock, "clock");
        if (deadAfterMillis < 0) {
            throw new IllegalArgumentException("deadAfter");
        }
        this.deadAfterMillis = deadAfterMillis;
        this.bootEpoch = clock.currentTimeMillis();
        this.currentMac = mac(derive(key.current()));
        this.previousMac = key.hasPrevious() ? mac(derive(key.previous())) : null;
        for (int i = 0; i < this.rejected.length; i++) {
            this.rejected[i] = new AtomicInteger();
        }
    }

    /** Boot-time millis captured from the clock when this authenticator was created. */
    public long bootEpoch() {
        return this.bootEpoch;
    }

    /** Senders currently tracked. Only members are inserted. */
    public int trackedSenders() {
        this.lock.lock();
        try {
            return this.last.size();
        } finally {
            this.lock.unlock();
        }
    }

    /** Drops replay state for wire ids that are no longer members. */
    public void retainMembers(int[] members, int count) {
        Objects.requireNonNull(members, "members");
        this.lock.lock();
        try {
            this.last.keySet().removeIf(id -> !contains(members, count, id));
        } finally {
            this.lock.unlock();
        }
    }

    /** Packets dropped for any reason. */
    public int rejected() {
        int sum = 0;
        for (var reason : Reason.values()) {
            sum += rejected(reason);
        }
        return sum;
    }

    public int rejected(Reason reason) {
        Objects.requireNonNull(reason, "reason");
        return this.rejected[reason.ordinal()].get();
    }

    /**
     * Writes an authenticated datagram. The sequence is the next value for this sender.
     * The boot epoch is this process's. The destination is the body's {@code to} wire id.
     * {@code dest} must hold {@link #PACKET_BYTES}.
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
            write(dest, body, nowMillis, seq, this.bootEpoch, wireAt(body, 6), KEY_CURRENT);
            writeMac(this.currentMac, dest);
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Checks the tag, the timestamp window, the destination, membership, the body, and the
     * per-sender epoch and sequence.
     *
     * @param selfWire this node's wire id; a packet for anyone else is dropped
     * @param members  wire ids allowed to update replay state; {@code count} is how many are live
     * @return the membership payload, or null when the packet is dropped
     */
    public Heartbeat open(byte[] packet, long nowMillis, int selfWire, int[] members, int count) {
        Objects.requireNonNull(members, "members");
        this.lock.lock();
        try {
            if (packet == null || packet.length != PACKET_BYTES || packet[0] != VERSION
                    || (packet[1] != KEY_CURRENT && packet[1] != KEY_PREVIOUS)) {
                return drop(Reason.MALFORMED);
            }
            var mac = packet[1] == KEY_CURRENT ? this.currentMac : this.previousMac;
            if (mac == null || !matches(mac, packet)) {
                return drop(Reason.TAG);
            }
            long timestamp = getLong(packet, TS_OFFSET);
            long delta = nowMillis - timestamp;
            if (delta > WINDOW_MILLIS || -delta > WINDOW_MILLIS || timestamp < this.bootEpoch) {
                warnSkew(nowMillis, delta);
                return drop(Reason.TIME);
            }
            System.arraycopy(packet, BODY_OFFSET, this.body, 0, BODY_BYTES);
            var beat = HeartbeatCodec.decode(this.body);
            if (beat == null) {
                return drop(Reason.BODY);
            }
            int dest = ((packet[DEST_OFFSET] & 0xFF) << 8) | (packet[DEST_OFFSET + 1] & 0xFF);
            if (dest != selfWire || beat.toWire() != selfWire) {
                return drop(Reason.DEST);
            }
            if (!contains(members, count, beat.fromWire())) {
                return drop(Reason.MEMBER);
            }
            long epoch = getLong(packet, EPOCH_OFFSET);
            long seq = getLong(packet, SEQ_OFFSET);
            if (seq <= 0) {
                return drop(Reason.SEQ);
            }
            // A boot epoch is a time, so it cannot lead the packet or the receiver by more than
            // the skew window. Reject before touching replay state.
            if (epochAfter(epoch, timestamp) || epochAfter(epoch, nowMillis)) {
                return drop(Reason.EPOCH);
            }
            var sender = this.last.get(beat.fromWire());
            if (sender == null || epoch > sender.epoch || lowerEpochAfterSilence(sender, epoch, nowMillis)) {
                this.last.put(beat.fromWire(), new Sender(epoch, seq, nowMillis));
                return beat;
            }
            if (epoch < sender.epoch) {
                return drop(Reason.EPOCH);
            }
            if (seq <= sender.seq) {
                return drop(Reason.SEQ);
            }
            sender.seq = seq;
            sender.seenMillis = nowMillis;
            return beat;
        } finally {
            this.lock.unlock();
        }
    }

    /** Test helper. Signs {@code body} with {@code key} at an explicit sequence, epoch, and key id. */
    static void seal(byte[] key, byte[] body, long nowMillis, long seq, long bootEpoch, int destWire, int keyId,
            byte[] dest) {
        Objects.requireNonNull(key, "key");
        if (body == null || body.length < BODY_BYTES || dest == null || dest.length < PACKET_BYTES || seq <= 0
                || (keyId != KEY_CURRENT && keyId != KEY_PREVIOUS)) {
            throw new IllegalArgumentException("heartbeat packet");
        }
        write(dest, body, nowMillis, seq, bootEpoch, destWire, keyId);
        writeMac(mac(derive(key)), dest);
    }

    /** True when {@code epoch} is later than {@code time} by more than {@link #WINDOW_MILLIS}. */
    private static boolean epochAfter(long epoch, long time) {
        long limit = time > Long.MAX_VALUE - WINDOW_MILLIS ? Long.MAX_VALUE : time + WINDOW_MILLIS;
        return epoch > limit;
    }

    /**
     * A member that has already been silent for the dead interval may come back with an earlier
     * boot epoch. Its clock stepped backwards across the restart. A live member is not reset.
     */
    private boolean lowerEpochAfterSilence(Sender sender, long epoch, long nowMillis) {
        if (this.deadAfterMillis <= 0 || epoch >= sender.epoch) {
            return false;
        }
        return nowMillis - sender.seenMillis >= this.deadAfterMillis;
    }

    private Heartbeat drop(Reason reason) {
        this.rejected[reason.ordinal()].incrementAndGet();
        return null;
    }

    private void warnSkew(long nowMillis, long delta) {
        if (nowMillis - this.lastTimeWarnMillis < WARN_EVERY_MILLIS) {
            return;
        }
        this.lastTimeWarnMillis = nowMillis;
        System.err.println("WARN: HA heartbeat dropped for clock skew (delta " + delta
                + " ms); synchronize clocks with NTP");
    }

    private static void write(byte[] dest, byte[] body, long nowMillis, long seq, long bootEpoch, int destWire,
            int keyId) {
        dest[0] = (byte) VERSION;
        dest[1] = (byte) keyId;
        System.arraycopy(body, 0, dest, BODY_OFFSET, BODY_BYTES);
        putLong(dest, TS_OFFSET, nowMillis);
        putLong(dest, SEQ_OFFSET, seq);
        putLong(dest, EPOCH_OFFSET, bootEpoch);
        dest[DEST_OFFSET] = (byte) (destWire >>> 8);
        dest[DEST_OFFSET + 1] = (byte) destWire;
    }

    private boolean matches(Mac mac, byte[] packet) {
        mac.reset();
        feed(mac, packet);
        try {
            mac.doFinal(this.macBuf, 0);
        } catch (ShortBufferException | IllegalStateException failed) {
            throw new IllegalStateException(failed);
        }
        System.arraycopy(packet, MAC_OFFSET, this.tagBuf, 0, MAC_LENGTH);
        return MessageDigest.isEqual(this.macBuf, this.tagBuf);
    }

    private static void writeMac(Mac mac, byte[] dest) {
        mac.reset();
        feed(mac, dest);
        try {
            mac.doFinal(dest, MAC_OFFSET);
        } catch (ShortBufferException | IllegalStateException failed) {
            throw new IllegalStateException(failed);
        }
    }

    /** Label, then the bytes the wire layout already stores in MAC order. */
    private static void feed(Mac mac, byte[] packet) {
        mac.update(LABEL);
        mac.update(packet, 0, MAC_OFFSET);
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

    /** HKDF-SHA256, info {@code sjws-ha-heartbeat-v1}, 32 bytes. Not a key for any other purpose. */
    private static byte[] derive(byte[] ikm) {
        try {
            var kdf = KDF.getInstance("HKDF-SHA256");
            var params = HKDFParameterSpec.ofExtract().addIKM(ikm).thenExpand(HKDF_INFO, MAC_LENGTH);
            return kdf.deriveData(params);
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static boolean contains(int[] members, int count, int wire) {
        int n = Math.min(count, members.length);
        for (int i = 0; i < n; i++) {
            if (members[i] == wire) {
                return true;
            }
        }
        return false;
    }

    private static int wireAt(byte[] body, int offset) {
        return ((body[offset] & 0xFF) << 8) | (body[offset + 1] & 0xFF);
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

    private static final class Sender {
        private final long epoch;
        private long seq;
        private long seenMillis;

        private Sender(long epoch, long seq, long seenMillis) {
            this.epoch = epoch;
            this.seq = seq;
            this.seenMillis = seenMillis;
        }
    }
}
