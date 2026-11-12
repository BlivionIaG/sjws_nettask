/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;

/** A restored last-good refreshes the access-log credential query names before the content bind. */
@Timeout(20)
class LastGoodRestoreAccessLogTest {

    private static final String SECRET = "RESTORED-QUERY-SECRET";

    @TempDir
    Path dir;

    @Test
    void restoredSnapshotMasksItsCredentialQueryParam() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "ok");
        var log = dir.resolve("access.log");
        var state = dir.resolve("state");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath()
                + "\n  access_log: " + log.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var keys = KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var pin = dir.resolve("pin");
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        long now = System.currentTimeMillis();
        var body = "schema: 1\nversion: 2\nnode:\n  mode: data\nserver:\n  port: 0\n  root: "
                + html.toAbsolutePath() + "\n  access_log: " + log.toAbsolutePath()
                + "\nmiddleware:\n  - name: api-key\n    options:\n      keys: " + SECRET
                + "\n      query: restored_key\n";
        var snap = DataReadinessTest.signYaml(keys, body, 2L, 1L, now, now + 3_600_000L);
        LastGoodFiles.write(state, snap.version(), snap.epoch(), snap.keyId(), keys.bundle().keys(),
                keys.bundle().versionFloor(), keys.bundle().encoded(), snap);
        try (var server = SjwsServer.start(loaded, null, true, pin)) {
            assertTrue(server.contentBound());
            assertTrue(server.dataPlane().hasLastGood());
            HttpTestSupport.rawRequest(server.tcpPort(), "GET /index.html?restored_key=" + SECRET
                    + "&marker=1 HTTP/1.1\r\nHost: h\r\nx-api-key: " + SECRET + "\r\n\r\n", 4000);
            var line = awaitLine(log, "marker=1");
            assertTrue(line.contains("restored_key=***"), line);
            assertFalse(line.contains(SECRET), line);
        }
    }

    private static String awaitLine(Path log, String needle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            if (Files.exists(log)) {
                for (var line : Files.readAllLines(log)) {
                    if (line.contains(needle)) {
                        return line;
                    }
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("access log missing " + needle);
            }
            Thread.sleep(20);
        }
    }
}
