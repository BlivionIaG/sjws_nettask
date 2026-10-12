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
import java.util.List;
import java.util.Objects;
import sjws_nettask.ServerConfig;

/**
 * Which sections differ between two documents. One entry per section. The detail names fields,
 * never a password, a PEM body, or file bytes. A section is {@link Effect#RESTART} when any of
 * its changed fields is fixed until process restart.
 */
public final class ConfigDiff {

    private ConfigDiff() {
    }

    public enum Effect {
        LIVE, RESTART
    }

    /**
     * @param section {@code server}, {@code http3}, {@code tls}, {@code node}, and the other file sections
     * @param detail comma-separated field names
     */
    public record Change(String section, Effect effect, String detail) {
    }

    public static List<Change> between(ConfigDocument left, ConfigDocument right, boolean http3Running) {
        var out = new ArrayList<Change>();
        if (left.schemaVersion() != right.schemaVersion()) {
            out.add(new Change("schema", Effect.RESTART, "schema"));
        }
        if (left.bundleVersion() != right.bundleVersion()) {
            out.add(new Change("version", Effect.LIVE, "version"));
        }
        node(out, left.node(), right.node());
        server(out, left.server(), right.server(), http3Running);
        http3(out, left.server(), right.server());
        tls(out, left.server(), right.server());
        acme(out, left.acme(), right.acme());
        routing(out, left.routing(), right.routing());
        if (!left.middleware().equals(right.middleware())) {
            out.add(new Change("middleware", Effect.LIVE, "middleware"));
        }
        access(out, left.access(), right.access());
        startup(out, left.server(), right.server());
        return List.copyOf(out);
    }

    private static void node(List<Change> out, NodeSettings left, NodeSettings right) {
        var live = new ArrayList<String>();
        var restart = new ArrayList<String>();
        if (left.mode() != right.mode()) {
            restart.add("mode");
        }
        if (!left.labels().equals(right.labels())) {
            live.add("labels");
        }
        section(out, "node", live, restart);
    }

    private static void server(List<Change> out, ServerConfig left, ServerConfig right, boolean http3Running) {
        var live = new ArrayList<String>();
        var restart = new ArrayList<String>();
        if (left.port() != right.port()) {
            restart.add("port");
        }
        if (!left.root().equals(right.root())) {
            live.add("root");
        }
        if (left.maxConnections() != right.maxConnections()) {
            restart.add("max_connections");
        }
        if (left.maxConnectionsPerIp() != right.maxConnectionsPerIp()) {
            restart.add("max_connections_per_ip");
        }
        if (left.backlog() != right.backlog()) {
            restart.add("backlog");
        }
        boolean timeouts = !left.idleTimeout().equals(right.idleTimeout())
                || !left.headerTimeout().equals(right.headerTimeout())
                || !left.writeTimeout().equals(right.writeTimeout());
        if (timeouts) {
            (http3Running ? restart : live).add("timeouts");
        }
        if (left.cacheBytes() != right.cacheBytes()) {
            live.add("cache_bytes");
        }
        if (left.allowUnlistableDirs() != right.allowUnlistableDirs()) {
            live.add("allow_unlistable_dirs");
        }
        if (!Objects.equals(left.accessLog(), right.accessLog())) {
            restart.add("access_log");
        }
        section(out, "server", live, restart);
    }

    private static void http3(List<Change> out, ServerConfig left, ServerConfig right) {
        var restart = new ArrayList<String>();
        if (left.h3Enabled() != right.h3Enabled()) {
            restart.add("enabled");
        }
        if (left.h3Port() != right.h3Port()) {
            restart.add("port");
        }
        if (!left.h3Host().equals(right.h3Host())) {
            restart.add("host");
        }
        if (left.h3MaxConnections() != right.h3MaxConnections()) {
            restart.add("max_connections");
        }
        if (left.h3MaxConnectionsPerIp() != right.h3MaxConnectionsPerIp()) {
            restart.add("max_connections_per_ip");
        }
        if (left.h3MinRate() != right.h3MinRate()) {
            restart.add("min_rate");
        }
        if (left.altSvc() != right.altSvc()) {
            restart.add("alt_svc");
        }
        if (!left.altSvcMaxAge().equals(right.altSvcMaxAge())) {
            restart.add("alt_svc_max_age");
        }
        section(out, "http3", List.of(), restart);
    }

    private static void tls(List<Change> out, ServerConfig left, ServerConfig right) {
        var live = new ArrayList<String>();
        var restart = new ArrayList<String>();
        boolean removed = left.tlsCert() != null && right.tlsCert() == null;
        if (removed) {
            restart.add("cert");
        } else if (!Objects.equals(left.tlsCert(), right.tlsCert()) || !Objects.equals(left.tlsKey(), right.tlsKey())) {
            live.add("cert");
        }
        if (!Objects.equals(left.tlsKeyPassword(), right.tlsKeyPassword())) {
            live.add("key_password");
        }
        section(out, "tls", live, restart);
    }

    private static void acme(List<Change> out, AcmeSettings left, AcmeSettings right) {
        if (left.equals(right)) {
            return;
        }
        var fields = new ArrayList<String>();
        if (left.enabled() != right.enabled()) {
            fields.add("enabled");
        }
        if (!left.directory().equals(right.directory())) {
            fields.add("directory");
        }
        if (!left.email().equals(right.email())) {
            fields.add("email");
        }
        if (!left.domains().equals(right.domains())) {
            fields.add("domains");
        }
        if (!left.storage().equals(right.storage())) {
            fields.add("storage");
        }
        if (left.httpPort() != right.httpPort()) {
            fields.add("http_port");
        }
        if (left.acceptTerms() != right.acceptTerms()) {
            fields.add("accept_terms");
        }
        if (!left.renewBefore().equals(right.renewBefore())) {
            fields.add("renew_before");
        }
        if (fields.isEmpty()) {
            fields.add("acme");
        }
        out.add(new Change("acme", Effect.LIVE, String.join(", ", fields)));
    }

    private static void routing(List<Change> out, Routing left, Routing right) {
        var fields = new ArrayList<String>();
        if (left.enabled() != right.enabled()) {
            fields.add("enabled");
        }
        if (left.forwardedProto() != right.forwardedProto()) {
            fields.add("forwarded_proto");
        }
        if (!left.trustedProxies().equals(right.trustedProxies())) {
            fields.add("trusted_proxies");
        }
        if (!left.upstreams().equals(right.upstreams())) {
            fields.add("upstreams");
        }
        if (!left.routes().routes().equals(right.routes().routes())) {
            fields.add("routes");
        }
        if (!fields.isEmpty()) {
            out.add(new Change("routing", Effect.LIVE, String.join(", ", fields)));
        }
    }

    private static void access(List<Change> out, AccessSettings left, AccessSettings right) {
        if (left.equals(right)) {
            return;
        }
        var fields = new ArrayList<String>();
        if (!left.deny().equals(right.deny())) {
            fields.add("deny");
        }
        if (!left.rules().equals(right.rules())) {
            fields.add("rules");
        }
        if (left.allowDotfiles() != right.allowDotfiles()) {
            fields.add("allow_dotfiles");
        }
        if (!Objects.equals(left.requireOwner(), right.requireOwner())) {
            fields.add("require_owner");
        }
        if (left.requireWorldReadable() != right.requireWorldReadable()) {
            fields.add("require_world_readable");
        }
        if (fields.isEmpty()) {
            fields.add("access");
        }
        out.add(new Change("access", Effect.LIVE, String.join(", ", fields)));
    }

    private static void startup(List<Change> out, ServerConfig left, ServerConfig right) {
        var restart = new ArrayList<String>();
        if (left.h2Enabled() != right.h2Enabled() || left.h2Port() != right.h2Port() || left.h2cPort() != right.h2cPort()) {
            restart.add("h2");
        }
        if (left.metricsExposure() != right.metricsExposure()) {
            restart.add("metrics");
        }
        if (left.healthz() != right.healthz()) {
            restart.add("healthz");
        }
        if (left.accessLogFormat() != right.accessLogFormat()) {
            restart.add("access_log_format");
        }
        section(out, "startup", List.of(), restart);
    }

    private static void section(List<Change> out, String name, List<String> live, List<String> restart) {
        if (live.isEmpty() && restart.isEmpty()) {
            return;
        }
        var detail = new ArrayList<String>(restart.size() + live.size());
        detail.addAll(restart);
        detail.addAll(live);
        var effect = restart.isEmpty() ? Effect.LIVE : Effect.RESTART;
        out.add(new Change(name, effect, String.join(", ", detail)));
    }
}
