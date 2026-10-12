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
 * Fields marked "not used yet" are carried for later milestones; their defaults keep the
 * current behaviour. See {@link #usage()} for flags and environment variables.
 * Precedence: defaults &lt; command line &lt; environment.
 *
 * @param port TCP port (0 = ephemeral)
 * @param root document root
 * @param maxConnections HTTP/1 connections served concurrently; extra ones wait in the backlog
 * @param idleTimeout keep-alive idle timeout (also the QUIC max idle timeout)
 * @param headerTimeout time allowed to receive a complete request head (slowloris)
 * @param writeTimeout HTTP/3: reset a response whose writes make no progress for this long (also the
 *        window of the minimum send rate); HTTP/1.1 does not use it yet (S4)
 * @param h3Enabled start the HTTP/3 (QUIC) listener
 * @param h3Port UDP port for HTTP/3; {@link #H3_PORT_SAME_AS_TCP} (-1) = same as {@code port}, 0 = ephemeral
 * @param h3Host UDP bind address for HTTP/3
 * @param h3MaxConnections concurrent QUIC connections
 * @param tlsCert PEM certificate chain (null = self-signed for development)
 * @param tlsKey PKCS#8 PEM private key (must be set together with tlsCert)
 * @param tlsKeyPassword key password, or null (masked in {@link #toString()})
 * @param altSvcMaxAge {@code ma} of the Alt-Svc header advertising HTTP/3
 * @param cacheBytes not used yet (S3): small-file cache budget in bytes (0 = disabled)
 * @param accessLog not used yet: "-" for stdout, a file path, or null for off
 * @param altSvc send {@code Alt-Svc} on HTTP/1.1 responses once HTTP/3 is bound (opt-in: browsers
 *        ignore it on plain http:// origins, see the README)
 * @param maxConnectionsPerIp concurrent HTTP/1.1 connections from one client IP address (generous:
 *        browsers open ~6 per host, and many users may share one NAT address)
 * @param h3MaxConnectionsPerIp established QUIC connections from one client IP address (a browser
 *        needs one)
 * @param h3MinRate HTTP/3: minimum average send rate per connection, bytes/s, enforced only while file
 *        slots are contended (0 = off)
 * @param allowUnlistableDirs serve files below directories the server can search but not read (mode
 *        {@code --x}) through the weaker path-based open; off: such requests get 403 (security
 *        verification V7: anyone who can write in the root could create such a directory to force it)
 */
public record ServerConfig(int port, Path root, int maxConnections, Duration idleTimeout, Duration headerTimeout,
        Duration writeTimeout, boolean h3Enabled, int h3Port, String h3Host, int h3MaxConnections, Path tlsCert,
        Path tlsKey, String tlsKeyPassword, Duration altSvcMaxAge, long cacheBytes, String accessLog, boolean altSvc,
        int maxConnectionsPerIp, int h3MaxConnectionsPerIp, long h3MinRate, boolean allowUnlistableDirs) {

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
    public static final int DEFAULT_MAX_CONNECTIONS_PER_IP = 256;
    public static final int DEFAULT_H3_MAX_CONNECTIONS_PER_IP = 16;
    public static final long DEFAULT_H3_MIN_RATE = 2048;

    public ServerConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (h3Port < H3_PORT_SAME_AS_TCP || h3Port > 65535) {
            throw new IllegalArgumentException("h3Port out of range: " + h3Port);
        }
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(h3Host, "h3Host");
        if (maxConnections < 1 || h3MaxConnections < 1 || maxConnectionsPerIp < 1
                || h3MaxConnectionsPerIp < 1) {
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
        if (h3MinRate < 0) {
            throw new IllegalArgumentException("h3MinRate must not be negative");
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
                + ", cacheBytes=" + cacheBytes + ", accessLog=" + accessLog + ", altSvc=" + altSvc
                + ", maxConnectionsPerIp=" + maxConnectionsPerIp + ", h3MaxConnectionsPerIp="
                + h3MaxConnectionsPerIp + ", h3MinRate=" + h3MinRate + ", allowUnlistableDirs=" + allowUnlistableDirs + "]";
    }

    /** Mutable builder; new fields get a default here so existing callers keep compiling. */
    public static final class Builder {

        private int port = DEFAULT_PORT;
        private Path root;
        private int maxConnections = DEFAULT_MAX_CONNECTIONS;
        private int maxConnectionsPerIp = DEFAULT_MAX_CONNECTIONS_PER_IP;
        private int h3MaxConnectionsPerIp = DEFAULT_H3_MAX_CONNECTIONS_PER_IP;
        private long h3MinRate = DEFAULT_H3_MIN_RATE;
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
        private boolean altSvc;
        private boolean allowUnlistableDirs;

        private Builder(Path root) {
            this.root = root;
        }

        private Builder(ServerConfig c) {
            this.port = c.port;
            this.root = c.root;
            this.maxConnections = c.maxConnections;
            this.maxConnectionsPerIp = c.maxConnectionsPerIp;
            this.h3MaxConnectionsPerIp = c.h3MaxConnectionsPerIp;
            this.h3MinRate = c.h3MinRate;
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
            this.altSvc = c.altSvc;
            this.allowUnlistableDirs = c.allowUnlistableDirs;
        }

        public Builder port(int v) { this.port = v; return this; }
        public Builder root(Path v) { this.root = v; return this; }
        public Builder maxConnections(int v) { this.maxConnections = v; return this; }
        public Builder maxConnectionsPerIp(int v) { this.maxConnectionsPerIp = v; return this; }
        public Builder h3MaxConnectionsPerIp(int v) { this.h3MaxConnectionsPerIp = v; return this; }
        public Builder h3MinRate(long v) { this.h3MinRate = v; return this; }
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
        public Builder altSvc(boolean v) { this.altSvc = v; return this; }
        public Builder allowUnlistableDirs(boolean v) { this.allowUnlistableDirs = v; return this; }

        public ServerConfig build() {
            return new ServerConfig(port, root, maxConnections, idleTimeout, headerTimeout, writeTimeout, h3Enabled,
                    h3Port, h3Host, h3MaxConnections, tlsCert, tlsKey, tlsKeyPassword, altSvcMaxAge, cacheBytes,
                    accessLog, altSvc, maxConnectionsPerIp, h3MaxConnectionsPerIp, h3MinRate, allowUnlistableDirs);
        }
    }

    /**
     * Result of {@link #parse}.
     *
     * @param config usable configuration (invalid values replaced by defaults, TLS dropped on error)
     * @param warnings ignored or questionable values; the server can still start
     * @param errors problems that must stop startup (exit code 64)
     * @param helpRequested {@code --help} / {@code -h} was given
     */
    public record Parsed(ServerConfig config, List<String> warnings, List<String> errors, boolean helpRequested) {

        public boolean ok() {
            return this.errors.isEmpty();
        }
    }

    /** Usage text for {@code --help}. */
    public static String usage() {
        return """
                Usage: java -jar sjws_nettask.jar [port] [document root] [options]

                  port                      TCP port (default 8080)                       WEB_SERVER_PORT
                  document root             folder to serve (default ./HTML)             WEB_SERVER_DIR
                  --max-connections=N       concurrent HTTP/1 connections (2048)          WEB_SERVER_MAX_CONNECTIONS
                  --max-connections-per-ip=N  HTTP/1 connections per client IP (256)      WEB_SERVER_MAX_CONNECTIONS_PER_IP
                  --idle-timeout=S          keep-alive idle timeout, seconds (30)         WEB_SERVER_IDLE_TIMEOUT
                  --header-timeout=S        time to send a request head, seconds (10)     WEB_SERVER_HEADER_TIMEOUT
                  --write-timeout=S         HTTP/3: reset a stalled response, seconds (30) WEB_SERVER_WRITE_TIMEOUT
                  --h3 | --no-h3            enable HTTP/3 over QUIC (off)                 WEB_SERVER_H3=true|false
                  --h3-port=N               UDP port (default: the TCP port; 0 = ephemeral) WEB_SERVER_H3_PORT
                  --h3-host=ADDR            UDP bind address (0.0.0.0)                    WEB_SERVER_H3_HOST
                  --h3-max-connections=N    concurrent QUIC connections (1024)            WEB_SERVER_H3_MAX_CONNECTIONS
                  --h3-max-connections-per-ip=N  QUIC connections per client IP (16)   WEB_SERVER_H3_MAX_CONNECTIONS_PER_IP
                  --h3-min-rate=B           bytes/s per connection while file slots are   WEB_SERVER_H3_MIN_RATE
                                            contended (2048; 0 = off)
                  --cert=PATH               TLS certificate chain, PEM (self-signed)      WEB_SERVER_TLS_CERT
                  --key=PATH                TLS private key, PKCS#8 PEM                   WEB_SERVER_TLS_KEY
                  --key-password-file=PATH  file holding the key password                 WEB_SERVER_TLS_KEY_PASSWORD_FILE
                                            (or the password itself in                    WEB_SERVER_TLS_KEY_PASSWORD)
                  --alt-svc | --no-alt-svc  advertise HTTP/3 with Alt-Svc on HTTP/1.1 (off) WEB_SERVER_ALT_SVC=true|false
                                            (browsers ignore it on http:// origins)
                  --alt-svc-max-age=S       Alt-Svc max-age, seconds (86400)              WEB_SERVER_ALT_SVC_MA
                  --allow-unlistable-dirs   serve below unreadable (--x) directories by   WEB_SERVER_ALLOW_UNLISTABLE_DIRS
                                            path (weaker against local writers; off: 403)
                  -h, --help                show this help

                Environment variables override arguments. The key password is never accepted
                on the command line (it would be visible in the process list).
                """;
    }

    public static Parsed parse(String[] args, Map<String, String> env) {
        return parse(args, env, ServerConfig::readPasswordFile);
    }

    private static String readPasswordFile(Path file) throws java.io.IOException {
        var text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        // a single trailing newline (as written by echo / editors) is not part of the password
        if (text.endsWith("\r\n")) {
            return text.substring(0, text.length() - 2);
        }
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    /** Reads a password file; separated so {@link #parse} stays testable without files. */
    @FunctionalInterface
    public interface PasswordFileReader {
        String read(Path file) throws java.io.IOException;
    }

    /**
     * Parses arguments and environment. Pure apart from reading the key password file through
     * {@code passwordReader}.
     */
    public static Parsed parse(String[] args, Map<String, String> env, PasswordFileReader passwordReader) {
        var warnings = new ArrayList<String>();
        var errors = new ArrayList<String>();
        boolean help = false;
        var b = builder(Path.of(CONSTANTS.DEFAULT_HTML_PATH));
        int port = DEFAULT_PORT;
        int max = DEFAULT_MAX_CONNECTIONS;
        long idle = DEFAULT_IDLE_TIMEOUT.toSeconds();
        long header = DEFAULT_HEADER_TIMEOUT.toSeconds();
        long write = DEFAULT_WRITE_TIMEOUT.toSeconds();
        boolean altSvcOn = false;
        boolean unlistable = false;
        String dir = CONSTANTS.DEFAULT_HTML_PATH;
        boolean h3 = false;
        int h3Port = H3_PORT_SAME_AS_TCP;
        String h3Host = DEFAULT_H3_HOST;
        int h3Max = DEFAULT_H3_MAX_CONNECTIONS;
        int perIp = DEFAULT_MAX_CONNECTIONS_PER_IP;
        int h3PerIp = DEFAULT_H3_MAX_CONNECTIONS_PER_IP;
        int minRate = (int) DEFAULT_H3_MIN_RATE;
        String cert = null;
        String key = null;
        String passwordFile = null;
        long altSvc = DEFAULT_ALT_SVC_MAX_AGE.toSeconds();
        var explicitH3Options = new ArrayList<String>();

        // command line: positional [port] [dir], --flags anywhere
        int positional = 0;
        for (var arg : args) {
            if (arg.equals("-h") || arg.equals("--help")) {
                help = true;
                continue;
            }
            if (arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                var name = eq < 0 ? arg : arg.substring(0, eq);
                var value = eq < 0 ? null : arg.substring(eq + 1);
                switch (name) {
                    case "--max-connections" -> max = number(value, max, 1, 10_000_000, name, warnings);
                    case "--max-connections-per-ip" -> perIp = number(value, perIp, 1, 10_000_000, name, warnings);
                    case "--h3-max-connections-per-ip" -> {
                        h3PerIp = number(value, h3PerIp, 1, 10_000_000, name, warnings);
                        explicitH3Options.add(name);
                    }
                    case "--h3-min-rate" -> {
                        minRate = number(value, minRate, 0, 1_000_000_000, name, warnings);
                        explicitH3Options.add(name);
                    }
                    case "--idle-timeout" -> idle = number(value, idle, 1, 86_400, name, warnings);
                    case "--header-timeout" -> header = number(value, header, 1, 86_400, name, warnings);
                    case "--write-timeout" -> write = number(value, write, 1, 86_400, name, warnings);
                    case "--h3" -> h3 = value == null || bool(value, h3, name, warnings);
                    case "--no-h3" -> h3 = false;
                    case "--h3-port" -> {
                        h3Port = number(value, h3Port, 0, 65535, name, warnings);
                        explicitH3Options.add(name);
                    }
                    case "--h3-host" -> {
                        h3Host = text(value, h3Host, name, warnings);
                        explicitH3Options.add(name);
                    }
                    case "--h3-max-connections" -> {
                        h3Max = number(value, h3Max, 1, 10_000_000, name, warnings);
                        explicitH3Options.add(name);
                    }
                    case "--cert" -> {
                        cert = text(value, cert, name, warnings);
                        explicitH3Options.add(name);
                    }
                    case "--key" -> {
                        key = text(value, key, name, warnings);
                        explicitH3Options.add(name);
                    }
                    case "--key-password-file" -> {
                        passwordFile = text(value, passwordFile, name, warnings);
                        explicitH3Options.add(name);
                    }
                    case "--key-password" -> warnings.add("--key-password is not accepted on the command line "
                            + "(visible to other users); use WEB_SERVER_TLS_KEY_PASSWORD or --key-password-file");
                    case "--alt-svc" -> {
                        altSvcOn = value == null || bool(value, altSvcOn, name, warnings);
                        explicitH3Options.add(name);
                    }
                    case "--no-alt-svc" -> altSvcOn = false;
                    case "--allow-unlistable-dirs" -> unlistable = value == null || bool(value, unlistable, name, warnings);
                    case "--alt-svc-max-age" -> {
                        altSvc = number(value, altSvc, 0, 31_536_000, name, warnings);
                        explicitH3Options.add(name);
                    }
                    default -> warnings.add("Unknown option '" + name + "' (see --help)");
                }
            } else if (positional == 0) {
                positional++;
                port = (int) number(arg, port, 1, 65535, "port", warnings);
            } else if (positional == 1) {
                positional++;
                dir = arg;
            } else {
                warnings.add("Ignoring extra argument '" + arg + "'");
            }
        }

        // environment overrides
        String v;
        if ((v = env.get("WEB_SERVER_PORT")) != null) {
            port = number(v, port, 1, 65535, "WEB_SERVER_PORT", warnings);
        }
        if ((v = env.get("WEB_SERVER_DIR")) != null) {
            dir = v;
        }
        if ((v = env.get("WEB_SERVER_MAX_CONNECTIONS")) != null) {
            max = number(v, max, 1, 10_000_000, "WEB_SERVER_MAX_CONNECTIONS", warnings);
        }
        if ((v = env.get("WEB_SERVER_MAX_CONNECTIONS_PER_IP")) != null) {
            perIp = number(v, perIp, 1, 10_000_000, "WEB_SERVER_MAX_CONNECTIONS_PER_IP", warnings);
        }
        if ((v = env.get("WEB_SERVER_H3_MAX_CONNECTIONS_PER_IP")) != null) {
            h3PerIp = number(v, h3PerIp, 1, 10_000_000, "WEB_SERVER_H3_MAX_CONNECTIONS_PER_IP", warnings);
            explicitH3Options.add("WEB_SERVER_H3_MAX_CONNECTIONS_PER_IP");
        }
        if ((v = env.get("WEB_SERVER_H3_MIN_RATE")) != null) {
            minRate = number(v, minRate, 0, 1_000_000_000, "WEB_SERVER_H3_MIN_RATE", warnings);
            explicitH3Options.add("WEB_SERVER_H3_MIN_RATE");
        }
        if ((v = env.get("WEB_SERVER_IDLE_TIMEOUT")) != null) {
            idle = number(v, idle, 1, 86_400, "WEB_SERVER_IDLE_TIMEOUT", warnings);
        }
        if ((v = env.get("WEB_SERVER_HEADER_TIMEOUT")) != null) {
            header = number(v, header, 1, 86_400, "WEB_SERVER_HEADER_TIMEOUT", warnings);
        }
        if ((v = env.get("WEB_SERVER_WRITE_TIMEOUT")) != null) {
            write = number(v, write, 1, 86_400, "WEB_SERVER_WRITE_TIMEOUT", warnings);
        }
        if ((v = env.get("WEB_SERVER_ALT_SVC")) != null) {
            altSvcOn = bool(v, altSvcOn, "WEB_SERVER_ALT_SVC", warnings);
            if (altSvcOn) {
                explicitH3Options.add("WEB_SERVER_ALT_SVC");
            }
        }
        if ((v = env.get("WEB_SERVER_ALLOW_UNLISTABLE_DIRS")) != null) {
            unlistable = bool(v, unlistable, "WEB_SERVER_ALLOW_UNLISTABLE_DIRS", warnings);
        }
        if ((v = env.get("WEB_SERVER_H3")) != null) {
            h3 = bool(v, h3, "WEB_SERVER_H3", warnings);
        }
        if ((v = env.get("WEB_SERVER_H3_PORT")) != null) {
            h3Port = number(v, h3Port, 0, 65535, "WEB_SERVER_H3_PORT", warnings);
            explicitH3Options.add("WEB_SERVER_H3_PORT");
        }
        if ((v = env.get("WEB_SERVER_H3_HOST")) != null) {
            h3Host = text(v, h3Host, "WEB_SERVER_H3_HOST", warnings);
            explicitH3Options.add("WEB_SERVER_H3_HOST");
        }
        if ((v = env.get("WEB_SERVER_H3_MAX_CONNECTIONS")) != null) {
            h3Max = number(v, h3Max, 1, 10_000_000, "WEB_SERVER_H3_MAX_CONNECTIONS", warnings);
            explicitH3Options.add("WEB_SERVER_H3_MAX_CONNECTIONS");
        }
        if ((v = env.get("WEB_SERVER_TLS_CERT")) != null) {
            cert = text(v, cert, "WEB_SERVER_TLS_CERT", warnings);
            explicitH3Options.add("WEB_SERVER_TLS_CERT");
        }
        if ((v = env.get("WEB_SERVER_TLS_KEY")) != null) {
            key = text(v, key, "WEB_SERVER_TLS_KEY", warnings);
            explicitH3Options.add("WEB_SERVER_TLS_KEY");
        }
        if ((v = env.get("WEB_SERVER_TLS_KEY_PASSWORD_FILE")) != null) {
            passwordFile = text(v, passwordFile, "WEB_SERVER_TLS_KEY_PASSWORD_FILE", warnings);
            explicitH3Options.add("WEB_SERVER_TLS_KEY_PASSWORD_FILE");
        }
        if ((v = env.get("WEB_SERVER_ALT_SVC_MA")) != null) {
            altSvc = number(v, altSvc, 0, 31_536_000, "WEB_SERVER_ALT_SVC_MA", warnings);
            explicitH3Options.add("WEB_SERVER_ALT_SVC_MA");
        }

        // key password: environment value wins over a file
        String password = env.get("WEB_SERVER_TLS_KEY_PASSWORD");
        if (password != null && passwordFile != null) {
            warnings.add("Both WEB_SERVER_TLS_KEY_PASSWORD and a key password file are set; using the variable");
        } else if (passwordFile != null && h3) { // only HTTP/3 uses the key: don't fail over an unused file
            try {
                password = passwordReader.read(Path.of(passwordFile));
            } catch (java.io.IOException | RuntimeException e) {
                errors.add("Cannot read the key password file " + passwordFile + ": " + e.getMessage());
            }
        }
        if (password != null && key == null) {
            warnings.add("A key password was given without --key/WEB_SERVER_TLS_KEY; ignoring it");
            password = null;
        }
        if ((cert == null) != (key == null)) {
            errors.add("--cert and --key must be given together (only " + (cert != null ? "--cert" : "--key")
                    + " is set)");
            cert = null;
            key = null;
            password = null;
        }
        if (h3 && altSvcOn) {
            warnings.add("Alt-Svc is enabled but this server only speaks plain HTTP/1.1 (http://): browsers "
                    + "ignore Alt-Svc from http:// origins and will not switch to HTTP/3. Clients that know the "
                    + "UDP port reach HTTP/3 directly (curl --http3-only https://host:port/); browser upgrade "
                    + "needs an HTTPS listener with a trusted certificate");
        }
        if (!altSvcOn && explicitH3Options.contains("--alt-svc-max-age")) {
            warnings.add("--alt-svc-max-age has no effect without --alt-svc");
        }
        explicitH3Options.remove("--alt-svc-max-age");
        explicitH3Options.remove("WEB_SERVER_ALT_SVC_MA");
        if (!h3 && !explicitH3Options.isEmpty()) {
            warnings.add("HTTP/3 is disabled, so " + String.join(", ", explicitH3Options)
                    + " ha" + (explicitH3Options.size() == 1 ? "s" : "ve") + " no effect (add --h3)");
        }

        var config = b.port(port).root(Path.of(dir)).maxConnections(max).maxConnectionsPerIp(perIp)
                .h3MaxConnectionsPerIp(h3PerIp).h3MinRate(minRate)
                .idleTimeout(Duration.ofSeconds(idle)).headerTimeout(Duration.ofSeconds(header))
                .writeTimeout(Duration.ofSeconds(write)).altSvc(altSvcOn).allowUnlistableDirs(unlistable)
                .h3Enabled(h3).h3Port(h3Port).h3Host(h3Host).h3MaxConnections(h3Max)
                .tlsCert(cert == null ? null : Path.of(cert)).tlsKey(key == null ? null : Path.of(key))
                .tlsKeyPassword(password).altSvcMaxAge(Duration.ofSeconds(altSvc)).build();
        return new Parsed(config, List.copyOf(warnings), List.copyOf(errors), help);
    }

    private static boolean bool(String value, boolean fallback, String what, List<String> warnings) {
        switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "on":
                return true;
            case "false", "0", "no", "off":
                return false;
            default:
                warnings.add("Invalid " + what + " (value '" + value + "'), expected true/false; using " + fallback);
                return fallback;
        }
    }

    private static String text(String value, String fallback, String what, List<String> warnings) {
        if (value == null || value.isBlank()) {
            warnings.add("Missing value for " + what + " (use " + what + "=VALUE)");
            return fallback;
        }
        return value;
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
        warnings.add("Invalid " + what + " (value '" + value + "'), expected " + min + ".." + maxValue
                + "; using " + fallback);
        return fallback;
    }
}
