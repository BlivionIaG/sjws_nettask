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
package sjws_nettask.acme;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.AcmeSettings;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.NodeSettings;

class AcmeLeadershipTest {

    @TempDir
    Path dir;

    @Test
    @Timeout(20)
    void issuingWaitsForLeaderReadyAndStopsWhenTheTermChanges() throws Exception {
        var leadership = LocalLeadership.control();
        assertFalse(leadership.isLeader());
        var attempts = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var service = new AcmeService(settings(dir.resolve("store")), new ChallengeBoard(), material -> {
        }, leadership);
        service.beforeAttempt = () -> {
            attempts.incrementAndGet();
            entered.countDown();
            leadership.change(3, "other", false);
        };
        service.start();
        assertEquals(0, attempts.get());
        leadership.change(2, "self", true);
        assertTrue(leadership.isLeader());
        assertEquals(0, attempts.get(), "isLeader alone must not issue");
        leadership.ready(2, 9);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(1, attempts.get());
        Thread.sleep(200);
        assertEquals(1, attempts.get(), "a new term must not schedule another attempt");
        service.close();
    }

    @Test
    void aNegativeAppliedIndexIsNotReady() throws Exception {
        var leadership = new LocalLeadership(true);
        var attempts = new AtomicInteger();
        var service = new AcmeService(settings(dir.resolve("neg")), new ChallengeBoard(), material -> {
        }, leadership);
        service.beforeAttempt = attempts::incrementAndGet;
        service.start();
        leadership.ready(-1, -1);
        Thread.sleep(150);
        assertEquals(0, attempts.get());
        service.close();
    }

    @Test
    void readyCallbacksThatAreNotLeaderReadyDoNotIssue() throws Exception {
        var gate = new Gate();
        var attempts = new AtomicInteger();
        var service = new AcmeService(settings(dir.resolve("gate")), new ChallengeBoard(), material -> {
        }, gate);
        service.beforeAttempt = attempts::incrementAndGet;
        service.start();
        gate.leader = false;
        gate.ready.accept(2L, 4L);
        gate.leader = true;
        gate.ready.accept(0L, 4L);
        gate.ready.accept(2L, -1L);
        gate.changed.accept(1L, Optional.of("self"));
        Thread.sleep(100);
        assertEquals(0, attempts.get());
        gate.ready.accept(2L, 4L);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (attempts.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, attempts.get());
        gate.changed.accept(2L, Optional.of("self"));
        service.update(settings(dir.resolve("gate")));
        service.close();
        gate.changed.accept(9L, Optional.empty());
    }

    @Test
    void logsDropPem() {
        var pem = "-----BEGIN PRIVATE KEY-----\nMIISECRET\n-----END PRIVATE KEY-----";
        assertEquals("IllegalStateException", AcmeIssuer.safe(new IllegalStateException(pem)));
        assertEquals("IllegalStateException",
                AcmeIssuer.safe(new IllegalStateException("-----BEGIN CERTIFICATE-----\nMII")));
        assertEquals("line", AcmeIssuer.safe(new IllegalStateException("line\n" + pem)));
    }

    @Test
    void dataNodeDoesNotStartAcmeAndControlWaits() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var storage = dir.resolve("acme");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, """
                node:
                  mode: data
                server:
                  port: 0
                  root: %s
                acme:
                  enabled: true
                  directory: http://127.0.0.1:1/directory
                  email: ops@example.com
                  domains: [example.com]
                  storage: %s
                  accept_terms: true
                """.formatted(root.toAbsolutePath(), storage.toAbsolutePath()));
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertEquals(NodeSettings.Mode.DATA, loaded.document().node().mode());
        var before = names();
        try (var server = SjwsServer.start(loaded)) {
            assertNull(server.leadership());
            var started = names();
            started.removeAll(before);
            assertFalse(started.contains("acme-renew"), started.toString());
        }
        assertFalse(Files.isRegularFile(storage.resolve("account.key")));

        Files.writeString(yaml, """
                node:
                  mode: control
                server:
                  port: 0
                  root: %s
                acme:
                  enabled: true
                  directory: http://127.0.0.1:1/directory
                  email: ops@example.com
                  domains: [example.com]
                  storage: %s
                  accept_terms: true
                """.formatted(root.toAbsolutePath(), storage.toAbsolutePath()));
        var control = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(control.ok(), control.errors().toString());
        try (var server = SjwsServer.start(control)) {
            assertFalse(server.leadership().isLeader());
            Thread.sleep(200);
            assertFalse(Files.isRegularFile(storage.resolve("account.key")));
            ((LocalLeadership) server.leadership()).ready(4, 11);
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.isRegularFile(storage.resolve("account.key")) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(Files.isRegularFile(storage.resolve("account.key")));
            var text = Files.readString(storage.resolve("account.key"));
            assertTrue(text.contains("PRIVATE KEY"));
            assertFalse(text.contains("MIISECRET"));
        }
    }

    private static AcmeSettings settings(Path storage) {
        return new AcmeSettings(true, "http://127.0.0.1:1/directory", "ops@example.com",
                java.util.List.of("example.com"), storage, 0, true, Duration.ofDays(30));
    }

    private static final class Gate implements ControlLeadership {
        private boolean leader;
        private BiConsumer<Long, Optional<String>> changed = (term, id) -> {
        };
        private BiConsumer<Long, Long> ready = (term, index) -> {
        };

        public boolean isLeader() {
            return this.leader;
        }

        public void onLeaderChange(BiConsumer<Long, Optional<String>> listener) {
            this.changed = listener;
        }

        public void leaderReady(BiConsumer<Long, Long> listener) {
            this.ready = listener;
        }
    }

    private static HashSet<String> names() {
        var names = new HashSet<String>();
        for (var thread : Thread.getAllStackTraces().keySet()) {
            names.add(thread.getName());
        }
        return names;
    }
}
