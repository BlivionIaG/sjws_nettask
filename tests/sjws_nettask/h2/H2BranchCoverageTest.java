package sjws_nettask.h2;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.timeout.IdleStateEvent;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HandlerRef;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.core.TestSource;

/** Branches the HTTP/2 package gate still misses: file budgets, zero window, deadlines, fallback. */
class H2BranchCoverageTest {

    @TempDir
    Path root;

    @Test
    void connectionFileBudgetIsTakenReleasedAndRefused() throws IOException {
        var large = file("big.bin", ResponseWriter.CHUNK + 1);
        var connection = new Semaphore(0);
        var refused = new ResponseWriterTest.Recorder();
        assertEquals(ResponseWriter.Outcome.REJECTED, writer(refused, connection, new Semaphore(4), Duration.ofMillis(20),
                Duration.ofMillis(50), Duration.ofMillis(200)).write(large));
        assertEquals(0, connection.availablePermits());

        var held = new Semaphore(1);
        var server = new Semaphore(0);
        var released = new ResponseWriterTest.Recorder();
        assertEquals(ResponseWriter.Outcome.REJECTED, writer(released, held, server, Duration.ofMillis(20),
                Duration.ofMillis(50), Duration.ofMillis(200)).write(large));
        assertEquals(1, held.availablePermits(), "the connection permit is returned when the server slot is busy");

        var both = new Semaphore(1);
        var files = new Semaphore(1);
        var ok = new ResponseWriterTest.Recorder();
        assertEquals(ResponseWriter.Outcome.COMPLETE, writer(ok, both, files, Duration.ofMillis(200),
                Duration.ofSeconds(1), Duration.ofSeconds(1)).write(large));
        assertEquals(1, both.availablePermits());
        assertEquals(1, files.availablePermits());
        assertEquals(ResponseWriter.CHUNK + 1, ok.bytes);
    }

    @Test
    void closedWindowStarvesAndAWritablePollResetsTheTimer() {
        var polls = new AtomicInteger();
        var r = new ResponseWriterTest.Recorder() {
            @Override
            public boolean writable() {
                return polls.incrementAndGet() == 1;
            }
        };
        r.stallAfter = 1;
        var response = new Response(200, ResponseHeaders.of(), Body.Bytes.of(new byte[ResponseWriter.CHUNK * 2]));
        assertEquals(ResponseWriter.Outcome.STARVED, writer(r, null, new Semaphore(4), Duration.ofMillis(20),
                Duration.ofNanos(1), Duration.ofSeconds(2)).write(response));
        assertEquals(ResponseWriter.Outcome.STARVED, r.aborted);
        assertTrue(polls.get() >= 3, "writable, then closed, then starved");
    }

    @Test
    void largeFileEdges() throws IOException {
        var inactive = new ResponseWriterTest.Recorder();
        inactive.active = false;
        assertEquals(ResponseWriter.Outcome.CLOSED, writer(inactive, new Semaphore(1), new Semaphore(1),
                Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMillis(200)).write(file("wide.bin", ResponseWriter.CHUNK + 8)));
        assertTrue(inactive.statuses.isEmpty());

        var gone = file("gone.bin", ResponseWriter.CHUNK + 8);
        Files.delete(this.root.resolve("gone.bin"));
        var swapped = new ResponseWriterTest.Recorder();
        assertEquals(ResponseWriter.Outcome.SWAPPED, writer(swapped, new Semaphore(1), new Semaphore(1),
                Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMillis(200)).write(gone));
        assertEquals(List.of(404), swapped.statuses);

        var shrunkPath = this.root.resolve("small.txt");
        Files.writeString(shrunkPath, "hello");
        var shrunk = new StaticFileHandler(this.root).handle(new Request("GET", "/small.txt"));
        Files.writeString(shrunkPath, "x");
        var shortFile = new ResponseWriterTest.Recorder();
        assertEquals(ResponseWriter.Outcome.SHRANK, writer(shortFile, null, new Semaphore(1), Duration.ofMillis(50),
                Duration.ofMillis(50), Duration.ofMillis(200)).write(shrunk));

        var stall = new ResponseWriterTest.Recorder();
        stall.stallAfter = 2;
        assertEquals(ResponseWriter.Outcome.STALLED, writer(stall, new Semaphore(1), new Semaphore(1),
                Duration.ofMillis(50), Duration.ofSeconds(5), Duration.ofMillis(120)).write(file("tail.bin", ResponseWriter.CHUNK + 1)));
    }

    @Test
    void headersOmitHopByHopAndContentLengthOnlyWhenThereIsABody() {
        var headers = ResponseHeaders.of().add("connection", "close").add("x-trace", "1");
        var h = Http2StreamHandler.headers(new Response(200, headers, Body.Bytes.of(new byte[] {1, 2})), "Tue");
        assertNull(h.get("connection"));
        assertEquals("1", h.get("x-trace").toString());
        assertEquals("2", h.get("content-length").toString());
        var none = Http2StreamHandler.headers(new Response(204, ResponseHeaders.of().add("x-a", "b"), Body.Bytes.of(new byte[0])), "Tue");
        assertNull(none.get("content-length"));
        var stream = Http2StreamHandler.headers(new Response(200, ResponseHeaders.of(), new Body.Stream(-1, new TestSource(true))), "Tue");
        assertNull(stream.get("content-length"));
    }

    @Test
    void streamErrorsCloseAndConnectionErrorsDoNot() {
        var shared = shared(holding(), new Semaphore(1), new Semaphore(1));
        var stream = new EmbeddedChannel(new Http2StreamHandler(shared));
        stream.pipeline().fireExceptionCaught(Http2Exception.streamError(1, Http2Error.PROTOCOL_ERROR, "s"));
        assertFalse(stream.isOpen());
        var plain = new EmbeddedChannel(new Http2StreamHandler(shared));
        plain.pipeline().fireExceptionCaught(new IOException("x"));
        assertFalse(plain.isOpen());
        var connection = new EmbeddedChannel(new Http2StreamHandler(shared));
        connection.pipeline().fireExceptionCaught(new Http2Exception(Http2Error.PROTOCOL_ERROR, "c"));
        assertTrue(connection.isOpen());
    }

    @Test
    void headDeadlineArmsDisarmsAndFires() throws Exception {
        var deadline = new Deadlines.HeadDeadline(Duration.ofMillis(30));
        var channel = new EmbeddedChannel(deadline.front(), deadline.back());
        assertFalse(deadline.armed());
        channel.writeInbound(Unpooled.EMPTY_BUFFER);
        assertFalse(deadline.armed());
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {1}));
        assertTrue(deadline.armed());
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {2}));
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        assertFalse(deadline.armed());

        var upgrade = new Deadlines.HeadDeadline(Duration.ofSeconds(5));
        var upgraded = new EmbeddedChannel(upgrade.front(), upgrade.back());
        upgraded.writeInbound(Unpooled.wrappedBuffer(new byte[] {1}));
        upgraded.pipeline().fireUserEventTriggered(priorKnowledge());
        assertFalse(upgraded.pipeline().toMap().containsValue(upgrade.front()));
        assertFalse(upgrade.armed());

        var missingFront = new Deadlines.HeadDeadline(Duration.ofSeconds(5));
        var backOnly = new EmbeddedChannel(missingFront.back());
        backOnly.pipeline().fireUserEventTriggered(priorKnowledge());
        assertFalse(backOnly.pipeline().toMap().containsValue(missingFront.back()));

        var fired = new Deadlines.HeadDeadline(Duration.ofMillis(20));
        var slow = new EmbeddedChannel(fired.front());
        slow.writeInbound(Unpooled.wrappedBuffer(new byte[] {9}));
        Thread.sleep(40);
        slow.runScheduledPendingTasks();
        assertFalse(slow.isOpen());

        var dropped = new Deadlines.HeadDeadline(Duration.ofSeconds(5));
        var closed = new EmbeddedChannel(dropped.front());
        closed.writeInbound(Unpooled.wrappedBuffer(new byte[] {3}));
        closed.close();
        assertFalse(dropped.armed());
    }

    @Test
    void streamIdleClosesAQuietConnectionAndIgnoresOtherReads() throws Exception {
        var codec = Http2FrameCodecBuilder.forServer().build();
        var idle = new Deadlines.StreamIdle(codec, Duration.ofNanos(1));
        idle.handlerRemoved(null);
        var channel = new EmbeddedChannel(idle);
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {1}));
        channel.writeInbound(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        Thread.sleep(2);
        idle.tick(channel.pipeline().context(idle));
        assertFalse(channel.isOpen());
        var quiet = new Deadlines.StreamIdle(Http2FrameCodecBuilder.forServer().build(), Duration.ofSeconds(30));
        var removed = new EmbeddedChannel(quiet);
        removed.pipeline().fireChannelInactive();
        removed.pipeline().remove(quiet);
    }

    @Test
    void idleAndQuietHandlers() {
        var idle = new EmbeddedChannel(Http2Server.IdleClose.INSTANCE);
        idle.pipeline().fireUserEventTriggered("not-idle");
        assertTrue(idle.isOpen());
        idle.pipeline().fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);
        assertFalse(idle.isOpen());

        var quiet = new EmbeddedChannel(Http2Server.Quiet.INSTANCE);
        quiet.pipeline().fireExceptionCaught(new Http2Exception(Http2Error.ENHANCE_YOUR_CALM, "codec"));
        assertTrue(quiet.isOpen());
        quiet.pipeline().fireExceptionCaught(new IOException("other"));
        assertFalse(quiet.isOpen());
    }

    @Test
    void http1FallbackRefusalsPipelineAndUpgrade() throws Exception {
        var held = new ArrayList<Runnable>();
        var handler = new Http1FallbackHandler(shared(holding(held), new Semaphore(4), new Semaphore(4)));
        var channel = new EmbeddedChannel(handler);
        var broken = request(HttpVersion.HTTP_1_1, "/");
        broken.setDecoderResult(DecoderResult.failure(new IOException("bad")));
        channel.writeInbound(broken);
        var twoHosts = request(HttpVersion.HTTP_1_1, "/");
        twoHosts.headers().add("Host", "a");
        twoHosts.headers().add("Host", "b");
        channel.writeInbound(twoHosts);
        channel.writeInbound(request(HttpVersion.HTTP_1_0, "/ok"));
        assertEquals(1, held.size(), "later requests wait behind the one already being served");

        var queued = new ArrayList<Runnable>();
        var piped = new EmbeddedChannel(new Http1FallbackHandler(shared(holding(queued), new Semaphore(4), new Semaphore(4))));
        piped.writeInbound(request(HttpVersion.HTTP_1_1, "/run"));
        for (int i = 0; i < Http1FallbackHandler.MAX_PIPELINED; i++) {
            piped.writeInbound(request(HttpVersion.HTTP_1_1, "/p" + i));
        }
        assertTrue(piped.isOpen());
        piped.writeInbound(request(HttpVersion.HTTP_1_1, "/overflow"));
        assertFalse(piped.isOpen());

        // #446 Codex P1: queued retains must be released when the channel closes before run().
        var leakHeld = new ArrayList<Runnable>();
        var leakHandler = new Http1FallbackHandler(shared(holding(leakHeld), new Semaphore(4), new Semaphore(4)));
        var leakCh = new EmbeddedChannel(leakHandler);
        leakCh.writeInbound(request(HttpVersion.HTTP_1_1, "/run"));
        var queuedMsg = request(HttpVersion.HTTP_1_1, "/queued");
        assertEquals(1, queuedMsg.refCnt());
        leakCh.writeInbound(queuedMsg);
        assertEquals(1, leakHeld.size());
        assertEquals(1, queuedMsg.refCnt(), "Task holds the retain after inbound release");
        assertTrue(leakCh.close().awaitUninterruptibly().isSuccess());
        assertEquals(0, queuedMsg.refCnt(), "channelInactive must release pending FullHttpRequest retains");

        var busy = new Http1FallbackHandler(shared(holding(), new Semaphore(0), new Semaphore(1)));
        var refused = new EmbeddedChannel(busy);
        refused.writeInbound(request(HttpVersion.HTTP_1_1, "/"));
        refused.runPendingTasks();
        assertFalse(refused.isOpen());

        var rejecting = new Http1FallbackHandler(shared(rejecting(), new Semaphore(1), new Semaphore(1)));
        var shut = new EmbeddedChannel(rejecting);
        shut.writeInbound(request(HttpVersion.HTTP_1_1, "/"));
        assertFalse(shut.isOpen());

        var upgrade = new Http1FallbackHandler(shared(holding(), new Semaphore(1), new Semaphore(1)));
        var pipe = new EmbeddedChannel();
        pipe.pipeline().addLast("expect-continue", new ChannelInboundHandlerAdapter() {});
        pipe.pipeline().addLast("h1-aggregator", new ChannelInboundHandlerAdapter() {});
        pipe.pipeline().addLast(upgrade);
        pipe.pipeline().fireUserEventTriggered(priorKnowledge());
        assertNull(pipe.pipeline().get("expect-continue"));
        assertNull(pipe.pipeline().get("h1-aggregator"));

        var bare = new EmbeddedChannel(new Http1FallbackHandler(shared(holding(), new Semaphore(1), new Semaphore(1))));
        bare.pipeline().fireExceptionCaught(new IOException("late"));
        assertFalse(bare.isOpen());
        var upgraded = new EmbeddedChannel(new Http1FallbackHandler(shared(holding(), new Semaphore(1), new Semaphore(1))));
        upgraded.pipeline().fireUserEventTriggered(priorKnowledge());
        assertNull(upgraded.pipeline().get(Http1FallbackHandler.class));
    }

    private Response file(String name, int bytes) throws IOException {
        Files.write(this.root.resolve(name), new byte[bytes]);
        return new StaticFileHandler(this.root).handle(new Request("GET", "/" + name));
    }

    private static ResponseWriter writer(ResponseWriterTest.Recorder sink, Semaphore connection, Semaphore files,
            Duration fileWait, Duration zeroWindow, Duration writeTimeout) {
        return new ResponseWriter(sink, writeTimeout, connection, files, fileWait, zeroWindow);
    }

    private static Http2Server.Shared shared(ExecutorService executor, Semaphore inflight, Semaphore files) {
        Handler startup = request -> new Response(200, ResponseHeaders.of().add("connection", "close").add("x-trace", "1"),
                Body.Bytes.of(new byte[] {'o'}));
        return new Http2Server.Shared(startup, executor, inflight, files, Duration.ofMillis(200), new LongAdder(),
                Http2Server.Limits.DEFAULT, new java.util.concurrent.atomic.AtomicReference<>(),
                new Http2Server.Generation(startup, false));
    }

    private static ExecutorService holding(List<Runnable> held) {
        return new AbstractExecutorService() {
            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                held.add(command);
            }
        };
    }

    private static ExecutorService holding() {
        return holding(new ArrayList<>());
    }

    private static ExecutorService rejecting() {
        return new AbstractExecutorService() {
            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                throw new java.util.concurrent.RejectedExecutionException("down");
            }
        };
    }

    private static DefaultFullHttpRequest request(HttpVersion version, String path) {
        var request = new DefaultFullHttpRequest(version, HttpMethod.GET, path);
        if (version == HttpVersion.HTTP_1_1) {
            request.headers().add("Host", "h");
        }
        return request;
    }

    private static Object priorKnowledge() throws Exception {
        Class<?> type = Class.forName("io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler$PriorKnowledgeUpgradeEvent");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }
}
