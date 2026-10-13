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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Join tokens are idempotent for one node identity until the TTL. A second presentation from that
 * identity is the same join. A different identity, or a presentation after the TTL, is rejected.
 */
public final class JoinTokens {

    public static final long DEFAULT_TTL_MILLIS = 3_600_000L;
    public static final long MAX_TTL_MILLIS = 86_400_000L;

    private final Map<String, Join> byNode = new LinkedHashMap<>();
    private final Map<String, String> tokenOwner = new LinkedHashMap<>();

    public Result present(String nodeId, String tokenId, byte[] secret, long nowMillis, long ttlMillis) {
        if (nodeId == null || !nodeId.matches("[A-Za-z0-9._:-]{1,64}")) {
            return Result.rejected("identity");
        }
        if (tokenId == null || tokenId.isBlank() || secret == null || secret.length < 16) {
            return Result.rejected("token");
        }
        if (ttlMillis <= 0 || ttlMillis > MAX_TTL_MILLIS) {
            return Result.rejected("ttl");
        }
        var hash = sha256(secret);
        var existing = byNode.get(nodeId);
        if (existing != null) {
            if (nowMillis > existing.expiresAtMillis) {
                return Result.rejected("expired");
            }
            if (!existing.tokenId.equals(tokenId) || !existing.secretHash.equals(hash)) {
                return Result.rejected("identity");
            }
            return Result.same(existing);
        }
        var owner = tokenOwner.get(tokenId);
        if (owner != null && !owner.equals(nodeId)) {
            return Result.rejected("identity");
        }
        long expires = nowMillis + ttlMillis;
        if (expires < nowMillis) {
            return Result.rejected("ttl");
        }
        var created = new Join(nodeId, tokenId, hash, expires);
        byNode.put(nodeId, created);
        tokenOwner.put(tokenId, nodeId);
        return Result.created(created);
    }

    private static String sha256(byte[] secret) {
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

    public record Join(String nodeId, String tokenId, String secretHash, long expiresAtMillis) {
    }

    public record Result(Join join, boolean replay, String error) {
        static Result created(Join join) {
            return new Result(join, false, null);
        }

        static Result same(Join join) {
            return new Result(join, true, null);
        }

        static Result rejected(String error) {
            return new Result(null, false, error);
        }

        public boolean accepted() {
            return error == null;
        }
    }
}
