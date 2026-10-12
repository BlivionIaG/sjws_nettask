package sjws_nettask.http1;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Body;
import sjws_nettask.core.DateClock;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/** S3: head + small body leave in one write; the Date comes from the cached clock. */
class Http1WriterSingleWriteTest {

    /** Records every write(...) call it receives (what a socket would see as syscalls). */
    static final class Recording extends OutputStream {
        final List<Integer> writes = new ArrayList<>();
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        int flushes;

        @Override
        public void write(int b) {
            writes.add(1);
            data.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            writes.add(len);
            data.write(b, off, len);
        }

        @Override
        public void flush() {
            flushes++;
        }
    }

    private static Response bytes(int n, boolean head) {
        var body = new byte[n];
        java.util.Arrays.fill(body, (byte) 'x');
        return new Response(200, ResponseHeaders.of().add("content-type", "text/plain"), Body.Bytes.of(body), head);
    }

    @Test
    void smallBodyIsOneWrite() throws IOException {
        var out = new Recording();
        Http1Writer.write(out, bytes(1024, false), true, ResponseHeaders.of());
        assertEquals(1, out.writes.size(), out.writes.toString());
        var s = out.data.toString(StandardCharsets.ISO_8859_1);
        assertTrue(s.endsWith("\r\n\r\n" + "x".repeat(1024)));
        assertTrue(s.contains("Content-Length: 1024\r\n"));
    }

    @Test
    void bodiesUpToTheCacheLimitAreOneWriteEvenThroughA64KBufferedStream() throws IOException {
        for (int n : new int[] {0, 1, 64 * 1024 - 300, 64 * 1024, 100 * 1024, Http1Writer.SINGLE_WRITE_MAX}) {
            var sink = new Recording();
            var buffered = new BufferedOutputStream(sink, 64 * 1024); // as in Client
            Http1Writer.write(buffered, bytes(n, false), true, ResponseHeaders.of());
            assertEquals(1, sink.writes.size(), n + " bytes: " + sink.writes);
            assertTrue(sink.data.size() > n);
        }
    }

    @Test
    void headAndErrorsAreOneWrite() throws IOException {
        for (var r : new Response[] {bytes(500, true), StaticFileHandler.error(404, false),
            StaticFileHandler.redirect("/a/", false), new Response(304, ResponseHeaders.of(), Body.Empty.INSTANCE)}) {
            var sink = new Recording();
            Http1Writer.write(new BufferedOutputStream(sink, 64 * 1024), r, true, ResponseHeaders.of());
            assertEquals(1, sink.writes.size(), r.status() + " " + sink.writes);
        }
    }

    @Test
    void largerBodiesStillArriveIntact() throws IOException {
        int n = Http1Writer.SINGLE_WRITE_MAX + 1;
        var sink = new Recording();
        Http1Writer.write(new BufferedOutputStream(sink, 64 * 1024), bytes(n, false), false, ResponseHeaders.of());
        var s = sink.data.toString(StandardCharsets.ISO_8859_1);
        assertTrue(s.endsWith("\r\n\r\n" + "x".repeat(n)));
    }

    @Test
    void singleWriteIsByteIdenticalToHeadThenBody() throws IOException {
        var r = bytes(3000, false);
        var extra = ResponseHeaders.of().add("alt-svc", "h3=\":443\"; ma=60");
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, r, false, extra);
        var expected = new ByteArrayOutputStream();
        expected.write(Http1Writer.serializeHead(r, false, extra));
        expected.write("x".repeat(3000).getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(norm(expected.toString(StandardCharsets.ISO_8859_1)), norm(out.toString(StandardCharsets.ISO_8859_1)));
    }

    private static String norm(String s) {
        return s.replaceAll("Date: [^\r]*", "Date: D");
    }

    @Test
    void writerUsesTheCachedDate() throws IOException {
        long before = DateClock.SYSTEM.formats();
        long start = System.currentTimeMillis();
        for (int i = 0; i < 2000; i++) {
            Http1Writer.write(OutputStream.nullOutputStream(), bytes(10, false), true, ResponseHeaders.of());
        }
        long seconds = (System.currentTimeMillis() - start) / 1000 + 2;
        assertTrue(DateClock.SYSTEM.formats() - before <= seconds, "formatted per response");
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, bytes(1, false), true, ResponseHeaders.of());
        var date = out.toString(StandardCharsets.ISO_8859_1).split("\r\nDate: ")[1].split("\r\n")[0];
        assertTrue(Math.abs(sjws_nettask.core.HttpDate.parse(date).getEpochSecond()
                - java.time.Instant.now().getEpochSecond()) <= 1, date);
    }

    @Test
    void fileBodiesAreUnchanged(@TempDir Path tmp) throws IOException {
        var f = Files.writeString(tmp.resolve("a.txt"), "file-body").toRealPath();
        var r = StaticFileHandler.fileResponse(f, false, null);
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, r, true, ResponseHeaders.of());
        assertTrue(out.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\nfile-body"));
    }

    @Test
    void emptyBodyWith200IsOneWriteWithZeroLength() throws IOException {
        var sink = new Recording();
        Http1Writer.write(new BufferedOutputStream(sink, 64 * 1024),
                new Response(200, ResponseHeaders.of(), Body.Empty.INSTANCE), true, ResponseHeaders.of());
        assertEquals(1, sink.writes.size());
        var s = sink.data.toString(StandardCharsets.ISO_8859_1);
        assertTrue(s.contains("\r\nContent-Length: 0\r\n") && s.endsWith("\r\n\r\n"), s);
    }

    @Test
    void namesOutsideTheTableAreTitleCased() throws IOException {
        assertEquals("X-Custom-Name", Http1Writer.canonicalName("x-custom-name"));
        assertEquals("Content-Type", Http1Writer.canonicalName("content-type"));
        assertEquals("ETag", Http1Writer.canonicalName("etag"));
        assertEquals("A", Http1Writer.canonicalName("a"));
        assertEquals("X--Y", Http1Writer.canonicalName("x--y"));
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, new Response(200, ResponseHeaders.of().add("x-frame-options", "DENY"),
                Body.Bytes.of(new byte[] {'k'})), true, ResponseHeaders.of());
        assertTrue(out.toString(StandardCharsets.ISO_8859_1).contains("\r\nX-Frame-Options: DENY\r\n"));
    }
}
