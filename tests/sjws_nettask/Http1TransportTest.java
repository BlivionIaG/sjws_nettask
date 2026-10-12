package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Random;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The S4 channel transport: deadlines enforced by {@link ConnectionReaper} (head, idle,
 * write/minimum progress), sendfile for large files with shrink detection, pipelining and
 * timer deregistration.
 */
class Http1TransportTest {

    private static final int LARGE = 50 * 1024 * 1024;

    @TempDir
    Path root;

    private ServerSocketChannel listener;
    private ClientManager manager;
    private Thread acceptThread;
    private int port;

    private void start(Duration idle, Duration head, Duration write) throws Exception {
        start(ServerConfig.defaults(root.toString()).toBuilder().idleTimeout(idle).headerTimeout(head)
                .writeTimeout(write).build());
    }

    private void start(ServerConfig config) throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        listener = ServerSocketChannel.open().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
        port = listener.socket().getLocalPort();
        manager = new ClientManager(listener, config);
        acceptThread = new Thread(manager, "accept-loop");
        acceptThread.setDaemon(true);
        acceptThread.start();
        waitFor(() -> manager.reaper() != null, 2000, "reaper started");
    }

    @AfterEach
    void stop() throws Exception {
        if (listener != null) {
            listener.close();
            acceptThread.join(2000);
        }
    }

    private Socket connect() throws IOException {
        var s = new Socket(InetAddress.getLoopbackAddress(), port);
        s.setSoTimeout(15_000);
        return s;
    }

    private static void waitFor(BooleanSupplier condition, long ms, String what) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    private Path largeFile() throws IOException {
        var file = root.resolve("large.bin");
        var data = new byte[LARGE];
        new Random(42).nextBytes(data);
        Files.write(file, data);
        return file;
    }

    /** Reads a response head (up to the empty line) as text. */
    private static String readHead(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("EOF in head: " + head);
            }
            head.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1
                    : b == '\r' ? 1 : 0;
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    private static long cpuNanos() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime();
    }

    @Test
    void slowlorisGets408AtTheHeadDeadline() throws Exception {
        start(Duration.ofSeconds(10), Duration.ofMillis(500), Duration.ofSeconds(5));
        try (var s = connect()) {
            long t0 = System.nanoTime();
            var out = s.getOutputStream();
            out.write("GET /hello.txt HTTP/1.1\r\nHo".getBytes(StandardCharsets.ISO_8859_1));
            out.flush(); // ... and never finishes the head
            var r = HttpTestSupport.readResponse(s.getInputStream());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(408, r.statusCode());
            assertEquals(-1, s.getInputStream().read(), "closed after the 408");
            assertTrue(ms >= 450 && ms < 3000, "took " + ms + " ms");
        }
        assertEquals(1, manager.reaper().headExpirations());
        waitFor(() -> manager.reaper().tracked() == 0 && manager.activeConnections() == 0, 2000, "deregistration");
    }

    @Test
    void tricklingHeadIsStillCutAtTheAbsoluteDeadline() throws Exception {
        start(Duration.ofSeconds(10), Duration.ofMillis(600), Duration.ofSeconds(5));
        try (var s = connect()) {
            long t0 = System.nanoTime();
            var out = s.getOutputStream();
            try {
                for (char c : "GET /hello.txt HTTP/1.1\r\nX-Pad: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".toCharArray()) {
                    out.write(c); // one byte every 50 ms: always progressing, never complete
                    out.flush();
                    Thread.sleep(50);
                }
            } catch (IOException expected) {
                // the server closed while we were still trickling
            }
            var r = HttpTestSupport.readResponse(s.getInputStream());
            assertEquals(408, r.statusCode());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 3000, "took " + ms + " ms");
        }
    }

    @Test
    void idleKeepAliveConnectionIsClosed() throws Exception {
        start(Duration.ofMillis(400), Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (var s = connect()) {
            var in = s.getInputStream();
            HttpTestSupport.write(s.getOutputStream(), "GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n");
            assertEquals("hello", HttpTestSupport.readResponse(in).bodyText());
            long t0 = System.nanoTime();
            assertEquals(-1, in.read(), "closed by the idle timeout");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms >= 300 && ms < 3000, "idle close after " + ms + " ms");
        }
        try (var s = connect()) { // a connection that never sends anything is idle too
            assertEquals(-1, s.getInputStream().read());
        }
        assertEquals(2, manager.reaper().idleExpirations());
        assertEquals(0, manager.reaper().headExpirations());
        waitFor(() -> manager.reaper().tracked() == 0, 2000, "deregistration");
    }

    @Test
    void stalledReaderIsClosedByTheWriteDeadline() throws Exception {
        largeFile();
        start(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofMillis(500));
        try (var s = connect()) {
            s.setReceiveBufferSize(64 * 1024);
            HttpTestSupport.write(s.getOutputStream(), "GET /large.bin HTTP/1.1\r\nHost: h\r\n\r\n");
            // read nothing: socket buffers fill, sendfile blocks, no progress for the write timeout
            waitFor(() -> manager.reaper().writeExpirations() == 1, 5000, "write deadline");
            waitFor(() -> manager.activeConnections() == 0, 2000, "connection closed");
            long got = 0;
            var in = s.getInputStream();
            var buf = new byte[64 * 1024];
            try {
                for (int n; (n = in.read(buf)) > 0;) {
                    got += n;
                }
            } catch (IOException reset) {
                // closed with unsent data: a reset is fine
            }
            assertTrue(got < LARGE, "the download was cut: " + got);
        }
        assertEquals(0, manager.reaper().tracked());
    }

    @Test
    void slowButProgressingDownloadSurvives() throws Exception {
        var file = largeFile();
        start(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(1));
        try (var s = connect()) {
            s.setReceiveBufferSize(64 * 1024);
            var in = s.getInputStream();
            HttpTestSupport.write(s.getOutputStream(), "GET /large.bin HTTP/1.1\r\nHost: h\r\n\r\n");
            assertTrue(readHead(in).contains("Content-Length: " + LARGE));
            long t0 = System.nanoTime();
            var body = new byte[LARGE];
            int got = 0;
            int sinceSleep = 0;
            while (got < LARGE) {
                int n = in.read(body, got, Math.min(64 * 1024, LARGE - got));
                assertTrue(n > 0, "EOF after " + got);
                got += n;
                sinceSleep += n;
                if (sinceSleep >= 3 * 1024 * 1024) { // pause 400 ms every 3 MB (well under the 1 s write timeout)
                    sinceSleep = 0;
                    Thread.sleep(400);
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms > 3000, "the download outlived the write timeout several times: " + ms + " ms");
            assertArrayEquals(Files.readAllBytes(file), body);
            // still usable afterwards
            HttpTestSupport.write(s.getOutputStream(), "GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n");
            assertEquals("hello", HttpTestSupport.readResponse(in).bodyText());
        }
        assertEquals(0, manager.reaper().writeExpirations());
    }

    @Test
    void largeFileIsSentWithSendfileExactly() throws Exception {
        var file = largeFile();
        start(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (var s = connect()) {
            var in = s.getInputStream();
            HttpTestSupport.write(s.getOutputStream(), "GET /large.bin HTTP/1.1\r\nHost: h\r\n\r\n"
                    + "HEAD /large.bin HTTP/1.1\r\nHost: h\r\n\r\n");
            assertTrue(readHead(in).contains("Content-Length: " + LARGE));
            assertArrayEquals(Files.readAllBytes(file), in.readNBytes(LARGE));
            var head = HttpTestSupport.readResponse(in, true);
            assertEquals(200, head.statusCode());
            assertEquals(0, head.body().length);
        }
    }

    @Test
    void uncachedMediumFileGoesThroughTheSinkToo() throws Exception {
        var data = new byte[200 * 1024]; // > SMALL_FILE_MAX, not cached (cache disabled)
        new Random(7).nextBytes(data);
        Files.write(root.resolve("medium.bin"), data);
        start(ServerConfig.defaults(root.toString()).toBuilder().cacheBytes(0).build());
        try (var s = connect()) {
            var in = s.getInputStream();
            HttpTestSupport.write(s.getOutputStream(), "GET /medium.bin HTTP/1.1\r\nHost: h\r\n\r\n");
            assertTrue(readHead(in).contains("Content-Length: " + data.length));
            assertArrayEquals(data, in.readNBytes(data.length));
        }
    }

    @Test
    void fileShrinkingDuringSendfileClosesWithoutSpinning() throws Exception {
        var file = largeFile();
        start(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(10));
        try (var s = connect()) {
            s.setReceiveBufferSize(64 * 1024);
            var in = s.getInputStream();
            HttpTestSupport.write(s.getOutputStream(), "GET /large.bin HTTP/1.1\r\nHost: h\r\n\r\n");
            readHead(in);
            in.readNBytes(1024 * 1024); // the transfer is under way, then blocks on our full window
            try (var w = FileChannel.open(file, StandardOpenOption.WRITE)) {
                w.truncate(1024 * 1024);
            }
            long cpu0 = cpuNanos();
            long t0 = System.nanoTime();
            long got = 1024 * 1024;
            var buf = new byte[64 * 1024];
            try {
                for (int n; (n = in.read(buf)) > 0;) {
                    got += n;
                }
            } catch (IOException reset) {
                // fine: the response could not be completed
            }
            assertTrue(got < LARGE, "short response: " + got);
            waitFor(() -> manager.activeConnections() == 0, 3000, "connection closed");
            Thread.sleep(500); // a spinning sender would burn a core here
            long wall = System.nanoTime() - t0;
            long cpu = cpuNanos() - cpu0;
            assertTrue(cpu < wall / 2, "no spin: cpu " + cpu / 1_000_000 + " ms over " + wall / 1_000_000 + " ms");
        }
        assertEquals(0, manager.reaper().tracked());
        assertEquals(0, manager.reaper().writeExpirations(), "ended by shrink detection, not by the deadline");
    }

    @Test
    void interruptedLargeDownloadResumesByteExactFromAnOffset() throws Exception {
        var file = largeFile();
        // an established file: a version written this very second has only weak validators, and
        // If-Range needs a strong one (a resume would then restart with a 200, also correct)
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2020-01-01T00:00:00Z")));
        var all = Files.readAllBytes(file);
        start(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(5));
        int cut = 12_345_678; // not chunk-aligned
        var got = new byte[LARGE];
        try (var s = connect()) { // first attempt: dropped after `cut` bytes
            var in = s.getInputStream();
            HttpTestSupport.write(s.getOutputStream(), "GET /large.bin HTTP/1.1\r\nHost: h\r\n\r\n");
            var head = readHead(in);
            var etag = head.lines().filter(l -> l.startsWith("ETag: ")).findFirst().orElseThrow().substring(6);
            assertFalse(etag.startsWith("W/"), etag);
            assertTrue(head.contains("Accept-Ranges: bytes\r\n"), head);
            in.readNBytes(got, 0, cut);
            s.close();
            try (var resume = connect()) { // resume with Range + If-Range (what browsers and curl -C do)
                var rin = resume.getInputStream();
                HttpTestSupport.write(resume.getOutputStream(), "GET /large.bin HTTP/1.1\r\nHost: h\r\nRange: bytes="
                        + cut + "-\r\nIf-Range: " + etag + "\r\n\r\n");
                var rhead = readHead(rin);
                assertTrue(rhead.startsWith("HTTP/1.1 206 Partial Content\r\n"), rhead);
                assertTrue(rhead.contains("Content-Range: bytes " + cut + "-" + (LARGE - 1) + "/" + LARGE + "\r\n"), rhead);
                assertTrue(rhead.contains("Content-Length: " + (LARGE - cut) + "\r\n"), rhead);
                assertEquals(LARGE - cut, rin.readNBytes(got, cut, LARGE - cut));
                // keep-alive still works after a ranged sendfile
                HttpTestSupport.write(resume.getOutputStream(), "GET /hello.txt HTTP/1.1\r\nHost: h\r\nRange: bytes=1-3\r\n\r\n");
                assertEquals("ell", HttpTestSupport.readResponse(rin).bodyText());
            }
        }
        assertArrayEquals(all, got);
    }

    @Test
    void fileShrinkingDuringARangedSendfileClosesWithoutSpinning() throws Exception {
        var file = largeFile();
        start(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(10));
        int from = 20 * 1024 * 1024;
        try (var s = connect()) {
            s.setReceiveBufferSize(64 * 1024);
            var in = s.getInputStream();
            HttpTestSupport.write(s.getOutputStream(), "GET /large.bin HTTP/1.1\r\nHost: h\r\nRange: bytes=" + from
                    + "-\r\n\r\n");
            assertTrue(readHead(in).startsWith("HTTP/1.1 206 "));
            var first = in.readNBytes(1024 * 1024);
            var all = Files.readAllBytes(file);
            assertArrayEquals(java.util.Arrays.copyOfRange(all, from, from + first.length), first, "starts at the offset");
            try (var w = FileChannel.open(file, StandardOpenOption.WRITE)) {
                w.truncate(from + 2 * 1024 * 1024); // inside the range
            }
            long cpu0 = cpuNanos();
            long t0 = System.nanoTime();
            long got = first.length;
            var buf = new byte[64 * 1024];
            try {
                for (int n; (n = in.read(buf)) > 0;) {
                    got += n;
                }
            } catch (IOException reset) {
                // fine: the response could not be completed
            }
            assertTrue(got < LARGE - from, "short response: " + got);
            waitFor(() -> manager.activeConnections() == 0, 3000, "connection closed");
            Thread.sleep(500);
            long wall = System.nanoTime() - t0;
            long cpu = cpuNanos() - cpu0;
            assertTrue(cpu < wall / 2, "no spin: cpu " + cpu / 1_000_000 + " ms over " + wall / 1_000_000 + " ms");
        }
        assertEquals(0, manager.reaper().writeExpirations(), "ended by shrink detection");
    }

    @Test
    void uncachedRangesUseBothWritePaths() throws Exception {
        var data = new byte[200 * 1024];
        new Random(9).nextBytes(data);
        Files.write(root.resolve("medium.bin"), data);
        start(ServerConfig.defaults(root.toString()).toBuilder().cacheBytes(0).build());
        try (var s = connect()) {
            var in = s.getInputStream();
            for (int[] r : new int[][] {{1000, 1999}, {10_000, 150_000}, {0, data.length - 1}, {data.length - 1, data.length - 1}}) {
                HttpTestSupport.write(s.getOutputStream(), "GET /medium.bin HTTP/1.1\r\nHost: h\r\nRange: bytes="
                        + r[0] + "-" + r[1] + "\r\n\r\n");
                var head = readHead(in);
                assertTrue(head.contains("Content-Range: bytes " + r[0] + "-" + r[1] + "/" + data.length), head);
                int len = r[1] - r[0] + 1;
                assertArrayEquals(java.util.Arrays.copyOfRange(data, r[0], r[1] + 1), in.readNBytes(len));
            }
            HttpTestSupport.write(s.getOutputStream(), "GET /medium.bin HTTP/1.1\r\nHost: h\r\nRange: bytes="
                    + data.length + "-\r\n\r\n");
            var r416 = HttpTestSupport.readResponse(in);
            assertEquals(416, r416.statusCode());
            assertEquals("bytes */" + data.length, r416.headers().get("content-range"));
        }
    }

    @Test
    void pipelinedRequestsWithAFragmentedBodyAreAnsweredInOrder() throws Exception {
        start(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(5));
        Files.writeString(root.resolve("b.txt"), "bee");
        var wire = ("GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n"
                + "GET /b.txt HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nxxxxx"
                + "HEAD /b.txt HTTP/1.1\r\nHost: h\r\n\r\n"
                + "GET /missing HTTP/1.1\r\nHost: h\r\n\r\n"
                + "GET /hello.txt HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        try (var s = connect()) {
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            for (int i = 0; i < wire.length; i += 7) { // fragments cut across heads and bodies
                out.write(wire, i, Math.min(7, wire.length - i));
                out.flush();
                Thread.sleep(1);
            }
            var in = s.getInputStream();
            assertEquals("hello", HttpTestSupport.readResponse(in).bodyText());
            assertEquals("bee", HttpTestSupport.readResponse(in).bodyText());
            var head = HttpTestSupport.readResponse(in, true);
            assertEquals(200, head.statusCode());
            assertEquals("3", head.headers().get("content-length"));
            assertEquals(404, HttpTestSupport.readResponse(in).statusCode());
            assertEquals("hello", HttpTestSupport.readResponse(in).bodyText());
            assertEquals(-1, in.read());
        }
        waitFor(() -> manager.reaper().tracked() == 0, 2000, "deregistration");
    }

    @Test
    void manyIdleConnectionsAreAllTrackedAndReleased() throws Exception {
        start(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(5));
        var sockets = new java.util.ArrayList<Socket>();
        try {
            for (int i = 0; i < 50; i++) {
                var s = connect();
                HttpTestSupport.write(s.getOutputStream(), "GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n");
                assertEquals("hello", HttpTestSupport.readResponse(s.getInputStream()).bodyText());
                sockets.add(s);
            }
            waitFor(() -> manager.reaper().tracked() == 50, 2000, "50 tracked");
            assertEquals(50, manager.activeConnections());
        } finally {
            for (var s : sockets) {
                s.close();
            }
        }
        waitFor(() -> manager.reaper().tracked() == 0 && manager.activeConnections() == 0, 3000, "all released");
    }

    // --- unit level -------------------------------------------------------------------

    @Test
    void transferStopsAtOnceWhenTheFileIsAlreadyShorter() throws Exception {
        var file = root.resolve("short.bin");
        Files.write(file, new byte[1000]);
        try (var reaper = new ConnectionReaper(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
                var fc = FileChannel.open(file);
                var pair = new Pair()) {
            var out = new ChannelOutput(pair.server, reaper.register(pair.server));
            var e = assertThrows(IOException.class, () -> out.transferFrom(fc, 0, 5000));
            assertTrue(e.getMessage().contains("shrank"), e.getMessage());
            assertTrue(e.getMessage().contains("(1000 of 5000"), e.getMessage());
        }
    }

    /** A connected loopback channel pair. */
    private static final class Pair implements AutoCloseable {
        final java.nio.channels.SocketChannel client;
        final java.nio.channels.SocketChannel server;

        Pair() throws IOException {
            try (var l = ServerSocketChannel.open().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
                this.client = java.nio.channels.SocketChannel.open(l.getLocalAddress());
                this.server = l.accept();
            }
        }

        @Override
        public void close() throws IOException {
            this.client.close();
            this.server.close();
        }
    }

    @Test
    void transferThatNeverProgressesGivesUpAfterBoundedStalls() throws Exception {
        var calls = new int[1];
        var stuck = new FakeFileChannel(1 << 20) {
            @Override
            public long transferTo(long position, long count, WritableByteChannel target) {
                calls[0]++;
                return 0; // "socket full" ...
            }

            @Override
            public int read(ByteBuffer dst, long position) {
                return 0; // ... and a file that never yields a byte (not a regular file's behaviour)
            }
        };
        try (var reaper = new ConnectionReaper(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            var timer = reaper.register(null);
            var out = new ChannelOutput(null, timer);
            var e = assertThrows(IOException.class, () -> out.transferFrom(stuck, 0, 1000));
            assertEquals("transferTo made no progress", e.getMessage());
            assertEquals(ChannelOutput.MAX_STALLS, calls[0]);
            assertEquals(ConnectionReaper.State.WRITING, timer.state());
            out.flush();
            out.close();
            reaper.deregister(timer);
            assertEquals(0, reaper.tracked());
        }
    }

    @Test
    void reaperSweepExpiresByStateOnly() throws Exception {
        try (var reaper = new ConnectionReaper(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3));
                var pa = new Pair(); var pb = new Pair(); var pc = new Pair()) {
            var a = pa.server;
            var b = pb.server;
            var c = pc.server;
            var idle = reaper.register(a);
            var processing = reaper.register(b);
            var head = reaper.register(c);
            idle.idle();
            processing.processing();
            head.head();
            long now = System.nanoTime();
            reaper.sweep(now);
            assertEquals(3, reaper.tracked(), "nothing due yet");
            reaper.sweep(now + Duration.ofMinutes(10).toNanos());
            assertEquals(-1, pa.client.read(ByteBuffer.allocate(1)), "idle expired: the peer got its FIN at once");
            waitFor(() -> !a.isOpen(), 2000, "idle expired: closed");
            assertTrue(b.isOpen(), "processing has no deadline");
            assertTrue(head.headExpired());
            assertTrue(c.isOpen(), "head expiry first shuts the input down (a 408 can still be sent)");
            assertEquals(-1, c.read(ByteBuffer.allocate(1)), "input shut down");
            assertEquals(2, reaper.tracked());
            reaper.sweep(now + Duration.ofMinutes(20).toNanos());
            waitFor(() -> !c.isOpen(), 2000, "closed when the 408 did not go out within the grace period");
            assertEquals(1, reaper.idleExpirations());
            assertEquals(1, reaper.headExpirations());
            assertEquals(0, reaper.writeExpirations());
        }
    }

    @Test
    void routineFailuresAreNotLogged() {
        assertTrue(Http1Connection.isRoutine(new java.io.EOFException()));
        assertTrue(Http1Connection.isRoutine(new java.nio.channels.AsynchronousCloseException()));
        assertTrue(Http1Connection.isRoutine(new IOException("Broken pipe")));
        assertTrue(Http1Connection.isRoutine(new java.net.SocketException("Connection reset")));
        assertFalse(Http1Connection.isRoutine(new IOException("file shrank during transfer")));
        assertFalse(Http1Connection.isRoutine(new java.net.SocketException("weird")));
        assertFalse(Http1Connection.isRoutine(new IllegalStateException("bug")));
    }

    /** Minimal FileChannel of a given size; only what ChannelOutput uses is meaningful. */
    private abstract static class FakeFileChannel extends FileChannel {
        private final long size;

        FakeFileChannel(long size) {
            this.size = size;
        }

        @Override public long size() { return size; }
        @Override public int read(ByteBuffer dst) { throw new UnsupportedOperationException(); }
        @Override public long read(ByteBuffer[] dsts, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer src) { throw new UnsupportedOperationException(); }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public long position() { return 0; }
        @Override public FileChannel position(long newPosition) { return this; }
        @Override public FileChannel truncate(long s) { throw new UnsupportedOperationException(); }
        @Override public void force(boolean metaData) { }
        @Override public long transferFrom(ReadableByteChannel src, long position, long count) { throw new UnsupportedOperationException(); }
        @Override public int read(ByteBuffer dst, long position) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer src, long position) { throw new UnsupportedOperationException(); }
        @Override public MappedByteBuffer map(MapMode mode, long position, long s) { throw new UnsupportedOperationException(); }
        @Override public FileLock lock(long position, long s, boolean shared) { throw new UnsupportedOperationException(); }
        @Override public FileLock tryLock(long position, long s, boolean shared) { throw new UnsupportedOperationException(); }
        @Override protected void implCloseChannel() { }
    }
}
