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
package sjws_nettask.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * API key check. Keys are hashed at compile time. A presented key is hashed once and compared
 * with every stored hash, so the compare does not stop on the first match. The raw keys are not
 * retained.
 * <p>
 * Params: {@code keys} (comma-separated) or {@code keys-file} (path to the same material, read
 * with {@link sjws_nettask.config.SecureKeyFile}; exactly one is required), {@code header}
 * (default {@code x-api-key}), {@code query} (optional query name; used only when the header is
 * absent). Query values are not percent-decoded. A control-plane proposal must use
 * {@code keys-file}; see {@link sjws_nettask.config.MiddlewareSecrets}.
 */
public final class ApiKeyFilter implements RequestFilter {

    private final String header;
    private final String query;
    private final byte[][] hashes;

    private ApiKeyFilter(String header, String query, byte[][] hashes) {
        this.header = header;
        this.query = query;
        this.hashes = hashes;
    }

    public static RequestFilter create(MiddlewareSpec spec) {
        var raw = sjws_nettask.config.MiddlewareSecrets.resolve(spec, "keys", "api-key");
        if (raw.isBlank()) {
            throw new IllegalArgumentException("api-key keys is required");
        }
        var parts = raw.split(",", -1);
        var hashes = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            var key = parts[i].trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("api-key keys has an empty entry");
            }
            hashes[i] = sha256(key.getBytes(StandardCharsets.UTF_8));
        }
        var header = spec.option("header");
        if (header == null || header.isBlank()) {
            header = "x-api-key";
        }
        header = header.trim().toLowerCase(java.util.Locale.ROOT);
        var query = spec.option("query");
        if (query != null) {
            query = query.trim();
            if (query.isEmpty()) {
                query = null;
            }
        }
        return new ApiKeyFilter(header, query, hashes);
    }

    @Override
    public Response filter(Request request, Chain next) {
        var presented = first(request, this.header);
        if (presented == null && this.query != null) {
            presented = query(request.target(), this.query);
        }
        if (presented != null && matches(this.hashes, presented.getBytes(StandardCharsets.UTF_8))) {
            return next.proceed(request);
        }
        var response = StaticFileHandler.error(401, request.headOnly());
        response.headers().set("www-authenticate", "ApiKey");
        return response;
    }

    private static String first(Request request, String name) {
        var values = request.headers().values(name);
        return values.isEmpty() ? null : values.get(0);
    }

    static String query(String target, String name) {
        int q = target.indexOf('?');
        if (q < 0) {
            return null;
        }
        int i = q + 1;
        int n = target.length();
        while (i < n) {
            int amp = target.indexOf('&', i);
            int end = amp < 0 ? n : amp;
            int eq = target.indexOf('=', i);
            if (eq >= i && eq < end && eq - i == name.length() && target.regionMatches(i, name, 0, name.length())) {
                int hash = target.indexOf('#', eq + 1);
                int valueEnd = end;
                if (hash >= 0 && hash < valueEnd) {
                    valueEnd = hash;
                }
                return target.substring(eq + 1, valueEnd);
            }
            if (amp < 0) {
                return null;
            }
            i = amp + 1;
        }
        return null;
    }

    private static boolean matches(byte[][] hashes, byte[] presented) {
        var dig = sha256(presented);
        boolean ok = false;
        for (var hash : hashes) {
            ok |= MessageDigest.isEqual(dig, hash);
        }
        return ok;
    }

    private static byte[] sha256(byte[] data) {
        var md = SHA256.get();
        md.reset();
        return md.digest(data);
    }

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    /** Visible to tests that want to confirm the raw key is not what equality uses. */
    byte[][] hashes() {
        return Arrays.copyOf(this.hashes, this.hashes.length);
    }
}
