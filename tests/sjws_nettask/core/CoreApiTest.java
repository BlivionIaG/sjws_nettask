package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Small public-API behaviours of the core helpers not reached through a server. */
class CoreApiTest {

    @TempDir
    Path root;

    @Test
    void responseHeadersSetRemoveContains() {
        var h = ResponseHeaders.of().set("X-A", "1").add("x-b", "2").add("X-A", "3");
        assertEquals("1", h.get("x-a"));
        h.set("X-A", "9");
        assertEquals("9", h.get("X-A"));
        assertTrue(h.contains("X-B"));
        assertSame(h, h.remove("X-B").remove("x-none"));
        assertFalse(h.contains("x-b"));
        assertNull(h.get("x-b"));
        for (var bad : new String[] {"a\rb", "a\nb", "a\0b"}) {
            assertThrows(IllegalArgumentException.class, () -> ResponseHeaders.of().add("x", bad), bad);
        }
    }

    @Test
    void textualTypesGetACharset() {
        assertEquals("text/plain; charset=utf-8", Mime.contentType("text/plain"));
        assertEquals("application/javascript; charset=utf-8", Mime.contentType("application/javascript"));
        assertEquals("application/json; charset=utf-8", Mime.contentType("application/json"));
        assertEquals("image/svg+xml; charset=utf-8", Mime.contentType("image/svg+xml"));
        assertEquals("image/png", Mime.contentType("image/png"));
        assertEquals("text/html", Mime.types().get("html"));
    }

    @Test
    void statusCodesOutsideThreeDigitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Response(99, ResponseHeaders.of(), Body.Bytes.of(new byte[0]), false));
        assertThrows(IllegalArgumentException.class, () -> new Response(1000, ResponseHeaders.of(), Body.Bytes.of(new byte[0]), false));
        assertEquals("Content Too Large", StaticFileHandler.reasonPhrase(413));
    }

    @Test
    void resolverRefusesUnrepresentablePaths() {
        var handler = new StaticFileHandler(root);
        assertEquals(root.toAbsolutePath().normalize(), handler.resolver().root());
        assertNull(handler.resolver().logical("/a\0b"));
    }

    @Test
    void cacheBoundsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new FileCache(-1, 1, 1, Duration.ZERO, System::nanoTime));
        assertThrows(IllegalArgumentException.class, () -> new FileCache(1, 0, 1, Duration.ZERO, System::nanoTime));
        assertThrows(IllegalArgumentException.class, () -> new FileCache(1, 1, -1, Duration.ZERO, System::nanoTime));
        assertThrows(IllegalArgumentException.class, () -> new FileCache(1, 1, 1, Duration.ofSeconds(-1), System::nanoTime));
        assertEquals(0, new FileCache(1 << 20).misses());
    }
}
