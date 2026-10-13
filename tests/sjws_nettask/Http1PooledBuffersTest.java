/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Body;
import sjws_nettask.core.FileCache;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.core.buf.BufferPool;
import sjws_nettask.core.buf.NoBufferLeaks;

/**
 * HTTP/1 on the shared buffer pool: every exchange runs once with the pool off and once on a debug pool (every
 * buffer tracked), the responses must be byte-identical (Date masked), and the pool must hold nothing afterwards
 * (aborted connections, errors, pipelining, the 414/431 drain path included).
 */
@ExtendWith(NoBufferLeaks.class)
class Http1PooledBuffersTest {

    @TempDir
    Path root;
    private ConnectionReaper reaper;
    private Handler handler;
    private final byte[] small = random(10_000, 1);
    private final byte[] large = random(200 * 1024, 2);
    private final byte[] bytes = random(1000, 3);
    private final byte[] mid = random(100 * 1024, 4);
    private final byte[] huge = random(300 * 1024, 5);

    private static byte[] random(int n, long seed) {
        var b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static BufferPool debugPool() {
        return NoBufferLeaks.watch(BufferPool.create(new BufferPool.Settings(true, 8L << 20, Map.of(), true, 1, 0)));
    }

    @BeforeEach
    void setUp() throws IOException {
        Files.write(this.root.resolve("small.bin"), this.small);
        Files.write(this.root.resolve("large.bin"), this.large);
        Files.write(this.root.resolve("empty.bin"), new byte[0]);
        this.reaper = new ConnectionReaper(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));
        var files = new StaticFileHandler(this.root, false, FileCache.disabled());
        this.handler = request -> switch (request.target()) {
            case "/bytes" -> new Response(200, ResponseHeaders.of(), Body.Bytes.of(this.bytes));
            case "/mid" -> new Response(200, ResponseHeaders.of(), Body.Bytes.of(this.mid));
            case "/huge" -> new Response(200, ResponseHeaders.of(), Body.Bytes.of(this.huge));
            case "/latin" -> new Response(200, ResponseHeaders.of().add("x-v", "caf\u00e9 \u20ac"), Body.Bytes.of(this.bytes));
            case "/bighead" -> new Response(200, ResponseHeaders.of().add("x-big", "a".repeat(70_000)),
                    Body.Bytes.of(this.bytes));
            case "/bighead-file" -> {
                var r = files.handle(request.withTarget("/small.bin"));
                r.headers().add("x-big", "a".repeat(70_000));
                yield r;
            }
            case "/gone" -> {
                var r = files.handle(request.withTarget("/small.bin"));
                try {
                    Files.delete(this.root.resolve("small.bin"));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                yield r;
            }
            case "/stream" -> new Response(200, ResponseHeaders.of(), new Body.Stream(-1, new Body.Stream.Source() {
                private boolean done;

                @Override
                public ByteBuffer next() {
                    if (this.done) {
                        return null;
                    }
                    this.done = true;
                    return ByteBuffer.wrap("streamed".getBytes(StandardCharsets.US_ASCII));
                }

                @Override
                public void close() {
                }
            }));
            case "/echo" -> {
                var out = new ByteArrayOutputStream();
                var buf = new byte[300];
                try {
                    for (int n; (n = request.body().read(buf, 0, buf.length)) >= 0;) {
                        out.write(buf, 0, n);
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                yield new Response(200, ResponseHeaders.of(), Body.Bytes.of(out.toByteArray()));
            }
            default -> files.handle(request);
        };
    }

    @AfterEach
    void tearDown() {
        this.reaper.close();
    }

    /** Sends {@code request}, then reads until the server closes. */
    private byte[] exchange(BufferPool pool, String request) throws Exception {
        return exchange(pool, request.getBytes(StandardCharsets.ISO_8859_1));
    }

    private byte[] exchange(BufferPool pool, byte[] request) throws Exception {
        try (var server = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
                var client = SocketChannel.open(server.getLocalAddress())) {
            var accepted = server.accept();
            var thread = Thread.ofVirtual().start(
                    new Http1Connection(accepted, this.handler, ResponseHeaders::of, this.reaper).withPool(pool));
            var writer = Thread.ofVirtual().start(() -> {
                try {
                    client.write(ByteBuffer.wrap(request));
                } catch (IOException e) {
                    // the server answered and closed first (error paths)
                }
            });
            var out = new ByteArrayOutputStream();
            var buf = ByteBuffer.allocate(64 * 1024);
            try {
                while (client.read(buf) >= 0) {
                    out.write(buf.array(), 0, buf.position());
                    buf.clear();
                }
            } catch (IOException e) {
                // reset after an error response
            }
            client.close();
            writer.join(5000);
            thread.join(5000);
            return out.toByteArray();
        }
    }

    private static String masked(byte[] response) {
        return new String(response, StandardCharsets.ISO_8859_1).replaceAll("Date: [^\r]*\r\n", "Date: -\r\n");
    }

    /** Same bytes with the pool off and on; returns the pooled response. */
    private String same(String request) throws Exception {
        var off = masked(exchange(BufferPool.disabled(), request));
        var on = masked(exchange(debugPool(), request));
        assertEquals(off, on);
        return on;
    }

    private static String get(String target) {
        return "GET " + target + " HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n";
    }

    @Test
    void bodiesAndFilesAreIdenticalWithThePoolOn() throws Exception {
        assertTrue(same(get("/bytes")).endsWith(new String(this.bytes, StandardCharsets.ISO_8859_1)));
        assertTrue(same(get("/mid")).endsWith(new String(this.mid, StandardCharsets.ISO_8859_1)));
        assertTrue(same(get("/huge")).endsWith(new String(this.huge, StandardCharsets.ISO_8859_1)));
        assertTrue(same(get("/small.bin")).endsWith(new String(this.small, StandardCharsets.ISO_8859_1)));
        assertTrue(same(get("/large.bin")).endsWith(new String(this.large, StandardCharsets.ISO_8859_1)));
        assertTrue(same(get("/empty.bin")).contains("Content-Length: 0\r\n"));
        assertTrue(same(get("/latin")).contains("X-V: caf\u00e9 ?\r\n"));
        assertTrue(same(get("/bighead")).contains("X-Big: aaa"));
        assertTrue(same(get("/bighead-file")).endsWith(new String(this.small, StandardCharsets.ISO_8859_1)));
        assertTrue(same(get("/stream")).contains("streamed"));
        assertTrue(same("HEAD /stream HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 200 "));
        assertTrue(same(get("/missing")).startsWith("HTTP/1.1 404 "));
        assertTrue(same("HEAD /small.bin HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n").endsWith("\r\n\r\n"));
    }

    @Test
    void fileDeletedBeforeTheWriteAnswers404() throws Exception {
        var off = masked(exchange(BufferPool.disabled(), get("/gone")));
        Files.write(this.root.resolve("small.bin"), this.small);
        var on = masked(exchange(debugPool(), get("/gone")));
        assertEquals(off, on);
        assertTrue(on.startsWith("HTTP/1.1 404 "), on);
    }

    @Test
    void pipelinedAndLargeHeadsAndBodies() throws Exception {
        var longHead = "GET /bytes HTTP/1.1\r\nHost: h\r\nX-Pad: " + "p".repeat(3000) + "\r\n\r\n";
        var body = "x".repeat(5000);
        var pipelined = "GET /bytes HTTP/1.1\r\nHost: h\r\n\r\n" + longHead
                + "POST /echo HTTP/1.1\r\nHost: h\r\nContent-Length: " + body.length() + "\r\n\r\n" + body
                + "GET /small.bin HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
                + "GET /bytes HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\nabc"
                + get("/empty.bin");
        var on = same(pipelined);
        assertEquals(6, on.split("HTTP/1.1 200 ", -1).length - 1, on);
        assertTrue(on.contains(body));
    }

    @Test
    void errorsAndTheLingerDrainReleaseEverything() throws Exception {
        var longTarget = "GET /" + "a".repeat(9000) + " HTTP/1.1\r\nHost: h\r\n\r\n" + "z".repeat(20_000);
        var on = masked(exchange(debugPool(), longTarget));
        assertTrue(on.startsWith("HTTP/1.1 414 "), on);
        var longHeader = "GET / HTTP/1.1\r\nHost: h\r\nX: " + "b".repeat(9000) + "\r\n\r\n" + "z".repeat(20_000);
        on = masked(exchange(debugPool(), longHeader));
        assertTrue(on.startsWith("HTTP/1.1 431 "), on);
        on = masked(exchange(debugPool(), "GET / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n"));
        assertTrue(on.startsWith("HTTP/1.1 400 "), on);
        var chunk = "x".repeat(70_000);
        on = same("GET /bytes HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n"
                + Integer.toHexString(chunk.length()) + "\r\n" + chunk + "\r\n0\r\n\r\n");
        assertTrue(on.startsWith("HTTP/1.1 200 ") && on.contains("Connection: close"), on);
        on = same("GET /x HTTP/1.1\rbad\r\n\r\n");
        assertTrue(on.startsWith("HTTP/1.1 400 "), on);
    }

    @Test
    void abortedConnectionsReleaseTheirBuffers() throws Exception {
        var pool = debugPool();
        for (var partial : new String[] {"GET /bytes HTTP/1.1\r\nHo", "GET /bytes HTTP/1.1\r\nX: " + "c".repeat(4000),
                "POST /echo HTTP/1.1\r\nHost: h\r\nContent-Length: 100\r\n\r\nabc", ""}) {
            try (var server = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
                    var client = SocketChannel.open(server.getLocalAddress())) {
                var accepted = server.accept();
                var thread = Thread.ofVirtual().start(
                        new Http1Connection(accepted, this.handler, ResponseHeaders::of, this.reaper).withPool(pool));
                client.write(ByteBuffer.wrap(partial.getBytes(StandardCharsets.ISO_8859_1)));
                Thread.sleep(50);
                client.setOption(StandardSocketOptions.SO_LINGER, 0); // reset
                client.close();
                thread.join(5000);
                assertTrue(!thread.isAlive());
            }
        }
    }

    @Test
    void idleConnectionsHoldOnlyTheSmallBuffer() throws Exception {
        var pool = debugPool();
        try (var server = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
                var client = SocketChannel.open(server.getLocalAddress())) {
            var accepted = server.accept();
            var thread = Thread.ofVirtual().start(
                    new Http1Connection(accepted, this.handler, ResponseHeaders::of, this.reaper).withPool(pool));
            client.write(ByteBuffer.wrap(("GET /bytes HTTP/1.1\r\nHost: h\r\nX-Pad: " + "p".repeat(3000) + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1)));
            var buf = ByteBuffer.allocate(64 * 1024);
            int got = 0;
            while (got < this.bytes.length + 60) {
                got += client.read(buf);
            }
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (pool.budget(BufferPool.HTTP).usedBytes() > Http1Connection.IDLE_BUFFER && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(1, pool.trackedOutstanding()); // the idle buffer only
            client.close();
            thread.join(5000);
        }
    }

    @Test
    void pooledSinkWithoutSendfileCopiesThroughAPooledBuffer() throws Exception {
        var pool = debugPool();
        var file = this.root.resolve("large.bin");
        class Sink extends ByteArrayOutputStream implements sjws_nettask.http1.Http1Writer.PooledSink {
            private final boolean on;

            Sink(boolean on) {
                this.on = on;
            }

            @Override
            public sjws_nettask.core.buf.BufferBudget buffers() {
                return this.on ? pool.budget(BufferPool.HTTP) : null;
            }

            @Override
            public void write(ByteBuffer data) {
                var b = new byte[data.remaining()];
                data.get(b);
                writeBytes(b);
            }

            @Override
            public void write(ByteBuffer head, ByteBuffer body) {
                write(head);
                write(body);
            }
        }
        var files = new StaticFileHandler(this.root, false, FileCache.disabled());
        for (boolean on : new boolean[] {true, false}) {
            var sink = new Sink(on);
            var response = files.handle(new sjws_nettask.core.Request("GET", "/large.bin"));
            sjws_nettask.http1.Http1Writer.write(sink, response, true, ResponseHeaders.of());
            var text = sink.toByteArray();
            var tail = java.util.Arrays.copyOfRange(text, text.length - this.large.length, text.length);
            assertArrayEquals(Files.readAllBytes(file), tail);
        }
    }
}
