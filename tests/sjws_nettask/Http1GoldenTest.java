package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Byte-for-byte regression check of the HTTP/1.1 wire output (Date and the file-key part of ETag
 * normalised). S3 changes vs review-fixes: ETag on file responses; 304 carries only ETag and
 * Last-Modified (no Content-Type/Accept-Ranges, RFC 9110 §15.4.5).
 * <p>
 * Golden file: tests/regression/http1-golden.txt, captured from the review-fixes jar with
 * {@code mvn test -Dtest=Http1GoldenTest -Dgolden.jar=/path/to/before.jar}
 * (runs the jar as a subprocess and rewrites the golden file instead of asserting).
 */
class Http1GoldenTest {

    static final Path GOLDEN = Path.of("tests", "regression", "http1-golden.txt");
    static final Instant MTIME = Instant.parse("2001-02-03T04:05:06Z");
    static final Pattern DATE = Pattern.compile("\r\nDate: [^\r]*\r\n");
    /** The file-key (inode) hash differs per run; the W/ marker, mtime and size parts stay checked. */
    static final Pattern ETAG_KEY = Pattern.compile("\r\nETag: (W/)?\"[0-9a-f]+-");

    @TempDir
    Path tmp;

    /** Ordered cases: name -> raw request bytes (each closes the connection so the response ends at EOF). */
    static Map<String, String> cases() {
        var c = new LinkedHashMap<String, String>();
        c.put("200-file", "GET /hello.txt HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("200-css-charset", "GET /sub/a.css HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("200-binary", "GET /blob.bin HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("200-index", "GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("200-absolute-form", "GET http://h/hello.txt HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("200-http10", "GET /hello.txt HTTP/1.0\r\n\r\n");
        c.put("200-query", "GET /hello.txt?v=1 HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("200-listing", "GET /sub/ HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("200-listing-odd-names", "GET /odd/ HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("head-file", "HEAD /hello.txt HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("head-404", "HEAD /missing.txt HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("404", "GET /missing.txt HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("404-traversal", "GET /../secret.txt HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("404-dotfile", "GET /.hidden HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("301", "GET /sub HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("301-query", "GET /sub?x=1&y=%20 HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("304", "GET /hello.txt HTTP/1.1\r\nHost: h\r\nIf-Modified-Since: Sat, 03 Feb 2001 04:05:06 GMT\r\nConnection: close\r\n\r\n");
        c.put("304-head", "HEAD /hello.txt HTTP/1.1\r\nHost: h\r\nIf-Modified-Since: Sat, 03 Feb 2001 04:05:06 GMT\r\nConnection: close\r\n\r\n");
        c.put("200-ims-older", "GET /hello.txt HTTP/1.1\r\nHost: h\r\nIf-Modified-Since: Sat, 03 Feb 2001 04:05:05 GMT\r\nConnection: close\r\n\r\n");
        c.put("403-dir", "GET /locked/ HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("403-file", "GET /private.txt HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("405-post", "POST /hello.txt HTTP/1.1\r\nHost: h\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        c.put("405-with-body", "PUT /x HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nhello");
        c.put("400-bad-encoding", "GET /%zz HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        c.put("400-parser", "GET / HTTP/1.1\r\nHost: h\r\nContent-Length : 5\r\n\r\n");
        c.put("505", "GET / HTTP/2.0\r\nHost: h\r\n\r\n");
        c.put("501-unknown-method", "BREW / HTTP/1.1\r\nHost: h\r\n\r\n");
        c.put("206-range", "GET /blob.bin HTTP/1.1\r\nHost: h\r\nRange: bytes=10-29\r\nConnection: close\r\n\r\n");
        c.put("206-suffix", "GET /hello.txt HTTP/1.1\r\nHost: h\r\nRange: bytes=-6\r\nConnection: close\r\n\r\n");
        c.put("206-open-end", "GET /hello.txt HTTP/1.1\r\nHost: h\r\nRange: bytes=6-\r\nConnection: close\r\n\r\n");
        c.put("416", "GET /hello.txt HTTP/1.1\r\nHost: h\r\nRange: bytes=100-\r\nConnection: close\r\n\r\n");
        c.put("200-multi-range", "GET /hello.txt HTTP/1.1\r\nHost: h\r\nRange: bytes=0-1,3-4\r\nConnection: close\r\n\r\n");
        c.put("206-if-range-date", "GET /hello.txt HTTP/1.1\r\nHost: h\r\nRange: bytes=0-4\r\n"
                + "If-Range: Sat, 03 Feb 2001 04:05:06 GMT\r\nConnection: close\r\n\r\n");
        c.put("200-if-range-etag-mismatch", "GET /hello.txt HTTP/1.1\r\nHost: h\r\nRange: bytes=0-4\r\n"
                + "If-Range: \"other\"\r\nConnection: close\r\n\r\n");
        c.put("head-range", "HEAD /hello.txt HTTP/1.1\r\nHost: h\r\nRange: bytes=0-4\r\nConnection: close\r\n\r\n");
        c.put("200-range-listing", "GET /sub/ HTTP/1.1\r\nHost: h\r\nRange: bytes=0-4\r\nConnection: close\r\n\r\n");
        c.put("keepalive-pipelined", "GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n"
                + "HEAD /sub/a.css HTTP/1.1\r\nHost: h\r\n\r\n"
                + "GET /missing HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        return c;
    }

    static void buildRoot(Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("hello.txt"), "hello world\n");
        Files.writeString(root.resolve("index.html"), "<h1>home</h1>\n");
        Files.writeString(root.resolve(".hidden"), "secret");
        var sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("line ").append(i).append(" abcdefghijklmnopqrstuvwxyz0123456789\n");
        }
        Files.writeString(root.resolve("blob.bin"), sb);
        var sub = Files.createDirectories(root.resolve("sub"));
        Files.writeString(sub.resolve("a.css"), "body{}");
        Files.writeString(sub.resolve("b.txt"), "b");
        Files.createDirectories(sub.resolve("nested"));
        var odd = Files.createDirectories(root.resolve("odd"));
        for (var name : List.of("a b.txt", "100%.txt", "plus+sign.txt", "café.txt", "<x>&\"'.txt")) {
            Files.writeString(odd.resolve(name), "x");
        }
        Files.writeString(root.resolve("private.txt"), "private");
        var locked = Files.createDirectories(root.resolve("locked"));
        Files.writeString(root.getParent().resolve("secret.txt"), "TOP-SECRET");
        try (var walk = Files.walk(root)) {
            for (var p : (Iterable<Path>) walk::iterator) {
                Files.setLastModifiedTime(p, FileTime.from(MTIME));
            }
        }
        Files.setPosixFilePermissions(root.resolve("private.txt"), Set.of());
        Files.setPosixFilePermissions(locked, Set.of());
    }

    static void unlock(Path root) throws IOException {
        Files.setPosixFilePermissions(root.resolve("locked"), PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(root.resolve("private.txt"), PosixFilePermissions.fromString("rw-------"));
    }

    static byte[] exchange(int port, String request) throws IOException {
        try (var s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            return s.getInputStream().readAllBytes(); // every case ends with the server closing
        }
    }

    static String normalise(byte[] raw) {
        var text = new String(raw, StandardCharsets.ISO_8859_1);
        text = DATE.matcher(text).replaceAll("\r\nDate: <normalised>\r\n");
        return ETAG_KEY.matcher(text).replaceAll("\r\nETag: $1\"<key>-");
    }

    static String capture(int port) throws IOException {
        var out = new StringBuilder();
        for (var e : cases().entrySet()) {
            out.append("=== ").append(e.getKey()).append(" ===\n").append(normalise(exchange(port, e.getValue()))).append("\n");
        }
        return out.toString();
    }

    @Test
    void http1OutputIsByteIdenticalToGolden() throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "403 cases need a non-root user");
        var root = tmp.resolve("www");
        buildRoot(root);
        try {
            var jar = System.getProperty("golden.jar");
            if (jar != null) {
                Files.writeString(GOLDEN, captureFromJar(Path.of(jar), root), StandardCharsets.ISO_8859_1);
                return;
            }
            var server = java.nio.channels.ServerSocketChannel.open()
                    .bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
            var manager = new ClientManager(server, ServerConfig.defaults(root));
            var t = new Thread(manager);
            t.setDaemon(true);
            t.start();
            try {
                String actual = capture(server.socket().getLocalPort());
                String expected = Files.readString(GOLDEN, StandardCharsets.ISO_8859_1);
                if (!expected.equals(actual)) {
                    Files.writeString(Path.of("target", "http1-actual.txt"), actual, StandardCharsets.ISO_8859_1);
                }
                assertEquals(expected, actual, "HTTP/1 output differs from golden (see target/http1-actual.txt)");
            } finally {
                server.close();
            }
        } finally {
            unlock(root);
        }
    }

    private static String captureFromJar(Path jar, Path root) throws Exception {
        int port;
        try (var probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var pb = new ProcessBuilder(new ArrayList<>(List.of(javaBin, "-jar", jar.toString(), String.valueOf(port),
                root.toString()))).redirectErrorStream(true);
        pb.environment().remove("WEB_SERVER_DIR");
        pb.environment().remove("WEB_SERVER_PORT");
        Process p = pb.start();
        try {
            var startup = new ByteArrayOutputStream();
            int b;
            while ((b = p.getInputStream().read()) != -1 && !startup.toString().contains("Server started")) {
                startup.write(b);
            }
            Thread.sleep(200);
            return capture(port);
        } finally {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
