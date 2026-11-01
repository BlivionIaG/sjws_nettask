package sjws_nettask.h2;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/** {@link ResponseWriter} against a recording sink: chunking, file failures, stalls, refusals. */
class ResponseWriterTest {

    @TempDir
    Path root;

    /** Records what was written; {@code stallAfter} writes never complete after that many. */
    static class Recorder implements ResponseWriter.Sink {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final List<Integer> statuses = new ArrayList<>();
        final List<Boolean> ends = new ArrayList<>();
        long bytes;
        ResponseWriter.Outcome aborted;
        int stallAfter = Integer.MAX_VALUE;
        boolean active = true;
        boolean failWrites;
        int writes;

        private ChannelFuture next() {
            if (++this.writes > this.stallAfter) {
                return new DefaultChannelPromise(this.channel); // never completes
            }
            return this.failWrites ? this.channel.newFailedFuture(new IOException("x")) : this.channel.newSucceededFuture();
        }

        @Override
        public ChannelFuture head(Response response, boolean endOfResponse) {
            this.statuses.add(response.status());
            this.ends.add(endOfResponse);
            return next();
        }

        @Override
        public ChannelFuture data(ByteBuf data, boolean endOfResponse) {
            this.bytes += data.readableBytes();
            data.release();
            this.ends.add(endOfResponse);
            return next();
        }

        @Override
        public boolean active() {
            return this.active;
        }

        @Override
        public ByteBufAllocator alloc() {
            return ByteBufAllocator.DEFAULT;
        }

        @Override
        public void abort(ResponseWriter.Outcome why) {
            this.aborted = why;
        }
    }

    private static ResponseWriter.Outcome write(Recorder r, Response response) {
        return new ResponseWriter(r, Duration.ofMillis(300), new Semaphore(4)).write(response);
    }

    @Test
    void bytesAreChunkedAndTheLastChunkEndsTheResponse() {
        var r = new Recorder();
        var data = new byte[ResponseWriter.CHUNK * 2 + 5];
        var response = new Response(200, ResponseHeaders.of(), Body.Bytes.of(data));
        assertEquals(ResponseWriter.Outcome.COMPLETE, write(r, response));
        assertEquals(data.length, r.bytes);
        assertEquals(List.of(false, false, false, true), r.ends);
        assertNull(r.aborted);
    }

    @Test
    void streamIsSentChunkByChunkAndEndsWithAnEmptyLastFrame() {
        var r = new Recorder();
        var src = new sjws_nettask.core.TestSource(false, new byte[3], new byte[4]);
        assertEquals(ResponseWriter.Outcome.COMPLETE, write(r, new Response(200, ResponseHeaders.of(), new Body.Stream(7, src))));
        assertEquals(7, r.bytes);
        assertEquals(List.of(false, false, false, true), r.ends);
        assertEquals(1, src.closes);
        var unknown = new Recorder();
        assertEquals(ResponseWriter.Outcome.COMPLETE, write(unknown,
                new Response(200, ResponseHeaders.of(), new Body.Stream(-1, new sjws_nettask.core.TestSource(true, new byte[2])))));
        assertEquals(2, unknown.bytes);
    }

    @Test
    void streamStopsWhenTheStreamIsGone() {
        var stalled = new Recorder();
        stalled.active = false;
        stalled.stallAfter = 0; // the head write never completes and the stream is inactive
        var src = new sjws_nettask.core.TestSource(false, new byte[2]);
        assertEquals(ResponseWriter.Outcome.CLOSED, write(stalled, new Response(200, ResponseHeaders.of(), new Body.Stream(2, src))));
        assertEquals(1, src.closes);
    }

    @Test
    void zeroLengthStreamEndsWithTheHead() {
        var r = new Recorder();
        var src = new sjws_nettask.core.TestSource(false);
        assertEquals(ResponseWriter.Outcome.COMPLETE, write(r, new Response(200, ResponseHeaders.of(), new Body.Stream(0, src))));
        assertEquals(List.of(true), r.ends);
        assertEquals(1, src.closes);
    }

    @Test
    void streamLengthMismatchHeadAndClosedSink() {
        var shortR = new Recorder();
        assertEquals(ResponseWriter.Outcome.SHRANK, write(shortR,
                new Response(200, ResponseHeaders.of(), new Body.Stream(9, new sjws_nettask.core.TestSource(false, new byte[2])))));
        var longR = new Recorder();
        assertEquals(ResponseWriter.Outcome.SHRANK, write(longR,
                new Response(200, ResponseHeaders.of(), new Body.Stream(1, new sjws_nettask.core.TestSource(false, new byte[2])))));
        var head = new Recorder();
        var src = new sjws_nettask.core.TestSource(false, new byte[2]);
        assertEquals(ResponseWriter.Outcome.COMPLETE, write(head,
                new Response(200, ResponseHeaders.of(), new Body.Stream(2, src), true)));
        assertEquals(List.of(true), head.ends);
        assertEquals(1, src.closes);
        var closed = new Recorder();
        closed.failWrites = true;
        assertEquals(ResponseWriter.Outcome.CLOSED, write(closed,
                new Response(200, ResponseHeaders.of(), new Body.Stream(4, new sjws_nettask.core.TestSource(false, new byte[2], new byte[2])))));
    }

    @Test
    void noBodyEndsWithTheHead() {
        var r = new Recorder();
        assertEquals(ResponseWriter.Outcome.COMPLETE, write(r, StaticFileHandler.options()));
        assertEquals(List.of(true), r.ends);
    }

    @Test
    void fileIsStreamedInChunks() throws IOException {
        Files.write(this.root.resolve("f.bin"), new byte[ResponseWriter.CHUNK + 1]);
        var response = new StaticFileHandler(this.root).handle(new Request("GET", "/f.bin"));
        var r = new Recorder();
        assertEquals(ResponseWriter.Outcome.COMPLETE, write(r, response));
        assertEquals(ResponseWriter.CHUNK + 1, r.bytes);
        assertEquals(List.of(false, false, true), r.ends);
    }

    @Test
    void deletedFileIsAnsweredWithTheMatchingError() throws IOException {
        var f = this.root.resolve("gone.txt");
        Files.writeString(f, "x".repeat(100));
        var response = new StaticFileHandler(this.root).handle(new Request("GET", "/gone.txt"));
        Files.delete(f);
        var r = new Recorder();
        assertEquals(ResponseWriter.Outcome.SWAPPED, write(r, response));
        assertEquals(List.of(404), r.statuses);
        assertNull(r.aborted, "an error response was sent instead of a reset");
    }

    @Test
    void shrunkFileAborts() throws IOException {
        var f = this.root.resolve("s.bin");
        Files.write(f, new byte[ResponseWriter.CHUNK * 3]);
        var response = new StaticFileHandler(this.root).handle(new Request("GET", "/s.bin"));
        try (var raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.setLength(10);
        }
        var r = new Recorder();
        assertEquals(ResponseWriter.Outcome.SHRANK, write(r, response));
        assertEquals(ResponseWriter.Outcome.SHRANK, r.aborted);
    }

    @Test
    void stalledPeerIsAbortedAfterTheWriteTimeout() {
        var r = new Recorder();
        r.stallAfter = 1;
        var response = new Response(200, ResponseHeaders.of(), Body.Bytes.of(new byte[ResponseWriter.CHUNK * 3]));
        assertEquals(ResponseWriter.Outcome.STALLED, write(r, response));
        assertEquals(ResponseWriter.Outcome.STALLED, r.aborted);
    }

    @Test
    void failedWriteMeansClosed() {
        var r = new Recorder();
        r.failWrites = true;
        var response = new Response(200, ResponseHeaders.of(), Body.Bytes.of(new byte[ResponseWriter.CHUNK * 2]));
        assertEquals(ResponseWriter.Outcome.CLOSED, write(r, response));
    }

    @Test
    void inactiveSinkIsNotWrittenTo() throws IOException {
        Files.writeString(this.root.resolve("a.txt"), "abc");
        var response = new StaticFileHandler(this.root).handle(new Request("GET", "/a.txt"));
        var r = new Recorder();
        r.active = false;
        assertEquals(ResponseWriter.Outcome.CLOSED, write(r, response));
        assertTrue(r.statuses.isEmpty());
        var bytes = new Recorder();
        bytes.active = false;
        bytes.stallAfter = 0;
        assertEquals(ResponseWriter.Outcome.CLOSED,
                write(bytes, new Response(200, ResponseHeaders.of(), Body.Bytes.of(new byte[10]))));
    }

    @Test
    void noFileSlotIsRejected() throws IOException {
        Files.writeString(this.root.resolve("a.txt"), "abc");
        var response = new StaticFileHandler(this.root).handle(new Request("GET", "/a.txt"));
        var r = new Recorder();
        assertEquals(ResponseWriter.Outcome.REJECTED,
                new ResponseWriter(r, Duration.ofMillis(50), new Semaphore(0)).write(response));
        assertEquals(ResponseWriter.Outcome.REJECTED, r.aborted);
    }

    @Test
    void interruptedWriterReportsClosed() {
        var r = new Recorder();
        r.stallAfter = 1;
        Thread.currentThread().interrupt();
        try {
            var response = new Response(200, ResponseHeaders.of(), Body.Bytes.of(new byte[ResponseWriter.CHUNK * 2]));
            assertEquals(ResponseWriter.Outcome.CLOSED, write(r, response));
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt is kept");
        }
    }

    @Test
    void sinkFailureIsFailed() {
        var r = new Recorder() {
            @Override
            public ChannelFuture head(Response response, boolean endOfResponse) {
                throw new IllegalStateException("boom");
            }
        };
        assertEquals(ResponseWriter.Outcome.FAILED, write(r, StaticFileHandler.options()));
        assertEquals(ResponseWriter.Outcome.FAILED, r.aborted);
    }

    @Test
    void requestMappingPrefersAuthorityAndToleratesMissingPseudoHeaders() {
        var h = new DefaultHttp2Headers().method("GET").path("/x").authority("a.example");
        h.add("host", "h.example");
        h.add("range", "bytes=0-1");
        var req = Http2StreamHandler.toRequest(h);
        assertEquals("HTTP/2", req.protocol());
        assertEquals("a.example", req.headers().host());
        assertEquals("bytes=0-1", req.headers().range());
        var hostOnly = new DefaultHttp2Headers().method("GET").path("/x");
        hostOnly.add("host", "h.example");
        assertEquals("h.example", Http2StreamHandler.toRequest(hostOnly).headers().host());
        var empty = Http2StreamHandler.toRequest(new DefaultHttp2Headers());
        assertEquals("", empty.method());
        assertEquals("", empty.target());
        assertEquals(400, Http2StreamHandler.refusal(empty).status());
    }

    /** #383 / #296: captureFields (generation.attachBodies) fills the raw map; false keeps static path. */
    @Test
    void captureFieldsCopiesRegularHeadersWhenAttachBodiesWould() {
        var h = new DefaultHttp2Headers().method("GET").path("/x").authority("a.example");
        h.add("x-api-key", "secret");
        h.add("host", "h.example");
        var off = Http2StreamHandler.toRequest(h, null, false, false);
        assertTrue(off.headers().fields().isEmpty(), "static path: no field map");
        assertEquals("a.example", off.headers().host());
        var on = Http2StreamHandler.toRequest(h, null, false, true);
        assertEquals(List.of("secret"), on.headers().fields().get("x-api-key"));
        assertFalse(on.headers().fields().containsKey(":method"));
        assertEquals("a.example", on.headers().host());
    }

    /** #383 audit: HTTP/1.1 ALPN fallback on the h2 TLS listener still omits the raw map. */
    @Test
    void http1FallbackLeavesFieldsEmpty() {
        var req = new io.netty.handler.codec.http.DefaultFullHttpRequest(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpMethod.GET, "/x");
        req.headers().set("x-api-key", "secret");
        req.headers().set("host", "h.example");
        var mapped = Http1FallbackHandler.toRequest(req);
        assertTrue(mapped.headers().fields().isEmpty());
        assertEquals("h.example", mapped.headers().host());
    }
}
