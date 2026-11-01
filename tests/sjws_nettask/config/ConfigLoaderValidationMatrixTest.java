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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;

/**
 * Strict YAML / CLI / env validation branches that the main ConfigLoader tests leave cold.
 * Raising the config package JaCoCo branch gate to 0.85 depends on these paths running.
 */
class ConfigLoaderValidationMatrixTest {

    @TempDir
    Path dir;

    private Path write(String yaml) throws Exception {
        var file = dir.resolve("sjws-" + Integer.toHexString(yaml.hashCode()) + ".yaml");
        Files.writeString(file, yaml);
        return file;
    }

    private String error(String yaml) throws Exception {
        var loaded = ConfigLoader.load(new String[] {"--config=" + write(yaml)}, Map.of());
        assertFalse(loaded.ok(), yaml);
        return loaded.errors().get(0);
    }

    @Test
    void versionSchemaAndScalarRejects() throws Exception {
        var ok = ConfigLoader.load(new String[] {"--config=" + write("version: \"12\"\n")}, Map.of());
        assertTrue(ok.ok(), ok.errors().toString());
        assertEquals(12, ok.document().bundleVersion());
        assertTrue(error("version: \"nope\"\n").contains("version"));
        assertTrue(error("version: []\n").contains("version"));
        assertTrue(error("schema: \"2\"\n").contains("unsupported") || error("schema: \"2\"\n").contains("schema"));
        assertTrue(error("server:\n  port: \"nope\"\n").contains("integer"));
        assertTrue(error("server:\n  port: 1.5\n").contains("integer"));
        assertTrue(error("server:\n  port: 70000\n").contains("0..65535") || error("server:\n  port: 70000\n").contains("expected"));
        assertTrue(error("server:\n  idle_timeout: 1.5s\n").contains("whole number")
                || error("server:\n  idle_timeout: 1.5s\n").contains("idle_timeout"));
        assertTrue(error("server:\n  access_log: {}\n").contains("string"));
    }

    @Test
    void accessLogOffAndServerFieldMatrix() throws Exception {
        var off = ConfigLoader.load(new String[] {"--config=" + write("""
                server:
                  port: 0
                  access_log: "off"
                  max_connections: "100"
                  max_connections_per_ip: 4
                  backlog: 128
                  cache_bytes: 1m
                  allow_unlistable_dirs: true
                  bind_content: false
                http3:
                  enabled: true
                  port: same
                  host: quic.example
                  max_connections: 10
                  max_connections_per_ip: 2
                  min_rate: 0
                  alt_svc: false
                  alt_svc_max_age: 0
                """)}, Map.of());
        assertTrue(off.ok(), off.errors().toString());
        assertNull(off.document().server().accessLog());
        assertEquals(100, off.document().server().maxConnections());
        assertEquals(ServerConfig.H3_PORT_SAME_AS_TCP, off.document().server().h3Port());
        assertEquals("quic.example", off.document().server().h3Host());
        var blank = ConfigLoader.load(new String[] {"--config=" + write("server:\n  access_log: \"  \"\n")}, Map.of());
        // blank access_log text() rejects missing value before accessLog off-path; either is fine
        assertTrue(!blank.ok() || blank.document().server().accessLog() == null);
        var missingConfig = ConfigLoader.load(new String[] {"--config"}, Map.of());
        assertFalse(missingConfig.ok());
        assertTrue(missingConfig.errors().get(0).contains("--config"));
    }

    @Test
    void ledgerBudgetMatrix() throws Exception {
        String bytesProp = sjws_nettask.messaging.ledger.LedgerWriterBudget.BYTES_PROPERTY;
        String fracProp = sjws_nettask.messaging.ledger.LedgerWriterBudget.FRACTION_PROPERTY;
        var savedBytes = System.getProperty(bytesProp);
        var savedFrac = System.getProperty(fracProp);
        try {
            System.clearProperty(bytesProp);
            System.clearProperty(fracProp);
            var off = ConfigLoader.load(new String[] {"--config=" + write("""
                    ledger:
                      budget_bytes: "off"
                    """)}, Map.of());
            assertTrue(off.ok(), off.errors().toString());
            assertEquals("0", System.getProperty(bytesProp));
            System.clearProperty(bytesProp);
            var unlimited = ConfigLoader.load(new String[] {"--config=" + write("""
                    ledger:
                      budget_bytes: "unlimited"
                      budget_heap_fraction: 0.25
                    """)}, Map.of());
            assertTrue(unlimited.ok(), unlimited.errors().toString());
            assertEquals("0", System.getProperty(bytesProp));
            assertEquals("0.25", System.getProperty(fracProp));
            System.clearProperty(bytesProp);
            System.clearProperty(fracProp);
            var sized = ConfigLoader.load(new String[] {"--config=" + write("""
                    ledger:
                      budget_bytes: 64m
                      budget_heap_fraction: "0.5"
                    """)}, Map.of());
            assertTrue(sized.ok(), sized.errors().toString());
            assertEquals(Long.toString(64L << 20), System.getProperty(bytesProp));
            assertEquals("0.5", System.getProperty(fracProp));
            assertTrue(error("ledger:\n  budget_bytes: {}\n").contains("budget_bytes"));
            assertTrue(error("ledger:\n  budget_bytes: 1\n").contains("budget_bytes"));
            assertTrue(error("ledger:\n  budget_heap_fraction: 0\n").contains("budget_heap_fraction"));
            assertTrue(error("ledger:\n  budget_heap_fraction: nope\n").contains("budget_heap_fraction"));
            assertTrue(error("ledger:\n  budget_heap_fraction: []\n").contains("budget_heap_fraction"));
            assertTrue(error("ledger:\n  extra: 1\n").contains("Unknown key"));
        } finally {
            if (savedBytes == null) {
                System.clearProperty(bytesProp);
            } else {
                System.setProperty(bytesProp, savedBytes);
            }
            if (savedFrac == null) {
                System.clearProperty(fracProp);
            } else {
                System.setProperty(fracProp, savedFrac);
            }
        }
    }

    @Test
    void acmeCliAndEnvironmentOverlay() throws Exception {
        var storage = dir.resolve("acme-data");
        Files.createDirectories(storage);
        var loaded = ConfigLoader.load(new String[] {
                "--acme=true",
                "--acme-directory=https://acme.example/directory",
                "--acme-email=ops@example.com",
                "--acme-domain=one.example",
                "--acme-domain=two.example",
                "--acme-storage=" + storage,
                "--acme-http-port=8080",
                "--acme-accept-terms=yes",
                "--acme-renew-before=14d",
                "--no-acme"
        }, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertFalse(loaded.document().acme().enabled());

        var env = new LinkedHashMap<String, String>();
        env.put("WEB_SERVER_ACME", "true");
        env.put("WEB_SERVER_ACME_DIRECTORY", "https://acme.env/directory");
        env.put("WEB_SERVER_ACME_EMAIL", "env@example.com");
        env.put("WEB_SERVER_ACME_DOMAINS", "env.example, other.example");
        env.put("WEB_SERVER_ACME_STORAGE", storage.toString());
        env.put("WEB_SERVER_ACME_HTTP_PORT", "90");
        env.put("WEB_SERVER_ACME_ACCEPT_TERMS", "1");
        env.put("WEB_SERVER_ACME_RENEW_BEFORE", "21d");
        var fromEnv = ConfigLoader.load(new String[] {}, env);
        assertTrue(fromEnv.ok(), fromEnv.errors().toString());
        assertTrue(fromEnv.document().acme().enabled());
        assertEquals("https://acme.env/directory", fromEnv.document().acme().directory());
        assertEquals("env@example.com", fromEnv.document().acme().email());
        assertEquals(List.of("env.example", "other.example"), fromEnv.document().acme().domains());
        assertEquals(90, fromEnv.document().acme().httpPort());
        assertEquals(Duration.ofDays(21), fromEnv.document().acme().renewBefore());

        var emptyDomains = ConfigLoader.load(new String[] {}, Map.of(
                "WEB_SERVER_ACME", "true",
                "WEB_SERVER_ACME_EMAIL", "ops@example.com",
                "WEB_SERVER_ACME_DOMAINS", " , ",
                "WEB_SERVER_ACME_ACCEPT_TERMS", "true"));
        assertFalse(emptyDomains.ok());
        assertTrue(emptyDomains.errors().get(0).contains("WEB_SERVER_ACME_DOMAINS"));

        assertTrue(error("""
                acme:
                  enabled: true
                  domains: [example.com]
                  accept_terms: true
                """).contains("email"));
        assertTrue(error("""
                acme:
                  enabled: true
                  email: ops@example.com
                  accept_terms: true
                """).contains("domains"));
        assertTrue(error("""
                acme:
                  enabled: true
                  email: ops@example.com
                  domains: [example.com]
                  accept_terms: false
                """).contains("accept_terms"));
        assertTrue(error("""
                acme:
                  email: not-an-email
                """).contains("email"));
        assertTrue(error("""
                acme:
                  directory: ""
                """).contains("directory") || error("""
                acme:
                  directory: ""
                """).contains("missing"));
    }

    @Test
    void routingPrefixRedirectAndHostRejects() throws Exception {
        assertTrue(error("""
                routing:
                  routes:
                    - prefix: ""
                      static: true
                """).contains("prefix"));
        assertTrue(error("""
                routing:
                  routes:
                    - prefix: "no-slash"
                      static: true
                """).contains("prefix"));
        assertTrue(error("""
                routing:
                  routes:
                    - prefix: "/x?q=1"
                      static: true
                """).contains("prefix"));
        assertTrue(error("""
                routing:
                  routes:
                    - prefix: "/x#frag"
                      static: true
                """).contains("prefix"));
        assertTrue(error("""
                routing:
                  routes:
                    - prefix: "/x//y"
                      static: true
                """).contains("prefix"));
        assertTrue(error("routing:\n  routes:\n    - prefix: \"/x\ty\"\n      static: true\n").contains("control"));
        assertTrue(error("""
                routing:
                  routes:
                    - prefix: /x
                      redirect: { status: 200, location: /y }
                """).contains("status"));
        assertTrue(error("""
                routing:
                  routes:
                    - host: "-bad-.example"
                      prefix: /
                      static: true
                """).contains("host") || error("""
                routing:
                  routes:
                    - host: "-bad-.example"
                      prefix: /
                      static: true
                """).toLowerCase().contains("host"));
        assertTrue(error("""
                routing:
                  upstreams:
                    api: { url: "ftp://127.0.0.1/" }
                  routes:
                    - prefix: /x
                      proxy: api
                """).contains("http") || error("""
                routing:
                  upstreams:
                    api: { url: "ftp://127.0.0.1/" }
                  routes:
                    - prefix: /x
                      proxy: api
                """).contains("url"));
        assertTrue(error("""
                routing:
                  trusted_proxies: ["127.0.0.1/33"]
                """).contains("trusted") || error("""
                routing:
                  trusted_proxies: ["127.0.0.1/33"]
                """).contains("CIDR") || error("""
                routing:
                  trusted_proxies: ["127.0.0.1/33"]
                """).toLowerCase().contains("prefix"));
    }

    @Test
    void messagingShapeErrorsViaValidator() throws Exception {
        var root = Files.createDirectories(dir.resolve("msg-root"));
        var badName = ConfigLoader.load(new String[] {"--config=" + write("""
                server:
                  root: %s
                messaging:
                  enabled: true
                  topics:
                    BadName:
                      partitions: 1
                """.formatted(root.toAbsolutePath()))}, Map.of());
        assertTrue(badName.ok(), badName.errors().toString());
        assertTrue(ConfigValidator.validate(badName.document()).stream().anyMatch(e -> e.contains("invalid topic")));

        var partitions = ConfigLoader.load(new String[] {"--config=" + write("""
                server:
                  root: %s
                messaging:
                  enabled: true
                  topics:
                    orders:
                      partitions: 0
                """.formatted(root.toAbsolutePath()))}, Map.of());
        assertTrue(partitions.ok(), partitions.errors().toString());
        assertTrue(ConfigValidator.validate(partitions.document()).stream().anyMatch(e -> e.contains("partitions")));

        var durability = ConfigLoader.load(new String[] {"--config=" + write("""
                server:
                  root: %s
                messaging:
                  enabled: true
                  topics:
                    orders:
                      durability: async
                      replication: 3
                """.formatted(root.toAbsolutePath()))}, Map.of());
        assertTrue(durability.ok(), durability.errors().toString());
        assertTrue(ConfigValidator.validate(durability.document()).stream()
                .anyMatch(e -> e.contains("replication") || e.contains("durability")));

        var assignment = ConfigLoader.load(new String[] {"--config=" + write("""
                server:
                  root: %s
                messaging:
                  enabled: true
                  assignment:
                    orders: [n1]
                """.formatted(root.toAbsolutePath()))}, Map.of());
        assertTrue(assignment.ok(), assignment.errors().toString());
        assertTrue(ConfigValidator.validate(assignment.document()).stream()
                .anyMatch(e -> e.contains("topic/partition")));

        // Parse-time rejects cover more MessagingSettings branches.
        assertTrue(error("""
                messaging:
                  enabled: true
                  topics:
                    orders:
                      dlq: { enabled: maybe }
                """).contains("true or false") || error("""
                messaging:
                  enabled: true
                  topics:
                    orders:
                      dlq: { enabled: maybe }
                """).contains("dlq"));
        assertTrue(error("""
                messaging:
                  enabled: true
                  topics:
                    orders:
                      retention: { compact_live_ratio: 2 }
                """).contains("compact_live_ratio") || error("""
                messaging:
                  enabled: true
                  topics:
                    orders:
                      retention: { compact_live_ratio: 2 }
                """).contains("ratio"));
        var optIn = ConfigLoader.load(new String[] {"--config=" + write("""
                server:
                  root: %s
                messaging:
                  defaults:
                    durability_opt_in: [nope]
                """.formatted(root.toAbsolutePath()))}, Map.of());
        assertTrue(optIn.ok(), optIn.errors().toString());
        assertTrue(ConfigValidator.validate(optIn.document()).stream()
                .anyMatch(e -> e.contains("durability_opt_in")));
    }

    @Test
    void middlewareSecretsHelpers() {
        assertEquals(Map.of(), MiddlewareSecrets.maskOptions(null));
        assertEquals(Map.of(), MiddlewareSecrets.maskOptions(Map.of()));
        var masked = MiddlewareSecrets.maskOptions(Map.of("keys", "secret", "realm", "api"));
        assertEquals(MiddlewareSecrets.MASK, masked.get("keys"));
        assertEquals("api", masked.get("realm"));
        assertNull(MiddlewareSecrets.maskYaml(null));
        assertEquals("", MiddlewareSecrets.maskYaml(""));
        assertTrue(MiddlewareSecrets.hasInline(Map.of("keys", "x")));
        assertFalse(MiddlewareSecrets.hasInline(Map.of("realm", "x")));
        assertFalse(MiddlewareSecrets.hasInline(null));
        assertNotNull(MiddlewareSecrets.refuseInline(List.of(
                new sjws_nettask.core.MiddlewareSpec("api-key", 0, "*", Map.of("keys", "secret")))));
        assertNull(MiddlewareSecrets.refuseInline(List.of(
                new sjws_nettask.core.MiddlewareSpec("access-log", 0, "*", Map.of("format", "combined")))));
        assertNull(MiddlewareSecrets.refuseInline(null));
        assertNull(MiddlewareSecrets.refuseInline(List.of()));
    }

    @Test
    void stateDirAndNodeLabelEdges() throws Exception {
        var root = Files.createDirectories(dir.resolve("html"));
        var state = Files.createDirectories(dir.resolve("state"));
        var ok = ConfigLoader.load(new String[] {
                "--config=" + write("server:\n  root: " + root.toAbsolutePath()
                        + "\nnode:\n  state_dir: " + state.getFileName()
                        + "\n  mode: data\n  labels:\n    site: edge\n"),
                "--state-dir=" + state.toAbsolutePath()
        }, Map.of());
        assertTrue(ok.ok(), ok.errors().toString());
        assertEquals(NodeSettings.Mode.DATA, ok.document().node().mode());
        assertEquals("edge", ok.document().node().labels().get("site"));
        var missingState = ConfigLoader.load(new String[] {"--state-dir"}, Map.of());
        assertFalse(missingState.ok());
        assertTrue(missingState.errors().get(0).contains("state-dir"));
        assertTrue(error("node:\n  mode: banana\n").contains("standalone")
                || error("node:\n  mode: banana\n").contains("mode"));
        assertTrue(error("node:\n  labels: []\n").contains("mapping")
                || error("node:\n  labels: []\n").contains("labels"));
    }

    @Test
    void moreLoaderAndMessagingEdges() throws Exception {
        var root = Files.createDirectories(dir.resolve("more-root"));
        var logPath = ConfigLoader.load(new String[] {"--config=" + write("""
                server:
                  access_log: access.log
                """)}, Map.of());
        assertTrue(logPath.ok(), logPath.errors().toString());
        assertEquals("access.log", logPath.document().server().accessLog());

        var staging = ConfigLoader.load(new String[] {"--config=" + write("""
                acme:
                  directory: staging
                  email: ops@example.com
                  domains: [example.com]
                  accept_terms: true
                  renew_before: 89d
                """), "--acme"}, Map.of());
        assertTrue(staging.ok(), staging.errors().toString());
        assertEquals(AcmeSettings.STAGING, staging.document().acme().directory());
        assertTrue(error("""
                acme:
                  directory: ftp://nope
                """).contains("directory"));
        assertTrue(error("""
                acme:
                  renew_before: 90d
                """).contains("renew_before"));

        var h3 = ConfigLoader.load(new String[] {"--config=" + write("""
                http3:
                  enabled: true
                  alt_svc_max_age: "0"
                """)}, Map.of());
        assertTrue(h3.ok(), h3.errors().toString());
        assertEquals(Duration.ZERO, h3.document().server().altSvcMaxAge());

        var msg = ConfigLoader.load(new String[] {"--config=" + write("""
                server:
                  root: %s
                messaging:
                  enabled: true
                  topics:
                    orders:
                      durability: group
                      replication: 1
                      groups:
                        billing:
                          start: middle
                        bad.dlq:
                          start: earliest
                      dlq:
                        enabled: true
                        suffix: .dead
                        retention:
                          max_age: 3d
                      retry:
                        max_attempts: 0
                        backoff_base: 2s
                        backoff_max: 1s
                """.formatted(root.toAbsolutePath()))}, Map.of());
        assertTrue(msg.ok(), msg.errors().toString());
        var errs = ConfigValidator.validate(msg.document());
        assertTrue(errs.stream().anyMatch(e -> e.contains("start")), errs.toString());
        assertTrue(errs.stream().anyMatch(e -> e.contains("max_attempts") || e.contains("backoff")), errs.toString());

        var okProxy = ConfigLoader.load(new String[] {"--config=" + write("""
                routing:
                  trusted_proxies: ["::1/128", "127.0.0.1/32"]
                  enabled: false
                """)}, Map.of());
        assertTrue(okProxy.ok(), okProxy.errors().toString());

        MiddlewareSecrets.resetStartupWarnGateForTest();
        var inline = List.of(new sjws_nettask.core.MiddlewareSpec("jwt", 0, "*", Map.of("hmac-keys", "secret")));
        assertNotNull(MiddlewareSecrets.startupWarnMessage(inline, null, null));
        var logger = System.getLogger("cfg-359-test");
        MiddlewareSecrets.logStartupWarn(logger, inline, null, null);
        MiddlewareSecrets.logStartupWarn(logger, inline, null, null);
        MiddlewareSecrets.logStartupWarn(null, inline, null, null);
        MiddlewareSecrets.resetStartupWarnGateForTest();
    }

}
