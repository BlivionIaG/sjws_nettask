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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigDocument;
import sjws_nettask.config.ConfigLoader;

class DataApplyLastGoodVerifyTest {

    @TempDir
    Path dir;

    @Test
    void verifiedLastGoodIsAcceptedAndRejectsBrokenFixtures() throws Exception {
        var fixture = persist(5, System.currentTimeMillis() - 1_000, System.currentTimeMillis() + 3_600_000L);
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, fixture.pin).isPresent());
        assertTrue(DataApply.tryReadVerifiedLastGood(null, fixture.pin).isEmpty());
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, null).isEmpty());
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, dir.resolve("missing.pin")).isEmpty());
        assertTrue(DataApply.tryReadVerifiedLastGood(dir.resolve("empty-state"), fixture.pin).isEmpty());

        Files.delete(LastGoodFiles.configDir(fixture.state).resolve("trust.json"));
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, fixture.pin).isEmpty());

        fixture = persist(6, System.currentTimeMillis() - 1_000, System.currentTimeMillis() + 3_600_000L);
        Files.delete(LastGoodFiles.configDir(fixture.state).resolve("floor.json"));
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, fixture.pin).isEmpty());

        fixture = persist(7, System.currentTimeMillis() - 1_000, System.currentTimeMillis() + 3_600_000L);
        var other = KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var wrongPin = dir.resolve("wrong.pin");
        LastGoodFiles.writePin(wrongPin, other.rootPublic(), 0);
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, wrongPin).isEmpty());

        LastGoodFiles.writePin(fixture.pin, fixture.keys.rootPublic(), 8);
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, fixture.pin).isEmpty());
    }

    @Test
    void verifiedLastGoodRejectsTamperedEnvelopeAndStaleVersions() throws Exception {
        var fixture = persist(5, System.currentTimeMillis() - 1_000, System.currentTimeMillis() + 3_600_000L);
        var lastGood = LastGoodFiles.configDir(fixture.state).resolve("last-good.json");
        var original = Files.readString(lastGood);

        Files.writeString(lastGood, original.replace("\"parentVersion\":0", "\"parentVersion\":1"));
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, fixture.pin).isEmpty());

        Files.writeString(lastGood, original.replace("\"kind\":1", "\"kind\":2"));
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, fixture.pin).isEmpty());

        Files.writeString(lastGood, original);
        var json = Files.readString(lastGood);
        var badHash = json.replaceFirst("\"canonicalHash\":\"[0-9a-f]+\"",
                "\"canonicalHash\":\"00\"");
        Files.writeString(lastGood, badHash);
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, fixture.pin).isEmpty());

        Files.writeString(lastGood, original);
        var flipped = flipSignature(original);
        Files.writeString(lastGood, flipped);
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, fixture.pin).isEmpty());

        Files.writeString(lastGood, original.replace("\"keyId\":\"online-1\"", "\"keyId\":\"missing-key\""));
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, fixture.pin).isEmpty());

        Files.writeString(lastGood, original);
        Files.writeString(LastGoodFiles.configDir(fixture.state).resolve("floor.json"),
                "{\"maxVersion\":4,\"maxEpoch\":1,\"keyId\":\"online-1\"}");
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, fixture.pin).isEmpty());

        Files.writeString(LastGoodFiles.configDir(fixture.state).resolve("trust.json"), "{not-json");
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, fixture.pin).isEmpty());
    }

    @Test
    void verifiedLastGoodRejectsExpiredMaterialAndHashMismatch() throws Exception {
        var expired = persist(3, System.currentTimeMillis() - 10_000, System.currentTimeMillis() - 1);
        assertTrue(DataApply.tryReadVerifiedLastGood(expired.state, expired.pin).isEmpty());

        var fixture = persist(4, System.currentTimeMillis() - 1_000, System.currentTimeMillis() + 3_600_000L);
        var lastGood = LastGoodFiles.configDir(fixture.state).resolve("last-good.json");
        var json = Files.readString(lastGood);
        var hash = "aa".repeat(32);
        Files.writeString(lastGood, json.replaceFirst("\"canonicalHash\":\"[0-9a-f]+\"",
                "\"canonicalHash\":\"" + hash + "\""));
        assertTrue(DataApply.tryReadVerifiedLastGood(fixture.state, fixture.pin).isEmpty());
        assertEquals(4, DataApply.tryReadLastGood(fixture.state).orElseThrow().version());

        var issuedLater = persist(8, System.currentTimeMillis() + 60_000, System.currentTimeMillis() + 120_000);
        assertTrue(DataApply.tryReadVerifiedLastGood(issuedLater.state, issuedLater.pin).isEmpty());
    }

    @Test
    void loadLastGoodUsesTheVerifiedPath() throws Exception {
        var yaml = dir.resolve("guard.yaml");
        var html = Files.createDirectories(dir.resolve("guard-html"));
        Files.writeString(html.resolve("index.html"), "ok");
        var state = dir.resolve("guard-state");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, java.util.Map.of());
        var pin = DataReadinessTest.persistVerifiedLastGood(dir, state, loaded.document(), 2);
        try (var server = sjws_nettask.SjwsServer.start(loaded, null, true, pin)) {
            assertTrue(server.dataPlane().hasLastGood());
            assertTrue(server.dataPlane().ready());
            assertTrue(server.contentBound());
        }
        var unsigned = DataPlaneGuard.forDocument(loaded.document());
        unsigned.loadLastGood(state);
        assertFalse(unsigned.hasLastGood());
    }

    private Fixture persist(long version, long issued, long expires) throws Exception {
        var html = Files.createDirectories(dir.resolve("html-" + version));
        Files.writeString(html.resolve("index.html"), "ok");
        var state = dir.resolve("state-" + version);
        var yaml = dir.resolve("sjws-" + version + ".yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, java.util.Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        ConfigDocument document = loaded.document();
        var keys = KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var pin = dir.resolve("root-" + version + ".pin");
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        var snap = keys.sign(document, version, 1L, 1L, issued, expires, 0L);
        LastGoodFiles.write(state, snap.version(), snap.epoch(), snap.keyId(), keys.bundle().keys(),
                keys.bundle().versionFloor(), keys.bundle().encoded(), snap);
        return new Fixture(state, pin, keys);
    }

    private static String flipSignature(String json) {
        int start = json.indexOf("\"signature\":\"") + "\"signature\":\"".length();
        int end = json.indexOf('"', start);
        var encoded = json.substring(start, end);
        var bytes = Base64.getDecoder().decode(encoded);
        bytes[0] ^= 0x01;
        return json.substring(0, start) + Base64.getEncoder().encodeToString(bytes) + json.substring(end);
    }

    private record Fixture(Path state, Path pin, KeyRing keys) {
    }
}
