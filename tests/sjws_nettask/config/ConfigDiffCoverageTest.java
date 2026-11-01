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

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.MiddlewareSpec;

/**
 * Exercises ConfigDiff branches that the happy-path model tests never flip: ACME/access/TLS
 * presence, middleware map diffs, routing field changes, and per-topic messaging diffs.
 */
class ConfigDiffCoverageTest {

    @TempDir
    Path dir;

    @Test
    void serverHttp3TlsAndStartupFieldDiffs() throws Exception {
        var left = base();
        var server = left.server().toBuilder()
                .maxConnections(11)
                .maxConnectionsPerIp(3)
                .backlog(64)
                .idleTimeout(Duration.ofSeconds(9))
                .cacheBytes(1 << 20)
                .allowUnlistableDirs(true)
                .accessLog(dir.resolve("a.log").toString())
                .h3Enabled(true)
                .h3Port(443)
                .h3Host("quic.example")
                .h3MaxConnections(50)
                .h3MaxConnectionsPerIp(5)
                .h3MinRate(100)
                .altSvc(false)
                .altSvcMaxAge(Duration.ofSeconds(60))
                .h2Enabled(true)
                .h2Port(8443)
                .metricsExposure(ServerConfig.MetricsExposure.LOCALHOST)
                .healthz(true)
                .accessLogFormat(ServerConfig.AccessLogFormat.JSON)
                .build();
        var right = ConfigDocument.of(server, left.acme(), left.routing(), left.middleware(), left.access(),
                left.node(), left.schemaVersion(), left.bundleVersion(), left.configFile());
        var changes = ConfigDiff.between(left, right, true);
        assertTrue(section(changes, "server").detail().contains("max_connections"));
        assertTrue(section(changes, "server").detail().contains("timeouts"));
        assertTrue(section(changes, "server").detail().contains("access_log"));
        assertEquals(ConfigDiff.Effect.RESTART, section(changes, "server").effect());
        assertTrue(section(changes, "http3").detail().contains("enabled"));
        assertTrue(section(changes, "http3").detail().contains("host"));
        assertTrue(section(changes, "startup").detail().contains("h2"));
        assertTrue(section(changes, "startup").detail().contains("metrics"));

        var cleared = ConfigDocument.of(server.toBuilder().accessLog(null).build(), left.acme(), left.routing(),
                left.middleware(), left.access(), left.node(), left.schemaVersion(), left.bundleVersion(), null);
        var dropLog = ConfigDiff.between(right, cleared, false);
        assertTrue(section(dropLog, "server").detail().contains("access_log"));
        assertEquals(ConfigDiff.Kind.REMOVED,
                section(dropLog, "server").keys().stream().filter(k -> k.key().equals("access_log")).findFirst()
                        .orElseThrow().kind());
    }

    @Test
    void tlsCertAddedChangedAndRemoved() throws Exception {
        var left = base();
        var cert = dir.resolve("cert.pem");
        var key = dir.resolve("key.pem");
        var otherCert = dir.resolve("other.pem");
        var otherKey = dir.resolve("other-key.pem");
        java.nio.file.Files.writeString(cert, "c");
        java.nio.file.Files.writeString(key, "k");
        java.nio.file.Files.writeString(otherCert, "c2");
        java.nio.file.Files.writeString(otherKey, "k2");
        var withTls = ConfigDocument.of(left.server().toBuilder().tlsCert(cert).tlsKey(key).build(), left.acme(),
                left.routing(), left.middleware(), left.access(), left.node(), 1, 0, null);
        var added = ConfigDiff.between(left, withTls, false);
        assertEquals(ConfigDiff.Kind.ADDED,
                section(added, "tls").keys().stream().filter(k -> k.key().equals("cert")).findFirst().orElseThrow()
                        .kind());
        var changedTls = ConfigDocument.of(left.server().toBuilder().tlsCert(otherCert).tlsKey(otherKey).build(),
                left.acme(), left.routing(), left.middleware(), left.access(), left.node(), 1, 0, null);
        var changed = ConfigDiff.between(withTls, changedTls, false);
        assertEquals(ConfigDiff.Kind.CHANGED,
                section(changed, "tls").keys().stream().filter(k -> k.key().equals("cert")).findFirst().orElseThrow()
                        .kind());
        var removed = ConfigDiff.between(withTls, left, false);
        assertEquals(ConfigDiff.Kind.REMOVED,
                section(removed, "tls").keys().stream().filter(k -> k.key().equals("cert")).findFirst().orElseThrow()
                        .kind());
        assertEquals(ConfigDiff.Effect.RESTART, section(removed, "tls").effect());
    }

    @Test
    void acmeAccessMiddlewareAndRoutingDiffs() throws Exception {
        var left = base();
        var acme = new AcmeSettings(true, AcmeSettings.PRODUCTION, "ops@example.com", List.of("a.example", "b.example"),
                dir.resolve("acme-store"), 80, true, Duration.ofDays(20));
        var withAcme = ConfigDocument.of(left.server(), acme, left.routing(), left.middleware(), left.access(),
                left.node(), 1, 0, null);
        var acmeDiff = ConfigDiff.between(left, withAcme, false);
        assertTrue(section(acmeDiff, "acme").detail().contains("enabled"));
        assertTrue(section(acmeDiff, "acme").detail().contains("email"));
        assertTrue(section(acmeDiff, "acme").detail().contains("domains."));
        assertTrue(section(acmeDiff, "acme").detail().contains("http_port"));

        var otherAcme = new AcmeSettings(true, AcmeSettings.STAGING, "other@example.com", List.of("a.example"),
                dir.resolve("other"), 8080, false, Duration.ofDays(10));
        var acmeChange = ConfigDiff.between(withAcme,
                ConfigDocument.of(left.server(), otherAcme, left.routing(), left.middleware(), left.access(),
                        left.node(), 1, 0, null),
                false);
        assertTrue(section(acmeChange, "acme").detail().contains("directory"));
        assertTrue(section(acmeChange, "acme").detail().contains("accept_terms"));
        assertTrue(section(acmeChange, "acme").detail().contains("renew_before"));
        assertTrue(section(acmeChange, "acme").detail().contains("storage"));

        var access = new AccessSettings(List.of("*.bak", "*.tmp"), List.of("allow:/public", "deny:/secret"), true,
                "www-data", true);
        var withAccess = ConfigDocument.of(left.server(), left.acme(), left.routing(), left.middleware(), access,
                left.node(), 1, 0, null);
        var accessDiff = ConfigDiff.between(left, withAccess, false);
        assertTrue(section(accessDiff, "access").detail().contains("deny."));
        assertTrue(section(accessDiff, "access").detail().contains("rules."));
        assertTrue(section(accessDiff, "access").detail().contains("allow_dotfiles"));
        assertTrue(section(accessDiff, "access").detail().contains("require_owner"));
        assertTrue(section(accessDiff, "access").detail().contains("require_world_readable"));
        var ownerCleared = new AccessSettings(List.of("*.bak", "*.tmp"), List.of("allow:/public", "deny:/secret"), true,
                null, true);
        var ownerRemoved = ConfigDiff.between(withAccess,
                ConfigDocument.of(left.server(), left.acme(), left.routing(), left.middleware(), ownerCleared,
                        left.node(), 1, 0, null),
                false);
        assertEquals(ConfigDiff.Kind.REMOVED,
                section(ownerRemoved, "access").keys().stream().filter(k -> k.key().equals("require_owner")).findFirst()
                        .orElseThrow().kind());

        var mw = List.of(new MiddlewareSpec("access-log", 0, "*", Map.of("format", "combined")),
                new MiddlewareSpec("api-key", 10, "/api/*", Map.of("keys", "secret")));
        var withMw = ConfigDocument.of(left.server(), left.acme(), left.routing(), mw, left.access(), left.node(), 1, 0,
                null);
        var mwDiff = ConfigDiff.between(left, withMw, false);
        assertEquals(ConfigDiff.Kind.ADDED, section(mwDiff, "middleware").kind());
        var otherMw = List.of(new MiddlewareSpec("access-log", 0, "*", Map.of("format", "json")));
        var mwChange = ConfigDiff.between(withMw,
                ConfigDocument.of(left.server(), left.acme(), left.routing(), otherMw, left.access(), left.node(), 1, 0,
                        null),
                false);
        assertTrue(section(mwChange, "middleware").keys().stream().anyMatch(k -> k.kind() == ConfigDiff.Kind.CHANGED
                || k.kind() == ConfigDiff.Kind.REMOVED || k.kind() == ConfigDiff.Kind.ADDED));

        var upstream = new Upstream("api", URI.create("http://127.0.0.1:9000"), Duration.ofSeconds(1),
                Duration.ofSeconds(2), Duration.ofSeconds(3), 1024, true);
        var routes = new RouteTable(List.of(new RouteSpec(null, false, null, "/api", null,
                new RouteSpec.Action.Proxy(upstream)),
                new RouteSpec(null, false, null, "/go", null, new RouteSpec.Action.Redirect(302, "/there", false)),
                new RouteSpec(null, false, null, "/files", null, new RouteSpec.Action.Static())));
        var routing = new Routing(true, ForwardedProto.HTTPS, List.of("10.0.0.0/8", "192.168.0.0/16"),
                Map.of("api", upstream), routes);
        var withRouting = ConfigDocument.of(left.server(), left.acme(), routing, left.middleware(), left.access(),
                left.node(), 1, 0, null);
        var routingDiff = ConfigDiff.between(left, withRouting, false);
        assertTrue(section(routingDiff, "routing").detail().contains("enabled"));
        assertTrue(section(routingDiff, "routing").detail().contains("forwarded_proto"));
        assertTrue(section(routingDiff, "routing").detail().contains("trusted_proxies."));
        assertTrue(section(routingDiff, "routing").detail().contains("upstreams."));
        assertTrue(section(routingDiff, "routing").detail().contains("routes."));
    }

    @Test
    void messagingTopicAndGroupDiffs() {
        var left = base();
        var topic = new TopicSpec(3, 1, "async", List.of("strict"), 5, Duration.ofMillis(100), Duration.ofSeconds(1),
                true, ".dlq", Duration.ofDays(7), true, 0.5, Duration.ofDays(14), 1_000_000, 1 << 20,
                Duration.ofSeconds(30), Map.of(), "", Map.of("billing",
                        new GroupSpec("earliest", Duration.ofSeconds(30), 5, Duration.ofMillis(100), Duration.ofSeconds(1))),
                false);
        var messaging = new MessagingSettings(true, 3, 1, "async", List.of(), 5, Duration.ofMillis(100),
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofDays(14), 0, true, 0.5, 1 << 20, Map.of(), "",
                Map.of("orders", topic), Map.of());
        var withMsg = left.withMessaging(messaging);
        var added = ConfigDiff.between(left, withMsg, false);
        assertEquals(ConfigDiff.Kind.ADDED, section(added, "topics/orders").kind());

        var changedTopic = new TopicSpec(6, 2, "quorum", List.of("none"), 9, Duration.ofMillis(200),
                Duration.ofSeconds(2), false, ".dead", Duration.ofDays(3), false, 0.25, Duration.ofDays(30), 2_000_000,
                1 << 21, Duration.ofSeconds(45), Map.of("zone", "a"), "rack",
                Map.of("billing", new GroupSpec("latest", Duration.ofSeconds(45), 9, Duration.ofMillis(200),
                        Duration.ofSeconds(2)),
                        "audit", new GroupSpec("offset:0", Duration.ofSeconds(45), 9, Duration.ofMillis(200),
                                Duration.ofSeconds(2))),
                true);
        var changedMsg = new MessagingSettings(true, 3, 1, "async", List.of(), 5, Duration.ofMillis(100),
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofDays(14), 0, true, 0.5, 1 << 20, Map.of(), "",
                Map.of("orders", changedTopic), Map.of());
        var fieldDiff = ConfigDiff.between(withMsg, left.withMessaging(changedMsg), false);
        var topicChange = section(fieldDiff, "topics/orders");
        assertTrue(topicChange.detail().contains("partitions"));
        assertTrue(topicChange.detail().contains("replication"));
        assertTrue(topicChange.detail().contains("durability"));
        assertTrue(topicChange.detail().contains("retry"));
        assertTrue(topicChange.detail().contains("dlq"));
        assertTrue(topicChange.detail().contains("retention"));
        assertEquals(ConfigDiff.Kind.ADDED, section(fieldDiff, "topics/orders/groups/audit").kind());
        assertEquals(ConfigDiff.Kind.CHANGED, section(fieldDiff, "topics/orders/groups/billing").kind());

        var removed = ConfigDiff.between(withMsg, left, false);
        assertEquals(ConfigDiff.Kind.REMOVED, section(removed, "topics/orders").kind());
        var noGroups = new TopicSpec(3, 1, "async", List.of("strict"), 5, Duration.ofMillis(100), Duration.ofSeconds(1),
                true, ".dlq", Duration.ofDays(7), true, 0.5, Duration.ofDays(14), 1_000_000, 1 << 20,
                Duration.ofSeconds(30), Map.of(), "", Map.of(), false);
        var dropGroup = ConfigDiff.between(withMsg,
                left.withMessaging(new MessagingSettings(true, 3, 1, "async", List.of(), 5, Duration.ofMillis(100),
                        Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofDays(14), 0, true, 0.5, 1 << 20,
                        Map.of(), "", Map.of("orders", noGroups), Map.of())),
                false);
        assertEquals(ConfigDiff.Kind.REMOVED, section(dropGroup, "topics/orders/groups/billing").kind());
    }

    private ConfigDocument base() {
        return ConfigDocument.defaults(ServerConfig.defaults(dir));
    }

    private static ConfigDiff.Change section(List<ConfigDiff.Change> changes, String name) {
        return changes.stream().filter(c -> c.section().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("missing section " + name + " in " + changes));
    }
}
