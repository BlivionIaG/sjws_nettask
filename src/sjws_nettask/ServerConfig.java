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
import sjws_nettask.core.AccessRules;

/**
 * Server settings: the complete field set of the merged plan (S1 superset contract).
 * Fields marked "not used yet" are carried for later milestones; their defaults keep the
 * current behaviour. See {@link #usage()} for flags and environment variables.
 * Precedence: defaults, then the config file, then the command line, then the environment.
 * {@code --config} and the {@code --acme*} flags are recognized here and applied by
 * {@code config.ConfigLoader}; routing and middleware are file-only.
 *
 * @param port TCP port (0 = ephemeral)
 * @param root document root
 * @param maxConnections HTTP/1 connections served concurrently; extra ones wait in the backlog
 * @param idleTimeout keep-alive idle timeout (also the QUIC max idle timeout)
 * @param headerTimeout time allowed to receive a complete request head (slowloris)
 * @param writeTimeout reset (HTTP/3) or close (HTTP/1.1) a response whose writes make no progress for
 *        this long (also the window of the minimum send rate)
 * @param h3Enabled start the HTTP/3 (QUIC) listener
 * @param h3Port UDP port for HTTP/3; {@link #H3_PORT_SAME_AS_TCP} (-1) = same as {@code port}, 0 = ephemeral
 * @param h3Host UDP bind address for HTTP/3
 * @param h3MaxConnections concurrent QUIC connections
 * @param tlsCert PEM certificate chain (null = self-signed for development)
 * @param tlsKey PKCS#8 PEM private key (must be set together with tlsCert)
 * @param tlsKeyPassword key password, or null (masked in {@link #toString()})
 * @param altSvcMaxAge {@code ma} of the Alt-Svc header advertising HTTP/3
 * @param cacheBytes small-file cache budget in bytes (0 = disabled), see {@code core.FileCache}
 * @param accessLog "-" for stdout, a file path to append, or null for off
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
 * @param access which paths and files may be served: deny list, allow/deny/hide rules, dotfiles,
 *        owner/permission policy (see {@code core.AccessRules}; default: dotfiles hidden, nothing else)
 * @param backlog TCP accept backlog: connections the kernel completes and queues before the server
 *        accepts them (capped by the OS, e.g. {@code net.core.somaxconn} on Linux)
 * @param accessLogFormat common, combined (default) or json; ignored when {@code accessLog} is null
 * @param metricsExposure who may read {@code /metrics} ({@link MetricsExposure#OFF} by default)
 * @param healthz answer GET/HEAD {@code /healthz} with {@code ok} instead of a document-root file
 * @param h2Enabled start the HTTP/2 listener (TLS, ALPN h2 with http/1.1 fallback; same certificate as HTTP/3)
 * @param h2Port TCP port of the HTTP/2 TLS listener (0 = ephemeral)
 * @param h2cPort TCP port for cleartext HTTP/2 (prior knowledge or Upgrade: h2c, else HTTP/1.1);
 *        {@link #H2C_DISABLED} (-1) = off, 0 = ephemeral; only with {@code h2Enabled}
 */
public record ServerConfig(int port, Path root, int maxConnections, Duration idleTimeout, Duration headerTimeout,
        Duration writeTimeout, boolean h3Enabled, int h3Port, String h3Host, int h3MaxConnections, Path tlsCert,
        Path tlsKey, String tlsKeyPassword, Duration altSvcMaxAge, long cacheBytes, String accessLog, boolean altSvc,
        int maxConnectionsPerIp, int h3MaxConnectionsPerIp, long h3MinRate, boolean allowUnlistableDirs,
        int backlog, AccessLogFormat accessLogFormat, MetricsExposure metricsExposure, boolean healthz,
        boolean h2Enabled, int h2Port, int h2cPort, AccessRules access) {

    public static final int DEFAULT_H2_PORT = 8443;
    public static final int H2C_DISABLED = -1;
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
    /** Up from the JDK's 50, which made bursts of new connections time out (S5 benchmark, c1000). */
    public static final int DEFAULT_BACKLOG = 1024;

    public ServerConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (h2Port < 0 || h2Port > 65535 || h2cPort < H2C_DISABLED || h2cPort > 65535) {
            throw new IllegalArgumentException("h2Port/h2cPort out of range: " + h2Port + "/" + h2cPort);
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
        if (backlog < 1 || backlog > 65535) {
            throw new IllegalArgumentException("backlog must be between 1 and 65535");
        }
        if (h3MinRate < 0) {
            throw new IllegalArgumentException("h3MinRate must not be negative");
        }
        if (cacheBytes < 0) {
            throw new IllegalArgumentException("cacheBytes must not be negative");
        }
        Objects.requireNonNull(access, "access");
        if ((tlsCert == null) != (tlsKey == null)) {
            throw new IllegalArgumentException("tlsCert and tlsKey must be given together");
        }
        Objects.requireNonNull(accessLogFormat, "accessLogFormat");
        Objects.requireNonNull(metricsExposure, "metricsExposure");
    }

    /** Line shape of {@link #accessLog()}. Combined and JSON also carry Referer and User-Agent. */
    public enum AccessLogFormat { COMMON, COMBINED, JSON }

    /** Who is answered at {@code /metrics}. {@link #OFF} leaves that path to the document root. */
    public enum MetricsExposure { OFF, LOCALHOST, ON }

    /**
     * @return true when startup should install a recording observer. False leaves the transports on
     *         {@link sjws_nettask.observe.RequestObserver#NONE} (no clock read, no counters, no probe
     *         path checks). The admin TUI can still arm counters later via
     *         {@link SjwsServer#metricsSnapshot()}.
     */
    public boolean observe() {
        return this.healthz || this.accessLog != null || this.metricsExposure != MetricsExposure.OFF;
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
                + h3MaxConnectionsPerIp + ", h3MinRate=" + h3MinRate + ", allowUnlistableDirs=" + allowUnlistableDirs
                + ", backlog=" + backlog + ", accessLogFormat=" + accessLogFormat + ", metricsExposure="
                + metricsExposure + ", healthz=" + healthz
                + ", h2Enabled=" + h2Enabled + ", h2Port=" + h2Port + ", h2cPort=" + h2cPort + ", access=" + access + "]";
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
        private int backlog = DEFAULT_BACKLOG;
        private AccessLogFormat accessLogFormat = AccessLogFormat.COMBINED;
        private MetricsExposure metricsExposure = MetricsExposure.OFF;
        private boolean healthz;
        private boolean h2Enabled;
        private int h2Port = DEFAULT_H2_PORT;
        private int h2cPort = H2C_DISABLED;
        private AccessRules access = AccessRules.DEFAULT;

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
            this.backlog = c.backlog;
            this.accessLogFormat = c.accessLogFormat;
            this.metricsExposure = c.metricsExposure;
            this.healthz = c.healthz;
            this.h2Enabled = c.h2Enabled;
            this.h2Port = c.h2Port;
            this.h2cPort = c.h2cPort;
            this.access = c.access;
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
        public Builder backlog(int v) { this.backlog = v; return this; }
        public Builder accessLogFormat(AccessLogFormat v) { this.accessLogFormat = v; return this; }
        public Builder metricsExposure(MetricsExposure v) { this.metricsExposure = v; return this; }
        public Builder healthz(boolean v) { this.healthz = v; return this; }
        public Builder h2Enabled(boolean v) { this.h2Enabled = v; return this; }
        public Builder h2Port(int v) { this.h2Port = v; return this; }
        public Builder h2cPort(int v) { this.h2cPort = v; return this; }
        public Builder access(AccessRules v) { this.access = v; return this; }

        public ServerConfig build() {
            return new ServerConfig(port, root, maxConnections, idleTimeout, headerTimeout, writeTimeout, h3Enabled,
                    h3Port, h3Host, h3MaxConnections, tlsCert, tlsKey, tlsKeyPassword, altSvcMaxAge, cacheBytes,
                    accessLog, altSvc, maxConnectionsPerIp, h3MaxConnectionsPerIp, h3MinRate, allowUnlistableDirs,
                    backlog, accessLogFormat, metricsExposure, healthz,
                    h2Enabled, h2Port, h2cPort, access);
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
                  --backlog=N               TCP accept backlog (1024; OS-capped)          WEB_SERVER_BACKLOG
                  --idle-timeout=S          keep-alive idle timeout, seconds (30)         WEB_SERVER_IDLE_TIMEOUT
                  --header-timeout=S        time to send a request head, seconds (10)     WEB_SERVER_HEADER_TIMEOUT
                  --write-timeout=S         end a response stalled this long, seconds (30) WEB_SERVER_WRITE_TIMEOUT
                  --cache-bytes=N[k|m|g]    small-file cache size (64m; 0 = off)          WEB_SERVER_CACHE_BYTES
                  --h3 | --no-h3            enable HTTP/3 over QUIC (off)                 WEB_SERVER_H3=true|false
                  --h3-port=N               UDP port (default: the TCP port; 0 = ephemeral) WEB_SERVER_H3_PORT
                  --h3-host=ADDR            UDP bind address (0.0.0.0)                    WEB_SERVER_H3_HOST
                  --h3-max-connections=N    concurrent QUIC connections (1024)            WEB_SERVER_H3_MAX_CONNECTIONS
                  --h3-max-connections-per-ip=N  QUIC connections per client IP (16)   WEB_SERVER_H3_MAX_CONNECTIONS_PER_IP
                  --h3-min-rate=B           bytes/s per connection while file slots are   WEB_SERVER_H3_MIN_RATE
                                            contended (2048; 0 = off)
                  --h2 | --no-h2            enable HTTP/2 over TLS (ALPN h2, http/1.1     WEB_SERVER_H2=true|false
                                            fallback) on its own port (off)
                  --h2-port=N               TCP port for HTTP/2 over TLS (8443)           WEB_SERVER_H2_PORT
                  --h2c-port=N              cleartext HTTP/2 port (off; needs --h2)       WEB_SERVER_H2C_PORT
                  --cert=PATH               TLS certificate chain, PEM (self-signed)      WEB_SERVER_TLS_CERT
                  --key=PATH                TLS private key, PKCS#8 PEM                   WEB_SERVER_TLS_KEY
                  --key-password-file=PATH  file holding the key password                 WEB_SERVER_TLS_KEY_PASSWORD_FILE
                                            (or the password itself in                    WEB_SERVER_TLS_KEY_PASSWORD)
                  --alt-svc | --no-alt-svc  advertise HTTP/3 with Alt-Svc on HTTP/1.1 (off) WEB_SERVER_ALT_SVC=true|false
                                            (browsers ignore it on http:// origins)
                  --alt-svc-max-age=S       Alt-Svc max-age, seconds (86400)              WEB_SERVER_ALT_SVC_MA
                  --allow-unlistable-dirs   serve below unreadable (--x) directories by   WEB_SERVER_ALLOW_UNLISTABLE_DIRS
                                            path (weaker against local writers; off: 403)
                  --access-log=PATH         access log: - is stdout, or a file to append  WEB_SERVER_ACCESS_LOG
                                            (unset: off). One line per response, written
                                            off the request thread
                  --access-log-format=FMT   common, combined (default) or json           WEB_SERVER_ACCESS_LOG_FORMAT
                  --metrics[=MODE]          /metrics: off (default), localhost or on     WEB_SERVER_METRICS
                                            (a bare --metrics means localhost)
                  --no-metrics              leave /metrics disabled
                  --healthz | --no-healthz  GET/HEAD /healthz answers "ok" (off).        WEB_SERVER_HEALTHZ=true|false
                                            Off: a document-root file at /healthz is
                                            served. On also turns request counters on
                  --deny=PAT[,PAT...]       refuse (403) matching paths; always wins      WEB_SERVER_DENY
                  --access-rule=ACT:PAT     allow:, deny: (403) or hide: (404) a path      WEB_SERVER_ACCESS_RULES
                                            pattern; repeatable, first match wins
                  --allow-dotfiles          serve names starting with '.' (off: 404)      WEB_SERVER_ALLOW_DOTFILES
                  --require-owner=USER      serve only files owned by USER (name or uid)  WEB_SERVER_REQUIRE_OWNER
                  --require-world-readable  serve only files with mode o+r (off)          WEB_SERVER_REQUIRE_WORLD_READABLE
                  --config=PATH             YAML configuration file (see docs/config.md)  WEB_SERVER_CONFIG
                  --acme | --no-acme        ACME certificate issuance (off; staging CA)   WEB_SERVER_ACME=true|false
                  --acme-directory=URL      staging, production, or a directory URL       WEB_SERVER_ACME_DIRECTORY
                  --acme-email=ADDR         account contact email                         WEB_SERVER_ACME_EMAIL
                  --acme-domain=NAME        domain name (repeatable; the variable is a    WEB_SERVER_ACME_DOMAINS
                                            comma-separated list)
                  --acme-storage=PATH       account key and certificates (./acme)        WEB_SERVER_ACME_STORAGE
                  --acme-http-port=N        HTTP-01 port (0 = the main listener)         WEB_SERVER_ACME_HTTP_PORT
                  --acme-accept-terms       agree to the CA terms of service (required)  WEB_SERVER_ACME_ACCEPT_TERMS
                  --acme-renew-before=DUR   renew this long before expiry (30d)          WEB_SERVER_ACME_RENEW_BEFORE
                  -h, --help                show this help

                Environment variables override arguments, which override the config file.
                The file is strict (a bad value is an error). Invalid values of the server
                flags above still warn and fall back. The key password is never accepted
                on the command line (it would be visible in the process list) or in the file.
                """;
    }

    public static Parsed parse(String[] args, Map<String, String> env) {
        return parse(args, env, null, ServerConfig::readPasswordFile);
    }

    public static String readPasswordFile(Path file) throws java.io.IOException {
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

    public static Parsed parse(String[] args, Map<String, String> env, PasswordFileReader passwordReader) {
        return parse(args, env, null, passwordReader);
    }

    /** @param baseline file settings under the command line; null starts from the defaults */
    public static Parsed parse(String[] args, Map<String, String> env, ServerConfig baseline) {
        return parse(args, env, baseline, ServerConfig::readPasswordFile);
    }

    /**
     * Parses arguments and environment. Pure apart from reading the key password file through
     * {@code passwordReader}. A non-null {@code baseline} is the config file: arguments and then
     * the environment override it.
     */
    public static Parsed parse(String[] args, Map<String, String> env, ServerConfig baseline,
            PasswordFileReader passwordReader) {
        var warnings = new ArrayList<String>();
        var errors = new ArrayList<String>();
        boolean help = false;
        var base = baseline;
        var b = builder(base == null ? Path.of(CONSTANTS.DEFAULT_HTML_PATH) : base.root());
        int port = base == null ? DEFAULT_PORT : base.port();
        int max = base == null ? DEFAULT_MAX_CONNECTIONS : base.maxConnections();
        long idle = (base == null ? DEFAULT_IDLE_TIMEOUT : base.idleTimeout()).toSeconds();
        long header = (base == null ? DEFAULT_HEADER_TIMEOUT : base.headerTimeout()).toSeconds();
        long write = (base == null ? DEFAULT_WRITE_TIMEOUT : base.writeTimeout()).toSeconds();
        long cache = base == null ? DEFAULT_CACHE_BYTES : base.cacheBytes();
        boolean altSvcOn = base != null && base.altSvc();
        boolean unlistable = base != null && base.allowUnlistableDirs();
        String dir = base == null ? CONSTANTS.DEFAULT_HTML_PATH : base.root().toString();
        boolean h3 = base != null && base.h3Enabled();
        int h3Port = base == null ? H3_PORT_SAME_AS_TCP : base.h3Port();
        String h3Host = base == null ? DEFAULT_H3_HOST : base.h3Host();
        int h3Max = base == null ? DEFAULT_H3_MAX_CONNECTIONS : base.h3MaxConnections();
        int perIp = base == null ? DEFAULT_MAX_CONNECTIONS_PER_IP : base.maxConnectionsPerIp();
        int backlog = base == null ? DEFAULT_BACKLOG : base.backlog();
        int h3PerIp = base == null ? DEFAULT_H3_MAX_CONNECTIONS_PER_IP : base.h3MaxConnectionsPerIp();
        int minRate = (int) (base == null ? DEFAULT_H3_MIN_RATE : base.h3MinRate());
        String cert = base == null || base.tlsCert() == null ? null : base.tlsCert().toString();
        String key = base == null || base.tlsKey() == null ? null : base.tlsKey().toString();
        String passwordFile = null;
        String accessLog = base == null ? null : base.accessLog();
        long altSvc = (base == null ? DEFAULT_ALT_SVC_MAX_AGE : base.altSvcMaxAge()).toSeconds();
        var accessLogFormat = base == null ? AccessLogFormat.COMBINED : base.accessLogFormat();
        var metricsExposure = base == null ? MetricsExposure.OFF : base.metricsExposure();
        boolean healthz = base != null && base.healthz();
        boolean accessLogFormatSet = false;
        var explicitH3Options = new ArrayList<String>();
        boolean h2 = false;
        int h2Port = DEFAULT_H2_PORT;
        int h2cPort = H2C_DISABLED;
        var explicitH2Options = new ArrayList<String>();
        var denyList = new ArrayList<String>();
        var accessRules = new ArrayList<String>();
        boolean dotfiles = false;
        String owner = null;
        boolean worldReadable = false;

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
                    case "--backlog" -> backlog = number(value, backlog, 1, 65535, name, warnings);
                    case "--idle-timeout" -> idle = number(value, idle, 1, 86_400, name, warnings);
                    case "--header-timeout" -> header = number(value, header, 1, 86_400, name, warnings);
                    case "--write-timeout" -> write = number(value, write, 1, 86_400, name, warnings);
                    case "--cache-bytes" -> cache = byteSize(value, cache, name, warnings);
                    case "--h3" -> h3 = value == null || bool(value, h3, name, warnings);
                    case "--no-h3" -> h3 = false;
                    case "--h2" -> h2 = value == null || bool(value, h2, name, warnings);
                    case "--no-h2" -> h2 = false;
                    case "--h2-port" -> {
                        h2Port = number(value, h2Port, 0, 65535, name, warnings);
                        explicitH2Options.add(name);
                    }
                    case "--h2c-port" -> {
                        h2cPort = number(value, h2cPort, 0, 65535, name, warnings);
                        explicitH2Options.add(name);
                    }
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
                    case "--access-log" -> accessLog = text(value, accessLog, name, warnings);
                    case "--access-log-format" -> {
                        accessLogFormat = logFormat(value, accessLogFormat, name, warnings);
                        accessLogFormatSet = true;
                    }
                    case "--metrics" -> metricsExposure = metricsMode(value, MetricsExposure.LOCALHOST, name, warnings);
                    case "--no-metrics" -> metricsExposure = MetricsExposure.OFF;
                    case "--healthz" -> healthz = value == null || bool(value, healthz, name, warnings);
                    case "--no-healthz" -> healthz = false;
                    case "--deny" -> list(value, denyList, name, warnings);
                    case "--access-rule" -> {
                        if (value == null || value.isBlank()) {
                            warnings.add("Missing value for " + name + " (use " + name + "=VALUE)");
                        } else {
                            accessRules.add(value);
                        }
                    }
                    case "--allow-dotfiles" -> dotfiles = value == null || bool(value, dotfiles, name, warnings);
                    case "--require-owner" -> owner = text(value, owner, name, warnings);
                    case "--require-world-readable" ->
                        worldReadable = value == null || bool(value, worldReadable, name, warnings);
                    case "--alt-svc-max-age" -> {
                        altSvc = number(value, altSvc, 0, 31_536_000, name, warnings);
                        explicitH3Options.add(name);
                    }
                    case "--config", "--acme", "--no-acme", "--acme-directory", "--acme-email", "--acme-domain",
                            "--acme-storage", "--acme-http-port", "--acme-accept-terms", "--acme-renew-before" -> {
                        // applied by config.ConfigLoader; recognized so they are not unknown options
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
        if ((v = env.get("WEB_SERVER_BACKLOG")) != null) {
            backlog = number(v, backlog, 1, 65535, "WEB_SERVER_BACKLOG", warnings);
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
        if ((v = env.get("WEB_SERVER_CACHE_BYTES")) != null) {
            cache = byteSize(v, cache, "WEB_SERVER_CACHE_BYTES", warnings);
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
        if ((v = env.get("WEB_SERVER_ACCESS_LOG")) != null) {
            accessLog = text(v, accessLog, "WEB_SERVER_ACCESS_LOG", warnings);
        }
        if ((v = env.get("WEB_SERVER_ACCESS_LOG_FORMAT")) != null) {
            accessLogFormat = logFormat(v, accessLogFormat, "WEB_SERVER_ACCESS_LOG_FORMAT", warnings);
            accessLogFormatSet = true;
        }
        if ((v = env.get("WEB_SERVER_METRICS")) != null) {
            metricsExposure = metricsMode(v, metricsExposure, "WEB_SERVER_METRICS", warnings);
        }
        if ((v = env.get("WEB_SERVER_HEALTHZ")) != null) {
            healthz = bool(v, healthz, "WEB_SERVER_HEALTHZ", warnings);
        }
        if ((v = env.get("WEB_SERVER_DENY")) != null) {
            denyList.clear();
            if (!v.isBlank()) { // empty: none
                list(v, denyList, "WEB_SERVER_DENY", warnings);
            }
        }
        if ((v = env.get("WEB_SERVER_ACCESS_RULES")) != null) {
            accessRules.clear();
            if (!v.isBlank()) { // empty: none
                list(v, accessRules, "WEB_SERVER_ACCESS_RULES", warnings);
            }
        }
        if ((v = env.get("WEB_SERVER_ALLOW_DOTFILES")) != null) {
            dotfiles = bool(v, dotfiles, "WEB_SERVER_ALLOW_DOTFILES", warnings);
        }
        if ((v = env.get("WEB_SERVER_REQUIRE_OWNER")) != null) {
            owner = v.isBlank() ? null : v.trim();
        }
        if ((v = env.get("WEB_SERVER_REQUIRE_WORLD_READABLE")) != null) {
            worldReadable = bool(v, worldReadable, "WEB_SERVER_REQUIRE_WORLD_READABLE", warnings);
        }
        if ((v = env.get("WEB_SERVER_H3")) != null) {
            h3 = bool(v, h3, "WEB_SERVER_H3", warnings);
        }
        if ((v = env.get("WEB_SERVER_H2")) != null) {
            h2 = bool(v, h2, "WEB_SERVER_H2", warnings);
        }
        if ((v = env.get("WEB_SERVER_H2_PORT")) != null) {
            h2Port = number(v, h2Port, 0, 65535, "WEB_SERVER_H2_PORT", warnings);
            explicitH2Options.add("WEB_SERVER_H2_PORT");
        }
        if ((v = env.get("WEB_SERVER_H2C_PORT")) != null) {
            h2cPort = number(v, h2cPort, 0, 65535, "WEB_SERVER_H2C_PORT", warnings);
            explicitH2Options.add("WEB_SERVER_H2C_PORT");
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
        } else if (passwordFile != null && (h3 || h2)) { // only HTTP/3 and HTTP/2 use the key: don't fail over an unused file
            try {
                password = passwordReader.read(Path.of(passwordFile));
            } catch (java.io.IOException | RuntimeException e) {
                errors.add("Cannot read the key password file " + passwordFile + ": " + e.getMessage());
            }
        }
        if (password == null && baseline != null && baseline.tlsKeyPassword() != null) {
            password = baseline.tlsKeyPassword();
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
        if (h2) { // the TLS options are shared with HTTP/2
            explicitH3Options.removeIf(o -> o.startsWith("--cert") || o.startsWith("--key") || o.startsWith("WEB_SERVER_TLS"));
        }
        if (!h2 && !explicitH2Options.isEmpty()) {
            warnings.add("HTTP/2 is disabled, so " + String.join(", ", explicitH2Options)
                    + " ha" + (explicitH2Options.size() == 1 ? "s" : "ve") + " no effect (add --h2)");
        }
        if (!h3 && !explicitH3Options.isEmpty()) {
            warnings.add("HTTP/3 is disabled, so " + String.join(", ", explicitH3Options)
                    + " ha" + (explicitH3Options.size() == 1 ? "s" : "ve") + " no effect (add --h3)");
        }
        if (accessLog == null && accessLogFormatSet) {
            warnings.add("--access-log-format has no effect without --access-log");
        }

        // access rules: a typo in a security setting must stop startup, not be ignored
        var access = AccessRules.DEFAULT;
        try {
            var rules = new ArrayList<AccessRules.Rule>();
            for (var spec : accessRules) {
                rules.add(AccessRules.Rule.parse(spec));
            }
            access = new AccessRules(denyList, rules, dotfiles, owner, worldReadable);
            if (dotfiles) {
                warnings.add("--allow-dotfiles serves names starting with '.' (e.g. .git, .env, .htpasswd); "
                        + "consider --deny for those");
            }
        } catch (IllegalArgumentException e) {
            errors.add("Invalid access configuration: " + e.getMessage());
        }

        var config = b.access(access).port(port).root(Path.of(dir)).maxConnections(max).maxConnectionsPerIp(perIp).backlog(backlog)
                .h3MaxConnectionsPerIp(h3PerIp).h3MinRate(minRate)
                .idleTimeout(Duration.ofSeconds(idle)).headerTimeout(Duration.ofSeconds(header))
                .writeTimeout(Duration.ofSeconds(write)).cacheBytes(cache).altSvc(altSvcOn).allowUnlistableDirs(unlistable)
                .h3Enabled(h3).h3Port(h3Port).h3Host(h3Host).h3MaxConnections(h3Max)
                .tlsCert(cert == null ? null : Path.of(cert)).tlsKey(key == null ? null : Path.of(key))
                .tlsKeyPassword(password).altSvcMaxAge(Duration.ofSeconds(altSvc))
                .accessLog(accessLog).accessLogFormat(accessLogFormat).metricsExposure(metricsExposure)
                .healthz(healthz)
                .h2Enabled(h2).h2Port(h2Port).h2cPort(h2 ? h2cPort : H2C_DISABLED).build();
        return new Parsed(config, List.copyOf(warnings), List.copyOf(errors), help);
    }

    private static AccessLogFormat logFormat(String value, AccessLogFormat fallback, String what, List<String> warnings) {
        if (value == null || value.isBlank()) {
            warnings.add("Missing value for " + what + " (use " + what + "=common|combined|json)");
            return fallback;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "common" -> AccessLogFormat.COMMON;
            case "combined" -> AccessLogFormat.COMBINED;
            case "json" -> AccessLogFormat.JSON;
            default -> {
                warnings.add("Invalid " + what + " (value '" + value + "'), expected common, combined or json; using "
                        + fallback.name().toLowerCase(java.util.Locale.ROOT));
                yield fallback;
            }
        };
    }

    /** {@code value == null} means the flag was given without {@code =} (bare {@code --metrics}). */
    private static MetricsExposure metricsMode(String value, MetricsExposure whenBare, String what, List<String> warnings) {
        if (value == null) {
            return whenBare;
        }
        if (value.isBlank()) {
            warnings.add("Missing value for " + what + " (use off, localhost or on)");
            return whenBare;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "on", "all", "true", "1", "yes" -> MetricsExposure.ON;
            case "off", "false", "0", "no" -> MetricsExposure.OFF;
            case "localhost", "local", "loopback" -> MetricsExposure.LOCALHOST;
            default -> {
                warnings.add("Invalid " + what + " (value '" + value + "'), expected off, localhost or on; using "
                        + whenBare.name().toLowerCase(java.util.Locale.ROOT));
                yield whenBare;
            }
        };
    }

    /** Appends the comma-separated, non-blank items of {@code value}. */
    private static void list(String value, List<String> into, String what, List<String> warnings) {
        if (value == null || value.isBlank()) {
            warnings.add("Missing value for " + what + " (use " + what + "=VALUE)");
            return;
        }
        for (var item : value.split(",")) {
            if (!item.isBlank()) {
                into.add(item.trim());
            }
        }
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

    /** Largest accepted cache size (the heap is the real limit; see the startup warning). */
    public static final long MAX_CACHE_BYTES = 16L << 30;

    /** "N", "Nk", "Nm", "Ng" (binary units, case-insensitive) in 0..{@link #MAX_CACHE_BYTES}. */
    static long byteSize(String value, long fallback, String what, List<String> warnings) {
        if (value != null) {
            var m = java.util.regex.Pattern.compile("([0-9]{1,11})([kKmMgG]?)").matcher(value.trim());
            if (m.matches()) {
                long n = Long.parseLong(m.group(1));
                int shift = switch (m.group(2).toLowerCase(java.util.Locale.ROOT)) {
                    case "k" -> 10;
                    case "m" -> 20;
                    case "g" -> 30;
                    default -> 0;
                };
                if (n <= (MAX_CACHE_BYTES >> shift)) {
                    return n << shift;
                }
            }
        }
        warnings.add("Invalid " + what + " (value '" + value + "'), expected a size such as 0, 65536, 64k, 64m or 1g"
                + " (at most 16g); using " + fallback);
        return fallback;
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
