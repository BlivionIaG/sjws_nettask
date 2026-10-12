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
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.SafeOpenTest;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.tls.TlsMaterial;

/**
 * The security review's repros (F1-F6, {@code sjws_nettask-h3-review-security.md}), inverted: each
 * one asserts the fix.
 */
@Tag("h3")
class SecurityReviewTest {

    @TempDir
    Path base;
    Path root;
    StaticFileHandler handler;
    TlsMaterial tls;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable());
        this.root = H3Fixture.webRoot(this.base);
        this.handler = new StaticFileHandler(this.root);
        this.tls = TlsMaterial.selfSigned("localhost");
    }

    ServerConfig.Builder config() {
        return ServerConfig.builder(this.root).h3Enabled(true).h3Host("127.0.0.1").h3Port(0);
    }

    /** F6 over HTTP/3: a parent directory flipping to a symlink to outside never leaks the outside file. */
    @Test
    void m4ParentSymlinkRaceNeverLeaksOverHttp3() throws Exception {
        var outside = Files.createDirectories(this.base.resolve("outside"));
        Files.writeString(outside.resolve("a.txt"), "SECRET-OUTSIDE-ROOT-0123456789");
        var d = Files.createDirectories(this.root.resolve("d"));
        Files.writeString(d.resolve("a.txt"), "public-inside-root-abcdefghijk");
        var stop = new AtomicBoolean();
        int leaked = 0;
        int served = 0;
        try (var server = new Http3Server(config().build(), this.handler, this.tls).start();
                var client = new H3TestClient(this.tls.certificate())) {
            var q = client.connect(server.localAddress());
            var flipper = Thread.ofPlatform().start(() -> SafeOpenTest.flip(d, this.root.resolve("d.real"), outside,
                    stop));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<
                            H3TestClient.H3Response>>();
                    for (int i = 0; i < 16; i++) {
                        futures.add(H3TestClient.request(q, "GET", "/d/a.txt", Map.of()));
                    }
                    for (var f : futures) {
                        var r = f.get(10, TimeUnit.SECONDS);
                        var text = H3Fixture.text(r);
                        if (text.contains("SECRET-OUTSIDE")) {
                            leaked++;
                        } else if (r.status() == 200 && !r.reset()) {
                            served++;
                        }
                    }
                }
            } finally {
                stop.set(true);
                flipper.join();
            }
            assertTrue(server.responses(Http3Writer.Outcome.SWAPPED) > 0, "the race was hit");
        }
        assertEquals(0, leaked, "served " + served);
        assertTrue(served > 0);
    }
}
