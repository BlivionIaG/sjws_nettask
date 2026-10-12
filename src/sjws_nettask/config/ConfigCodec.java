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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.MiddlewareSpec;

/**
 * Canonical YAML of a {@link ConfigDocument}. Key order is fixed and maps are sorted, so the
 * SHA-256 does not change when the source file only reorders keys. The TLS key password is not
 * written: it is not a file field, and a diff or a last-good copy must not carry it.
 */
public final class ConfigCodec {

    private ConfigCodec() {
    }

    public static String text(ConfigDocument document) {
        var out = new StringBuilder();
        out.append("schema: ").append(document.schemaVersion()).append('\n');
        out.append("version: ").append(document.bundleVersion()).append('\n');
        node(out, document.node());
        server(out, document.server());
        http3(out, document.server());
        tls(out, document.server());
        acme(out, document.acme());
        routing(out, document.routing());
        middleware(out, document.middleware());
        access(out, document.access());
        return out.toString();
    }

    public static String sha256(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void node(StringBuilder out, NodeSettings node) {
        out.append("node:\n");
        out.append("  mode: ").append(node.mode().wire()).append('\n');
        out.append("  labels:\n");
        if (node.labels().isEmpty()) {
            out.append("    {}\n");
            return;
        }
        for (var key : sorted(node.labels())) {
            out.append("    ").append(yamlKey(key)).append(": ").append(quote(node.labels().get(key))).append('\n');
        }
    }

    private static void server(StringBuilder out, ServerConfig server) {
        out.append("server:\n");
        out.append("  port: ").append(server.port()).append('\n');
        out.append("  root: ").append(quote(server.root().toString())).append('\n');
        out.append("  max_connections: ").append(server.maxConnections()).append('\n');
        out.append("  max_connections_per_ip: ").append(server.maxConnectionsPerIp()).append('\n');
        out.append("  backlog: ").append(server.backlog()).append('\n');
        out.append("  idle_timeout: ").append(server.idleTimeout().toSeconds()).append('\n');
        out.append("  header_timeout: ").append(server.headerTimeout().toSeconds()).append('\n');
        out.append("  write_timeout: ").append(server.writeTimeout().toSeconds()).append('\n');
        out.append("  cache_bytes: ").append(server.cacheBytes()).append('\n');
        out.append("  allow_unlistable_dirs: ").append(server.allowUnlistableDirs()).append('\n');
        if (server.accessLog() != null) {
            out.append("  access_log: ").append(quote(server.accessLog())).append('\n');
        }
    }

    private static void http3(StringBuilder out, ServerConfig server) {
        out.append("http3:\n");
        out.append("  enabled: ").append(server.h3Enabled()).append('\n');
        if (server.h3Port() == ServerConfig.H3_PORT_SAME_AS_TCP) {
            out.append("  port: same\n");
        } else {
            out.append("  port: ").append(server.h3Port()).append('\n');
        }
        out.append("  host: ").append(quote(server.h3Host())).append('\n');
        out.append("  max_connections: ").append(server.h3MaxConnections()).append('\n');
        out.append("  max_connections_per_ip: ").append(server.h3MaxConnectionsPerIp()).append('\n');
        out.append("  min_rate: ").append(server.h3MinRate()).append('\n');
        out.append("  alt_svc: ").append(server.altSvc()).append('\n');
        out.append("  alt_svc_max_age: ").append(server.altSvcMaxAge().toSeconds()).append('\n');
    }

    private static void tls(StringBuilder out, ServerConfig server) {
        if (server.tlsCert() == null && server.tlsKey() == null) {
            return;
        }
        out.append("tls:\n");
        if (server.tlsCert() != null) {
            out.append("  cert: ").append(quote(server.tlsCert().toString())).append('\n');
        }
        if (server.tlsKey() != null) {
            out.append("  key: ").append(quote(server.tlsKey().toString())).append('\n');
        }
    }

    private static void acme(StringBuilder out, AcmeSettings acme) {
        out.append("acme:\n");
        out.append("  enabled: ").append(acme.enabled()).append('\n');
        out.append("  directory: ").append(quote(acme.directory())).append('\n');
        if (!acme.email().isEmpty()) {
            out.append("  email: ").append(quote(acme.email())).append('\n');
        }
        if (!acme.domains().isEmpty()) {
            out.append("  domains: [");
            for (int i = 0; i < acme.domains().size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(quote(acme.domains().get(i)));
            }
            out.append("]\n");
        }
        out.append("  storage: ").append(quote(acme.storage().toString())).append('\n');
        out.append("  http_port: ").append(acme.httpPort()).append('\n');
        out.append("  accept_terms: ").append(acme.acceptTerms()).append('\n');
        out.append("  renew_before: ").append(quote(duration(acme.renewBefore()))).append('\n');
    }

    private static void routing(StringBuilder out, Routing routing) {
        if (!routing.enabled() && routing.trustedProxies().isEmpty() && routing.upstreams().isEmpty()
                && routing.routes().routes().isEmpty() && routing.forwardedProto() == ForwardedProto.AUTO) {
            return;
        }
        out.append("routing:\n");
        out.append("  enabled: ").append(routing.enabled()).append('\n');
        out.append("  forwarded_proto: ").append(routing.forwardedProto().name().toLowerCase(java.util.Locale.ROOT))
                .append('\n');
        if (!routing.trustedProxies().isEmpty()) {
            out.append("  trusted_proxies: [");
            for (int i = 0; i < routing.trustedProxies().size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(quote(routing.trustedProxies().get(i)));
            }
            out.append("]\n");
        }
        if (!routing.upstreams().isEmpty()) {
            out.append("  upstreams:\n");
            for (var name : sorted(routing.upstreams())) {
                var upstream = routing.upstreams().get(name);
                out.append("    ").append(yamlKey(name)).append(":\n");
                out.append("      url: ").append(quote(upstream.url().toString())).append('\n');
                out.append("      connect_timeout: ").append(quote(duration(upstream.connectTimeout()))).append('\n');
                out.append("      response_timeout: ").append(quote(duration(upstream.responseTimeout())))
                        .append('\n');
                out.append("      idle_read_timeout: ").append(quote(duration(upstream.idleReadTimeout())))
                        .append('\n');
                out.append("      max_body: ").append(upstream.maxBody()).append('\n');
                out.append("      preserve_host: ").append(upstream.preserveHost()).append('\n');
            }
        }
        if (!routing.routes().routes().isEmpty()) {
            out.append("  routes:\n");
            for (var route : routing.routes().routes()) {
                out.append("    - prefix: ").append(quote(route.prefix())).append('\n');
                if (route.host() != null) {
                    out.append("      host: ").append(quote(route.host())).append('\n');
                }
                if (route.rewrite() != null) {
                    out.append("      rewrite: ").append(quote(route.rewrite())).append('\n');
                }
                switch (route.action()) {
                    case RouteSpec.Action.Proxy proxy ->
                        out.append("      proxy: ").append(quote(proxy.upstream().name())).append('\n');
                    case RouteSpec.Action.Redirect redirect -> {
                        out.append("      redirect:\n");
                        out.append("        status: ").append(redirect.status()).append('\n');
                        out.append("        location: ").append(quote(redirect.location())).append('\n');
                        out.append("        keep_path: ").append(redirect.keepPath()).append('\n');
                    }
                    case RouteSpec.Action.Static ignored -> out.append("      static: true\n");
                }
            }
        }
    }

    private static void middleware(StringBuilder out, List<MiddlewareSpec> middleware) {
        if (middleware.isEmpty()) {
            return;
        }
        out.append("middleware:\n");
        for (var step : middleware) {
            out.append("  - name: ").append(quote(step.name())).append('\n');
            out.append("    order: ").append(step.order()).append('\n');
            if (step.route() != null) {
                out.append("    route: ").append(quote(step.route())).append('\n');
            }
            if (!step.options().isEmpty()) {
                out.append("    options:\n");
                for (var key : sorted(step.options())) {
                    out.append("      ").append(yamlKey(key)).append(": ").append(quote(step.options().get(key)))
                            .append('\n');
                }
            }
        }
    }

    private static void access(StringBuilder out, AccessSettings access) {
        if (access.isDefault()) {
            return;
        }
        out.append("access:\n");
        if (!access.deny().isEmpty()) {
            out.append("  deny: [");
            join(out, access.deny());
            out.append("]\n");
        }
        if (!access.rules().isEmpty()) {
            out.append("  rules:\n");
            for (var rule : access.rules()) {
                out.append("    - ").append(quote(rule)).append('\n');
            }
        }
        out.append("  allow_dotfiles: ").append(access.allowDotfiles()).append('\n');
        if (access.requireOwner() != null) {
            out.append("  require_owner: ").append(quote(access.requireOwner())).append('\n');
        }
        out.append("  require_world_readable: ").append(access.requireWorldReadable()).append('\n');
    }

    private static void join(StringBuilder out, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(quote(values.get(i)));
        }
    }

    private static String duration(Duration duration) {
        if (duration.toNanosPart() == 0 && duration.toSeconds() % 86_400 == 0 && duration.toSeconds() >= 86_400) {
            return (duration.toSeconds() / 86_400) + "d";
        }
        if (duration.toNanosPart() == 0 && duration.toSeconds() % 3600 == 0 && duration.toSeconds() >= 3600) {
            return (duration.toSeconds() / 3600) + "h";
        }
        if (duration.toNanosPart() == 0 && duration.toSeconds() % 60 == 0 && duration.toSeconds() >= 60) {
            return (duration.toSeconds() / 60) + "m";
        }
        if (duration.toNanosPart() == 0) {
            return duration.toSeconds() + "s";
        }
        return duration.toMillis() + "ms";
    }

    private static List<String> sorted(Map<String, ?> map) {
        var keys = new ArrayList<>(map.keySet());
        keys.sort(String::compareTo);
        return keys;
    }

    private static String yamlKey(String key) {
        if (key.matches("[A-Za-z0-9_.:-]{1,63}")) {
            return key;
        }
        return quote(key);
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                + "\"";
    }
}
