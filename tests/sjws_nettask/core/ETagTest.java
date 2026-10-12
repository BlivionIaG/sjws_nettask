package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sjws_nettask.HTTP.Http1Writer;

/** S3: ETag generation, the young/weak rule, If-None-Match matrix, 304 heads. */
class ETagTest {

    static final Instant MTIME = Instant.parse("2001-02-03T04:05:06.123456789Z");

    @TempDir
    Path tmp;
    Path root;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T00:00:00Z"));
    final InstantSource clock = now::get;
    StaticFileHandler handler;
    String tag;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tmp.resolve("www"));
        Files.writeString(root.resolve("a.txt"), "hello");
        Files.setLastModifiedTime(root.resolve("a.txt"), FileTime.from(MTIME));
        Files.createDirectory(root.resolve("dir"));
        Files.writeString(root.resolve("dir/index.html"), "<p>i</p>");
        Files.setLastModifiedTime(root.resolve("dir/index.html"), FileTime.from(MTIME));
        handler = new StaticFileHandler(root, false, clock);
        tag = get("/a.txt", RequestHeaders.NONE).headers().get("etag");
    }

    private Response get(String path, RequestHeaders h) {
        return handler.handle(new Request("GET", path, h));
    }

    private static RequestHeaders inm(String v) {
        return new RequestHeaders(v, null, null, null, null, null);
    }

    private static RequestHeaders both(String inm, String ims) {
        return new RequestHeaders(inm, ims, null, null, null, null);
    }

    // --- generation ---------------------------------------------------------------------------

    @Test
    void strongTagFromKeyMtimeAndSize() {
        var v = new Validator("k", 0x10, 0x20);
        assertEquals("\"" + Integer.toHexString("k".hashCode()) + "-10-20\"", EntityTag.strong(v));
        assertNotEquals(EntityTag.strong(v), EntityTag.strong(new Validator("k2", 0x10, 0x20)));
        assertNotEquals(EntityTag.strong(v), EntityTag.strong(new Validator("k", 0x11, 0x20)));
        assertNotEquals(EntityTag.strong(v), EntityTag.strong(new Validator("k", 0x10, 0x21)));
        assertEquals("\"0-10-20\"", EntityTag.strong(new Validator(null, 0x10, 0x20)), "Windows: no file key");
    }

    @Test
    void oldFileGetsAStrongTagWithNanosecondMtime() {
        assertFalse(tag.startsWith("W/"), tag);
        assertTrue(tag.contains("-" + Long.toHexString(MTIME.getEpochSecond() * 1_000_000_000L + MTIME.getNano()) + "-5\""),
                tag);
    }

    @Test
    void youngVersionIsWeakAndStaysWeakWhileUnchanged() throws IOException {
        var fresh = now.get().minusMillis(300);
        Files.setLastModifiedTime(root.resolve("a.txt"), FileTime.from(fresh));
        var first = get("/a.txt", RequestHeaders.NONE).headers().get("etag");
        assertTrue(first.startsWith("W/\""), first);
        now.set(now.get().plusSeconds(3600)); // much later, same version: same tag (parity, caches)
        assertEquals(first, get("/a.txt", RequestHeaders.NONE).headers().get("etag"));
        // a new version first seen when old is strong
        Files.setLastModifiedTime(root.resolve("a.txt"), FileTime.from(fresh.minusSeconds(10)));
        assertFalse(get("/a.txt", RequestHeaders.NONE).headers().get("etag").startsWith("W/"));
    }

    @Test
    void boundaryAndFutureMtimes() {
        var policy = new EntityTag.Policy(clock);
        long nowNs = now.get().getEpochSecond() * 1_000_000_000L;
        assertTrue(policy.tag(new Validator("a", nowNs - 999_000_000L, 1)).startsWith("W/"));
        assertFalse(policy.tag(new Validator("b", nowNs - 1_000_000_000L, 1)).startsWith("W/"));
        assertTrue(policy.tag(new Validator("c", nowNs + 5_000_000_000L, 1)).startsWith("W/"), "future mtime");
    }

    @Test
    void policyMemoryIsBounded() {
        var policy = new EntityTag.Policy(clock);
        long nowNs = now.get().getEpochSecond() * 1_000_000_000L;
        for (int i = 0; i < EntityTag.Policy.MAX_REMEMBERED * 3; i++) {
            policy.tag(new Validator(i, nowNs, i));
        }
        assertTrue(policy.remembered() <= EntityTag.Policy.MAX_REMEMBERED);
        for (int i = 0; i < 1000; i++) {
            policy.tag(new Validator("old" + i, 0, i)); // old versions are never remembered
        }
        assertTrue(policy.remembered() <= EntityTag.Policy.MAX_REMEMBERED);
    }

    // --- If-None-Match parsing (weak comparison) --------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "\"abc\"|\"abc\"|true",
        "W/\"abc\"|\"abc\"|true",
        "\"abc\"|W/\"abc\"|true",
        "W/\"abc\"|W/\"abc\"|true",
        "*|\"abc\"|true",
        " * |\"abc\"|true",
        "\"x\", \"abc\"|\"abc\"|true",
        "\"x\",W/\"abc\" , \"y\"|\"abc\"|true",
        "\"x\",,\t\"abc\"|\"abc\"|true",
        "\"ab\"|\"abc\"|false",
        "\"abcd\"|\"abc\"|false",
        "\"ABC\"|\"abc\"|false",
        "abc|\"abc\"|false",
        "\"abc|\"abc\"|false",
        "w/\"abc\"|\"abc\"|false",
        "\"x\", junk, \"abc\"|\"abc\"|false",
        "\"\"|\"abc\"|false",
        "''|\"abc\"|false"})
    void noneMatchMatrix(String header, String etag, boolean hit) {
        assertEquals(hit, EntityTag.noneMatchHits(header, etag), header + " vs " + etag);
    }

    // --- handler: precedence and 304 heads ------------------------------------------------

    @Test
    void matchingIfNoneMatchGives304WithItsOwnHead() {
        for (var method : List.of("GET", "HEAD")) {
            var r = handler.handle(new Request(method, "/a.txt", inm(tag)));
            assertEquals(304, r.status());
            assertEquals(List.of(Map.entry("etag", tag), Map.entry("last-modified", "Sat, 03 Feb 2001 04:05:06 GMT")),
                    r.headers().entries(), "304 head is not the 200 head");
            assertEquals(0, r.contentLength());
        }
    }

    @Test
    void the200HeadCarriesEtagInOrder() {
        var r = get("/a.txt", RequestHeaders.NONE);
        assertEquals(200, r.status());
        assertEquals(List.of("last-modified", "etag", "accept-ranges", "content-type"),
                r.headers().entries().stream().map(Map.Entry::getKey).toList());
    }

    @Test
    void ifNoneMatchTakesPrecedenceOverIfModifiedSince() {
        var later = "Sun, 04 Feb 2001 00:00:00 GMT"; // IMS alone would say 304
        assertEquals(304, get("/a.txt", RequestHeaders.ifModifiedSince(later)).status());
        assertEquals(200, get("/a.txt", both("\"other\"", later)).status(), "INM present: IMS ignored");
        var earlier = "Fri, 02 Feb 2001 00:00:00 GMT"; // IMS alone would say 200
        assertEquals(304, get("/a.txt", both(tag, earlier)).status());
        assertEquals(304, get("/a.txt", both("*", earlier)).status());
    }

    @Test
    void weakAndListFormsMatch() {
        assertEquals(304, get("/a.txt", inm("W/" + tag)).status());
        assertEquals(304, get("/a.txt", inm("\"nope\", " + tag)).status());
        assertEquals(200, get("/a.txt", inm("\"nope\"")).status());
        assertEquals(200, get("/a.txt", inm("garbage")).status());
    }

    @Test
    void ifModifiedSinceStillWorksAlone() {
        assertEquals(304, get("/a.txt", RequestHeaders.ifModifiedSince("Sat, 03 Feb 2001 04:05:06 GMT")).status());
        assertEquals(200, get("/a.txt", RequestHeaders.ifModifiedSince("Sat, 03 Feb 2001 04:05:05 GMT")).status());
        assertEquals(200, get("/a.txt", RequestHeaders.ifModifiedSince("not a date")).status());
    }

    @Test
    void changedFileNoLongerMatches() throws IOException {
        Files.writeString(root.resolve("a.txt"), "hello!");
        Files.setLastModifiedTime(root.resolve("a.txt"), FileTime.from(MTIME.plusSeconds(5)));
        var r = get("/a.txt", inm(tag));
        assertEquals(200, r.status());
        assertNotEquals(tag, r.headers().get("etag"));
    }

    @Test
    void missingFileIs404EvenWithStar() {
        assertEquals(404, get("/missing.txt", inm("*")).status());
    }

    @Test
    void indexGetsAnEtagListingsAndErrorsDoNot() throws IOException {
        var index = get("/dir/", RequestHeaders.NONE);
        assertNotNull(index.headers().get("etag"));
        assertEquals(304, get("/dir/", inm(index.headers().get("etag"))).status());
        Files.delete(root.resolve("dir/index.html"));
        assertNull(get("/dir/", RequestHeaders.NONE).headers().get("etag"));
        assertNull(get("/missing", RequestHeaders.NONE).headers().get("etag"));
    }

    @Test
    void http1304OnTheWire() throws IOException {
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, get("/a.txt", inm(tag)), true);
        var s = out.toString(StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("HTTP/1.1 304 Not Modified\r\n"), s);
        assertTrue(s.contains("\r\nETag: " + tag + "\r\n"), s);
        assertTrue(s.contains("\r\nDate: "), s);
        assertTrue(s.contains("\r\nLast-Modified: "), s);
        assertFalse(s.contains("Content-Length") || s.contains("Content-Type") || s.contains("Accept-Ranges"), s);
        assertTrue(s.endsWith("\r\n\r\n"), "no body");
    }

    @Test
    void staticHelpersAlsoTag() throws IOException {
        var r = StaticFileHandler.fileResponse(root.resolve("a.txt"), false, null);
        assertEquals(tag, r.headers().get("etag"));
    }
}
