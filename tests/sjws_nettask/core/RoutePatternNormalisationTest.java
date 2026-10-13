package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-route filters match the decoded, normalised path, so a percent-encoded or dot-segment
 * spelling of a protected path cannot skip the filter; ambiguous spellings fail closed (match).
 */
class RoutePatternNormalisationTest {

    static final String[] ADMIN_SPELLINGS = {
        "/admin", "/admin/", "/admin/x", "/%61dmin", "/%61%64%6d%69%6e/x", "/ADMIN".toLowerCase(),
        "//admin", "/./admin", "/x/../admin", "/x/%2e%2e/admin", "/%2e/admin/x", "/admin/./x?q", "/admin/.",
        // ambiguous between readers: fail closed
        "/admin;x/y", "/x/..;/admin", "/x%2f..%2fadmin", "/x/..%2Fadmin", "/x%5c..%5cadmin", "/x\\..\\admin",
        "/%2561dmin", "/%252e%252e/admin", "/%zz/admin", "/%", "/%E0%A4/admin", "/x/../../admin", "/a%00/admin",
    };

    @Test
    void everySpellingOfTheProtectedPathMatches() {
        var prefix = RoutePattern.parse("/admin/*");
        for (var t : ADMIN_SPELLINGS) {
            assertTrue(prefix.matches(t), t);
        }
        var exact = RoutePattern.parse("/admin");
        for (var t : new String[] {"/%61dmin", "//admin", "/x/../admin", "/./admin?x", "/%61dmin#f", "/admin;x"}) {
            assertTrue(exact.matches(t), t);
        }
    }

    @Test
    void otherPathsStillDoNotMatch() {
        var prefix = RoutePattern.parse("/admin/*");
        for (var t : new String[] {"/", "/adminx", "/public/admin", "/%61dminx", "/admin/../public", "/x/./y",
                "/public/%20x", "/caf%C3%A9", "/admin%20", "*", "", "/ad%6Din2/x"}) {
            assertFalse(prefix.matches(t), t);
        }
        var exact = RoutePattern.parse("/health");
        assertFalse(exact.matches("/health/"));
        assertFalse(exact.matches("/he%61lthy"));
        assertTrue(RoutePattern.parse("/caf\u00e9/*").matches("/caf%C3%A9/menu"));
    }

    @Test
    void plainPathsTakeTheFastPath() {
        assertTrue(RoutePattern.isPlain("/a/b.c/d", 8));
        assertTrue(RoutePattern.isPlain("/a/.well-known/x", 16));
        assertTrue(RoutePattern.isPlain("/a/..b", 6));
        for (var t : new String[] {"/a%20", "//a", "/./a", "/a/..", "/a/../b", "/a;b", "/a\\b", "/.", "x"}) {
            assertFalse(RoutePattern.isPlain(t, t.length()), t);
        }
    }

    @Test
    void canonicalForm() {
        assertEquals("/admin", RoutePattern.canonical("/%61dmin", 8));
        assertEquals("/admin/", RoutePattern.canonical("//admin//", 9));
        assertEquals("/", RoutePattern.canonical("/x/..", 5));
        assertEquals("/b", RoutePattern.canonical("/a/./../b", 9));
        assertNull(RoutePattern.canonical("/../a", 5));
        assertNull(RoutePattern.canonical("/%2561", 6));
        assertNull(RoutePattern.canonical("/a%2fb", 6));
    }

    @Test
    void routedFilterRunsForEncodedSpellings(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("admin"));
        Files.writeString(dir.resolve("admin").resolve("secret.txt"), "s");
        var hits = new AtomicInteger();
        var chain = RequestFilters.create().add(0, "/admin/*", (request, next) -> {
            hits.incrementAndGet();
            return StaticFileHandler.error(403, request.headOnly());
        }).build(new StaticFileHandler(dir));
        for (var t : new String[] {"/admin/secret.txt", "/%61dmin/secret.txt", "//admin/secret.txt",
                "/x/../admin/secret.txt", "/admin/%73ecret.txt", "/%2561dmin/secret.txt"}) {
            assertEquals(403, chain.handle(new Request("GET", t)).status(), t);
        }
        assertEquals(6, hits.get());
        assertEquals(404, chain.handle(new Request("GET", "/other.txt")).status());
        assertEquals(6, hits.get());
    }
}
