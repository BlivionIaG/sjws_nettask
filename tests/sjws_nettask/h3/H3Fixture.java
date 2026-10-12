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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import sjws_nettask.HTTP.Http1Writer;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.StaticFileHandler;

/** Web root fixture and the HTTP/1.1 parity oracle shared by the HTTP/3 tests. */
final class H3Fixture {

    static final String SECRET = "TOP-SECRET-OUTSIDE-ROOT";
    static final int LARGE = 8 * 1024 * 1024 + 12_345; // not a multiple of the chunk size

    private H3Fixture() {
    }

    /** Creates {@code base/root} with the test files and {@code base/secret.txt} outside it. */
    static Path webRoot(Path base) throws IOException {
        var root = Files.createDirectories(base.resolve("root"));
        Files.writeString(base.resolve("secret.txt"), SECRET);
        Files.writeString(root.resolve("index.html"), "<!DOCTYPE html><title>home</title>root index\n");
        Files.writeString(root.resolve("hello.txt"), "Hello over HTTP/3\n");
        var bin = new byte[256 * 1024];
        for (int i = 0; i < bin.length; i++) {
            bin[i] = (byte) i; // every byte value, many times
        }
        Files.write(root.resolve("bin.dat"), bin);
        var large = new byte[LARGE];
        new Random(42).nextBytes(large);
        Files.write(root.resolve("large.bin"), large);
        Files.writeString(root.resolve("a b+c.txt"), "spaces and plus\n");
        Files.writeString(root.resolve("\u00fcn\u00ef.txt"), "unicode name\n");
        Files.writeString(root.resolve("style.css"), "body{}\n");
        Files.write(root.resolve("empty.txt"), new byte[0]);
        var sub = Files.createDirectories(root.resolve("sub"));
        Files.writeString(sub.resolve("<script>x.txt"), "escaped\n");
        Files.writeString(sub.resolve("plain.txt"), "plain\n");
        Files.createDirectories(sub.resolve("nested"));
        var sub2 = Files.createDirectories(root.resolve("sub2"));
        Files.writeString(sub2.resolve("index.html"), "<p>sub2 index</p>\n");
        Files.createSymbolicLink(root.resolve("link-out"), base.resolve("secret.txt"));
        Files.createSymbolicLink(root.resolve("link-in.txt"), root.resolve("hello.txt"));
        return root;
    }

    static RequestHeaders requestHeaders(Map<String, String> h) {
        return new RequestHeaders(h.get("if-none-match"), h.get("if-modified-since"), h.get("if-range"),
                h.get("range"), h.get("accept-encoding"), "localhost");
    }

    /** What HTTP/1.1 sends for the same request through the same handler (parsed). */
    static HttpTestSupport.Response http1(StaticFileHandler handler, String method, String path,
            Map<String, String> headers) throws IOException {
        var request = new Request(method, path, requestHeaders(headers));
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, handler.handle(request), true);
        return HttpTestSupport.readResponse(new ByteArrayInputStream(out.toByteArray()), request.headOnly());
    }

    /** Fields only one of the transports owns. */
    static final Set<String> TRANSPORT = Set.of("date", "server", "connection", "alt-svc");

    /**
     * Asserts the HTTP/3 response equals the HTTP/1.1 one for the same request: status, every
     * representation field (both directions), content-length and body; plus the HTTP/3 transport
     * rules (date and server present, no connection-specific fields, lower-case names).
     */
    static void assertParity(StaticFileHandler handler, String method, String path, Map<String, String> headers,
            H3TestClient.H3Response h3) throws IOException {
        var h1 = http1(handler, method, path, headers);
        var what = method + " " + path + " " + headers;
        assertFalse(h3.reset(), what + ": stream was reset " + h3.error());
        assertEquals(h1.statusCode(), h3.status(), what);
        for (var e : h1.headers().entrySet()) {
            if (!TRANSPORT.contains(e.getKey())) {
                assertEquals(e.getValue(), h3.header(e.getKey()), what + ": field " + e.getKey());
            }
        }
        for (var name : h3.headers().keySet()) {
            if (!TRANSPORT.contains(name)) {
                assertTrue(h1.headers().containsKey(name), what + ": extra HTTP/3 field " + name);
            }
        }
        assertArrayEquals(h1.body(), h3.body(), what + ": body");
        assertNotNull(h3.header("date"), what);
        assertEquals(sjws_nettask.core.ServerInfo.NAME, h3.header("server"), what);
        for (var forbidden : new String[] {"connection", "keep-alive", "transfer-encoding", "upgrade",
                "proxy-connection"}) {
            assertNull(h3.header(forbidden), what + ": " + forbidden);
        }
        for (var name : h3.headerNames()) {
            assertEquals(name.toLowerCase(Locale.ROOT), name, what);
        }
        if (h3.status() == 304 || "HEAD".equals(method)) {
            assertEquals(0, h3.dataFrames(), what + ": no DATA expected");
        }
    }

    /** The body HTTP/1.1 sends for {@code response}. */
    static byte[] http1Body(sjws_nettask.core.Response response) throws IOException {
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, response, true);
        return HttpTestSupport.readResponse(new ByteArrayInputStream(out.toByteArray()), false).body();
    }

    static String text(H3TestClient.H3Response r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }
}
