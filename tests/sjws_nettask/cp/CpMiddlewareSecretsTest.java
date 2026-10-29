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
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.config.AccessSettings;
import sjws_nettask.config.AcmeSettings;
import sjws_nettask.config.ConfigCodec;
import sjws_nettask.config.ConfigDocument;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.ConfigSnapshot;
import sjws_nettask.config.ConfigReloader;
import sjws_nettask.acme.ChallengeBoard;
import sjws_nettask.config.MiddlewareSecrets;
import sjws_nettask.config.NodeSettings;
import sjws_nettask.config.Routing;
import sjws_nettask.core.MiddlewareSpec;

/**
 * S1: gateway credential options stay out of the CP shared document. Each assertion is meant to
 * fail on master (inline secrets are accepted and round-trip into the log / viewer body).
 */
class CpMiddlewareSecretsTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;
    /** Canary that must never appear in the Raft log, a signed snapshot, or a viewer GET body. */
    private static final String CANARY = "s1-canary-secret-DO-NOT-REPLICATE";

    @TempDir
    Path dir;

    @Test
    void inlineSecretProposeIsRefusedWithoutEchoingTheValue() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock)) {
            elect(store, clock);
            var yaml = baseYaml(root) + """
                    middleware:
                      - name: api-key
                        order: 10
                        options:
                          keys: "%s"
                    """.formatted(CANARY);
            var refused = store.propose(yaml);
            assertEquals(400, refused.status(), refused.body());
            assertTrue(refused.body().contains("keys-file"), refused.body());
            assertFalse(refused.body().contains(CANARY), refused.body());

            var jwt = store.propose(baseYaml(root) + """
                    middleware:
                      - name: jwt
                        order: 10
                        options:
                          alg: HS256
                          hmac-keys: "k:%s"
                    """.formatted(Base64.getEncoder().encodeToString(CANARY.getBytes(StandardCharsets.UTF_8))));
            assertEquals(400, jwt.status(), jwt.body());
            assertTrue(jwt.body().contains("hmac-keys-file"), jwt.body());
            assertFalse(jwt.body().contains(CANARY), jwt.body());

            var validated = store.validate(yaml);
            assertTrue(validated.body().contains("\"ok\":false"), validated.body());
            assertFalse(validated.body().contains(CANARY), validated.body());
        }
    }

    @Test
    void validFileRefLeavesNoSecretInLogSnapshotOrViewerBody() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var secretFile = writeSecret(dir.resolve("api-keys"), CANARY);
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        var state = dir.resolve("cp");
        try (var store = CpStore.open(state, keys, clock)) {
            elect(store, clock);
            // On master an inline canary is accepted and lands in the log; the fix refuses it first.
            var inline = store.propose(baseYaml(root) + """
                    middleware:
                      - name: api-key
                        order: 10
                        options:
                          keys: "%s"
                    """.formatted(CANARY));
            assertEquals(400, inline.status(), inline.body());
            assertFalse(inline.body().contains(CANARY), inline.body());
            var yaml = baseYaml(root) + """
                    middleware:
                      - name: api-key
                        order: 10
                        options:
                          keys-file: "%s"
                    """.formatted(secretFile.toAbsolutePath());
            var proposed = store.propose(yaml);
            assertEquals(200, proposed.status(), proposed.body());
            var activated = store.activate(NOW, TTL);
            assertEquals(200, activated.status(), activated.body());

            var body = store.configJson();
            assertFalse(body.contains(CANARY), body);
            assertTrue(body.contains("keys-file"), body);

            var versioned = store.configVersion(1);
            assertEquals(200, versioned.status(), versioned.body());
            assertFalse(versioned.body().contains(CANARY), versioned.body());
        }
        try (Stream<Path> walk = Files.walk(state)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                try {
                    var bytes = Files.readAllBytes(path);
                    assertTrue(indexOf(bytes, CANARY.getBytes(StandardCharsets.UTF_8)) < 0,
                            "secret bytes in " + path);
                } catch (java.io.IOException e) {
                    fail(path + ": " + e);
                }
            });
        }
    }

    @Test
    void viewerBodyAndCodecMaskLegacyInlineSecrets() {
        var leaked = """
                schema: 1
                version: 1
                middleware:
                  - name: "api-key"
                    order: 10
                    options:
                      keys: "%s"
                      header: "x-api-key"
                """.formatted(CANARY);
        var masked = MiddlewareSecrets.maskYaml(leaked);
        assertFalse(masked.contains(CANARY), masked);
        assertTrue(masked.contains(MiddlewareSecrets.MASK), masked);
        assertTrue(masked.contains("header"), masked);

        var spec = MiddlewareSpec.of("api-key", 10, null, Map.of("keys", CANARY, "header", "x-api-key"));
        var document = ConfigDocument.of(ServerConfig.builder(dir).port(0).build(), AcmeSettings.disabled(),
                Routing.disabled(), List.of(spec), AccessSettings.DEFAULT, NodeSettings.STANDALONE, 1, 1, null, null);
        // Identity encoding keeps the real value so a key rotation changes the hash.
        var identity = ConfigCodec.text(document);
        assertTrue(identity.contains(CANARY), identity);
        assertFalse(identity.contains(MiddlewareSecrets.MASK), identity);
        var viewer = ConfigCodec.viewerText(document);
        assertFalse(viewer.contains(CANARY), viewer);
        assertTrue(viewer.contains(MiddlewareSecrets.MASK), viewer);
    }

    @Test
    void inlineKeyRotationChangesCanonicalHashAndReloadTakesEffect() throws Exception {
        var root = Files.createDirectory(dir.resolve("rotate-html"));
        var first = ConfigDocument.of(ServerConfig.builder(root).port(0).build(), AcmeSettings.disabled(),
                Routing.disabled(),
                List.of(MiddlewareSpec.of("api-key", 10, null, Map.of("keys", "alpha-key", "header", "x-api-key"))),
                AccessSettings.DEFAULT, NodeSettings.STANDALONE, 1, 1, null, null);
        var second = ConfigDocument.of(ServerConfig.builder(root).port(0).build(), AcmeSettings.disabled(),
                Routing.disabled(),
                List.of(MiddlewareSpec.of("api-key", 10, null, Map.of("keys", "beta-key-rotated", "header", "x-api-key"))),
                AccessSettings.DEFAULT, NodeSettings.STANDALONE, 1, 2, null, null);
        assertNotEquals(first.canonicalHash(), second.canonicalHash());
        assertTrue(ConfigCodec.text(first).contains("alpha-key"));
        assertTrue(ConfigCodec.text(second).contains("beta-key-rotated"));
        assertFalse(ConfigCodec.viewerText(second).contains("beta-key-rotated"));
        assertTrue(ConfigCodec.viewerText(second).contains(MiddlewareSecrets.MASK));

        var yaml = dir.resolve("rotate.yaml");
        Files.writeString(yaml, baseYaml(root) + """
                version: 1
                middleware:
                  - name: api-key
                    order: 10
                    options:
                      keys: "alpha-key"
                      header: "x-api-key"
                """);
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var board = new ChallengeBoard();
        var current = ConfigSnapshot.compile(loaded.document(), board, 1);
        var reloader = new ConfigReloader(loaded);
        var rotated = ConfigDocument.of(loaded.document().server(), loaded.document().acme(),
                loaded.document().routing(),
                List.of(MiddlewareSpec.of("api-key", 10, null, Map.of("keys", "beta-key-rotated", "header", "x-api-key"))),
                loaded.document().access(), loaded.document().node(), 1, 2, yaml);
        var result = reloader.apply(current, rotated, false, board, true);
        assertTrue(result.applied(), result.errors().toString());
        assertNotEquals(current.generation(), result.generation());
        assertEquals("beta-key-rotated", result.snapshot().document().middleware().get(0).option("keys"));
        assertNotEquals(current.document().canonicalHash(), result.snapshot().document().canonicalHash());
    }

    @Test
    void maskYamlLeavesNonFilterKeysNodesIntact() throws Exception {
        var root = Files.createDirectory(dir.resolve("keys-topic-html"));
        var cfg = dir.resolve("keys-topic.yaml");
        Files.writeString(cfg, baseYaml(root) + """
                messaging:
                  enabled: true
                  topics:
                    keys:
                      partitions: 2
                      replication: 1
                      durability: async
                    hmac-keys:
                      partitions: 1
                      replication: 1
                      durability: async
                middleware:
                  - name: api-key
                    order: 10
                    options:
                      keys: "%s"
                  - name: access-log
                    order: 0
                    options:
                      format: combined
                """.formatted(CANARY));
        var loaded = ConfigLoader.load(new String[] {"--config=" + cfg}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertTrue(loaded.document().messaging().topics().containsKey("keys"));
        assertTrue(loaded.document().messaging().topics().containsKey("hmac-keys"));
        var masked = MiddlewareSecrets.maskYaml(loaded.document().canonicalText());
        assertFalse(masked.contains(CANARY), masked);
        assertTrue(masked.contains(MiddlewareSecrets.MASK), masked);
        // Topic names stay map keys (a value-only mask would make this YAML invalid).
        var maskedFile = dir.resolve("masked-roundtrip.yaml");
        Files.writeString(maskedFile, masked);
        var again = ConfigLoader.load(new String[] {"--config=" + maskedFile}, Map.of());
        assertTrue(again.ok(), again.errors().toString());
        assertTrue(again.document().messaging().topics().containsKey("keys"), again.errors().toString());
        assertTrue(again.document().messaging().topics().containsKey("hmac-keys"));
        assertEquals(MiddlewareSecrets.MASK, again.document().middleware().stream()
                .filter(step -> "api-key".equals(step.name())).findFirst().orElseThrow().option("keys"));
    }

    @Test
    void startupWarnNamesFilterNotSecretAndStaysQuietForFileRefs() {
        MiddlewareSecrets.resetStartupWarnGateForTest();
        var inline = List.of(MiddlewareSpec.of("api-key", 10, "/api/*", Map.of("keys", CANARY)));
        var msg = MiddlewareSecrets.startupWarnMessage(inline, null, null);
        assertNotNull(msg);
        assertTrue(msg.contains("applied"), msg);
        assertTrue(msg.contains("api-key"), msg);
        assertTrue(msg.contains("keys"), msg);
        assertTrue(msg.contains("/api/*") || msg.contains("route=/api/*"), msg);
        assertTrue(msg.contains(MiddlewareSecrets.ROTATION_DOC), msg);
        assertFalse(msg.contains(CANARY), msg);
        assertFalse(msg.contains(Integer.toString(CANARY.length())), msg);

        var fileOnly = List.of(MiddlewareSpec.of("api-key", 10, null, Map.of("keys-file", "/etc/sjws/api-keys")));
        assertNull(MiddlewareSecrets.startupWarnMessage(null, fileOnly, fileOnly));
        assertNull(MiddlewareSecrets.startupWarnMessage(fileOnly, fileOnly, fileOnly));

        var activatedInline = MiddlewareSecrets.startupWarnMessage(null, inline, null);
        assertNotNull(activatedInline);
        assertTrue(activatedInline.contains("activated"), activatedInline);
        assertFalse(activatedInline.contains(CANARY), activatedInline);

        var lastGoodInline = MiddlewareSecrets.startupWarnMessage(null, null, inline);
        assertNotNull(lastGoodInline);
        assertTrue(lastGoodInline.contains("last-good"), lastGoodInline);
        assertFalse(lastGoodInline.contains(CANARY), lastGoodInline);
    }

    @Test
    void localInlineKeysStillLoadWithAWarning() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var cfg = dir.resolve("local.yaml");
        Files.writeString(cfg, baseYaml(root) + """
                middleware:
                  - name: api-key
                    order: 10
                    options:
                      keys: "local-only-key"
                """);
        var loaded = ConfigLoader.load(new String[] {"--config=" + cfg.toAbsolutePath()}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertEquals(1, loaded.document().middleware().size());
        assertEquals("local-only-key", loaded.document().middleware().get(0).option("keys"));
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("keys-file")), loaded.warnings().toString());
        assertFalse(loaded.warnings().toString().contains("local-only-key"), loaded.warnings().toString());
    }

    private static String baseYaml(Path root) {
        return "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n";
    }

    private static Path writeSecret(Path path, String content) throws Exception {
        Files.writeString(path, content);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        return path;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void elect(CpStore store, FakeClock clock) {
        var saw = new AtomicBoolean();
        store.leadership().onLeaderChange((term, id) -> saw.set(true));
        clock.advance(Duration.ofMillis(100));
        store.drive();
        assertTrue(saw.get());
        assertTrue(store.leadership().isLeader());
        assertTrue(store.leadership().leaderReady());
    }
}
