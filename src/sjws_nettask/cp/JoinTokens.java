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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;

/**
 * One-shot join tokens, as a deterministic state machine applied from the replicated CP log.
 * <ul>
 * <li>{@link #issue} records a token: its id, the SHA-256 of its secret (never the secret) and an
 * expiry at most {@link #MAX_TTL_MILLIS} away. Only issued tokens can be used.</li>
 * <li>{@link #consume} burns the token on its first successful use and records the node it joined.
 * Every later presentation by another node, or after the token expired unused, is rejected.</li>
 * <li>A retry from the <b>same</b> node identity with the same token, for example after the response
 * was lost, does not re-accept the token: it returns the join already recorded (by node identity),
 * flagged {@link Result#replay()}, for at most {@link #REPLAY_GRACE_MILLIS} after the burn.</li>
 * </ul>
 * Callers evaluate with {@link #evaluate} first (read-only), then append a consume entry; the apply
 * runs the same decision again, so of two concurrent first uses exactly one wins. The CP apply
 * thread is the only writer, and the writes hold {@link #writeLock} so the check and the burn are one
 * atomic step even if apply were ever entered concurrently; request threads only read (concurrent maps).
 */
public final class JoinTokens {

    public static final long DEFAULT_TTL_MILLIS = 3_600_000L;
    public static final long MAX_TTL_MILLIS = 86_400_000L;
    /** How long a lost-response retry from the joined node gets its recorded join back. */
    public static final long REPLAY_GRACE_MILLIS = 600_000L;
    /** Issued tokens kept at once; expired ones are pruned first. */
    static final int MAX_TOKENS = 10_000;

    private final Map<String, Issued> tokens = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Join> byNode = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock writeLock = new java.util.concurrent.locks.ReentrantLock();

    /** Validates an issue request; null if it can be appended. */
    public static String checkIssue(String tokenId, byte[] secret, long ttlMillis) {
        if (tokenId == null || !tokenId.matches("[A-Za-z0-9._:-]{1,64}")) {
            return "token";
        }
        if (secret == null || secret.length < 16) {
            return "secret";
        }
        if (ttlMillis <= 0 || ttlMillis > MAX_TTL_MILLIS) {
            return "ttl";
        }
        return null;
    }

    /** Apply: records an issued token (an existing id is left unchanged). */
    public boolean issue(String tokenId, String secretHash, long expiresAtMillis, long nowMillis) {
        writeLock.lock();
        try {
            return issueLocked(tokenId, secretHash, expiresAtMillis, nowMillis);
        } finally {
            writeLock.unlock();
        }
    }

    private boolean issueLocked(String tokenId, String secretHash, long expiresAtMillis, long nowMillis) {
        if (!isHash(secretHash) || tokens.containsKey(tokenId)) {
            return false;
        }
        if (tokens.size() >= MAX_TOKENS) {
            tokens.values().removeIf(t -> t.consumedBy == null && nowMillis > t.expiresAtMillis);
            if (tokens.size() >= MAX_TOKENS) {
                return false;
            }
        }
        tokens.put(tokenId, new Issued(tokenId, secretHash, expiresAtMillis, null, 0L));
        return true;
    }

    /** Read-only decision for a presentation. {@link Result#join()} is null unless it is a replay. */
    public Result evaluate(String nodeId, String tokenId, String secretHash, long nowMillis) {
        if (nodeId == null || !nodeId.matches("[A-Za-z0-9._:-]{1,64}")) {
            return Result.rejected("identity");
        }
        if (tokenId == null || secretHash == null) {
            return Result.rejected("token");
        }
        var t = tokens.get(tokenId);
        if (t == null || !MessageDigest.isEqual(t.secretHash.getBytes(StandardCharsets.US_ASCII),
                secretHash.getBytes(StandardCharsets.US_ASCII))) {
            return Result.rejected("token");
        }
        if (t.consumedBy != null) {
            var join = byNode.get(nodeId);
            if (t.consumedBy.equals(nodeId) && join != null && join.tokenId.equals(tokenId)
                    && nowMillis <= t.consumedAtMillis + REPLAY_GRACE_MILLIS) {
                return Result.same(join);
            }
            return Result.rejected("used");
        }
        if (nowMillis > t.expiresAtMillis) {
            return Result.rejected("expired");
        }
        if (byNode.containsKey(nodeId)) {
            return Result.rejected("identity"); // already joined with another token
        }
        return Result.consumable();
    }

    /** Apply: burns the token if {@link #evaluate} still allows it; the outcome is deterministic. */
    public Result consume(String nodeId, String tokenId, String secretHash, long nowMillis) {
        writeLock.lock();
        try {
            return consumeLocked(nodeId, tokenId, secretHash, nowMillis);
        } finally {
            writeLock.unlock();
        }
    }

    private Result consumeLocked(String nodeId, String tokenId, String secretHash, long nowMillis) {
        var decision = evaluate(nodeId, tokenId, secretHash, nowMillis);
        if (!decision.accepted() || decision.replay()) {
            return decision;
        }
        var t = tokens.get(tokenId);
        tokens.put(tokenId, new Issued(tokenId, t.secretHash, t.expiresAtMillis, nodeId, nowMillis));
        var join = new Join(nodeId, tokenId, t.secretHash, t.expiresAtMillis);
        byNode.put(nodeId, join);
        return Result.created(join);
    }

    /** @return the join recorded for {@code nodeId}, or null */
    public Join joined(String nodeId) {
        return byNode.get(nodeId);
    }

    /**
     * True for a stored secret in its only accepted form, a lowercase hex SHA-256. Anything else (for
     * example a plaintext secret from an older log) is rejected on apply, fail closed: that node must
     * join again with a freshly issued token.
     */
    public static boolean isHash(String secretHash) {
        return secretHash != null && secretHash.matches("[0-9a-f]{64}");
    }

    public static String sha256(byte[] secret) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var raw = digest.digest(secret);
            try {
                return java.util.HexFormat.of().formatHex(raw);
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }


    /** Replace contents from another table (#341 install). */
    void replaceWith(JoinTokens other) {
        writeLock.lock();
        try {
            tokens.clear();
            byNode.clear();
            other.writeLock.lock();
            try {
                tokens.putAll(other.tokens);
                byNode.putAll(other.byNode);
            } finally {
                other.writeLock.unlock();
            }
        } finally {
            writeLock.unlock();
        }
    }

    /** Snapshot encode (#341): issued tokens then by-node joins. Replace-on-read. */
    void writeSnapshot(DataOutput out) throws IOException {
        writeLock.lock();
        try {
            out.writeInt(tokens.size());
            for (var e : tokens.values()) {
                writeStr(out, e.tokenId);
                writeStr(out, e.secretHash);
                out.writeLong(e.expiresAtMillis);
                writeStr(out, e.consumedBy == null ? "" : e.consumedBy);
                out.writeLong(e.consumedAtMillis);
            }
            out.writeInt(byNode.size());
            for (var j : byNode.values()) {
                writeStr(out, j.nodeId());
                writeStr(out, j.tokenId());
                writeStr(out, j.secretHash());
                out.writeLong(j.expiresAtMillis());
            }
        } finally {
            writeLock.unlock();
        }
    }

    /** Snapshot decode (#341): clears then loads. Caps match {@link #MAX_TOKENS}. */
    void readSnapshot(DataInput in) throws IOException {
        writeLock.lock();
        try {
            tokens.clear();
            byNode.clear();
            int n = in.readInt();
            if (n < 0 || n > MAX_TOKENS) {
                throw new IOException("join tokens: " + n + " issued");
            }
            for (int i = 0; i < n; i++) {
                var id = readStr(in, 64);
                var hash = readStr(in, 64);
                long exp = in.readLong();
                var consumedBy = readStr(in, 64);
                long consumedAt = in.readLong();
                if (!isHash(hash)) {
                    throw new IOException("join tokens: bad hash");
                }
                tokens.put(id, new Issued(id, hash, exp, consumedBy.isEmpty() ? null : consumedBy, consumedAt));
            }
            int m = in.readInt();
            if (m < 0 || m > MAX_TOKENS) {
                throw new IOException("join tokens: " + m + " joins");
            }
            for (int i = 0; i < m; i++) {
                var nodeId = readStr(in, 64);
                var tokenId = readStr(in, 64);
                var hash = readStr(in, 64);
                long exp = in.readLong();
                if (!isHash(hash)) {
                    throw new IOException("join tokens: bad join hash");
                }
                byNode.put(nodeId, new Join(nodeId, tokenId, hash, exp));
            }
        } finally {
            writeLock.unlock();
        }
    }

    private static void writeStr(DataOutput out, String s) throws IOException {
        var raw = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(raw.length);
        out.write(raw);
    }

    private static String readStr(DataInput in, int maxChars) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > maxChars * 4) { // UTF-8 upper bound
            throw new IOException("join tokens: string length " + len);
        }
        var raw = new byte[len];
        in.readFully(raw);
        var s = new String(raw, StandardCharsets.UTF_8);
        if (s.length() > maxChars) {
            throw new IOException("join tokens: string too long");
        }
        return s;
    }

    private record Issued(String tokenId, String secretHash, long expiresAtMillis, String consumedBy,
            long consumedAtMillis) {
    }

    public record Join(String nodeId, String tokenId, String secretHash, long expiresAtMillis) {
    }

    /**
     * @param join the recorded join (created or replayed), null when rejected or merely consumable
     * @param replay true when a retry from the joined node got its recorded join back
     * @param error rejection reason, null when accepted
     */
    public record Result(Join join, boolean replay, String error) {
        static Result created(Join join) {
            return new Result(join, false, null);
        }

        static Result same(Join join) {
            return new Result(join, true, null);
        }

        static Result consumable() {
            return new Result(null, false, null);
        }

        static Result rejected(String error) {
            return new Result(null, false, error);
        }

        public boolean accepted() {
            return error == null;
        }
    }
}
