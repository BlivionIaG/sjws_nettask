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
package sjws_nettask;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server settings: the complete field set of the merged plan (S1 superset contract).
 * Fields marked "inert" are accepted and carried but not used yet; their defaults keep the
 * current behaviour. {@link #parse} is pure (no I/O); W1 extends it with the h3/tls flags.
 * Precedence: defaults &lt; command line &lt; environment.
 *
 * <pre>
 * [port] [dir] [--max-connections=N] [--idle-timeout=S] [--header-timeout=S]
 * WEB_SERVER_PORT  WEB_SERVER_DIR  WEB_SERVER_MAX_CONNECTIONS  WEB_SERVER_IDLE_TIMEOUT  WEB_SERVER_HEADER_TIMEOUT
 * </pre>
 *
 * @param port TCP port (0 = ephemeral)
 * @param root document root
 * @param maxConnections HTTP/1 connections served concurrently; extra ones wait in the backlog
 * @param idleTimeout keep-alive idle timeout (also the QUIC max idle timeout)
 * @param headerTimeout time allowed to receive a complete request head (slowloris)
 * @param writeTimeout inert until S4: close a connection whose writes make no progress for this long
 * @param h3Enabled inert until S2: start the HTTP/3 (QUIC) listener
 * @param h3Port UDP port for HTTP/3; {@link #H3_PORT_SAME_AS_TCP} (-1) = same as {@code port}, 0 = ephemeral
 * @param h3Host UDP bind address for HTTP/3
 * @param h3MaxConnections inert until S2: concurrent QUIC connections
 * @param tlsCert PEM certificate chain (null = self-signed for development)
 * @param tlsKey PKCS#8 PEM private key (must be set together with tlsCert)
 * @param tlsKeyPassword key password, or null (masked in {@link #toString()})
 * @param altSvcMaxAge inert until S2: {@code ma} of the Alt-Svc header advertising HTTP/3
 * @param cacheBytes inert until S3: small-file cache budget in bytes (0 = disabled)
 * @param accessLog inert: "-" for stdout, a file path, or null for off
 */
public record ServerConfig(int port, Path root, int maxConnections, Duration idleTimeout, Duration headerTimeout,
        Duration writeTimeout, boolean h3Enabled, int h3Port, String h3Host, int h3MaxConnections, Path tlsCert,
        Path tlsKey, String tlsKeyPassword, Duration altSvcMaxAge, long cacheBytes, String accessLog) {

    public static final int DEFAULT_PORT = CONSTANTS.DEFAULT_PORT;
    /** Design review M11: 64 KB output buffer per connection, so 2048 x 64 KB = 128 MB worst case. */
    public static final int DEFAULT_MAX_CONNECTIONS = 2048;
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_HEADER_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);
    public static final int H3_PORT_SAME_AS_TCP = -1;
    public static final String DEFAULT_H3_HOST = "0.0.0.0";
    public static final int DEFAULT_H3_MAX_CONNECTIONS = 1024;
    public static final Duration DEFAULT_ALT_SVC_MAX_AGE = Duration.ofSeconds(86_400);
    public static final long DEFAULT_CACHE_BYTES = 64L * 1024 * 1024;

    public ServerConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (h3Port < H3_PORT_SAME_AS_TCP || h3Port > 65535) {
            throw new IllegalArgumentException("h3Port out of range: " + h3Port);
        }
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(h3Host, "h3Host");
        if (maxConnections < 1 || h3MaxConnections < 1) {
            throw new IllegalArgumentException("connection limits must be positive");
        }
        for (var d : new Duration[] {idleTimeout, headerTimeout, writeTimeout}) {
            if (d.toMillis() < 1 || d.toMillis() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("timeouts must be between 1 ms and ~24 days");
            }
        }
        if (altSvcMaxAge.isNegative()) {
            throw new IllegalArgumentException("altSvcMaxAge must not be negative");
        }
        if (cacheBytes < 0) {
            throw new IllegalArgumentException("cacheBytes must not be negative");
        }
        if ((tlsCert == null) != (tlsKey == null)) {
            throw new IllegalArgumentException("tlsCert and tlsKey must be given together");
        }
    }

    /** @return the UDP port HTTP/3 binds to (resolving {@link #H3_PORT_SAME_AS_TCP}) */
    public int effectiveH3Port() {
        return this.h3Port == H3_PORT_SAME_AS_TCP ? this.port : this.h3Port;
    }

    public static Builder builder(Path root) {
        return new Builder(root);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static ServerConfig defaults(Path root) {
        return builder(root).build();
    }

    public static ServerConfig defaults(String root) {
        return defaults(Path.of(root));
    }

    public ServerConfig withPort(int newPort) {
        return toBuilder().port(newPort).build();
    }

    public ServerConfig withMaxConnections(int max) {
        return toBuilder().maxConnections(max).build();
    }

    public ServerConfig withTimeouts(Duration idle, Duration header) {
        return toBuilder().idleTimeout(idle).headerTimeout(header).build();
    }

    @Override
    public String toString() {
        return "ServerConfig[port=" + port + ", root=" + root + ", maxConnections=" + maxConnections
                + ", idleTimeout=" + idleTimeout + ", headerTimeout=" + headerTimeout + ", writeTimeout=" + writeTimeout
                + ", h3Enabled=" + h3Enabled + ", h3Port=" + h3Port + ", h3Host=" + h3Host
                + ", h3MaxConnections=" + h3MaxConnections + ", tlsCert=" + tlsCert + ", tlsKey=" + tlsKey
                + ", tlsKeyPassword=" + (tlsKeyPassword == null ? "null" : "****") + ", altSvcMaxAge=" + altSvcMaxAge
                + ", cacheBytes=" + cacheBytes + ", accessLog=" + accessLog + "]";
    }

    /** Mutable builder; new fields get a default here so existing callers keep compiling. */
    public static final class Builder {

        private int port = DEFAULT_PORT;
        private Path root;
        private int maxConnections = DEFAULT_MAX_CONNECTIONS;
        private Duration idleTimeout = DEFAULT_IDLE_TIMEOUT;
        private Duration headerTimeout = DEFAULT_HEADER_TIMEOUT;
        private Duration writeTimeout = DEFAULT_WRITE_TIMEOUT;
        private boolean h3Enabled;
        private int h3Port = H3_PORT_SAME_AS_TCP;
        private String h3Host = DEFAULT_H3_HOST;
        private int h3MaxConnections = DEFAULT_H3_MAX_CONNECTIONS;
        private Path tlsCert;
        private Path tlsKey;
        private String tlsKeyPassword;
        private Duration altSvcMaxAge = DEFAULT_ALT_SVC_MAX_AGE;
        private long cacheBytes = DEFAULT_CACHE_BYTES;
        private String accessLog;

        private Builder(Path root) {
            this.root = root;
        }

        private Builder(ServerConfig c) {
            this.port = c.port;
            this.root = c.root;
            this.maxConnections = c.maxConnections;
            this.idleTimeout = c.idleTimeout;
            this.headerTimeout = c.headerTimeout;
            this.writeTimeout = c.writeTimeout;
            this.h3Enabled = c.h3Enabled;
            this.h3Port = c.h3Port;
            this.h3Host = c.h3Host;
            this.h3MaxConnections = c.h3MaxConnections;
            this.tlsCert = c.tlsCert;
            this.tlsKey = c.tlsKey;
            this.tlsKeyPassword = c.tlsKeyPassword;
            this.altSvcMaxAge = c.altSvcMaxAge;
            this.cacheBytes = c.cacheBytes;
            this.accessLog = c.accessLog;
        }

        public Builder port(int v) { this.port = v; return this; }
        public Builder root(Path v) { this.root = v; return this; }
        public Builder maxConnections(int v) { this.maxConnections = v; return this; }
        public Builder idleTimeout(Duration v) { this.idleTimeout = v; return this; }
        public Builder headerTimeout(Duration v) { this.headerTimeout = v; return this; }
        public Builder writeTimeout(Duration v) { this.writeTimeout = v; return this; }
        public Builder h3Enabled(boolean v) { this.h3Enabled = v; return this; }
        public Builder h3Port(int v) { this.h3Port = v; return this; }
        public Builder h3Host(String v) { this.h3Host = v; return this; }
        public Builder h3MaxConnections(int v) { this.h3MaxConnections = v; return this; }
        public Builder tlsCert(Path v) { this.tlsCert = v; return this; }
        public Builder tlsKey(Path v) { this.tlsKey = v; return this; }
        public Builder tlsKeyPassword(String v) { this.tlsKeyPassword = v; return this; }
        public Builder altSvcMaxAge(Duration v) { this.altSvcMaxAge = v; return this; }
        public Builder cacheBytes(long v) { this.cacheBytes = v; return this; }
        public Builder accessLog(String v) { this.accessLog = v; return this; }

        public ServerConfig build() {
            return new ServerConfig(port, root, maxConnections, idleTimeout, headerTimeout, writeTimeout, h3Enabled,
                    h3Port, h3Host, h3MaxConnections, tlsCert, tlsKey, tlsKeyPassword, altSvcMaxAge, cacheBytes,
                    accessLog);
        }
    }

    /** Result of {@link #parse}: the config plus human-readable warnings for ignored values. */
    public record Parsed(ServerConfig config, List<String> warnings) {
    }

    public static Parsed parse(String[] args, Map<String, String> env) {
        var warnings = new ArrayList<String>();
        int port = DEFAULT_PORT;
        String dir = CONSTANTS.DEFAULT_HTML_PATH;
        int max = DEFAULT_MAX_CONNECTIONS;
        long idle = DEFAULT_IDLE_TIMEOUT.toSeconds();
        long header = DEFAULT_HEADER_TIMEOUT.toSeconds();

        // command line: positional [port] [dir], --flags anywhere
        int positional = 0;
        for (var arg : args) {
            if (arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                var name = eq < 0 ? arg : arg.substring(0, eq);
                var value = eq < 0 ? "" : arg.substring(eq + 1);
                switch (name) {
                    case "--max-connections" -> max = number(value, max, 1, 10_000_000, arg, warnings);
                    case "--idle-timeout" -> idle = number(value, idle, 1, 86_400, arg, warnings);
                    case "--header-timeout" -> header = number(value, header, 1, 86_400, arg, warnings);
                    default -> warnings.add("Unknown option '" + arg + "'");
                }
            } else if (positional == 0) {
                positional++;
                port = (int) number(arg, port, 1, 65535, "port '" + arg + "'", warnings);
            } else if (positional == 1) {
                positional++;
                dir = arg;
            } else {
                warnings.add("Ignoring extra argument '" + arg + "'");
            }
        }

        // environment overrides
        if (env.containsKey("WEB_SERVER_PORT")) {
            port = (int) number(env.get("WEB_SERVER_PORT"), port, 1, 65535, "WEB_SERVER_PORT", warnings);
        }
        if (env.containsKey("WEB_SERVER_DIR")) {
            dir = env.get("WEB_SERVER_DIR");
        }
        if (env.containsKey("WEB_SERVER_MAX_CONNECTIONS")) {
            max = number(env.get("WEB_SERVER_MAX_CONNECTIONS"), max, 1, 10_000_000, "WEB_SERVER_MAX_CONNECTIONS", warnings);
        }
        if (env.containsKey("WEB_SERVER_IDLE_TIMEOUT")) {
            idle = number(env.get("WEB_SERVER_IDLE_TIMEOUT"), idle, 1, 86_400, "WEB_SERVER_IDLE_TIMEOUT", warnings);
        }
        if (env.containsKey("WEB_SERVER_HEADER_TIMEOUT")) {
            header = number(env.get("WEB_SERVER_HEADER_TIMEOUT"), header, 1, 86_400, "WEB_SERVER_HEADER_TIMEOUT", warnings);
        }

        var config = builder(Path.of(dir)).port(port).maxConnections(max)
                .idleTimeout(Duration.ofSeconds(idle)).headerTimeout(Duration.ofSeconds(header)).build();
        return new Parsed(config, List.copyOf(warnings));
    }

    private static int number(String value, int fallback, int min, int maxValue, String what, List<String> warnings) {
        return (int) number(value, (long) fallback, min, maxValue, what, warnings);
    }

    private static long number(String value, long fallback, long min, long maxValue, String what,
            List<String> warnings) {
        if (value != null && value.matches("[0-9]{1,9}")) {
            long n = Long.parseLong(value);
            if (n >= min && n <= maxValue) {
                return n;
            }
        }
        warnings.add("Invalid " + what + " (value '" + value + "'), using " + fallback);
        return fallback;
    }
}
