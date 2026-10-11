package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Invariants of the frozen core contract. */
class ContractTest {

    @Test
    void requestDefaults() {
        var r = new Request("HEAD", "/x");
        assertSame(RequestHeaders.NONE, r.headers());
        assertTrue(r.headOnly());
        assertFalse(new Request("GET", "/x").headOnly());
        assertThrows(NullPointerException.class, () -> new Request(null, "/"));
    }

    @Test
    void bytesBodyIsAReadOnlySnapshotOfTheRemainingBytes() {
        var src = ByteBuffer.wrap("abcdef".getBytes());
        src.position(2);
        var b = new Body.Bytes(src);
        assertEquals(4, b.length());
        assertTrue(b.data().isReadOnly());
        src.position(0); // later changes to the caller's position don't leak in
        assertEquals(4, b.length());
        assertEquals(6, Body.Bytes.of("abcdef".getBytes()).length());
    }

    @Test
    void fileBodyValidatesRegion() {
        var v = new Validator(null, 0, 10);
        assertThrows(IllegalArgumentException.class, () -> new Body.FileBody(Path.of("/x"), -1, 1, v));
        assertThrows(IllegalArgumentException.class, () -> new Body.FileBody(Path.of("/x"), 0, -1, v));
        assertEquals(5, new Body.FileBody(Path.of("/x"), 5, 5, v).length());
        assertEquals(0, Body.Empty.INSTANCE.length());
    }

    @Test
    void responseHeadersAreOrderedAndCaseInsensitive() {
        var h = ResponseHeaders.of().add("Content-Type", "a").add("X-One", "1").add("x-one", "2");
        assertEquals("a", h.get("content-type"));
        assertEquals("content-type", h.entries().get(0).getKey());
        assertEquals(3, h.size());
        h.set("X-ONE", "3");
        assertEquals(2, h.size());
        assertEquals("3", h.get("x-one"));
        assertEquals("x-one", h.entries().get(1).getKey());
        assertThrows(IllegalArgumentException.class, () -> ResponseHeaders.of().add("X", "a\r\nInjected: 1"));
        assertThrows(IllegalArgumentException.class, () -> ResponseHeaders.of().add("Bad Name", "v"));
        assertThrows(UnsupportedOperationException.class, () -> h.entries().clear());
    }

    @Test
    void responseBodyRules() {
        assertFalse(new Response(304, ResponseHeaders.of(), Body.Empty.INSTANCE).mayHaveBody());
        assertFalse(new Response(204, ResponseHeaders.of(), Body.Empty.INSTANCE).mayHaveBody());
        assertTrue(new Response(200, ResponseHeaders.of(), Body.Empty.INSTANCE).mayHaveBody());
        assertThrows(IllegalArgumentException.class, () -> new Response(42, ResponseHeaders.of(), Body.Empty.INSTANCE));
    }

    @Test
    void mime() {
        assertEquals("text/html", Mime.of("/A/INDEX.HTML"));
        assertEquals("text/css; charset=utf-8", Mime.contentType(Mime.of("x.css")));
        assertEquals("image/png", Mime.contentType(Mime.of("x.png")));
        assertEquals(Mime.DEFAULT, Mime.of("noext"));
    }

    @Test
    void httpDates() {
        var i = java.time.Instant.parse("2001-02-03T04:05:06Z");
        assertEquals("Sat, 03 Feb 2001 04:05:06 GMT", HttpDate.format(i));
        assertEquals(i, HttpDate.parse("Sat, 03 Feb 2001 04:05:06 GMT"));
        assertNull(HttpDate.parse("yesterday"));
        assertNull(HttpDate.parse(null));
    }
}
