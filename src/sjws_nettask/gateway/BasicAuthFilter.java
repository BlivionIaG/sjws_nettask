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
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * RFC 7617 HTTP Basic authentication. Credentials are hashed at compile time; the raw password
 * is not retained. A presented {@code user:password} is hashed once and compared with every
 * stored hash, so the compare does not stop on the first match.
 * <p>
 * Params: {@code users} (one {@code user:password} per line, or comma-separated when there is
 * no newline), and/or {@code users-file} (the same format, read through
 * {@link sjws_nettask.config.SecureKeyFile}). Exactly one of the two is required. {@code realm}
 * is optional (default {@code sjws}). A miss is 401 with
 * {@code WWW-Authenticate: Basic realm="...", charset="UTF-8"}.
 */
public final class BasicAuthFilter implements RequestFilter {

    static final int MAX_FILE_BYTES = 65_536;
    static final int MAX_USERS = 1_024;

    private final String challenge;
    private final byte[][] hashes;

    private BasicAuthFilter(String challenge, byte[][] hashes) {
        this.challenge = challenge;
        this.hashes = hashes;
    }

    public static RequestFilter create(MiddlewareSpec spec) {
        var users = spec.option("users");
        var file = spec.option("users-file");
        boolean hasUsers = users != null && !users.isBlank();
        boolean hasFile = file != null && !file.isBlank();
        if (hasUsers == hasFile) {
            throw new IllegalArgumentException("basic-auth needs users or users-file, not both");
        }
        var entries = hasFile ? readFile(file.trim()) : parseUsers(users, "users");
        var hashes = new byte[entries.size()][];
        for (int i = 0; i < entries.size(); i++) {
            hashes[i] = sha256(entries.get(i).getBytes(StandardCharsets.UTF_8));
        }
        return new BasicAuthFilter(challenge(spec.option("realm")), hashes);
    }

    @Override
    public Response filter(Request request, Chain next) {
        var presented = authorization(request);
        if (presented != null && matches(this.hashes, presented)) {
            return next.proceed(request);
        }
        var response = StaticFileHandler.error(401, request.headOnly());
        response.headers().set("www-authenticate", this.challenge);
        return response;
    }

    /**
     * @return UTF-8 {@code user:password} bytes when the request carries a well-formed Basic
     *         credential, otherwise null (a miss, not an exception)
     */
    static byte[] authorization(Request request) {
        var values = request.headers().values("authorization");
        if (values.isEmpty()) {
            return null;
        }
        var raw = values.get(0).strip();
        if (raw.length() < 6 || !raw.regionMatches(true, 0, "Basic", 0, 5)) {
            return null;
        }
        int i = 5;
        if (i < raw.length() && raw.charAt(i) != ' ' && raw.charAt(i) != '\t') {
            return null;
        }
        while (i < raw.length() && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t')) {
            i++;
        }
        if (i == raw.length()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(raw.substring(i));
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = -1;
        for (int c = 0; c < decoded.length; c++) {
            if (decoded[c] == ':') {
                colon = c;
                break;
            }
        }
        if (colon <= 0) {
            return null; // missing user or missing colon
        }
        return decoded;
    }

    private static boolean matches(byte[][] hashes, byte[] presented) {
        var dig = sha256(presented);
        boolean ok = false;
        for (var hash : hashes) {
            ok |= MessageDigest.isEqual(dig, hash);
        }
        return ok;
    }

    static String challenge(String realm) {
        if (realm == null || realm.isBlank()) {
            realm = "sjws";
        } else {
            realm = realm.trim();
        }
        for (int i = 0; i < realm.length(); i++) {
            char c = realm.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("basic-auth realm has a control character");
            }
        }
        var escaped = realm.replace("\\", "\\\\").replace("\"", "\\\"");
        return "Basic realm=\"" + escaped + "\", charset=\"UTF-8\"";
    }

    static java.util.List<String> parseUsers(String raw, String source) {
        var lines = raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0
                ? raw.split("\\r?\\n", -1)
                : raw.split(",", -1);
        var out = new ArrayList<String>();
        for (var line : lines) {
            var trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException(source + " entry needs user:password");
            }
            var user = line.substring(0, colon).trim();
            var password = line.substring(colon + 1); // keep significant whitespace
            if (user.isEmpty() || password.isEmpty()) {
                throw new IllegalArgumentException(source + " entry needs user:password");
            }
            out.add(user + ":" + password);
            if (out.size() > MAX_USERS) {
                throw new IllegalArgumentException(source + " has more than " + MAX_USERS + " users");
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(source + " has no users");
        }
        return out;
    }

    private static java.util.List<String> readFile(String path) {
        var bytes = sjws_nettask.config.SecureKeyFile.read(Path.of(path), "basic-auth users-file", MAX_FILE_BYTES);
        return parseUsers(new String(bytes, StandardCharsets.UTF_8), "users-file");
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

    /** Visible to tests that want to confirm the raw password is not what equality uses. */
    byte[][] hashes() {
        return Arrays.copyOf(this.hashes, this.hashes.length);
    }
}
