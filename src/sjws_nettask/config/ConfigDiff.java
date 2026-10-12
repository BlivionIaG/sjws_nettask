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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.MiddlewareSpec;

/**
 * Which sections differ between two documents. One entry per section. Each key in that section is
 * {@link Kind#ADDED}, {@link Kind#REMOVED} or {@link Kind#CHANGED}. This is a report, not a patch:
 * distribution sends the full signed snapshot. Detail names fields, never a password, a PEM body,
 * or file bytes. A section is {@link Effect#RESTART} when any of its changed fields is fixed until
 * process restart.
 */
public final class ConfigDiff {

    private ConfigDiff() {
    }

    public enum Effect {
        LIVE, RESTART
    }

    public enum Kind {
        ADDED, REMOVED, CHANGED
    }

    /** One key inside a section. */
    public record KeyChange(String key, Kind kind) {
    }

    /**
     * @param section {@code server}, {@code http3}, {@code tls}, {@code node}, and the other file sections
     * @param kind added when every key was added, removed when every key was removed, otherwise changed
     */
    public record Change(String section, Kind kind, Effect effect, List<KeyChange> keys) {
        public Change {
            keys = List.copyOf(keys);
        }

        /** Comma-separated key names, restart keys first. */
        public String detail() {
            return this.keys.stream().map(KeyChange::key).reduce((a, b) -> a + ", " + b).orElse("");
        }
    }

    public static List<Change> between(ConfigDocument left, ConfigDocument right, boolean http3Running) {
        var out = new ArrayList<Change>();
        if (left.schemaVersion() != right.schemaVersion()) {
            emit(out, "schema", Effect.RESTART, List.of(new KeyChange("schema", Kind.CHANGED)));
        }
        if (left.bundleVersion() != right.bundleVersion()) {
            emit(out, "version", Effect.LIVE, List.of(new KeyChange("version", Kind.CHANGED)));
        }
        node(out, left.node(), right.node());
        server(out, left.server(), right.server(), http3Running);
        http3(out, left.server(), right.server());
        tls(out, left.server(), right.server());
        acme(out, left.acme(), right.acme());
        routing(out, left.routing(), right.routing());
        middleware(out, left.middleware(), right.middleware());
        access(out, left.access(), right.access());
        startup(out, left.server(), right.server());
        return List.copyOf(out);
    }

    private static void node(List<Change> out, NodeSettings left, NodeSettings right) {
        var live = new ArrayList<KeyChange>();
        var restart = new ArrayList<KeyChange>();
        if (left.mode() != right.mode()) {
            restart.add(new KeyChange("mode", Kind.CHANGED));
        }
        map(live, "labels.", left.labels(), right.labels());
        section(out, "node", live, restart);
    }

    private static void server(List<Change> out, ServerConfig left, ServerConfig right, boolean http3Running) {
        var live = new ArrayList<KeyChange>();
        var restart = new ArrayList<KeyChange>();
        if (left.port() != right.port()) {
            restart.add(changed("port"));
        }
        if (!left.root().equals(right.root())) {
            live.add(changed("root"));
        }
        if (left.maxConnections() != right.maxConnections()) {
            restart.add(changed("max_connections"));
        }
        if (left.maxConnectionsPerIp() != right.maxConnectionsPerIp()) {
            restart.add(changed("max_connections_per_ip"));
        }
        if (left.backlog() != right.backlog()) {
            restart.add(changed("backlog"));
        }
        boolean timeouts = !left.idleTimeout().equals(right.idleTimeout())
                || !left.headerTimeout().equals(right.headerTimeout())
                || !left.writeTimeout().equals(right.writeTimeout());
        if (timeouts) {
            (http3Running ? restart : live).add(changed("timeouts"));
        }
        if (left.cacheBytes() != right.cacheBytes()) {
            live.add(changed("cache_bytes"));
        }
        if (left.allowUnlistableDirs() != right.allowUnlistableDirs()) {
            live.add(changed("allow_unlistable_dirs"));
        }
        presence(restart, "access_log", left.accessLog() != null, right.accessLog() != null,
                Objects.equals(left.accessLog(), right.accessLog()));
        section(out, "server", live, restart);
    }

    private static void http3(List<Change> out, ServerConfig left, ServerConfig right) {
        var restart = new ArrayList<KeyChange>();
        if (left.h3Enabled() != right.h3Enabled()) {
            restart.add(changed("enabled"));
        }
        if (left.h3Port() != right.h3Port()) {
            restart.add(changed("port"));
        }
        if (!left.h3Host().equals(right.h3Host())) {
            restart.add(changed("host"));
        }
        if (left.h3MaxConnections() != right.h3MaxConnections()) {
            restart.add(changed("max_connections"));
        }
        if (left.h3MaxConnectionsPerIp() != right.h3MaxConnectionsPerIp()) {
            restart.add(changed("max_connections_per_ip"));
        }
        if (left.h3MinRate() != right.h3MinRate()) {
            restart.add(changed("min_rate"));
        }
        if (left.altSvc() != right.altSvc()) {
            restart.add(changed("alt_svc"));
        }
        if (!left.altSvcMaxAge().equals(right.altSvcMaxAge())) {
            restart.add(changed("alt_svc_max_age"));
        }
        section(out, "http3", List.of(), restart);
    }

    private static void tls(List<Change> out, ServerConfig left, ServerConfig right) {
        var live = new ArrayList<KeyChange>();
        var restart = new ArrayList<KeyChange>();
        boolean leftCert = left.tlsCert() != null || left.tlsKey() != null;
        boolean rightCert = right.tlsCert() != null || right.tlsKey() != null;
        if (leftCert || rightCert) {
            if (!leftCert) {
                live.add(new KeyChange("cert", Kind.ADDED));
            } else if (!rightCert) {
                restart.add(new KeyChange("cert", Kind.REMOVED));
            } else if (!Objects.equals(left.tlsCert(), right.tlsCert()) || !Objects.equals(left.tlsKey(), right.tlsKey())) {
                live.add(new KeyChange("cert", Kind.CHANGED));
            }
        }
        if (!Objects.equals(left.tlsKeyPassword(), right.tlsKeyPassword())) {
            live.add(new KeyChange("key_password", Kind.CHANGED));
        }
        section(out, "tls", live, restart);
    }

    private static void acme(List<Change> out, AcmeSettings left, AcmeSettings right) {
        if (left.equals(right)) {
            return;
        }
        var fields = new ArrayList<KeyChange>();
        if (left.enabled() != right.enabled()) {
            fields.add(changed("enabled"));
        }
        if (!left.directory().equals(right.directory())) {
            fields.add(changed("directory"));
        }
        presence(fields, "email", !left.email().isEmpty(), !right.email().isEmpty(), left.email().equals(right.email()));
        strings(fields, "domains.", left.domains(), right.domains());
        if (!left.storage().equals(right.storage())) {
            fields.add(changed("storage"));
        }
        if (left.httpPort() != right.httpPort()) {
            fields.add(changed("http_port"));
        }
        if (left.acceptTerms() != right.acceptTerms()) {
            fields.add(changed("accept_terms"));
        }
        if (!left.renewBefore().equals(right.renewBefore())) {
            fields.add(changed("renew_before"));
        }
        if (fields.isEmpty()) {
            fields.add(changed("acme"));
        }
        emit(out, "acme", Effect.LIVE, fields);
    }

    private static void routing(List<Change> out, Routing left, Routing right) {
        var fields = new ArrayList<KeyChange>();
        if (left.enabled() != right.enabled()) {
            fields.add(changed("enabled"));
        }
        if (left.forwardedProto() != right.forwardedProto()) {
            fields.add(changed("forwarded_proto"));
        }
        strings(fields, "trusted_proxies.", left.trustedProxies(), right.trustedProxies());
        map(fields, "upstreams.", left.upstreams(), right.upstreams());
        routes(fields, left.routes().routes(), right.routes().routes());
        emit(out, "routing", Effect.LIVE, fields);
    }

    private static void middleware(List<Change> out, List<MiddlewareSpec> left, List<MiddlewareSpec> right) {
        if (left.equals(right)) {
            return;
        }
        var fields = new ArrayList<KeyChange>();
        var before = new LinkedHashMap<String, MiddlewareSpec>();
        var after = new LinkedHashMap<String, MiddlewareSpec>();
        for (var spec : left) {
            before.put(middlewareKey(spec), spec);
        }
        for (var spec : right) {
            after.put(middlewareKey(spec), spec);
        }
        map(fields, "middleware.", before, after);
        emit(out, "middleware", Effect.LIVE, fields);
    }

    private static void access(List<Change> out, AccessSettings left, AccessSettings right) {
        if (left.equals(right)) {
            return;
        }
        var fields = new ArrayList<KeyChange>();
        strings(fields, "deny.", left.deny(), right.deny());
        strings(fields, "rules.", left.rules(), right.rules());
        if (left.allowDotfiles() != right.allowDotfiles()) {
            fields.add(changed("allow_dotfiles"));
        }
        presence(fields, "require_owner", left.requireOwner() != null, right.requireOwner() != null,
                Objects.equals(left.requireOwner(), right.requireOwner()));
        if (left.requireWorldReadable() != right.requireWorldReadable()) {
            fields.add(changed("require_world_readable"));
        }
        if (fields.isEmpty()) {
            fields.add(changed("access"));
        }
        emit(out, "access", Effect.LIVE, fields);
    }

    private static void startup(List<Change> out, ServerConfig left, ServerConfig right) {
        var restart = new ArrayList<KeyChange>();
        if (left.h2Enabled() != right.h2Enabled() || left.h2Port() != right.h2Port() || left.h2cPort() != right.h2cPort()) {
            restart.add(changed("h2"));
        }
        if (left.metricsExposure() != right.metricsExposure()) {
            restart.add(changed("metrics"));
        }
        if (left.healthz() != right.healthz()) {
            restart.add(changed("healthz"));
        }
        if (left.accessLogFormat() != right.accessLogFormat()) {
            restart.add(changed("access_log_format"));
        }
        section(out, "startup", List.of(), restart);
    }

    private static void section(List<Change> out, String name, List<KeyChange> live, List<KeyChange> restart) {
        if (live.isEmpty() && restart.isEmpty()) {
            return;
        }
        var keys = new ArrayList<KeyChange>(restart.size() + live.size());
        keys.addAll(restart);
        keys.addAll(live);
        emit(out, name, restart.isEmpty() ? Effect.LIVE : Effect.RESTART, keys);
    }

    private static void emit(List<Change> out, String section, Effect effect, List<KeyChange> keys) {
        if (keys.isEmpty()) {
            return;
        }
        Kind kind = Kind.CHANGED;
        if (keys.stream().allMatch(key -> key.kind() == Kind.ADDED)) {
            kind = Kind.ADDED;
        } else if (keys.stream().allMatch(key -> key.kind() == Kind.REMOVED)) {
            kind = Kind.REMOVED;
        }
        out.add(new Change(section, kind, effect, keys));
    }

    private static KeyChange changed(String key) {
        return new KeyChange(key, Kind.CHANGED);
    }

    private static void presence(List<KeyChange> out, String key, boolean left, boolean right, boolean equal) {
        if (!left && right) {
            out.add(new KeyChange(key, Kind.ADDED));
        } else if (left && !right) {
            out.add(new KeyChange(key, Kind.REMOVED));
        } else if (left && right && !equal) {
            out.add(new KeyChange(key, Kind.CHANGED));
        }
    }

    private static void strings(List<KeyChange> out, String prefix, List<String> left, List<String> right) {
        if (left.equals(right)) {
            return;
        }
        var names = new TreeSet<String>();
        names.addAll(left);
        names.addAll(right);
        for (var name : names) {
            boolean hasLeft = left.contains(name);
            boolean hasRight = right.contains(name);
            presence(out, prefix + name, hasLeft, hasRight, true);
        }
    }

    private static <T> void map(List<KeyChange> out, String prefix, Map<String, T> left, Map<String, T> right) {
        if (left.equals(right)) {
            return;
        }
        var names = new TreeSet<String>();
        names.addAll(left.keySet());
        names.addAll(right.keySet());
        for (var name : names) {
            boolean hasLeft = left.containsKey(name);
            boolean hasRight = right.containsKey(name);
            boolean equal = hasLeft && hasRight && Objects.equals(left.get(name), right.get(name));
            presence(out, prefix + name, hasLeft, hasRight, equal);
        }
    }

    private static void routes(List<KeyChange> out, List<RouteSpec> left, List<RouteSpec> right) {
        if (left.equals(right)) {
            return;
        }
        map(out, "routes.", index(left), index(right));
    }

    private static Map<String, RouteSpec> index(List<RouteSpec> routes) {
        var out = new LinkedHashMap<String, RouteSpec>();
        for (int i = 0; i < routes.size(); i++) {
            var route = routes.get(i);
            var host = route.host() == null ? "*" : route.host();
            var key = host + " " + route.prefix();
            if (out.containsKey(key)) {
                key = key + "#" + i;
            }
            out.put(key, route);
        }
        return out;
    }

    private static String middlewareKey(MiddlewareSpec spec) {
        return spec.name() + " " + (spec.route() == null ? "*" : spec.route());
    }
}
