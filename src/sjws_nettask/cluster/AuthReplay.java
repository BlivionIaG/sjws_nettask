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

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Shared accept/reject rules for HMAC-authenticated cluster traffic: destination wire id,
 * membership, boot-epoch window, the 5 s lower-epoch silence floor, per-sender sequence
 * high-water marks, and constant-time tag compare. {@link HeartbeatAuthenticator} and
 * {@link TcpTransport} keep their own on-wire layouts and HKDF labels; both call this for the
 * common checks so a forged, replayed, misaddressed or non-member packet is refused the same way.
 */
final class AuthReplay {

    static final int KEY_CURRENT = 0;
    static final int KEY_PREVIOUS = 1;
    /** Inclusive skew window. Also the floor for accepting a lower boot epoch after silence. */
    static final long WINDOW_MILLIS = 5_000L;

    enum Decision {
        OK,
        DEST,
        MEMBER,
        EPOCH,
        SEQ
    }

    private final Map<Integer, Sender> last = new HashMap<>();

    /** Drop replay state for wire ids that fail {@code isMember}. */
    void retainMembers(IntPredicate isMember) {
        Objects.requireNonNull(isMember, "isMember");
        last.keySet().removeIf(id -> !isMember.test(id));
    }

    int trackedSenders() {
        return last.size();
    }

    /**
     * Checks destination, membership, epoch window, lower-epoch silence and sequence. On
     * {@link Decision#OK} the sender's high-water mark is updated.
     *
     * @param silenceMillis wait before a lower boot epoch is accepted; use
     *        {@link #WINDOW_MILLIS} when the caller has no separate dead interval
     */
    Decision accept(int fromWire, int destWire, int selfWire, long epoch, long seq, long nowMillis,
            long silenceMillis, IntPredicate isMember) {
        Objects.requireNonNull(isMember, "isMember");
        if (destWire != selfWire) {
            return Decision.DEST;
        }
        if (!isMember.test(fromWire)) {
            return Decision.MEMBER;
        }
        if (seq <= 0) {
            return Decision.SEQ;
        }
        if (epochAfter(epoch, nowMillis)) {
            return Decision.EPOCH;
        }
        var sender = last.get(fromWire);
        if (sender == null || epoch > sender.epoch || lowerEpochAfterSilence(sender, epoch, nowMillis, silenceMillis)) {
            last.put(fromWire, new Sender(epoch, seq, nowMillis));
            return Decision.OK;
        }
        if (epoch < sender.epoch) {
            return Decision.EPOCH;
        }
        if (seq <= sender.seq) {
            return Decision.SEQ;
        }
        sender.seq = seq;
        sender.seenMillis = nowMillis;
        return Decision.OK;
    }

    /** True when {@code epoch} is later than {@code time} by more than {@link #WINDOW_MILLIS}. */
    static boolean epochAfter(long epoch, long time) {
        long limit = time > Long.MAX_VALUE - WINDOW_MILLIS ? Long.MAX_VALUE : time + WINDOW_MILLIS;
        return epoch > limit;
    }

    static boolean keyIdOk(int keyId) {
        return keyId == KEY_CURRENT || keyId == KEY_PREVIOUS;
    }

    /** Constant-time compare of two MAC tags. */
    static boolean tagEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    private static boolean lowerEpochAfterSilence(Sender sender, long epoch, long nowMillis, long silenceMillis) {
        if (silenceMillis <= 0 || epoch >= sender.epoch) {
            return false;
        }
        long required = Math.max(silenceMillis, WINDOW_MILLIS);
        return nowMillis - sender.seenMillis >= required;
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
