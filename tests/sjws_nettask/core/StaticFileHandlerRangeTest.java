package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** S5: single byte ranges, If-Range, 416, and where Range is ignored (RFC 9110 §13.1.5, §14). */
class StaticFileHandlerRangeTest {

    static final Instant MTIME = Instant.parse("2001-02-03T04:05:06Z");
    static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz";
    static final String LAST_MODIFIED = "Sat, 03 Feb 2001 04:05:06 GMT";

    /** Uncached (FileBody region), cache miss that loads (first GET), cache hit (Bytes slice). */
    enum Mode { UNCACHED, CACHE_MISS, CACHE_HIT }

    @TempDir
    Path root;

    @BeforeEach
    void files() throws Exception {
        Files.writeString(root.resolve("a.txt"), ALPHABET);
        Files.writeString(root.resolve("empty.txt"), "");
        var dir = Files.createDirectories(root.resolve("dir"));
        Files.writeString(dir.resolve("x.txt"), "x");
        var site = Files.createDirectories(root.resolve("site"));
        Files.writeString(site.resolve("index.html"), "<h1>index</h1>");
        for (var p : new Path[] {root.resolve("a.txt"), root.resolve("empty.txt"), dir.resolve("x.txt"),
                site.resolve("index.html")}) {
            Files.setLastModifiedTime(p, FileTime.from(MTIME));
        }
    }

    private StaticFileHandler handler(Mode mode) {
        return mode == Mode.UNCACHED ? new StaticFileHandler(root)
                : new StaticFileHandler(root, false, new FileCache(1 << 20));
    }

    private static RequestHeaders range(String range, String ifRange) {
        return new RequestHeaders(null, null, ifRange, range, null, "h");
    }

    /** Runs the request in the given mode (CACHE_HIT: after a plain GET filled the cache). */
    private static Response send(StaticFileHandler h, Mode mode, String method, String target, RequestHeaders headers) {
        if (mode == Mode.CACHE_HIT) {
            assertEquals(200, h.handle(new Request("GET", target)).status());
        }
        return h.handle(new Request(method, target, headers));
    }

    private Response get(Mode mode, String target, String range, String ifRange) {
        return send(handler(mode), mode, "GET", target, range(range, ifRange));
    }

    private static String body(Response r) throws Exception {
        return new String(StaticFileHandlerTest.content(r.body()), StandardCharsets.UTF_8);
    }

    private static void assertPartial(Response r, long first, long last, long size) throws Exception {
        assertEquals(206, r.status());
        assertEquals("bytes " + first + "-" + last + "/" + size, r.headers().get("content-range"));
        assertEquals(last - first + 1, r.contentLength());
        assertEquals("bytes", r.headers().get("accept-ranges"));
        assertNotNull(r.headers().get("etag"));
        assertEquals(LAST_MODIFIED, r.headers().get("last-modified"));
        assertEquals("text/plain; charset=utf-8", r.headers().get("content-type"));
        assertFalse(r.headOnly());
    }

    private static void assertFull(Response r) throws Exception {
        assertEquals(200, r.status());
        assertNull(r.headers().get("content-range"));
        assertEquals("bytes", r.headers().get("accept-ranges"));
        assertEquals(26, r.contentLength());
        if (!r.headOnly()) {
            assertEquals(ALPHABET, body(r));
        }
    }

    // --- parser ---------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "bytes=0-0|0|1", "bytes=0-25|0|26", "bytes=5-9|5|5", "bytes=20-100|20|6", "bytes=25-|25|1",
        "bytes=0-|0|26", "bytes=-1|25|1", "bytes=-26|0|26", "bytes=-100|0|26", "BYTES=1-2|1|2",
        "Bytes=1-2|1|2", "'bytes= 1-2 '|1|2", "'bytes=\t1-2'|1|2", "'bytes=1-2,'|1|2", "'bytes=, ,1-2'|1|2",
        "bytes=0-999999999999999999|0|26", "bytes=000003-000004|3|2"})
    void satisfiableSingleRanges(String header, long offset, long length) {
        var d = ByteRanges.parse(header, 26);
        assertEquals(new ByteRanges.Partial(offset, length, 26), d, header);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bytes", "bytes=", "bytes=,", "bytes= , ", "items=0-1", "bytes 0-1", " bytes=0-1",
        "bytes =0-1", "bytes=0-1,3-4", "bytes=0-1, 0-1", "bytes=5-4", "bytes=a-b", "bytes=-", "bytes=1", "bytes=1-2-3",
        "bytes=1 -2", "bytes=+1-2", "bytes=-+1", "bytes=0x1-2", "bytes=1-2;x", "bytes=1000000000000000000-",
        "bytes=-1000000000000000000", "bytes=0-1000000000000000000", "bytes=99999999999999999999-1"})
    void ignoredRangesServeTheWholeFile(String header) {
        assertSame(ByteRanges.Full.INSTANCE, ByteRanges.parse(header, 26), header);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=26-", "bytes=26-30", "bytes=100-200", "bytes=-0", "bytes=999999999999999999-"})
    void unsatisfiableRanges(String header) {
        assertEquals(new ByteRanges.Unsatisfiable(26), ByteRanges.parse(header, 26), header);
        assertEquals("bytes */26", new ByteRanges.Unsatisfiable(26).contentRange());
    }

    @Test
    void emptyFilesAndNoHeaderIgnoreRanges() {
        assertSame(ByteRanges.Full.INSTANCE, ByteRanges.parse(null, 26));
        assertSame(ByteRanges.Full.INSTANCE, ByteRanges.parse("bytes=0-0", 0));
        assertSame(ByteRanges.Full.INSTANCE, ByteRanges.parse("bytes=-5", 0));
        assertThrows(IllegalArgumentException.class, () -> new ByteRanges.Partial(0, 0, 26));
        assertThrows(IllegalArgumentException.class, () -> new ByteRanges.Partial(20, 7, 26));
        assertThrows(IllegalArgumentException.class, () -> new ByteRanges.Partial(-1, 1, 26));
    }

    // --- handler, every mode ---------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Mode.class)
    void singleRangeIs206WithTheSlice(Mode mode) throws Exception {
        var r = get(mode, "/a.txt", "bytes=3-7", null);
        assertPartial(r, 3, 7, 26);
        assertEquals("defgh", body(r));
        if (mode == Mode.UNCACHED) {
            var f = assertInstanceOf(Body.FileBody.class, r.body());
            assertEquals(3, f.offset());
            assertEquals(5, f.length());
        } else {
            assertInstanceOf(Body.Bytes.class, r.body());
        }
        var suffix = get(mode, "/a.txt", "bytes=-4", null);
        assertPartial(suffix, 22, 25, 26);
        assertEquals("wxyz", body(suffix));
        var open = get(mode, "/a.txt", "bytes=24-", null);
        assertPartial(open, 24, 25, 26);
        assertEquals("yz", body(open));
        var clamped = get(mode, "/a.txt", "bytes=0-1000", null);
        assertPartial(clamped, 0, 25, 26);
        assertEquals(ALPHABET, body(clamped));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void unsatisfiableIs416WithContentRangeStar(Mode mode) throws Exception {
        var r = get(mode, "/a.txt", "bytes=26-", null);
        assertEquals(416, r.status());
        assertEquals("bytes */26", r.headers().get("content-range"));
        assertEquals("none", r.headers().get("accept-ranges"));
        assertNull(r.headers().get("etag"));
        assertTrue(body(r).contains("416 Range Not Satisfiable"));
        assertEquals(416, get(mode, "/a.txt", "bytes=-0", null).status());
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void multiAndMalformedRangesServe200(Mode mode) throws Exception {
        assertFull(get(mode, "/a.txt", "bytes=0-1,3-4", null));
        assertFull(get(mode, "/a.txt", "bytes=5-4", null));
        assertFull(get(mode, "/a.txt", "bytes=1000000000000000000-", null));
        assertFull(get(mode, "/a.txt", "lines=1-2", null));
        assertFull(get(mode, "/a.txt", null, null));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void ifRangeWithTheStrongEtagOrExactDate(Mode mode) throws Exception {
        var etag = handler(Mode.UNCACHED).handle(new Request("GET", "/a.txt")).headers().get("etag");
        assertFalse(etag.startsWith("W/"), "old file: strong ETag");
        assertPartial(get(mode, "/a.txt", "bytes=0-1", etag), 0, 1, 26);
        assertPartial(get(mode, "/a.txt", "bytes=0-1", " " + etag + " "), 0, 1, 26);
        assertFull(get(mode, "/a.txt", "bytes=0-1", "\"other\""));
        assertFull(get(mode, "/a.txt", "bytes=0-1", "W/" + etag)); // weak never matches (strong comparison)
        assertPartial(get(mode, "/a.txt", "bytes=0-1", LAST_MODIFIED), 0, 1, 26);
        // obsolete date forms are not parsed (HttpDate: IMF-fixdate only): fails safe to the full 200
        assertFull(get(mode, "/a.txt", "bytes=0-1", "Saturday, 03-Feb-01 04:05:06 GMT"));
        assertFull(get(mode, "/a.txt", "bytes=0-1", "Sat, 03 Feb 2001 04:05:07 GMT"));
        assertFull(get(mode, "/a.txt", "bytes=0-1", "Sat, 03 Feb 2001 04:05:05 GMT"));
        assertFull(get(mode, "/a.txt", "bytes=0-1", "yesterday"));
        assertFull(get(mode, "/a.txt", null, etag)); // If-Range alone means nothing
        // the 416 is only reached when If-Range holds
        assertEquals(416, get(mode, "/a.txt", "bytes=50-", etag).status());
        assertFull(get(mode, "/a.txt", "bytes=50-", "\"other\""));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void youngVersionsHaveNoStrongValidatorForIfRange(Mode mode) throws Exception {
        var young = root.resolve("young.txt");
        Files.writeString(young, ALPHABET); // mtime = now: weak ETag, weak Last-Modified
        var h = handler(mode);
        var first = send(h, mode, "GET", "/young.txt", RequestHeaders.NONE);
        var etag = first.headers().get("etag");
        assertTrue(etag.startsWith("W/"), etag);
        var lastModified = first.headers().get("last-modified");
        assertEquals(206, h.handle(new Request("GET", "/young.txt", range("bytes=0-1", null))).status(),
                "plain Range still works");
        assertEquals(200, h.handle(new Request("GET", "/young.txt", range("bytes=0-1", etag))).status());
        assertEquals(200, h.handle(new Request("GET", "/young.txt", range("bytes=0-1", lastModified))).status());
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void conditionalsComeFirst(Mode mode) throws Exception {
        var h = handler(mode);
        var etag = send(h, mode, "GET", "/a.txt", RequestHeaders.NONE).headers().get("etag");
        var inm = h.handle(new Request("GET", "/a.txt", new RequestHeaders(etag, null, null, "bytes=0-1", null, "h")));
        assertEquals(304, inm.status());
        assertNull(inm.headers().get("content-range"));
        var ims = h.handle(new Request("GET", "/a.txt",
                new RequestHeaders(null, LAST_MODIFIED, null, "bytes=0-1", null, "h")));
        assertEquals(304, ims.status());
        var unsatisfiableButFresh = h.handle(new Request("GET", "/a.txt",
                new RequestHeaders(etag, null, null, "bytes=99-", null, "h")));
        assertEquals(304, unsatisfiableButFresh.status());
        var changed = h.handle(new Request("GET", "/a.txt",
                new RequestHeaders("\"old\"", null, null, "bytes=0-1", null, "h")));
        assertPartial(changed, 0, 1, 26);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void headIgnoresRangeAndMatchesThePlainGetHead(Mode mode) throws Exception {
        var h = handler(mode);
        var plain = h.handle(new Request("GET", "/a.txt"));
        var head = send(h, mode, "HEAD", "/a.txt", range("bytes=0-1", null));
        assertFull(head);
        assertTrue(head.headOnly());
        assertEquals(plain.headers().toString(), head.headers().toString());
        assertEquals(200, send(h, mode, "HEAD", "/a.txt", range("bytes=99-", null)).status(), "not 416 either");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void rangeIsIgnoredForGeneratedPagesAndOtherMethods(Mode mode) throws Exception {
        var h = handler(mode);
        var listing = h.handle(new Request("GET", "/dir/", range("bytes=0-1", null)));
        assertEquals(200, listing.status());
        assertEquals("none", listing.headers().get("accept-ranges"));
        assertNull(listing.headers().get("content-range"));
        var missing = h.handle(new Request("GET", "/nope.txt", range("bytes=0-1", null)));
        assertEquals(404, missing.status());
        assertNull(missing.headers().get("content-range"));
        var redirect = h.handle(new Request("GET", "/dir", range("bytes=0-1", null)));
        assertEquals(301, redirect.status());
        var post = h.handle(new Request("POST", "/a.txt", range("bytes=0-1", null)));
        assertEquals(405, post.status());
        assertNull(post.headers().get("content-range"));
        var traversal = h.handle(new Request("GET", "/../a.txt", range("bytes=0-1", null)));
        assertEquals(404, traversal.status());
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void indexFilesAreFilesForRanges(Mode mode) throws Exception {
        var r = get(mode, "/site/", "bytes=4-8", null);
        assertEquals(206, r.status());
        assertEquals("bytes 4-8/14", r.headers().get("content-range"));
        assertEquals("index", body(r));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void emptyFileIgnoresRange(Mode mode) throws Exception {
        var r = get(mode, "/empty.txt", "bytes=0-0", null);
        assertEquals(200, r.status());
        assertEquals(0, r.contentLength());
        assertEquals(200, get(mode, "/empty.txt", "bytes=-1", null).status());
    }

    @Test
    void cachedSlicesDoNotDisturbTheCachedBody() throws Exception {
        var h = handler(Mode.CACHE_HIT);
        h.handle(new Request("GET", "/a.txt"));
        for (int i = 0; i < 3; i++) {
            assertEquals("cde", body(h.handle(new Request("GET", "/a.txt", range("bytes=2-4", null)))));
            assertEquals(ALPHABET, body(h.handle(new Request("GET", "/a.txt"))));
        }
        assertEquals(1, h.cache().size());
    }

    @Test
    void statusTextsAndConstants() {
        assertEquals("Partial Content", StaticFileHandler.reasonPhrase(206));
        assertEquals("Range Not Satisfiable", StaticFileHandler.reasonPhrase(416));
        assertEquals("bytes", StaticFileHandler.ACCEPT_RANGES);
        assertEquals("none", StaticFileHandler.NO_RANGES);
        assertEquals("bytes 0-9/26", new ByteRanges.Partial(0, 10, 26).contentRange());
    }
}
