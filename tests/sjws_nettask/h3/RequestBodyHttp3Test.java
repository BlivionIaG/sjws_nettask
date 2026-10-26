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
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.handler.codec.quic.QuicChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.H3Transports;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.swarm.SwarmMountForTests;
import sjws_nettask.swarm.wire.Frame;
import sjws_nettask.swarm.wire.StreamChunk;
import sjws_nettask.tls.TlsMaterial;

/**
 * #281: HTTP/3 request bodies reach the handler (through the filter chain) as a pull-style
 * {@link sjws_nettask.core.RequestBody}, declared or unknown length, larger than the stream's flow-control window; a
 * handler that stops reading answers without the transport buffering the rest. End to end, the swarm's body-carrying
 * verbs ({@code :stream}, {@code :chunk}) work over HTTP/3, including the credit-on LAST limit (65,536 B accepted,
 * 65,537 B is 413).
 */
@Tag("h3")
class RequestBodyHttp3Test {

    /** Largest body the echo handler reads; more is answered 413 without reading the rest. */
    private static final int ECHO_CAP = 4 << 20;

    @TempDir
    Path root;

    /** Reads at most {@code cap} bytes; null when the body is longer. */
    static byte[] readCapped(Request request, int cap) throws IOException {
        var out = new ByteArrayOutputStream();
        var buf = new byte[8192];
        int n;
        while ((n = request.body().read(buf, 0, buf.length)) >= 0) {
            out.write(buf, 0, n);
            if (out.size() > cap) {
                return null;
            }
        }
        return out.toByteArray();
    }

    static Response echo(Request request) {
        if (request.body().length() > ECHO_CAP) {
            return StaticFileHandler.error(413, false);
        }
        byte[] body;
        try {
            body = readCapped(request, ECHO_CAP);
        } catch (IOException e) {
            return StaticFileHandler.error(400, false);
        }
        if (body == null) {
            return StaticFileHandler.error(413, false);
        }
        var h = ResponseHeaders.of();
        h.set("x-declared", Long.toString(request.body().length()));
        h.set("x-received", Integer.toString(body.length));
        h.set("x-sha256", sha256(body));
        return new Response(200, h, new Body.Bytes(ByteBuffer.wrap(body)));
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private Http3Server echoServer() throws IOException {
        return echoServer(ServerConfig.builder(this.root).h3Enabled(true).h3Host("127.0.0.1").h3Port(0).build(),
                (request, next) -> next.proceed(request));
    }

    /** {@code /echo} echoes; everything else goes to {@code other}, then the files. */
    private Http3Server echoServer(ServerConfig config, sjws_nettask.core.RequestFilter other) throws IOException {
        var files = new StaticFileHandler(this.root);
        var chain = RequestFilters.create().add((request, next) -> {
            var response = next.proceed(request);
            response.headers().add("x-filter", "h3");
            return response;
        }).add((request, next) -> request.target().startsWith("/echo") ? echo(request) : next.proceed(request))
                .add(other).build(files);
        var server = (Http3Server) H3Transports.defaultFactory().create(config, files, chain);
        server.start();
        return server;
    }

    static H3TestClient.H3Response send(QuicChannel quic, String method, String path, byte[] body, boolean declare)
            throws Exception {
        Map<String, String> headers = declare ? Map.of("content-length", Integer.toString(body.length)) : Map.of();
        return H3TestClient.request(quic, method, path, headers, body, null).get(60, TimeUnit.SECONDS);
    }

    @Test
    void bodiesReachTheHandlerThroughTheChain() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        var server = echoServer();
        try (var client = new H3TestClient(server.tls().certificate())) {
            var quic = client.connect(server.localAddress());
            var random = new Random(281);
            // small, declared
            var small = "hello h3".getBytes(StandardCharsets.UTF_8);
            var r = send(quic, "POST", "/echo", small, true);
            assertEquals(200, r.status(), () -> "reset=" + r.reset() + " error=" + r.error());
            assertEquals("h3", r.header("x-filter"), "the chain ran");
            assertEquals("8", r.header("x-declared"));
            assertEquals("hello h3", new String(r.body(), StandardCharsets.UTF_8));
            // large (many times the 16 KiB stream window), declared
            var large = new byte[3 << 20];
            random.nextBytes(large);
            var l = send(quic, "PUT", "/echo", large, true);
            assertEquals(200, l.status(), () -> "reset=" + l.reset() + " error=" + l.error());
            assertEquals(Integer.toString(large.length), l.header("x-received"));
            assertEquals(sha256(large), l.header("x-sha256"));
            // unknown length (no content-length): the handler sees -1 and reads to the end
            var unknown = new byte[300_000];
            random.nextBytes(unknown);
            var u = send(quic, "POST", "/echo", unknown, false);
            assertEquals(200, u.status(), () -> "reset=" + u.reset() + " error=" + u.error());
            assertEquals("-1", u.header("x-declared"));
            assertEquals(sha256(unknown), u.header("x-sha256"));
            // no content and no content-length (e.g. a bare POST): length 0, as on HTTP/1.1, not "unknown"
            var bare = H3TestClient.request(quic, "POST", "/echo", Map.of()).get(30, TimeUnit.SECONDS);
            assertEquals(200, bare.status());
            assertEquals("0", bare.header("x-declared"));
            assertEquals("0", bare.header("x-received"));
            // empty, declared 0
            var e = send(quic, "POST", "/echo", new byte[0], true);
            assertEquals(200, e.status());
            assertEquals("0", e.header("x-received"));
            // GET still serves files on the same connection
            Files.writeString(this.root.resolve("f.txt"), "file");
            assertEquals("file", new String(H3TestClient.get(quic, "/f.txt").body(), StandardCharsets.UTF_8));
        } finally {
            server.close();
        }
    }

    @Test
    void anOversizedBodyIsRefusedAndTheConnectionStaysUsable() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        var server = echoServer();
        try (var client = new H3TestClient(server.tls().certificate())) {
            var quic = client.connect(server.localAddress());
            var big = new byte[ECHO_CAP + 4096];
            // declared too large: refused before a byte is read
            var declared = send(quic, "POST", "/echo", big, true);
            assertEquals(413, declared.status(), () -> "reset=" + declared.reset() + " error=" + declared.error());
            // unknown length: the handler stops reading at its cap and answers
            var unknown = send(quic, "POST", "/echo", big, false);
            assertEquals(413, unknown.status(), () -> "reset=" + unknown.reset() + " error=" + unknown.error());
            // the connection still carries requests with bodies
            var ok = send(quic, "POST", "/echo", "after".getBytes(StandardCharsets.UTF_8), true);
            assertEquals(200, ok.status(), () -> "reset=" + ok.reset() + " error=" + ok.error());
            assertEquals("after", new String(ok.body(), StandardCharsets.UTF_8));
        } finally {
            server.close();
        }
    }

    /** A handler that doesn't read yet holds the client to the stream window: the server buffers a bounded amount. */
    @Test
    void anUnreadBodyIsHeldByFlowControlNotBuffered() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        var most = new java.util.concurrent.atomic.AtomicInteger();
        var server = echoServer(ServerConfig.builder(this.root).h3Enabled(true).h3Host("127.0.0.1").h3Port(0).build(),
                (request, next) -> {
                    if (!request.target().equals("/slow")) {
                        return next.proceed(request);
                    }
                    var body = (Http3RequestBody) request.body();
                    try {
                        for (int i = 0; i < 20; i++) { // the client keeps sending meanwhile
                            Thread.sleep(25);
                            most.accumulateAndGet(body.buffered(), Math::max);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return echo(request);
                });
        try (var client = new H3TestClient(server.tls().certificate())) {
            var quic = client.connect(server.localAddress());
            var data = new byte[4 << 20];
            new Random(7).nextBytes(data);
            var r = send(quic, "POST", "/slow", data, true);
            assertEquals(200, r.status(), () -> "reset=" + r.reset() + " error=" + r.error());
            assertEquals(sha256(data), r.header("x-sha256"), "every byte arrived once reading resumed");
            assertTrue(most.get() > 0, "the client did send while the handler waited");
            assertTrue(most.get() <= 2 * Http3RequestBody.HIGH + Http3Server.MAX_STREAM_DATA,
                    "at most about HIGH + one stream window is buffered, was " + most.get());
        } finally {
            server.close();
        }
    }

    /** A body that stops arriving, or a stream the client resets, ends the handler's read with an IOException. */
    @Test
    void aStalledOrResetBodyFailsTheRead() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        var failures = new java.util.concurrent.LinkedBlockingQueue<String>();
        var config = ServerConfig.builder(this.root).h3Enabled(true).h3Host("127.0.0.1").h3Port(0)
                .writeTimeout(java.time.Duration.ofMillis(400)).build();
        var server = echoServer(config, (request, next) -> {
            if (!request.target().startsWith("/partial")) {
                return next.proceed(request);
            }
            try {
                readCapped(request, ECHO_CAP);
                failures.add("no failure");
            } catch (IOException e) {
                failures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return StaticFileHandler.error(400, false);
        });
        try (var client = new H3TestClient(server.tls().certificate())) {
            var quic = client.connect(server.localAddress());
            // stalled: 1000 of 5000 declared bytes, then nothing (no FIN)
            var stalled = openPartial(quic, "/partial-stall", 1000);
            var stall = failures.poll(10, TimeUnit.SECONDS);
            assertNotNull(stall);
            assertTrue(stall.contains("no request body bytes for 400 ms"), stall);
            stalled.close();
            // reset by the client mid-body
            var reset = openPartial(quic, "/partial-reset", 1000);
            Thread.sleep(100);
            reset.shutdown(0x10c); // H3_REQUEST_CANCELLED, both directions
            var cut = failures.poll(10, TimeUnit.SECONDS);
            assertNotNull(cut);
            assertTrue(cut.startsWith("IOException") || cut.startsWith("InterruptedIOException"), cut);
            // the connection carries the next request
            var ok = send(quic, "POST", "/echo", "still".getBytes(StandardCharsets.UTF_8), true);
            assertEquals(200, ok.status(), () -> "reset=" + ok.reset() + " error=" + ok.error());
        } finally {
            server.close();
        }
    }

    /** Opens a request declaring 5000 body bytes and sends only {@code sent} of them, without FIN. */
    private static io.netty.handler.codec.quic.QuicStreamChannel openPartial(QuicChannel quic, String path, int sent)
            throws Exception {
        var stream = io.netty.handler.codec.http3.Http3.newRequestStream(quic,
                new io.netty.handler.codec.http3.Http3RequestStreamInboundHandler() {
                    @Override
                    protected void channelRead(io.netty.channel.ChannelHandlerContext ctx,
                            io.netty.handler.codec.http3.Http3HeadersFrame frame) {
                        io.netty.util.ReferenceCountUtil.release(frame);
                    }

                    @Override
                    protected void channelRead(io.netty.channel.ChannelHandlerContext ctx,
                            io.netty.handler.codec.http3.Http3DataFrame frame) {
                        io.netty.util.ReferenceCountUtil.release(frame);
                    }

                    @Override
                    protected void channelInputClosed(io.netty.channel.ChannelHandlerContext ctx) {
                        ctx.close();
                    }

                    @Override
                    public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
                        ctx.close();
                    }
                }).get(10, TimeUnit.SECONDS);
        var head = new io.netty.handler.codec.http3.DefaultHttp3HeadersFrame();
        head.headers().method("POST").path(path).scheme("https").authority("localhost").add("content-length", "5000");
        stream.write(head);
        stream.writeAndFlush(new io.netty.handler.codec.http3.DefaultHttp3DataFrame(
                io.netty.buffer.Unpooled.wrappedBuffer(new byte[sent]))).sync();
        return stream;
    }

    /**
     * A response whose field section exceeds the client's SETTINGS_MAX_FIELD_SECTION_SIZE (Netty's client default:
     * 8 KiB) is replaced by a 500 on its stream instead of killing the client's connection; a client that advertises
     * more gets it whole.
     */
    @Test
    void aResponseHeaderSectionOverThePeersLimitIsA500NotAConnectionError() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        var big = "v".repeat(12_000);
        var server = echoServer(ServerConfig.builder(this.root).h3Enabled(true).h3Host("127.0.0.1").h3Port(0).build(),
                (request, next) -> {
                    if (!request.target().startsWith("/big-head")) {
                        return next.proceed(request);
                    }
                    var h = ResponseHeaders.of();
                    h.set("x-big", big);
                    return new Response(200, h, new Body.Bytes(ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8))));
                });
        Files.writeString(this.root.resolve("f.txt"), "file");
        try (var client = new H3TestClient(server.tls().certificate())) {
            var small = client.connect(server.localAddress()); // Netty's default: 8 KiB
            assertEquals(200, H3TestClient.get(small, "/f.txt").status()); // its SETTINGS have arrived by now
            var refused = H3TestClient.get(small, "/big-head");
            assertEquals(500, refused.status(), () -> "reset=" + refused.reset() + " error=" + refused.error());
            assertTrue(small.isActive(), "the client's connection survives");
            assertEquals("file", new String(H3TestClient.get(small, "/f.txt").body(), StandardCharsets.UTF_8));
            var large = client.connect(server.localAddress(), 64 * 1024);
            assertEquals(200, H3TestClient.get(large, "/f.txt").status());
            var whole = H3TestClient.get(large, "/big-head");
            assertEquals(200, whole.status(), () -> "reset=" + whole.reset() + " error=" + whole.error());
            assertEquals(big, whole.header("x-big"));
        } finally {
            server.close();
        }
    }

    /** The #281 acceptance test: submit a stream task, claim it, {@code :chunk} + LAST, read the session to its end. */
    @Test
    void swarmStreamOverHttp3WithCredit() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        var tls = TlsMaterial.selfSigned("localhost");
        var cert = Files.writeString(this.root.resolve("cert.pem"),
                SjwsServerHttp3Test.pem("CERTIFICATE", tls.certificate().getEncoded()));
        var key = Files.writeString(this.root.resolve("key.pem"),
                SjwsServerHttp3Test.pem("PRIVATE KEY", tls.key().getEncoded()));
        var config = ServerConfig.builder(this.root).port(0).h3Enabled(true).h3Port(0).h3Host("127.0.0.1")
                .tlsCert(cert).tlsKey(key).build();
        try (var mount = SwarmMountForTests.streaming(this.root.resolve("swarm"), 4, 4, true);
                var server = SjwsServer.start(config, RequestFilters.create(), null, List.of(mount.contributor()));
                var client = new H3TestClient(tls.certificate())) {
            assertEquals(null, server.h3Failure());
            var quic = client.connect(new InetSocketAddress("127.0.0.1", server.udpPort()), 64 * 1024);
            // :stream (body: one TASK frame)
            var submitted = swarm(quic, "POST", "/swarm/v1/tasks/gen:stream",
                    new Frame(Frame.Type.TASK, 0, "prompt".getBytes(StandardCharsets.UTF_8)).encode());
            assertEquals(201, submitted.status(), () -> text(submitted));
            var session = submitted.header("location");
            assertNotNull(session);
            // :claim (no body)
            var claimed = swarm(quic, "POST", "/swarm/v1/tasks/gen:claim?visibilityMs=60000", new byte[0]);
            assertEquals(200, claimed.status(), () -> text(claimed));
            var lease = leaseId(claimed.body());
            var entry = claimed.header("sjws-lease-stream");
            assertTrue(entry != null && entry.length() > 33 && entry.charAt(32) == '/', String.valueOf(entry));
            var id = ByteBuffer.wrap(HexFormat.of().parseHex(entry, 0, 32));
            long hi = id.getLong();
            long lo = id.getLong();
            // :chunk FIRST
            var first = chunk(quic, lease, new StreamChunk(hi, lo, 0, StreamChunk.FIRST,
                    "hel".getBytes(StandardCharsets.UTF_8)));
            assertTrue(first.status() >= 200 && first.status() < 300, () -> text(first));
            // credit on: a LAST over 64 KiB is 413 (the lease stays held), exactly 64 KiB is accepted
            var tooLarge = new byte[65_537];
            java.util.Arrays.fill(tooLarge, (byte) 'x');
            var refused = chunk(quic, lease, new StreamChunk(hi, lo, 1, Frame.LAST, tooLarge));
            assertEquals(413, refused.status(), () -> text(refused));
            var last = chunk(quic, lease, new StreamChunk(hi, lo, 1, Frame.LAST, java.util.Arrays.copyOf(tooLarge, 65_536)));
            assertEquals(204, last.status(), () -> text(last));
            // read the session to its end over HTTP/3
            var read = swarm(quic, "GET", session, null);
            assertEquals(200, read.status(), () -> text(read));
            assertFalse(read.reset(), "the session ends cleanly");
            var sse = text(read);
            assertTrue(sse.contains("id: 1.0\nevent: chunk\ndata: hel\n"), sse.substring(0, Math.min(200, sse.length())));
            assertTrue(sse.contains("data: " + "x".repeat(65_536) + "\n"), "the 64 KiB LAST arrived whole");
            assertTrue(sse.endsWith("event: end\ndata: ok\n\n"), sse.substring(Math.max(0, sse.length() - 200)));
        }
    }

    private static H3TestClient.H3Response chunk(QuicChannel quic, String lease, StreamChunk c) throws Exception {
        return swarm(quic, "POST", "/swarm/v1/leases/" + lease + ":chunk", c.toFrame().encode());
    }

    private static H3TestClient.H3Response swarm(QuicChannel quic, String method, String path, byte[] body)
            throws Exception {
        var headers = new HashMap<String, String>();
        headers.put("x-agent", "a");
        if (body != null) {
            headers.put("content-type", "application/vnd.sjws.swarm.v1");
            headers.put("content-length", Integer.toString(body.length));
        }
        return H3TestClient.request(quic, method, path, headers, body, null).get(30, TimeUnit.SECONDS);
    }

    private static String text(H3TestClient.H3Response r) {
        return r.status() + " reset=" + r.reset() + " error=" + r.error() + " "
                + new String(r.body(), StandardCharsets.UTF_8);
    }

    /** The first LEASE frame's lease id ({@code [idLen:u16][leaseId][deadline:i64][body]}). */
    private static String leaseId(byte[] claim) {
        var f = Frame.decode(ByteBuffer.wrap(claim));
        assertNotNull(f, "a LEASE frame");
        var p = ByteBuffer.wrap(f.payload());
        var id = new byte[p.getShort()];
        p.get(id);
        return new String(id, StandardCharsets.UTF_8);
    }
}
