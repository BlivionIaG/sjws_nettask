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
package sjws_nettask.swarm;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import sjws_nettask.config.SecureKeyFile;
import sjws_nettask.core.Request;

/**
 * Interim bearer-token {@link SwarmGate} until S2d. Tokens come from a file
 * ({@code -Dsjws.swarm.tokens.file} / {@code SJWS_SWARM_TOKENS_FILE}) or the {@code SJWS_SWARM_TOKENS} environment
 * variable, never from a command-line value. One agent per line: {@code <agentId> <token> <pool>[,<pool>...]}, where
 * pool {@code *} means every pool; {@code #} starts a comment; in the environment variable {@code ;} also separates
 * lines. Only SHA-256 digests of the tokens are kept, compared with {@link MessageDigest#isEqual} against every entry
 * (no early exit). Nothing here prints a token: errors name the line number only.
 */
public final class TokenGate implements SwarmGate {

    static final String FILE_PROPERTY = "sjws.swarm.tokens.file";
    static final String FILE_ENV = "SJWS_SWARM_TOKENS_FILE";
    static final String TOKENS_ENV = "SJWS_SWARM_TOKENS";
    static final String MAX_PROPERTY = "sjws.swarm.maxRequestBytes";
    static final String MAX_ENV = "SJWS_SWARM_MAX_REQUEST_BYTES";
    static final long DEFAULT_MAX = SwarmHttp.MAX_REQUEST;
    /** 32 random bytes in base64url without padding ({@code openssl rand -base64 32} gives 44 characters). */
    static final int MIN_TOKEN_CHARS = 43;
    /** Size cap of the tokens file. */
    static final int MAX_FILE_BYTES = 1 << 20;

    private record Entry(byte[] digest, Agent agent, Set<String> pools) { }

    private final Entry[] entries;
    private final long maxRequestBytes;

    TokenGate(List<String> lines, long maxRequestBytes) {
        if (maxRequestBytes < 1 || maxRequestBytes > SwarmHttp.MAX_REQUEST) {
            throw new IllegalArgumentException("swarm max request bytes must be in 1.." + SwarmHttp.MAX_REQUEST);
        }
        var out = new ArrayList<Entry>();
        var ids = new java.util.HashSet<String>();
        int n = 0;
        for (var raw : lines) {
            n++;
            int hash = raw.indexOf('#');
            var line = (hash < 0 ? raw : raw.substring(0, hash)).strip();
            if (line.isEmpty()) {
                continue;
            }
            var f = line.split("\\s+");
            if (f.length != 3) {
                throw new IllegalArgumentException("swarm tokens line " + n + ": expected <agentId> <token> <pools>");
            }
            if (f[1].length() < MIN_TOKEN_CHARS) {
                throw new IllegalArgumentException("swarm tokens line " + n + ": token shorter than " + MIN_TOKEN_CHARS);
            }
            if (!ids.add(f[0])) {
                throw new IllegalArgumentException("swarm tokens line " + n + ": duplicate agent id");
            }
            var pools = Set.of(f[2].split(","));
            if (pools.contains("")) {
                throw new IllegalArgumentException("swarm tokens line " + n + ": empty pool name");
            }
            out.add(new Entry(sha256(f[1]), new Agent(f[0]), pools));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("swarm tokens: no agent configured");
        }
        this.entries = out.toArray(Entry[]::new);
        this.maxRequestBytes = maxRequestBytes;
    }

    /**
     * The configured gate, or {@link SwarmGate#DENY_ALL} (with a warning) when no token source is set or it is
     * invalid. Fail closed: the swarm then answers 401 to every request.
     */
    static SwarmGate configured(Function<String, String> properties, Map<String, String> env, PrintStream err) {
        try {
            var file = first(properties.apply(FILE_PROPERTY), env.get(FILE_ENV));
            var inline = env.get(TOKENS_ENV);
            List<String> lines;
            if (file != null) {
                byte[] bytes;
                try {
                    // NOFOLLOW leaf, owner = this process, mode 0600/0400, secure parents, size cap (#152)
                    bytes = SecureKeyFile.read(Path.of(file), "swarm tokens file", MAX_FILE_BYTES);
                } catch (RuntimeException e) {
                    err.println("WARN: swarm: tokens file " + file + " refused (must be a regular file owned by this"
                            + " process, mode 0600 or 0400, in secure directories, at most " + MAX_FILE_BYTES
                            + " bytes); /swarm/v1 rejects every request");
                    return DENY_ALL;
                }
                lines = List.of(new String(bytes, StandardCharsets.UTF_8).split("\\R"));
            } else if (inline != null && !inline.isBlank()) {
                err.println("WARN: swarm: tokens from " + TOKENS_ENV + " are visible in /proc/<pid>/environ and"
                        + " inherited by child processes; prefer " + FILE_ENV + " (a 0600 file)");
                lines = List.of(inline.split("[;\\n]"));
            } else {
                err.println("WARN: swarm: no agent tokens configured (" + FILE_ENV + " or " + TOKENS_ENV
                        + "); /swarm/v1 rejects every request");
                return DENY_ALL;
            }
            var max = first(properties.apply(MAX_PROPERTY), env.get(MAX_ENV));
            var gate = new TokenGate(lines, max == null ? DEFAULT_MAX : Long.parseLong(max));
            err.println("swarm: token gate with " + gate.entries.length + " agent(s)");
            return gate;
        } catch (RuntimeException e) {
            // the message never contains a token (see the constructor); the exception class is enough
            err.println("WARN: swarm: agent tokens rejected (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage()
                    + "); /swarm/v1 rejects every request");
            return DENY_ALL;
        }
    }

    private static String first(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.strip();
        }
        return b == null || b.isBlank() ? null : b.strip();
    }

    @Override
    public Agent authenticate(Request request) {
        String token = null;
        for (var v : request.headers().values("authorization")) {
            if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
                token = v.substring(7).strip();
                break;
            }
        }
        if (token == null || token.isEmpty()) {
            return null;
        }
        var d = sha256(token);
        Agent found = null;
        for (var e : this.entries) {
            if (MessageDigest.isEqual(e.digest(), d) & found == null) {
                found = e.agent();
            }
        }
        return found;
    }

    @Override
    public boolean allows(Agent agent, String pool) {
        if (agent == null) {
            return false;
        }
        for (var e : this.entries) {
            if (e.agent().equals(agent)) {
                return e.pools().contains("*") || e.pools().contains(pool);
            }
        }
        return false;
    }

    /** An agent granted every pool ({@code *}) is the interim admin (S2c PR 5 cancel); S2d makes it a role. */
    @Override
    public boolean isAdmin(Agent agent) {
        if (agent == null) {
            return false;
        }
        for (var e : this.entries) {
            if (e.agent().equals(agent)) {
                return e.pools().contains("*");
            }
        }
        return false;
    }

    @Override
    public long maxRequestBytes() {
        return this.maxRequestBytes;
    }

    @Override
    public String toString() {
        return "TokenGate[agents=" + this.entries.length + ", maxRequestBytes=" + this.maxRequestBytes + "]";
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
