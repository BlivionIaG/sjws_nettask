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
package sjws_nettask.config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import sjws_nettask.ServerConfig;
import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.RoutePattern;

/**
 * Loads YAML ({@code --config} / {@code WEB_SERVER_CONFIG}, environment wins) and merges it under
 * the command line and the environment. The file is strict: a bad value is an error. Existing
 * server flags stay lenient inside {@link ServerConfig#parse}. Precedence is defaults, then the
 * file, then arguments, then the environment.
 */
public final class ConfigLoader {

    private static final List<String> TOP = List.of("schema", "version", "node", "server", "http3", "tls", "acme",
            "routing", "middleware", "access", "messaging", "swarm", "ha");
    private static final java.util.regex.Pattern HOST = java.util.regex.Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final List<Integer> REDIRECT_STATUS = List.of(301, 302, 303, 307, 308);

    private ConfigLoader() {
    }

    public static Loaded load(String[] args, Map<String, String> env) {
        return load(args, env, ServerConfig::readPasswordFile);
    }

    /** A server started from a {@link ServerConfig} alone (no file, so reload has nothing to read). */
    public static Loaded standalone(ServerConfig config) {
        return new Loaded(ConfigDocument.defaults(config), List.of(), List.of(), false, new String[0], Map.of(),
                ServerConfig::readPasswordFile, null);
    }

    public static Loaded load(String[] args, Map<String, String> env, ServerConfig.PasswordFileReader passwords) {
        var arguments = args.clone();
        var environment = Map.copyOf(env);
        for (var arg : arguments) {
            if (arg.equals("-h") || arg.equals("--help")) {
                return new Loaded(null, List.of(), List.of(), true, arguments, environment, passwords, null);
            }
        }
        try {
            return loadFile(arguments, environment, passwords);
        } catch (ConfigException e) {
            return new Loaded(null, List.of(), List.of(e.getMessage()), false, arguments, environment, passwords, null);
        }
    }

    private static Loaded loadFile(String[] args, Map<String, String> env, ServerConfig.PasswordFileReader passwords)
            throws ConfigException {
        var warnings = new ArrayList<String>();
        String fromArgs = null;
        for (var arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            var name = optionName(arg);
            if (name.equals("--config")) {
                var value = optionValue(arg);
                if (value == null || value.isBlank()) {
                    throw new ConfigException("Missing value for --config (use --config=PATH)");
                }
                fromArgs = value;
            }
        }
        var fromEnv = env.get("WEB_SERVER_CONFIG");
        var configured = fromEnv != null ? fromEnv : fromArgs;
        ServerConfig baseline = null;
        AcmeSettings acme = AcmeSettings.disabled();
        Routing routing = Routing.disabled();
        List<MiddlewareSpec> middleware = List.of();
        AccessSettings access = AccessSettings.DEFAULT;
        MessagingSettings messaging = MessagingSettings.disabled();
        Path configFile = null;
        Path yamlPassword = null;
        NodeSettings node = NodeSettings.STANDALONE;
        int schemaVersion = ConfigDocument.SCHEMA;
        long bundleVersion = 0;
        Boolean yamlBind = null;
        Boolean yamlSwarm = null;
        boolean haEnabled = false;
        byte[] haKey = null;
        byte[] haPrevious = null;
        byte[] bootSignature = null;
        String recoveredHash = null;
        Path yamlState = null;
        String text;
        Path readFrom = null;
        if (configured != null) {
            var requested = Path.of(configured);
            configFile = requested;
            if (!Files.isRegularFile(requested)) {
                var state = stateDir(args, env, null, requested.toAbsolutePath().getParent());
                var json = LastGoodStore.file(state);
                var recovered = LastGoodStore.dataPlaneYaml(json);
                var source = json;
                if (recovered == null) {
                    var legacy = LastGoodStore.beside(requested);
                    recovered = LastGoodStore.dataPlaneYaml(legacy);
                    source = legacy;
                }
                if (recovered == null) {
                    throw new ConfigException("Cannot read config file " + requested.toAbsolutePath());
                }
                warnings.add("config file " + requested.toAbsolutePath()
                        + " is missing; booted from last good config " + source.toAbsolutePath());
                text = recovered;
                readFrom = source;
                bootSignature = LastGoodStore.signature(source);
                recoveredHash = ConfigCodec.sha256(recovered);
            } else {
                try {
                    text = Files.readString(requested);
                } catch (IOException e) {
                    throw new ConfigException("Cannot read config file " + requested.toAbsolutePath() + ": "
                            + e.getMessage());
                }
                readFrom = requested;
            }
            if (text.getBytes(StandardCharsets.UTF_8).length > 1_000_000) {
                throw new ConfigException("Config file " + readFrom.toAbsolutePath() + " is larger than 1MB");
            }
            var root = YamlValues.parse(text, readFrom.toString());
            YamlValues.knownKeys(root, TOP, "config");
            schemaVersion = schemaOf(root);
            bundleVersion = versionOf(root);
            node = nodeSection(root);
            var base = configFile.toAbsolutePath().getParent();
            var bindHolder = new Boolean[1];
            yamlState = stateDirKey(root, base);
            baseline = serverSection(root, base, bindHolder);
            yamlBind = bindHolder[0];
            yamlPassword = tlsPassword(root, base);
            acme = acmeSection(root, base);
            routing = routingSection(root, warnings);
            middleware = middlewareSection(root, warnings);
            access = accessSection(root, warnings);
            messaging = MessagingSettings.parse(root);
            yamlSwarm = swarmSection(root);
            var ha = haSection(root, env, base, readFrom, warnings);
            haEnabled = ha.enabled();
            haKey = ha.current();
            haPrevious = ha.previous();
        }
        var parsed = baseline == null
                ? ServerConfig.parse(args, env, passwords)
                : ServerConfig.parse(args, env, baseline, passwords);
        warnings.addAll(parsed.warnings());
        if (!parsed.ok()) {
            return new Loaded(null, warnings, parsed.errors(), parsed.helpRequested(), args, env, passwords, null);
        }
        var server = applyYamlPassword(parsed.config(), yamlPassword, args, env, passwords, warnings);
        acme = overlayAcme(acme, args, env, configFile);
        if (server.tlsCert() != null && acme.enabled()) {
            throw new ConfigException("tls.cert and acme.enabled cannot both be set");
        }
        if (acme.enabled()) {
            access = access.withAcmeChallenge();
        }
        var base = configFile == null ? null : configFile.toAbsolutePath().getParent();
        var document = ConfigDocument.of(server, acme, routing, middleware, access, node, schemaVersion, bundleVersion,
                configFile, messaging).withStateDir(stateDir(args, env, yamlState, base));
        if (bootSignature != null && recoveredHash != null && recoveredHash.equals(document.canonicalHash())) {
            document = document.withSignature(bootSignature);
        }
        return new Loaded(document, warnings, List.of(), false, args, env, passwords, yamlBind, yamlSwarm, haEnabled,
                haKey, haPrevious);
    }

    private static int schemaOf(Map<String, Object> root) {
        if (!root.containsKey("schema")) {
            return ConfigDocument.SCHEMA;
        }
        int schema = integer(root.get("schema"), "schema", 1, Integer.MAX_VALUE);
        if (schema != ConfigDocument.SCHEMA) {
            throw new ConfigException("schema: unsupported schema version " + schema);
        }
        return schema;
    }

    private static long versionOf(Map<String, Object> root) {
        if (!root.containsKey("version")) {
            return 0;
        }
        var value = root.get("version");
        long version;
        if (value instanceof Integer || value instanceof Long) {
            version = ((Number) value).longValue();
        } else if (value instanceof String text && text.trim().matches("[0-9]{1,18}")) {
            version = Long.parseLong(text.trim());
        } else {
            throw new ConfigException("version: expected an integer >= 0");
        }
        if (version < 0) {
            throw new ConfigException("version: expected an integer >= 0");
        }
        return version;
    }

    private static NodeSettings nodeSection(Map<String, Object> root) {
        if (!(root.get("node") instanceof Object node)) {
            return NodeSettings.STANDALONE;
        }
        var map = YamlValues.mapping(node, "node");
        YamlValues.knownKeys(map, List.of("mode", "labels", "state_dir"), "node");
        var mode = map.containsKey("mode")
                ? NodeSettings.Mode.parse(text(map.get("mode"), "node.mode"))
                : NodeSettings.Mode.STANDALONE;
        var labels = map.containsKey("labels") ? labels(map.get("labels")) : Map.<String, String>of();
        return new NodeSettings(mode, labels);
    }

    private static Path stateDirKey(Map<String, Object> root, Path base) {
        if (!(root.get("node") instanceof Object node)) {
            return null;
        }
        var map = YamlValues.mapping(node, "node");
        if (!map.containsKey("state_dir")) {
            return null;
        }
        var path = Path.of(text(map.get("state_dir"), "node.state_dir"));
        if (!path.isAbsolute() && base != null) {
            path = base.resolve(path);
        }
        return path;
    }

    /**
     * Environment, then {@code --state-dir}, then {@code node.state_dir}, then {@link LastGoodStore#defaultStateDir()}.
     * Paths from the file are already resolved against the file. Flag and environment paths stay relative
     * to the working directory.
     */
    private static Path stateDir(String[] args, Map<String, String> env, Path fromYaml, Path base) {
        var envDir = env.get("WEB_SERVER_STATE_DIR");
        if (envDir != null && !envDir.isBlank()) {
            return Path.of(envDir.trim()).toAbsolutePath().normalize();
        }
        String fromArgs = null;
        for (var arg : args) {
            if (!arg.startsWith("--") || !optionName(arg).equals("--state-dir")) {
                continue;
            }
            var value = optionValue(arg);
            if (value == null || value.isBlank()) {
                throw new ConfigException("Missing value for --state-dir (use --state-dir=PATH)");
            }
            fromArgs = value.trim();
        }
        if (fromArgs != null) {
            return Path.of(fromArgs).toAbsolutePath().normalize();
        }
        if (fromYaml != null) {
            var path = fromYaml.isAbsolute() || base == null ? fromYaml : base.resolve(fromYaml);
            return path.toAbsolutePath().normalize();
        }
        return LastGoodStore.defaultStateDir();
    }

    private static Map<String, String> labels(Object value) {
        var map = YamlValues.mapping(value, "node.labels");
        if (map.size() > NodeSettings.MAX_LABELS) {
            throw new ConfigException("node.labels: at most " + NodeSettings.MAX_LABELS + " entries");
        }
        var out = new LinkedHashMap<String, String>();
        for (var entry : map.entrySet()) {
            if (!NodeSettings.LABEL.matcher(entry.getKey()).matches()) {
                throw new ConfigException("node.labels: invalid key");
            }
            if (!(entry.getValue() instanceof String text) || !NodeSettings.LABEL.matcher(text).matches()) {
                throw new ConfigException("node.labels." + entry.getKey() + ": invalid value");
            }
            out.put(entry.getKey(), text);
        }
        return Map.copyOf(out);
    }

    private static ServerConfig applyYamlPassword(ServerConfig server, Path yamlPassword, String[] args,
            Map<String, String> env, ServerConfig.PasswordFileReader passwords, List<String> warnings) {
        if (yamlPassword == null || server.tlsKeyPassword() != null || !server.h3Enabled()) {
            return server;
        }
        if (env.containsKey("WEB_SERVER_TLS_KEY_PASSWORD") || env.containsKey("WEB_SERVER_TLS_KEY_PASSWORD_FILE")) {
            return server;
        }
        for (var arg : args) {
            if (arg.startsWith("--key-password-file")) {
                return server;
            }
        }
        try {
            return server.toBuilder().tlsKeyPassword(passwords.read(yamlPassword)).build();
        } catch (IOException | RuntimeException e) {
            throw new ConfigException("Cannot read the key password file " + yamlPassword + ": " + e.getMessage());
        }
    }

    private static ServerConfig serverSection(Map<String, Object> root, Path base, Boolean[] bindContent) {
        var builder = ServerConfig.builder(Path.of(sjws_nettask.CONSTANTS.DEFAULT_HTML_PATH));
        if (root.get("server") instanceof Object server) {
            var map = YamlValues.mapping(server, "server");
            YamlValues.knownKeys(map, List.of("port", "root", "max_connections", "max_connections_per_ip", "backlog",
                    "idle_timeout", "header_timeout", "write_timeout", "cache_bytes", "allow_unlistable_dirs",
                    "access_log", "bind_content"), "server");
            if (map.containsKey("bind_content")) {
                bindContent[0] = bool(map.get("bind_content"), "server.bind_content");
            }
            if (map.containsKey("port")) {
                builder.port(integer(map.get("port"), "server.port", 0, 65535));
            }
            if (map.containsKey("root")) {
                builder.root(resolve(base, text(map.get("root"), "server.root"), "server.root"));
            }
            if (map.containsKey("max_connections")) {
                builder.maxConnections(integer(map.get("max_connections"), "server.max_connections", 1, 10_000_000));
            }
            if (map.containsKey("max_connections_per_ip")) {
                builder.maxConnectionsPerIp(
                        integer(map.get("max_connections_per_ip"), "server.max_connections_per_ip", 1, 10_000_000));
            }
            if (map.containsKey("backlog")) {
                builder.backlog(integer(map.get("backlog"), "server.backlog", 1, 65535));
            }
            if (map.containsKey("idle_timeout")) {
                builder.idleTimeout(Duration.ofSeconds(seconds(map.get("idle_timeout"), "server.idle_timeout", 1, 86_400)));
            }
            if (map.containsKey("header_timeout")) {
                builder.headerTimeout(
                        Duration.ofSeconds(seconds(map.get("header_timeout"), "server.header_timeout", 1, 86_400)));
            }
            if (map.containsKey("write_timeout")) {
                builder.writeTimeout(
                        Duration.ofSeconds(seconds(map.get("write_timeout"), "server.write_timeout", 1, 86_400)));
            }
            if (map.containsKey("cache_bytes")) {
                builder.cacheBytes(Sizes.parse(sizeText(map.get("cache_bytes"), "server.cache_bytes"),
                        ServerConfig.MAX_CACHE_BYTES, "server.cache_bytes"));
            }
            if (map.containsKey("allow_unlistable_dirs")) {
                builder.allowUnlistableDirs(bool(map.get("allow_unlistable_dirs"), "server.allow_unlistable_dirs"));
            }
            if (map.containsKey("access_log")) {
                builder.accessLog(accessLog(map.get("access_log")));
            }
        }
        if (root.get("http3") instanceof Object http3) {
            var map = YamlValues.mapping(http3, "http3");
            YamlValues.knownKeys(map, List.of("enabled", "port", "host", "max_connections", "max_connections_per_ip",
                    "min_rate", "alt_svc", "alt_svc_max_age"), "http3");
            if (map.containsKey("enabled")) {
                builder.h3Enabled(bool(map.get("enabled"), "http3.enabled"));
            }
            if (map.containsKey("port")) {
                builder.h3Port(h3Port(map.get("port")));
            }
            if (map.containsKey("host")) {
                builder.h3Host(text(map.get("host"), "http3.host"));
            }
            if (map.containsKey("max_connections")) {
                builder.h3MaxConnections(integer(map.get("max_connections"), "http3.max_connections", 1, 10_000_000));
            }
            if (map.containsKey("max_connections_per_ip")) {
                builder.h3MaxConnectionsPerIp(
                        integer(map.get("max_connections_per_ip"), "http3.max_connections_per_ip", 1, 10_000_000));
            }
            if (map.containsKey("min_rate")) {
                builder.h3MinRate(integer(map.get("min_rate"), "http3.min_rate", 0, 1_000_000_000));
            }
            if (map.containsKey("alt_svc")) {
                builder.altSvc(bool(map.get("alt_svc"), "http3.alt_svc"));
            }
            if (map.containsKey("alt_svc_max_age")) {
                builder.altSvcMaxAge(Duration.ofSeconds(
                        seconds(map.get("alt_svc_max_age"), "http3.alt_svc_max_age", 0, 31_536_000)));
            }
        }
        if (root.get("tls") instanceof Object tls) {
            var map = YamlValues.mapping(tls, "tls");
            if (map.containsKey("key_password")) {
                throw new ConfigException("tls.key_password is not accepted (it would sit in the file in clear); "
                        + "use tls.key_password_file");
            }
            YamlValues.knownKeys(map, List.of("cert", "key", "key_password_file"), "tls");
            Path cert = map.containsKey("cert") ? resolve(base, text(map.get("cert"), "tls.cert"), "tls.cert") : null;
            Path key = map.containsKey("key") ? resolve(base, text(map.get("key"), "tls.key"), "tls.key") : null;
            if ((cert == null) != (key == null)) {
                throw new ConfigException("tls.cert and tls.key must be given together");
            }
            builder.tlsCert(cert).tlsKey(key);
        }
        return builder.build();
    }

    private static Path tlsPassword(Map<String, Object> root, Path base) {
        if (!(root.get("tls") instanceof Object tls)) {
            return null;
        }
        var map = YamlValues.mapping(tls, "tls");
        if (!map.containsKey("key_password_file")) {
            return null;
        }
        return resolve(base, text(map.get("key_password_file"), "tls.key_password_file"), "tls.key_password_file");
    }

    private static AcmeSettings acmeSection(Map<String, Object> root, Path base) {
        if (!(root.get("acme") instanceof Object acme)) {
            return AcmeSettings.disabled();
        }
        var map = YamlValues.mapping(acme, "acme");
        YamlValues.knownKeys(map, List.of("enabled", "directory", "email", "domains", "storage", "http_port",
                "accept_terms", "renew_before"), "acme");
        boolean enabled = map.containsKey("enabled") && bool(map.get("enabled"), "acme.enabled");
        var directory = map.containsKey("directory")
                ? directory(text(map.get("directory"), "acme.directory"), "acme.directory")
                : AcmeSettings.STAGING;
        var email = map.containsKey("email") ? text(map.get("email"), "acme.email") : "";
        var domains = map.containsKey("domains") ? domains(map.get("domains"), "acme.domains") : List.<String>of();
        var storage = map.containsKey("storage")
                ? resolve(base, text(map.get("storage"), "acme.storage"), "acme.storage")
                : Path.of("acme");
        int httpPort = map.containsKey("http_port") ? integer(map.get("http_port"), "acme.http_port", 0, 65535) : 0;
        boolean terms = map.containsKey("accept_terms") && bool(map.get("accept_terms"), "acme.accept_terms");
        var renew = map.containsKey("renew_before")
                ? renewBefore(Durations.parse(text(map.get("renew_before"), "acme.renew_before"), "acme.renew_before"))
                : AcmeSettings.DEFAULT_RENEW_BEFORE;
        if (!email.isEmpty()) {
            checkEmail(email, "acme.email");
        }
        return checkEnabled(new AcmeSettings(enabled, directory, email, domains, storage, httpPort, terms, renew));
    }

    private static AcmeSettings overlayAcme(AcmeSettings file, String[] args, Map<String, String> env, Path configFile) {
        boolean enabled = file.enabled();
        var directory = file.directory();
        var email = file.email();
        var domains = new ArrayList<>(file.domains());
        boolean domainsSet = false;
        var storage = file.storage();
        boolean storageFromFile = configFile != null;
        int httpPort = file.httpPort();
        boolean terms = file.acceptTerms();
        var renew = file.renewBefore();
        var cliDomains = new ArrayList<String>();
        for (var arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            var name = optionName(arg);
            var value = optionValue(arg);
            switch (name) {
                case "--acme" -> enabled = value == null || strictBool(value, "--acme");
                case "--no-acme" -> enabled = false;
                case "--acme-directory" -> directory = directory(required(value, name), name);
                case "--acme-email" -> email = required(value, name);
                case "--acme-domain" -> cliDomains.add(domain(required(value, name), name));
                case "--acme-storage" -> {
                    storage = Path.of(required(value, name));
                    storageFromFile = false;
                }
                case "--acme-http-port" -> httpPort = strictInt(required(value, name), name, 0, 65535);
                case "--acme-accept-terms" -> terms = value == null || strictBool(value, name);
                case "--acme-renew-before" -> renew = renewBefore(Durations.parse(required(value, name), name));
                default -> {
                    // server flags are parsed by ServerConfig
                }
            }
        }
        if (!cliDomains.isEmpty()) {
            domains = cliDomains;
            domainsSet = true;
        }
        String envValue;
        if ((envValue = env.get("WEB_SERVER_ACME")) != null) {
            enabled = strictBool(envValue, "WEB_SERVER_ACME");
        }
        if ((envValue = env.get("WEB_SERVER_ACME_DIRECTORY")) != null) {
            directory = directory(envValue, "WEB_SERVER_ACME_DIRECTORY");
        }
        if ((envValue = env.get("WEB_SERVER_ACME_EMAIL")) != null) {
            email = envValue.trim();
        }
        if ((envValue = env.get("WEB_SERVER_ACME_DOMAINS")) != null) {
            domains = new ArrayList<>();
            for (var part : envValue.split(",", -1)) {
                if (!part.isBlank()) {
                    domains.add(domain(part.trim(), "WEB_SERVER_ACME_DOMAINS"));
                }
            }
            if (domains.isEmpty()) {
                throw new ConfigException("WEB_SERVER_ACME_DOMAINS: expected at least one domain");
            }
            domainsSet = true;
        }
        if ((envValue = env.get("WEB_SERVER_ACME_STORAGE")) != null) {
            storage = Path.of(envValue.trim());
            storageFromFile = false;
        }
        if ((envValue = env.get("WEB_SERVER_ACME_HTTP_PORT")) != null) {
            httpPort = strictInt(envValue, "WEB_SERVER_ACME_HTTP_PORT", 0, 65535);
        }
        if ((envValue = env.get("WEB_SERVER_ACME_ACCEPT_TERMS")) != null) {
            terms = strictBool(envValue, "WEB_SERVER_ACME_ACCEPT_TERMS");
        }
        if ((envValue = env.get("WEB_SERVER_ACME_RENEW_BEFORE")) != null) {
            renew = renewBefore(Durations.parse(envValue.trim(), "WEB_SERVER_ACME_RENEW_BEFORE"));
        }
        if (!email.isEmpty()) {
            checkEmail(email, "acme.email");
        }
        if (!domainsSet) {
            domains = new ArrayList<>(file.domains());
        }
        if (storageFromFile && !storage.isAbsolute() && configFile != null) {
            storage = configFile.toAbsolutePath().getParent().resolve(storage).normalize();
        }
        return checkEnabled(new AcmeSettings(enabled, directory, email, domains, storage, httpPort, terms, renew));
    }

    private static AcmeSettings checkEnabled(AcmeSettings settings) {
        if (!settings.enabled()) {
            return settings;
        }
        if (settings.email().isBlank()) {
            throw new ConfigException("acme.email is required when acme.enabled is true");
        }
        if (settings.domains().isEmpty()) {
            throw new ConfigException("acme.domains needs at least one name when acme.enabled is true");
        }
        if (!settings.acceptTerms()) {
            throw new ConfigException("acme.accept_terms must be true when acme.enabled is true");
        }
        return settings;
    }

    private static Routing routingSection(Map<String, Object> root, List<String> warnings) {
        if (!(root.get("routing") instanceof Object routing)) {
            return Routing.disabled();
        }
        var map = YamlValues.mapping(routing, "routing");
        YamlValues.knownKeys(map, List.of("enabled", "upstreams", "routes", "forwarded_proto", "trusted_proxies",
                "rate_limit"), "routing");
        boolean enabled = map.containsKey("enabled") && bool(map.get("enabled"), "routing.enabled");
        var proto = map.containsKey("forwarded_proto")
                ? forwarded(text(map.get("forwarded_proto"), "routing.forwarded_proto"))
                : ForwardedProto.AUTO;
        var proxies = map.containsKey("trusted_proxies")
                ? proxies(map.get("trusted_proxies"))
                : List.<String>of();
        var upstreams = map.containsKey("upstreams") ? upstreams(map.get("upstreams")) : Map.<String, Upstream>of();
        var routes = map.containsKey("routes") ? routes(map.get("routes"), upstreams) : List.<RouteSpec>of();
        var table = new RouteTable(routes);
        warnings.addAll(table.shadows());
        var limit = map.containsKey("rate_limit") ? rateLimit(map.get("rate_limit"), "routing.rate_limit", false) : null;
        return new Routing(enabled, proto, proxies, upstreams, table, limit);
    }

    private static Map<String, Upstream> upstreams(Object value) {
        var map = YamlValues.mapping(value, "routing.upstreams");
        var out = new LinkedHashMap<String, Upstream>();
        for (var entry : map.entrySet()) {
            var name = entry.getKey();
            if (name.isBlank()) {
                throw new ConfigException("routing.upstreams: empty name");
            }
            var body = YamlValues.mapping(entry.getValue(), "routing.upstreams." + name);
            YamlValues.knownKeys(body, List.of("url", "connect_timeout", "response_timeout", "idle_read_timeout",
                    "max_body", "preserve_host", "retry", "circuit", "endpoints", "lb"), "routing.upstreams." + name);
            boolean multi = body.containsKey("endpoints");
            if (multi == body.containsKey("url")) {
                throw new ConfigException("routing.upstreams." + name + ": exactly one of url or endpoints is required");
            }
            if (body.containsKey("lb") && !multi) {
                throw new ConfigException("routing.upstreams." + name + ".lb is only valid with endpoints");
            }
            var endpointUrls = new ArrayList<URI>();
            var endpoints = multi ? endpoints(body.get("endpoints"), name, endpointUrls) : null;
            var url = multi ? endpointUrls.get(0)
                    : upstreamUrl(text(body.get("url"), "routing.upstreams." + name + ".url"), name);
            var connect = body.containsKey("connect_timeout")
                    ? timeout(text(body.get("connect_timeout"), "routing.upstreams." + name + ".connect_timeout"),
                            "routing.upstreams." + name + ".connect_timeout")
                    : Duration.ofSeconds(5);
            var response = body.containsKey("response_timeout")
                    ? timeout(text(body.get("response_timeout"), "routing.upstreams." + name + ".response_timeout"),
                            "routing.upstreams." + name + ".response_timeout")
                    : Duration.ofSeconds(30);
            var idle = body.containsKey("idle_read_timeout")
                    ? timeout(text(body.get("idle_read_timeout"), "routing.upstreams." + name + ".idle_read_timeout"),
                            "routing.upstreams." + name + ".idle_read_timeout")
                    : Duration.ofSeconds(30);
            int maxBody = body.containsKey("max_body")
                    ? maxBody(body.get("max_body"), "routing.upstreams." + name + ".max_body")
                    : 16 << 20;
            boolean preserve = body.containsKey("preserve_host")
                    && bool(body.get("preserve_host"), "routing.upstreams." + name + ".preserve_host");
            var retry = body.containsKey("retry") ? retry(body.get("retry"), "routing.upstreams." + name + ".retry") : null;
            Upstream.Discovery discovery = null;
            sjws_nettask.routing.Resilience.Circuit circuit = null;
            if (multi) {
                var lb = sjws_nettask.routing.discovery.LbPolicy.WEIGHTED_RANDOM;
                if (body.containsKey("lb")) {
                    lb = sjws_nettask.routing.discovery.LbPolicy.parse(text(body.get("lb"), "routing.upstreams." + name + ".lb"));
                    if (lb == null) {
                        throw new ConfigException("routing.upstreams." + name
                                + ".lb: expected weighted_random, round_robin or least_request");
                    }
                }
                var outlier = body.containsKey("circuit")
                        ? outlier(body.get("circuit"), "routing.upstreams." + name + ".circuit")
                        : sjws_nettask.routing.EndpointBalancer.Outlier.DEFAULT;
                discovery = new Upstream.Discovery(endpoints, lb, outlier);
            } else if (body.containsKey("circuit")) {
                circuit = circuit(body.get("circuit"), "routing.upstreams." + name + ".circuit");
            }
            var resilience = retry == null && circuit == null ? null : new sjws_nettask.routing.Resilience(retry, circuit);
            out.put(name, new Upstream(name, url, connect, response, idle, maxBody, preserve, resilience, discovery));
        }
        return Map.copyOf(out);
    }

    /**
     * {@code endpoints: ["http://h:p", {url, weight, zone, id, labels}]}: 1 to 1024 endpoints sharing
     * one scheme and path, no query or fragment.
     */
    private static List<sjws_nettask.routing.discovery.Endpoint> endpoints(Object value, String name, List<URI> urls) {
        var path = "routing.upstreams." + name + ".endpoints";
        var list = YamlValues.sequence(value, path);
        if (list.isEmpty() || list.size() > 1024) {
            throw new ConfigException(path + ": expected 1 to 1024 endpoints");
        }
        var out = new ArrayList<sjws_nettask.routing.discovery.Endpoint>(list.size());
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < list.size(); i++) {
            var at = path + "[" + i + "]";
            var item = list.get(i);
            String urlText;
            int weight = 1;
            String zone = null;
            String id = null;
            Map<String, String> labels = Map.of();
            if (item instanceof Map<?, ?>) {
                var m = YamlValues.mapping(item, at);
                YamlValues.knownKeys(m, List.of("url", "weight", "zone", "id", "labels"), at);
                if (!m.containsKey("url")) {
                    throw new ConfigException(at + ".url is required");
                }
                urlText = text(m.get("url"), at + ".url");
                weight = m.containsKey("weight") ? integer(m.get("weight"), at + ".weight", 0, 1_000_000) : 1;
                zone = m.containsKey("zone") ? text(m.get("zone"), at + ".zone") : null;
                id = m.containsKey("id") ? text(m.get("id"), at + ".id") : null;
                if (m.containsKey("labels")) {
                    var raw = YamlValues.mapping(m.get("labels"), at + ".labels");
                    var copy = new java.util.TreeMap<String, String>();
                    raw.forEach((k, v) -> copy.put(k, String.valueOf(v)));
                    labels = copy;
                }
            } else {
                urlText = text(item, at);
            }
            URI uri;
            try {
                uri = URI.create(urlText.trim());
            } catch (IllegalArgumentException e) {
                throw new ConfigException(at + ": " + e.getMessage());
            }
            var scheme = uri.getScheme();
            if (uri.getHost() == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new ConfigException(at + ": expected an absolute http(s) URL");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                throw new ConfigException(at + ": no user info, query or fragment");
            }
            if (!urls.isEmpty()) {
                var first = urls.get(0);
                if (!first.getScheme().equalsIgnoreCase(scheme) || !java.util.Objects.equals(first.getRawPath(),
                        uri.getRawPath())) {
                    throw new ConfigException(at + ": every endpoint must use the scheme and path of the first");
                }
            }
            int port = uri.getPort() > 0 ? uri.getPort() : "https".equalsIgnoreCase(scheme) ? 443 : 80;
            var host = uri.getHost();
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            var endpoint = new sjws_nettask.routing.discovery.Endpoint(id, host, port, weight, labels, sjws_nettask.routing.discovery.Endpoint.Health.READY, zone);
            if (!seen.add(endpoint.authority())) {
                throw new ConfigException(at + ": duplicate endpoint " + endpoint.authority());
            }
            if (weight == 0 && list.size() == 1) {
                throw new ConfigException(at + ".weight: the only endpoint cannot have weight 0");
            }
            urls.add(uri);
            out.add(endpoint);
        }
        return out;
    }

    /**
     * {@code circuit:} on a multi-endpoint upstream: passive ejection per endpoint,
     * {@code {failures: 5, eject_for: 10s, error_rate: 50%, min_requests: 20, window: 10s, max_ejection: 50%}}.
     */
    private static sjws_nettask.routing.EndpointBalancer.Outlier outlier(Object value, String path) {
        var map = YamlValues.mapping(value, path);
        YamlValues.knownKeys(map, List.of("failures", "eject_for", "error_rate", "min_requests", "window",
                "max_ejection"), path);
        var d = sjws_nettask.routing.EndpointBalancer.Outlier.DEFAULT;
        int failures = map.containsKey("failures") ? integer(map.get("failures"), path + ".failures", 1, 1000)
                : d.consecutive();
        var ejectFor = map.containsKey("eject_for")
                ? timeout(text(map.get("eject_for"), path + ".eject_for"), path + ".eject_for") : d.ejectFor();
        int rate = map.containsKey("error_rate") ? percent(map.get("error_rate"), path + ".error_rate", 1)
                : d.errorRatePercent();
        int min = map.containsKey("min_requests")
                ? integer(map.get("min_requests"), path + ".min_requests", 1, 1_000_000) : d.minRequests();
        var window = map.containsKey("window")
                ? timeout(text(map.get("window"), path + ".window"), path + ".window") : d.window();
        if (window.toMillis() < 2) {
            throw new ConfigException(path + ".window: must be at least 2ms");
        }
        int max = map.containsKey("max_ejection") ? percent(map.get("max_ejection"), path + ".max_ejection", 0)
                : d.maxEjectionPercent();
        return new sjws_nettask.routing.EndpointBalancer.Outlier(failures, rate, min, window, ejectFor, max);
    }

    /** {@code 50%} or {@code 50}: an integer percentage, {@code min}..100. */
    private static int percent(Object value, String path, int min) {
        var text = String.valueOf(value).trim();
        if (text.endsWith("%")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        int n;
        try {
            n = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new ConfigException(path + ": expected a percentage such as 50%");
        }
        if (n < min || n > 100) {
            throw new ConfigException(path + ": must be " + min + "%..100%");
        }
        return n;
    }

    /** {@code retry: {attempts: 2, on: [connect, reset, 502, 503, 504], budget: 20%}} */
    private static sjws_nettask.routing.Resilience.Retry retry(Object value, String path) {
        var map = YamlValues.mapping(value, path);
        YamlValues.knownKeys(map, List.of("attempts", "on", "budget"), path);
        int attempts = map.containsKey("attempts") ? integer(map.get("attempts"), path + ".attempts", 1, 5) : 2;
        var on = java.util.EnumSet.noneOf(sjws_nettask.routing.Resilience.On.class);
        if (map.containsKey("on")) {
            if (!(map.get("on") instanceof List<?> list)) {
                throw new ConfigException(path + ".on: expected a list");
            }
            for (var item : list) {
                on.add(switch (String.valueOf(item).trim().toLowerCase(java.util.Locale.ROOT)) {
                    case "connect" -> sjws_nettask.routing.Resilience.On.CONNECT;
                    case "timeout" -> sjws_nettask.routing.Resilience.On.TIMEOUT;
                    case "reset" -> sjws_nettask.routing.Resilience.On.RESET;
                    case "502" -> sjws_nettask.routing.Resilience.On.STATUS_502;
                    case "503" -> sjws_nettask.routing.Resilience.On.STATUS_503;
                    case "504" -> sjws_nettask.routing.Resilience.On.STATUS_504;
                    default -> throw new ConfigException(path + ".on: unknown '" + item
                            + "' (connect, timeout, reset, 502, 503, 504)");
                });
            }
        } else {
            on.add(sjws_nettask.routing.Resilience.On.CONNECT);
            on.add(sjws_nettask.routing.Resilience.On.RESET);
            on.add(sjws_nettask.routing.Resilience.On.STATUS_503);
        }
        int budget = 20;
        if (map.containsKey("budget")) {
            var text = String.valueOf(map.get("budget")).trim();
            if (text.endsWith("%")) {
                text = text.substring(0, text.length() - 1).trim();
            }
            try {
                budget = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new ConfigException(path + ".budget: expected a percentage such as 20%");
            }
            if (budget < 1 || budget > 100) {
                throw new ConfigException(path + ".budget: must be 1%..100%");
            }
        }
        return new sjws_nettask.routing.Resilience.Retry(attempts, on, budget);
    }

    /** {@code circuit: {failures: 5, eject_for: 10s}} */
    private static sjws_nettask.routing.Resilience.Circuit circuit(Object value, String path) {
        var map = YamlValues.mapping(value, path);
        YamlValues.knownKeys(map, List.of("failures", "eject_for"), path);
        int failures = map.containsKey("failures") ? integer(map.get("failures"), path + ".failures", 1, 1000) : 5;
        var openFor = map.containsKey("eject_for")
                ? timeout(text(map.get("eject_for"), path + ".eject_for"), path + ".eject_for") : Duration.ofSeconds(10);
        return new sjws_nettask.routing.Resilience.Circuit(failures, openFor);
    }

    private static List<RouteSpec> routes(Object value, Map<String, Upstream> upstreams) {
        var list = YamlValues.sequence(value, "routing.routes");
        var out = new ArrayList<RouteSpec>(list.size());
        for (int i = 0; i < list.size(); i++) {
            var path = "routing.routes[" + i + "]";
            var map = YamlValues.mapping(list.get(i), path);
            YamlValues.knownKeys(map, List.of("host", "prefix", "rewrite", "proxy", "backends", "sticky", "redirect",
                    "static", "listing", "errors", "problem_type", "rate_limit"), path);
            if (!map.containsKey("prefix")) {
                throw new ConfigException(path + ".prefix is required");
            }
            var prefix = prefix(text(map.get("prefix"), path + ".prefix"), path + ".prefix");
            String host = null;
            boolean wildcard = false;
            String suffix = null;
            if (map.containsKey("host")) {
                var parsed = host(text(map.get("host"), path + ".host"), path + ".host");
                host = parsed.host();
                wildcard = parsed.wildcard();
                suffix = parsed.suffix();
            }
            String rewrite = null;
            if (map.containsKey("rewrite")) {
                rewrite = prefix(text(map.get("rewrite"), path + ".rewrite"), path + ".rewrite");
            }
            int actions = (map.containsKey("proxy") ? 1 : 0) + (map.containsKey("redirect") ? 1 : 0)
                    + (map.containsKey("static") ? 1 : 0) + (map.containsKey("backends") ? 1 : 0);
            if (actions != 1) {
                throw new ConfigException(path + ": exactly one of proxy, backends, redirect or static is required");
            }
            if (map.containsKey("sticky") && !map.containsKey("backends")) {
                throw new ConfigException(path + ".sticky is only valid with backends");
            }
            RouteSpec.Action action;
            if (map.containsKey("backends")) {
                action = weighted(map, upstreams, path);
            } else if (map.containsKey("proxy")) {
                var name = text(map.get("proxy"), path + ".proxy");
                var upstream = upstreams.get(name);
                if (upstream == null) {
                    throw new ConfigException(path + ".proxy: unknown upstream '" + name + "'");
                }
                action = new RouteSpec.Action.Proxy(upstream);
            } else if (map.containsKey("redirect")) {
                action = redirect(map.get("redirect"), path + ".redirect");
            } else if (!bool(map.get("static"), path + ".static")) {
                throw new ConfigException(path + ".static must be true");
            } else {
                action = new RouteSpec.Action.Static(
                        !map.containsKey("listing") || bool(map.get("listing"), path + ".listing"));
            }
            if (map.containsKey("listing") && !(action instanceof RouteSpec.Action.Static)) {
                throw new ConfigException(path + ".listing is only valid on a static route");
            }
            var limit = map.containsKey("rate_limit") ? rateLimit(map.get("rate_limit"), path + ".rate_limit", true) : null;
            out.add(new RouteSpec(host, wildcard, suffix, prefix, rewrite, action, errors(map, path), limit));
        }
        return List.copyOf(out);
    }

    /**
     * {@code rate_limit: {by: peer|header|route, header, rate: 100/s, burst, buckets}}. {@code by: route}
     * (one bucket for the route) is only valid on a route.
     */
    private static sjws_nettask.routing.RateLimitFilter.Limit rateLimit(Object value, String path, boolean onRoute) {
        var m = YamlValues.mapping(value, path);
        YamlValues.knownKeys(m, List.of("by", "header", "rate", "burst", "buckets"), path);
        var byText = m.containsKey("by") ? text(m.get("by"), path + ".by").trim().toLowerCase(Locale.ROOT) : "peer";
        var by = switch (byText) {
            case "peer" -> sjws_nettask.routing.RateLimitFilter.By.PEER;
            case "header" -> sjws_nettask.routing.RateLimitFilter.By.HEADER;
            case "route" -> {
                if (!onRoute) {
                    throw new ConfigException(path + ".by: route is only valid on a route (use peer or header here)");
                }
                yield sjws_nettask.routing.RateLimitFilter.By.ROUTE;
            }
            default -> throw new ConfigException(path + ".by: expected peer, header or route");
        };
        String header = null;
        if (by == sjws_nettask.routing.RateLimitFilter.By.HEADER) {
            if (!m.containsKey("header")) {
                throw new ConfigException(path + ".header is required with by: header");
            }
            header = text(m.get("header"), path + ".header").trim();
            if (header.isEmpty() || !header.chars().allMatch(c -> c > 32 && c < 127 && TSPECIALS.indexOf(c) < 0)) {
                throw new ConfigException(path + ".header: invalid name '" + header + "'");
            }
        } else if (m.containsKey("header")) {
            throw new ConfigException(path + ".header is only valid with by: header");
        }
        if (!m.containsKey("rate")) {
            throw new ConfigException(path + ".rate is required (for example 100/s, 600/m, 1000/h)");
        }
        var rate = String.valueOf(m.get("rate")).trim();
        long count;
        char unit = 's';
        int slash = rate.indexOf('/');
        try {
            if (slash >= 0) {
                var u = rate.substring(slash + 1).trim();
                if (u.length() != 1 || "smh".indexOf(u.charAt(0)) < 0) {
                    throw new NumberFormatException();
                }
                unit = u.charAt(0);
                rate = rate.substring(0, slash).trim();
            }
            count = Long.parseLong(rate);
        } catch (NumberFormatException e) {
            throw new ConfigException(path + ".rate: expected N/s, N/m or N/h");
        }
        if (count < 1 || count > 1_000_000_000L) {
            throw new ConfigException(path + ".rate: must be 1..1000000000 per unit");
        }
        int burst = m.containsKey("burst") ? integer(m.get("burst"), path + ".burst", 1, 1_000_000)
                : (int) Math.min(1_000_000, unit == 's' ? count : 1);
        int buckets = m.containsKey("buckets") ? integer(m.get("buckets"), path + ".buckets", 1, 1 << 20) : 4096;
        if (Integer.bitCount(buckets) != 1) {
            throw new ConfigException(path + ".buckets: must be a power of two");
        }
        return new sjws_nettask.routing.RateLimitFilter.Limit(by, header, count, unit, burst, buckets);
    }

    private static final String TSPECIALS = "()<>@,;:\\\"/[]?={}";

    /** {@code backends: [{upstream, weight (default 1), pin}]} and {@code sticky}. */
    private static RouteSpec.Action.Weighted weighted(Map<String, Object> route, Map<String, Upstream> upstreams,
            String path) {
        var list = YamlValues.sequence(route.get("backends"), path + ".backends");
        if (list.isEmpty() || list.size() > 64) {
            throw new ConfigException(path + ".backends: expected 1 to 64 backends");
        }
        var out = new ArrayList<RouteSpec.Action.Backend>(list.size());
        long total = 0;
        for (int i = 0; i < list.size(); i++) {
            var at = path + ".backends[" + i + "]";
            var b = YamlValues.mapping(list.get(i), at);
            YamlValues.knownKeys(b, List.of("upstream", "weight", "pin"), at);
            if (!b.containsKey("upstream")) {
                throw new ConfigException(at + ".upstream is required");
            }
            var name = text(b.get("upstream"), at + ".upstream");
            var upstream = upstreams.get(name);
            if (upstream == null) {
                throw new ConfigException(at + ".upstream: unknown upstream '" + name + "'");
            }
            int weight = b.containsKey("weight") ? integer(b.get("weight"), at + ".weight", 0, 1_000_000) : 1;
            var pin = b.containsKey("pin") ? match(b.get("pin"), at + ".pin", true) : null;
            if (weight == 0 && pin == null) {
                throw new ConfigException(at + ": weight 0 needs a pin (it would never be chosen)");
            }
            total += weight;
            out.add(new RouteSpec.Action.Backend(upstream, weight, pin));
        }
        if (total == 0) {
            throw new ConfigException(path + ".backends: total weight must be > 0");
        }
        var sticky = route.containsKey("sticky") ? match(route.get("sticky"), path + ".sticky", false) : null;
        return new RouteSpec.Action.Weighted(out, sticky);
    }

    /** {@code {header: name}} or {@code {cookie: name}}, with {@code value} when {@code withValue}. */
    private static sjws_nettask.routing.WeightedHandler.Match match(Object value, String path, boolean withValue) {
        var m = YamlValues.mapping(value, path);
        YamlValues.knownKeys(m, withValue ? List.of("header", "cookie", "value") : List.of("header", "cookie"), path);
        if (m.containsKey("header") == m.containsKey("cookie")) {
            throw new ConfigException(path + ": expected exactly one of header or cookie");
        }
        boolean cookie = m.containsKey("cookie");
        var name = text(m.get(cookie ? "cookie" : "header"), path + (cookie ? ".cookie" : ".header")).trim();
        if (name.isEmpty() || !name.chars().allMatch(c -> c > 32 && c < 127 && "()<>@,;:\\\"/[]?={}".indexOf(c) < 0)) {
            throw new ConfigException(path + ": invalid name '" + name + "'");
        }
        String v = m.containsKey("value") ? String.valueOf(m.get("value")) : null;
        return new sjws_nettask.routing.WeightedHandler.Match(cookie, name, v);
    }

    /** {@code errors: html | problem} (default html) and the optional {@code problem_type} URI base. */
    private static RouteSpec.Errors errors(Map<String, Object> map, String path) {
        boolean problem = false;
        if (map.containsKey("errors")) {
            var format = text(map.get("errors"), path + ".errors");
            problem = switch (format) {
                case "html" -> false;
                case "problem" -> true;
                default -> throw new ConfigException(path + ".errors: expected html or problem");
            };
        }
        String type = null;
        if (map.containsKey("problem_type")) {
            if (!problem) {
                throw new ConfigException(path + ".problem_type requires errors: problem");
            }
            type = text(map.get("problem_type"), path + ".problem_type");
            try {
                var uri = new java.net.URI(type);
                if (!uri.isAbsolute()) {
                    throw new ConfigException(path + ".problem_type: expected an absolute URI");
                }
            } catch (java.net.URISyntaxException e) {
                throw new ConfigException(path + ".problem_type: not a URI");
            }
        }
        return problem ? new RouteSpec.Errors(true, type) : RouteSpec.Errors.HTML;
    }

    private static RouteSpec.Action.Redirect redirect(Object value, String path) {
        var map = YamlValues.mapping(value, path);
        YamlValues.knownKeys(map, List.of("status", "location", "keep_path"), path);
        if (!map.containsKey("status") || !map.containsKey("location")) {
            throw new ConfigException(path + ": status and location are required");
        }
        int status = integer(map.get("status"), path + ".status", 100, 999);
        if (!REDIRECT_STATUS.contains(status)) {
            throw new ConfigException(path + ".status: expected 301, 302, 303, 307 or 308");
        }
        var location = text(map.get("location"), path + ".location");
        if (location.indexOf('\r') >= 0 || location.indexOf('\n') >= 0) {
            throw new ConfigException(path + ".location: control characters are not allowed");
        }
        boolean keep = map.containsKey("keep_path") && bool(map.get("keep_path"), path + ".keep_path");
        return new RouteSpec.Action.Redirect(status, location, keep);
    }

    private static List<MiddlewareSpec> middlewareSection(Map<String, Object> root, List<String> warnings) {
        if (!(root.get("middleware") instanceof Object middleware)) {
            return List.of();
        }
        var list = YamlValues.sequence(middleware, "middleware");
        var specs = new ArrayList<MiddlewareSpec>(list.size());
        for (int i = 0; i < list.size(); i++) {
            var path = "middleware[" + i + "]";
            var step = YamlValues.mapping(list.get(i), path);
            YamlValues.knownKeys(step, List.of("name", "order", "route", "options"), path);
            if (!step.containsKey("name")) {
                throw new ConfigException(path + ".name is required");
            }
            int order = step.containsKey("order")
                    ? integer(step.get("order"), path + ".order", Integer.MIN_VALUE, Integer.MAX_VALUE)
                    : 0;
            String route = step.containsKey("route") ? route(step.get("route"), path + ".route") : null;
            var options = new LinkedHashMap<String, String>();
            if (step.containsKey("options") && step.get("options") != null) {
                var body = YamlValues.mapping(step.get("options"), path + ".options");
                for (var entry : body.entrySet()) {
                    options.put(entry.getKey(), scalar(entry.getValue(), path + ".options." + entry.getKey()));
                }
            }
            try {
                specs.add(MiddlewareSpec.of(text(step.get("name"), path + ".name"), order, route, options));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(path + ": " + e.getMessage());
            }
        }
        if (!specs.isEmpty()) {
            warnings.add("middleware is stored but not applied");
        }
        return List.copyOf(specs);
    }

    /**
     * {@code *} and blank match every request and are stored as null. Otherwise {@code /exact},
     * {@code /prefix/*}, or {@code /*}, checked with {@link RoutePattern#parse}.
     */
    private static String route(Object value, String path) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String raw)) {
            throw new ConfigException(path + ": expected a string");
        }
        try {
            if (RoutePattern.parse(raw).isAll()) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path + ": " + e.getMessage());
        }
        return raw.trim();
    }

    /**
     * HA heartbeat key. Not part of the canonical document, so a last-good copy never stores it.
     * When {@code ha.enabled} is false the key bytes are discarded and {@link ClusterKey} is not
     * resolved. A missing key with HA enabled is an error. The environment overrides the file.
     * Values are never copied into the message. An inline secret in a group- or world-readable
     * file is a warning. {@code ha.cluster_key_file} must be mode 0600 or 0400 and owned by this
     * process. The leaf is not followed. A group- or world-writable parent is refused unless it
     * is sticky and owned by root or this process.
     */
    private static HaMaterial haSection(Map<String, Object> root, Map<String, String> env, Path base, Path configFile,
            List<String> warnings) {
        boolean enabled = false;
        String fileKey = null;
        String filePrevious = null;
        String keyFile = null;
        String previousFile = null;
        if (root.get("ha") instanceof Object ha) {
            var map = YamlValues.mapping(ha, "ha");
            YamlValues.knownKeys(map, List.of("enabled", "cluster_key", "cluster_key_previous", "cluster_key_file",
                    "cluster_key_previous_file"), "ha");
            if (map.containsKey("enabled")) {
                enabled = bool(map.get("enabled"), "ha.enabled");
            }
            if (map.containsKey("cluster_key")) {
                fileKey = secret(map.get("cluster_key"), "ha.cluster_key");
            }
            if (map.containsKey("cluster_key_previous")) {
                filePrevious = secret(map.get("cluster_key_previous"), "ha.cluster_key_previous");
            }
            if (map.containsKey("cluster_key_file")) {
                keyFile = text(map.get("cluster_key_file"), "ha.cluster_key_file");
            }
            if (map.containsKey("cluster_key_previous_file")) {
                previousFile = text(map.get("cluster_key_previous_file"), "ha.cluster_key_previous_file");
            }
        }
        if (fileKey != null && keyFile != null) {
            throw new ConfigException("ha.cluster_key and ha.cluster_key_file cannot both be set");
        }
        if (filePrevious != null && previousFile != null) {
            throw new ConfigException("ha.cluster_key_previous and ha.cluster_key_previous_file cannot both be set");
        }
        if (fileKey != null || filePrevious != null) {
            warnIfGroupReadable(configFile, warnings);
        }
        if (!enabled) {
            return HaMaterial.OFF;
        }
        String current = env.containsKey("WEB_SERVER_HA_CLUSTER_KEY") ? env.get("WEB_SERVER_HA_CLUSTER_KEY")
                : keyFile != null ? readKeyFile(resolve(base, keyFile, "ha.cluster_key_file"), "ha.cluster_key_file")
                        : fileKey;
        String previous = env.containsKey("WEB_SERVER_HA_CLUSTER_KEY_PREVIOUS")
                ? env.get("WEB_SERVER_HA_CLUSTER_KEY_PREVIOUS")
                : previousFile != null
                        ? readKeyFile(resolve(base, previousFile, "ha.cluster_key_previous_file"),
                                "ha.cluster_key_previous_file")
                        : filePrevious;
        if (current == null || current.isBlank()) {
            throw new ConfigException(
                    "HA is enabled but no cluster key is set; set ha.cluster_key or WEB_SERVER_HA_CLUSTER_KEY");
        }
        if (previous != null && previous.isBlank()) {
            throw new ConfigException("WEB_SERVER_HA_CLUSTER_KEY_PREVIOUS is empty");
        }
        return HaMaterial.enabled(current, previous);
    }

    /** Inline key material sits in this file. Group or world read is a warning, not a refusal. */
    private static void warnIfGroupReadable(Path configFile, List<String> warnings) {
        if (configFile == null) {
            return;
        }
        try {
            var perms = Files.getPosixFilePermissions(configFile);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                warnings.add("config file " + configFile + " is readable by group/others and contains ha.cluster_key; "
                        + "chmod 600 it, or use ha.cluster_key_file");
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // no POSIX permissions to check
        }
    }

    /**
     * Key file via {@link SecureKeyFile}: only the parent is resolved, the leaf is opened once
     * with {@code NOFOLLOW_LINKS}, and a group- or world-writable parent is refused. At most
     * 4 KiB. The bytes are not part of the error text. Java keeps the characters in a
     * {@code String}; they are not wiped.
     */
    private static String readKeyFile(Path path, String name) {
        return new String(SecureKeyFile.read(path, name, 4096), StandardCharsets.UTF_8);
    }

    /** UTF-8 secret. Blank is refused. The value is not included in the error. */
    private static String secret(Object value, String path) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ConfigException(path + ": expected a string");
        }
        return text;
    }

    /**
     * {@code swarm.enabled} (docs/design/agent-swarm.md §3.3, §12 S1), or null when the file has no {@code swarm}
     * section. Node-local like {@code server.bind_content}: not part of the canonical document, so a config
     * recovered from last-good boots with the swarm off unless the property or environment gate is set.
     */
    private static Boolean swarmSection(Map<String, Object> root) {
        if (!(root.get("swarm") instanceof Object swarm)) {
            return null;
        }
        var map = YamlValues.mapping(swarm, "swarm");
        YamlValues.knownKeys(map, List.of("enabled"), "swarm");
        return map.containsKey("enabled") ? bool(map.get("enabled"), "swarm.enabled") : null;
    }

    private static AccessSettings accessSection(Map<String, Object> root, List<String> warnings) {
        if (!(root.get("access") instanceof Object access)) {
            return AccessSettings.DEFAULT;
        }
        var map = YamlValues.mapping(access, "access");
        YamlValues.knownKeys(map, List.of("deny", "rules", "allow_dotfiles", "require_owner",
                "require_world_readable"), "access");
        var deny = map.containsKey("deny") ? patterns(map.get("deny"), "access.deny") : List.<String>of();
        var rules = map.containsKey("rules") ? rules(map.get("rules"), "access.rules") : List.<String>of();
        boolean dotfiles = map.containsKey("allow_dotfiles") && bool(map.get("allow_dotfiles"), "access.allow_dotfiles");
        String owner = null;
        if (map.containsKey("require_owner") && map.get("require_owner") != null) {
            owner = text(map.get("require_owner"), "access.require_owner");
        }
        boolean world = map.containsKey("require_world_readable")
                && bool(map.get("require_world_readable"), "access.require_world_readable");
        var settings = new AccessSettings(deny, rules, dotfiles, owner, world);
        return settings;
    }

    private static List<String> patterns(Object value, String path) {
        var out = new ArrayList<String>();
        if (value instanceof String text) {
            if (text.isBlank()) {
                return List.of();
            }
            for (var item : text.split(",")) {
                if (!item.isBlank()) {
                    out.add(item.trim());
                }
            }
            return List.copyOf(out);
        }
        for (var item : YamlValues.sequence(value, path)) {
            out.add(text(item, path));
        }
        return List.copyOf(out);
    }

    private static List<String> rules(Object value, String path) {
        var list = YamlValues.sequence(value, path);
        var out = new ArrayList<String>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(rule(text(list.get(i), path + "[" + i + "]"), path + "[" + i + "]"));
        }
        return List.copyOf(out);
    }

    /** {@code allow:PATTERN}, {@code deny:PATTERN} or {@code hide:PATTERN}. */
    private static String rule(String raw, String path) {
        int colon = raw.indexOf(':');
        if (colon <= 0) {
            throw new ConfigException(path + ": expected allow:, deny: or hide:PATTERN");
        }
        var action = raw.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        if (!action.equals("allow") && !action.equals("deny") && !action.equals("hide")) {
            throw new ConfigException(path + ": expected allow:, deny: or hide:");
        }
        var pattern = raw.substring(colon + 1).trim();
        if (pattern.isEmpty()) {
            throw new ConfigException(path + ": pattern is required");
        }
        return action + ":" + pattern;
    }

    private static String directory(String value, String path) {
        var text = value.trim();
        if (text.equalsIgnoreCase("staging")) {
            return AcmeSettings.STAGING;
        }
        if (text.equalsIgnoreCase("production")) {
            return AcmeSettings.PRODUCTION;
        }
        if (text.startsWith("https://") || text.startsWith("http://") || text.startsWith("acme://")) {
            return text;
        }
        throw new ConfigException(path + ": expected staging, production, or an http(s):// or acme:// URL");
    }

    private static Duration renewBefore(Duration duration) {
        if (duration.compareTo(Duration.ofDays(89)) > 0) {
            throw new ConfigException("acme.renew_before must be at most 89 days");
        }
        return duration;
    }

    private static void checkEmail(String email, String path) {
        if (email.indexOf('@') <= 0 || email.indexOf(' ') >= 0 || email.indexOf('\r') >= 0 || email.indexOf('\n') >= 0
                || email.indexOf('@') != email.lastIndexOf('@')) {
            throw new ConfigException(path + ": expected an email address");
        }
    }

    private static List<String> domains(Object value, String path) {
        var list = YamlValues.sequence(value, path);
        var out = new ArrayList<String>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(domain(text(list.get(i), path + "[" + i + "]"), path + "[" + i + "]"));
        }
        return List.copyOf(out);
    }

    private static String domain(String value, String path) {
        var name = value.trim().toLowerCase(Locale.ROOT);
        if (name.indexOf('*') >= 0) {
            throw new ConfigException(path + ": wildcards are not supported (HTTP-01)");
        }
        if (!HOST.matcher(name).matches()) {
            throw new ConfigException(path + ": expected a domain name");
        }
        return name;
    }

    private static ForwardedProto forwarded(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "http" -> ForwardedProto.HTTP;
            case "https" -> ForwardedProto.HTTPS;
            case "auto" -> ForwardedProto.AUTO;
            default -> throw new ConfigException("routing.forwarded_proto: expected http, https or auto");
        };
    }

    private static List<String> proxies(Object value) {
        var list = YamlValues.sequence(value, "routing.trusted_proxies");
        var out = new ArrayList<String>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(cidr(text(list.get(i), "routing.trusted_proxies[" + i + "]"), i));
        }
        return List.copyOf(out);
    }

    private static String cidr(String value, int index) {
        var path = "routing.trusted_proxies[" + index + "]";
        var text = value.trim();
        int slash = text.lastIndexOf('/');
        var literal = slash < 0 ? text : text.substring(0, slash);
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(literal);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path + ": expected an IP address or CIDR, not a name");
        }
        if (slash >= 0) {
            int max = address.getAddress().length == 4 ? 32 : 128;
            int prefix;
            try {
                prefix = Integer.parseInt(text.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new ConfigException(path + ": bad prefix length");
            }
            if (prefix < 0 || prefix > max) {
                throw new ConfigException(path + ": prefix length must be 0.." + max);
            }
        }
        return text;
    }

    private static URI upstreamUrl(String value, String name) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("routing.upstreams." + name + ".url: " + e.getMessage());
        }
        var scheme = uri.getScheme();
        if (uri.getHost() == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new ConfigException("routing.upstreams." + name + ".url: expected an absolute http(s) URL");
        }
        return uri;
    }

    private static Duration timeout(String value, String path) {
        var duration = Durations.parse(value, path);
        if (duration.toMillis() > Integer.MAX_VALUE) {
            throw new ConfigException(path + ": duration is too long");
        }
        return duration;
    }

    private static int maxBody(Object value, String path) {
        long bytes = Sizes.parse(sizeText(value, path), Integer.MAX_VALUE, path);
        if (bytes < 1) {
            throw new ConfigException(path + ": must be at least 1 byte");
        }
        return (int) bytes;
    }

    private static String prefix(String value, String path) {
        if (value.isEmpty() || value.charAt(0) != '/' || value.indexOf('?') >= 0 || value.indexOf('#') >= 0
                || value.indexOf('\\') >= 0 || value.contains("//")) {
            throw new ConfigException(path + ": expected a path such as / or /api");
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) <= ' ' || value.charAt(i) == 127) {
                throw new ConfigException(path + ": control characters are not allowed");
            }
        }
        return value;
    }

    private record HostPattern(String host, boolean wildcard, String suffix) {
    }

    /**
     * {@code [v6]} route host: a real IPv6 literal (parsed without DNS, no zone id, no port),
     * returned lower-case without brackets; {@link RouteSpec#routerHost()} brackets it again
     * as {@code [::1]}, the form a Host header carries.
     */
    private static String ipv6Literal(String text, String path) {
        var bad = new ConfigException(path + ": expected a bracketed IPv6 literal such as [::1] (no port)");
        if (text.length() < 3 || text.indexOf(']') != text.length() - 1) {
            throw bad;
        }
        var inner = text.substring(1, text.length() - 1);
        if (inner.indexOf(':') < 0 || inner.indexOf('%') >= 0 || inner.indexOf('[') >= 0) {
            throw bad;
        }
        try {
            if (!(InetAddress.ofLiteral(inner) instanceof java.net.Inet6Address)) {
                throw bad;
            }
        } catch (IllegalArgumentException e) {
            throw bad;
        }
        return inner;
    }

    private static HostPattern host(String value, String path) {
        var text = value.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("*.")) {
            var rest = text.substring(2);
            if (rest.indexOf('*') >= 0 || !HOST.matcher(rest).matches() || rest.indexOf('.') < 0) {
                throw new ConfigException(path + ": expected a name or *.suffix");
            }
            return new HostPattern(text, true, "." + rest);
        }
        if (text.startsWith("[")) {
            return new HostPattern(ipv6Literal(text, path), false, null);
        }
        if (text.indexOf('*') >= 0 || text.indexOf(':') >= 0) {
            throw new ConfigException(path + ": expected a name, *.suffix or [ipv6]");
        }
        try {
            InetAddress.ofLiteral(text);
            return new HostPattern(text, false, null);
        } catch (IllegalArgumentException e) {
            if (!HOST.matcher(text).matches()) {
                throw new ConfigException(path + ": expected a name or *.suffix");
            }
            return new HostPattern(text, false, null);
        }
    }

    private static String accessLog(Object value) {
        if (value == null) {
            return null;
        }
        var text = text(value, "server.access_log");
        if (text.isBlank() || text.equalsIgnoreCase("off")) {
            return null;
        }
        return text;
    }

    private static int h3Port(Object value) {
        if (value instanceof String text && text.equalsIgnoreCase("same")) {
            return ServerConfig.H3_PORT_SAME_AS_TCP;
        }
        return integer(value, "http3.port", -1, 65535);
    }

    private static int seconds(Object value, String path, int min, int max) {
        if (value instanceof String text) {
            if (min == 0 && text.trim().equals("0")) {
                return 0;
            }
            var duration = Durations.parse(text, path);
            if (duration.getNano() != 0) {
                throw new ConfigException(path + ": must be a whole number of seconds");
            }
            return integer(duration.toSeconds(), path, min, max);
        }
        return integer(value, path, min, max);
    }

    private static int integer(Object value, String path, int min, int max) {
        if (value instanceof Integer || value instanceof Long) {
            return integer(((Number) value).longValue(), path, min, max);
        }
        if (value instanceof String text && text.trim().matches("-?[0-9]{1,18}")) {
            return integer(Long.parseLong(text.trim()), path, min, max);
        }
        throw new ConfigException(path + ": expected an integer");
    }

    private static int integer(long value, String path, int min, int max) {
        if (value < min || value > max) {
            throw new ConfigException(path + ": expected " + min + ".." + max);
        }
        return (int) value;
    }

    private static int strictInt(String value, String path, int min, int max) {
        if (value == null || !value.trim().matches("[0-9]{1,9}")) {
            throw new ConfigException("Invalid " + path + " (value '" + value + "'), expected " + min + ".." + max);
        }
        return integer(Long.parseLong(value.trim()), path, min, max);
    }

    private static boolean bool(Object value, String path) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return strictBool(text, path);
        }
        throw new ConfigException(path + ": expected true or false");
    }

    private static boolean strictBool(String value, String path) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new ConfigException(path + ": expected true or false");
        };
    }

    private static String text(Object value, String path) {
        if (value instanceof String text) {
            if (text.isBlank()) {
                throw new ConfigException(path + ": missing value");
            }
            return text.trim();
        }
        throw new ConfigException(path + ": expected a string");
    }

    private static String scalar(Object value, String path) {
        if (value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return String.valueOf(value);
        }
        throw new ConfigException(path + ": expected a scalar");
    }

    private static String sizeText(Object value, String path) {
        if (value instanceof Integer || value instanceof Long) {
            return Long.toString(((Number) value).longValue());
        }
        return text(value, path);
    }

    private static Path resolve(Path base, String raw, String path) {
        var file = Path.of(raw);
        if (file.isAbsolute()) {
            return file.normalize();
        }
        if (base == null) {
            throw new ConfigException(path + ": relative path with no config directory");
        }
        return base.resolve(file).normalize();
    }

    private static String optionName(String arg) {
        int eq = arg.indexOf('=');
        return eq < 0 ? arg : arg.substring(0, eq);
    }

    private static String optionValue(String arg) {
        int eq = arg.indexOf('=');
        return eq < 0 ? null : arg.substring(eq + 1);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ConfigException("Missing value for " + name + " (use " + name + "=VALUE)");
        }
        return value.trim();
    }

    /** Key bytes kept only while HA is enabled. {@link #toString()} redacts them. */
    private static final class HaMaterial {
        private static final HaMaterial OFF = new HaMaterial(false, null, null);

        private final boolean enabled;
        private final byte[] current;
        private final byte[] previous;

        private HaMaterial(boolean enabled, byte[] current, byte[] previous) {
            this.enabled = enabled;
            this.current = current;
            this.previous = previous;
        }

        static HaMaterial enabled(String current, String previous) {
            try {
                byte[] prev = previous == null ? null : ClusterKey.parse(previous);
                return new HaMaterial(true, ClusterKey.parse(current), prev);
            } catch (IllegalArgumentException failed) {
                throw new ConfigException(failed.getMessage());
            }
        }

        boolean enabled() {
            return this.enabled;
        }

        byte[] current() {
            return this.current;
        }

        byte[] previous() {
            return this.previous;
        }
    }

    /** Arguments and environment captured at startup so a reload reapplies them (the process env does not change). */
    public static final class Loaded {
        private final ConfigDocument document;
        private final List<String> warnings;
        private final List<String> errors;
        private final boolean helpRequested;
        private final String[] args;
        private final Map<String, String> env;
        private final ServerConfig.PasswordFileReader passwords;
        /** {@code server.bind_content} from the file, or null when the key was omitted. */
        private final Boolean yamlBindContent;
        /** {@code swarm.enabled} from the file, or null when the key was omitted. */
        private final Boolean yamlSwarmEnabled;
        /** True when {@code ha.enabled} is true. The key bytes are null unless this is true. */
        private final boolean haEnabled;
        /** Current HMAC key, or null when HA is off. Not part of {@link #toString()}. */
        private final byte[] haClusterKey;
        /** Previous HMAC key during rotation, or null. Not part of {@link #toString()}. */
        private final byte[] haClusterKeyPrevious;

        Loaded(ConfigDocument document, List<String> warnings, List<String> errors, boolean helpRequested,
                String[] args, Map<String, String> env, ServerConfig.PasswordFileReader passwords,
                Boolean yamlBindContent) {
            this(document, warnings, errors, helpRequested, args, env, passwords, yamlBindContent, null, false, null,
                    null);
        }

        Loaded(ConfigDocument document, List<String> warnings, List<String> errors, boolean helpRequested,
                String[] args, Map<String, String> env, ServerConfig.PasswordFileReader passwords,
                Boolean yamlBindContent, Boolean yamlSwarmEnabled) {
            this(document, warnings, errors, helpRequested, args, env, passwords, yamlBindContent, yamlSwarmEnabled,
                    false, null, null);
        }

        Loaded(ConfigDocument document, List<String> warnings, List<String> errors, boolean helpRequested,
                String[] args, Map<String, String> env, ServerConfig.PasswordFileReader passwords,
                Boolean yamlBindContent, Boolean yamlSwarmEnabled, boolean haEnabled, byte[] haClusterKey,
                byte[] haClusterKeyPrevious) {
            this.document = document;
            this.warnings = List.copyOf(warnings);
            this.errors = List.copyOf(errors);
            this.helpRequested = helpRequested;
            this.args = args.clone();
            this.env = Map.copyOf(env);
            this.passwords = passwords;
            this.yamlBindContent = yamlBindContent;
            this.yamlSwarmEnabled = yamlSwarmEnabled;
            this.haEnabled = haEnabled;
            this.haClusterKey = haClusterKey == null ? null : haClusterKey.clone();
            this.haClusterKeyPrevious = haClusterKeyPrevious == null ? null : haClusterKeyPrevious.clone();
        }

        /** Same load, with the document replaced. The file's {@code bind_content}, swarm flag, and HA key are kept. */
        public Loaded withDocument(ConfigDocument document) {
            return new Loaded(document, this.warnings, this.errors, this.helpRequested, this.args, this.env,
                    this.passwords, this.yamlBindContent, this.yamlSwarmEnabled, this.haEnabled, this.haClusterKey,
                    this.haClusterKeyPrevious);
        }

        /** {@code ha.enabled}. False when the file omits it. */
        public boolean haEnabled() {
            return this.haEnabled;
        }

        /** @return a copy of the current cluster key, or null when HA is off. Do not log the bytes. */
        public byte[] haClusterKey() {
            return this.haClusterKey == null ? null : this.haClusterKey.clone();
        }

        /** @return a copy of the previous cluster key, or null. Do not log the bytes. */
        public byte[] haClusterKeyPrevious() {
            return this.haClusterKeyPrevious == null ? null : this.haClusterKeyPrevious.clone();
        }

        @Override
        public String toString() {
            return "Loaded{ok=" + ok() + ", haEnabled=" + this.haEnabled + ", clusterKey="
                    + (this.haClusterKey == null ? "none" : "****") + ", clusterKeyPrevious="
                    + (this.haClusterKeyPrevious == null ? "none" : "****") + "}";
        }

        /** {@code swarm.enabled}, or null when the file did not set it. Not part of the canonical document. */
        public Boolean yamlSwarmEnabled() {
            return this.yamlSwarmEnabled;
        }

        /** {@code server.bind_content}, or null when the file did not set it. Not part of the canonical document. */
        public Boolean yamlBindContent() {
            return this.yamlBindContent;
        }

        public boolean ok() {
            return this.errors.isEmpty();
        }

        public boolean helpRequested() {
            return this.helpRequested;
        }

        public ConfigDocument document() {
            return this.document;
        }

        public List<String> warnings() {
            return this.warnings;
        }

        public List<String> errors() {
            return this.errors;
        }

        String[] args() {
            return this.args.clone();
        }

        Map<String, String> env() {
            return this.env;
        }

        ServerConfig.PasswordFileReader passwords() {
            return this.passwords;
        }
    }
}
