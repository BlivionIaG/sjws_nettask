package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sjws_nettask.http1.Http1Writer;

/**
 * Platform edge cases from the core review (W5): missing fileKey (Windows), encoded
 * separators and NUL, very long paths, case-insensitive / normalising file systems,
 * Windows-only name tricks. Assertions that depend on the host FS are guarded, the
 * invariant everywhere is: never the secret outside the root, never a 500.
 */
class PlatformEdgeCaseTest {

    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    @TempDir
    Path tmp;
    Path root;
    StaticFileHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tmp.resolve("www"));
        Files.writeString(root.resolve("hello.txt"), "hello world");
        var sub = Files.createDirectory(root.resolve("sub"));
        Files.writeString(sub.resolve("a.css"), "body{}");
        Files.writeString(tmp.resolve("secret.txt"), "TOP-SECRET");
        handler = new StaticFileHandler(root);
    }

    private Response get(String target) {
        return handler.handle(new Request("GET", target));
    }

    private void assertSafe(Response r, String target) throws IOException {
        assertNotEquals(500, r.status(), target);
        assertFalse(StaticFileHandlerTest.text(r).contains("TOP-SECRET"), target);
    }

    // --- Validator without fileKey (Windows: BasicFileAttributes.fileKey() == null) ----------

    private BasicFileAttributes attrs(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    @Test
    void validatorWithNullFileKeyFallsBackToMtimeAndSize() throws IOException {
        var a = attrs(root.resolve("hello.txt"));
        var v = new Validator(null, Validator.of(a).mtimeNanos(), a.size());
        assertTrue(v.sameFile(a), "no key -> identity cannot be checked, must not fail");
        assertTrue(v.sameFile(attrs(root.resolve("sub/a.css"))));
        assertTrue(v.unchanged(a));
        assertFalse(new Validator(null, v.mtimeNanos(), a.size() + 1).unchanged(a));
        assertFalse(new Validator(null, v.mtimeNanos() + 1, a.size()).unchanged(a));
    }

    @Test
    void writerServesFileBodyWhoseValidatorHasNoFileKey() throws IOException {
        var file = root.resolve("hello.txt").toRealPath();
        var a = attrs(file);
        var body = new Body.FileBody(file, 0, a.size(), new Validator(null, Validator.of(a).mtimeNanos(), a.size()));
        var response = new Response(200, ResponseHeaders.of().add("content-type", "text/plain"), body);
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, response, false, ResponseHeaders.of());
        var s = out.toString(StandardCharsets.UTF_8);
        assertTrue(s.startsWith("HTTP/1.1 200 OK\r\n"), s);
        assertTrue(s.endsWith("\r\n\r\nhello world"), s);
    }

    @Test
    void writerStillRefusesAMismatchedFileKey() throws IOException {
        var file = root.resolve("hello.txt").toRealPath();
        var a = attrs(file);
        org.junit.jupiter.api.Assumptions.assumeTrue(a.fileKey() != null, "FS without file keys");
        var other = attrs(root.resolve("sub/a.css")).fileKey();
        var body = new Body.FileBody(file, 0, a.size(), new Validator(other, 0, a.size()));
        var response = new Response(200, ResponseHeaders.of(), body);
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, response, false, ResponseHeaders.of());
        var wire = out.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(wire.startsWith("HTTP/1.1 404 "), "the identity check fails before the status is committed: " + wire);
    }

    @Test
    void handlerResponsesCarryAValidatorWhateverTheKey() throws IOException {
        var r = get("/hello.txt");
        var body = assertInstanceOf(Body.FileBody.class, r.body());
        assertEquals(attrs(root.resolve("hello.txt")).fileKey(), body.expected().fileKey()); // may be null
        assertEquals(11, body.expected().size());
    }

    // --- encoded separators, dot segments, NUL ---------------------------------------------

    @Test
    void encodedSlashIsDecodedBeforeResolutionAndStaysContained() throws IOException {
        // %2F is decoded before resolution (like Jetty/Tomcat with decoding on), so it behaves as '/'
        var r = get("/sub%2Fa.css");
        assertEquals(200, r.status());
        assertEquals("body{}", StaticFileHandlerTest.text(r));
        assertEquals(301, get("/sub%2F..%2Fsub").status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/..%2Fsecret.txt", "/sub%2F..%2F..%2Fsecret.txt", "/%2e%2e/secret.txt",
        "/%2E%2E%2Fsecret.txt", "/..%5Csecret.txt", "/sub%5C..%5C..%5Csecret.txt", "/..\\secret.txt",
        "//secret.txt", "/%2F..%2Fsecret.txt", "/sub/..%2F..%2F..%2F..%2Fetc%2Fpasswd"})
    void encodedTraversalNeverEscapes(String target) throws IOException {
        var r = get(target);
        assertSafe(r, target);
        assertEquals(404, r.status(), target);
    }

    @Test
    void doubleLeadingSlashCannotBecomeAnAbsolutePath() throws IOException {
        // root.resolve("/abs") would return /abs: must be rejected, not served
        var abs = tmp.resolve("secret.txt").toAbsolutePath().toString().replace('\\', '/');
        for (var t : new String[] {"/" + abs, "//etc/passwd", "/%2Fetc%2Fpasswd", "//"}) {
            var r = get(t);
            assertSafe(r, t);
            assertEquals(404, r.status(), t);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/hello.txt%00", "/hello.txt%00.html", "/%00", "/sub/%00/a.css", "/a%zz", "/a%",
        "/a%0"})
    void nulAndMalformedEscapesAre400(String target) throws IOException {
        var r = get(target);
        assertEquals(400, r.status(), target);
        assertSafe(r, target);
    }

    @Test
    void invalidUtf8IsHarmless() throws IOException {
        for (var t : new String[] {"/%FF", "/%C0%AE%C0%AE/secret.txt", "/%E2%82"}) {
            var r = get(t);
            assertSafe(r, t);
            assertTrue(r.status() == 400 || r.status() == 404, t + " -> " + r.status());
        }
    }

    // --- long paths ---------------------------------------------------------------------------

    @Test
    void segmentLongerThanNameMaxIs404Not500() throws IOException {
        var r = get("/" + "a".repeat(300) + ".txt"); // > 255 bytes: ENAMETOOLONG on most file systems
        assertEquals(404, r.status());
        assertEquals(404, get("/sub/" + "é".repeat(200)).status()); // 400 chars but 400 UTF-8 bytes too
    }

    @Test
    void pathLongerThanPathMaxIs404Not500() {
        assertEquals(404, get("/" + "abcdefgh/".repeat(1000) + "x").status()); // 9 KB > PATH_MAX (4096)
        assertEquals(404, get("/" + "../".repeat(2000) + "secret.txt").status());
    }

    @Test
    void longestLegalFileNameIsServed() throws IOException {
        var name = "n".repeat(250) + ".txt"; // 254 bytes
        try {
            Files.writeString(root.resolve(name), "long");
        } catch (IOException e) {
            org.junit.jupiter.api.Assumptions.abort("FS cannot store a 254-byte name: " + e);
        }
        var r = get("/" + name);
        assertEquals(200, r.status());
        assertEquals("long", StaticFileHandlerTest.text(r));
    }

    // --- case-insensitive / normalising file systems (macOS APFS/HFS+, Windows NTFS) -----------

    private boolean caseInsensitive() {
        return Files.exists(root.resolve("HELLO.TXT"));
    }

    @Test
    void caseHandlingFollowsTheFileSystemAndStaysContained() throws IOException {
        var r = get("/HELLO.TXT");
        if (caseInsensitive()) {
            assertEquals(200, r.status());
            assertEquals("hello world", StaticFileHandlerTest.text(r));
        } else {
            assertEquals(404, r.status(), "case-sensitive FS: names are exact");
        }
        assertSafe(get("/SUB/../../secret.txt"), "/SUB/../../secret.txt");
        assertEquals(404, get("/SUB/../../secret.txt").status());
        assertSafe(get("/../WWW/../secret.txt"), "/../WWW/../secret.txt");
    }

    @Test
    void contentTypeIgnoresExtensionCase() throws IOException {
        Files.writeString(root.resolve("upper.CSS"), "x{}");
        assertEquals("text/css; charset=utf-8", get("/upper.CSS").headers().get("content-type"));
        assertEquals(Mime.of("a.css"), Mime.of("A.CSS"));
    }

    @Test
    void dotfileRuleCannotBeBypassedByCase() throws IOException {
        Files.writeString(root.resolve(".env"), "SECRET");
        for (var t : new String[] {"/.env", "/.ENV", "/%2Eenv", "/sub/../.env"}) {
            var r = get(t);
            assertEquals(404, r.status(), t);
            assertFalse(StaticFileHandlerTest.text(r).contains("SECRET"), t);
        }
    }

    @Test
    void unicodeNormalisationVariants() throws IOException {
        Files.writeString(root.resolve("caf\u00e9.txt"), "nfc"); // NFC on disk
        var nfc = get("/caf%C3%A9.txt");
        assertEquals(200, nfc.status());
        assertEquals("nfc", StaticFileHandlerTest.text(nfc));
        // NFD request: 404 on Linux/NTFS (bytes differ), 200 on normalisation-insensitive APFS
        var nfd = get("/cafe%CC%81.txt");
        assertSafe(nfd, "nfd");
        assertTrue(nfd.status() == 200 || nfd.status() == 404, "nfd -> " + nfd.status());
        if (nfd.status() == 200) {
            assertEquals("nfc", StaticFileHandlerTest.text(nfd));
        }
    }

    // --- Windows-only name tricks: on POSIX these are just missing files -----------------------

    @ParameterizedTest
    @ValueSource(strings = {"/hello.txt.", "/hello.txt%20", "/hello.txt::$DATA", "/CON", "/nul.txt", "/HELLO~1.TXT",
        "/sub/a.css%3A%3A%24DATA", "/C:%5Cwindows%5Cwin.ini", "/..%3A/secret.txt"})
    void windowsNameTricks(String target) throws IOException {
        var r = get(target);
        assertSafe(r, target);
        if (!WINDOWS) {
            assertEquals(404, r.status(), target);
        }
    }
}
