package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Security response headers: every response with a body carries {@code X-Content-Type-Options:
 * nosniff} (no MIME sniffing of uploaded/served files into HTML or script); generated HTML pages
 * (listings, errors, redirects) also carry a script-free, frame-free Content-Security-Policy.
 */
class SecurityHeadersTest {

    @TempDir
    Path tmp;
    StaticFileHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        var root = Files.createDirectory(tmp.resolve("www"));
        Files.writeString(root.resolve("evil.txt"), "<script>alert(1)</script>");
        Files.writeString(root.resolve("noext"), "<html><script>alert(1)</script>");
        var dir = Files.createDirectory(root.resolve("d"));
        Files.writeString(dir.resolve("<img src=x onerror=alert(1)>.txt"), "x");
        handler = new StaticFileHandler(root);
    }

    private Response get(String target) {
        return handler.handle(new Request("GET", target));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/evil.txt", "/noext"})
    void servedFilesAreNotSniffed(String target) {
        var r = get(target);
        assertEquals(200, r.status());
        assertEquals("nosniff", r.headers().get("X-Content-Type-Options"));
        assertNull(r.headers().get("content-security-policy"), "files keep their own semantics");
    }

    @Test
    void listingCarriesCspAndNosniff() {
        var r = get("/d/");
        assertEquals(200, r.status());
        assertEquals("nosniff", r.headers().get("x-content-type-options"));
        var csp = r.headers().get("content-security-policy");
        assertEquals(StaticFileHandler.HTML_CSP, csp);
        assertTrue(csp.contains("default-src 'none'"), "no script may run from a listing");
        assertTrue(csp.contains("frame-ancestors 'none'"), "no clickjacking via framing");
        assertFalse(csp.contains("script-src"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/missing", "/d", "/../etc/passwd"})
    void errorsAndRedirectsCarryCspAndNosniff(String target) {
        var r = get(target);
        assertTrue(r.status() >= 300, "status " + r.status());
        assertEquals("nosniff", r.headers().get("x-content-type-options"));
        assertEquals(StaticFileHandler.HTML_CSP, r.headers().get("content-security-policy"));
    }

    @Test
    void staticErrorBuilderHasHeaders() {
        for (int status : new int[] {400, 403, 404, 405, 408, 413, 414, 431, 500, 501, 505}) {
            var r = StaticFileHandler.error(status, false);
            assertEquals("nosniff", r.headers().get("x-content-type-options"), "status " + status);
            assertEquals(StaticFileHandler.HTML_CSP, r.headers().get("content-security-policy"));
        }
    }
}
