package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * The install-snapshot side channel across the #175 trust boundary: authenticated (cluster key, rotation key), fresh
 * (nonce, timestamp), bounded (manifest, chunk, topic name), integrity-checked (SHA-256 per chunk), paced, zero-copy.
 */
@Timeout(60)
class SnapshotTransferTest {

    static final byte[] KEY = SnapshotTransfer.deriveKey("cluster-key-0123456789abcdef-0123".getBytes(StandardCharsets.UTF_8));
    static final byte[] OLD = SnapshotTransfer.deriveKey("previous-key-0123456789abcdef-012".getBytes(StandardCharsets.UTF_8));
    static final byte[] OTHER = SnapshotTransfer.deriveKey("someone-else-0123456789abcdef-012".getBytes(StandardCharsets.UTF_8));

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    SegmentedLog log;
    SnapshotStore store;
    SnapshotTransfer.Server server;
    MsgSnapshot pinned;

    @AfterEach
    void stop() throws IOException {
        if (server != null) server.close();
        if (log != null) log.close();
    }

    /** A topic with {@code batches} 200-byte batches in 4 KiB segments, pinned at index 7. */
    void setUp(int batches, long bytesPerSecond, byte[] previous) throws IOException {
        log = SegmentedLog.open(dir.resolve("ledger"), 4096);
        for (int i = 0; i < batches; i++) log.append(List.of(("r" + i + "-" + "y".repeat(200)).getBytes(StandardCharsets.UTF_8)), i);
        log.force();
        store = new SnapshotStore(dir.resolve("snap"));
        pinned = store.pin("t", log, 7, 2, log.nextOffset(), 0, "state".getBytes(StandardCharsets.UTF_8), MsgSnapshot.MIN_CHUNK);
        assertNotNull(pinned);
        server = new SnapshotTransfer.Server(new InetSocketAddress("127.0.0.1", 0), KEY, previous, store, bytesPerSecond, now::get);
    }

    MsgSnapshot fetch(byte[] key, String topic, long minIndex, int maxManifest) throws IOException {
        return SnapshotTransfer.fetch(server.address(), key, topic, minIndex, dir.resolve("staging"), maxManifest, now::get);
    }

    void assertStagedEqualsLeader(MsgSnapshot got) throws IOException {
        for (var seg : got.segments()) {
            var leader = Files.readAllBytes(log.segmentFiles().stream().filter(f -> f.baseOffset() == seg.base()).findFirst().orElseThrow().file());
            var staged = Files.readAllBytes(dir.resolve("staging").resolve(SnapshotStore.name(seg.base())));
            assertArrayEquals(java.util.Arrays.copyOf(leader, (int) seg.bytes()), staged);
        }
    }

    @Test
    void authenticatedFetchShipsTheLeaderFilesAsTheyAre() throws Exception {
        setUp(100, 64L << 20, null);
        var got = fetch(KEY, "t", 7, 1 << 20);
        assertEquals(pinned.index(), got.index());
        assertEquals(log.nextOffset(), got.endOffset());
        assertArrayEquals("state".getBytes(StandardCharsets.UTF_8), got.state());
        assertTrue(got.segments().size() > 3);
        assertStagedEqualsLeader(got);
        assertEquals(got.dataBytes(), server.bytesSent.get());
        // A second fetch resumes: every chunk already staged and intact is kept, nothing is sent again.
        long sent = server.bytesSent.get();
        fetch(KEY, "t", 7, 1 << 20);
        assertEquals(sent, server.bytesSent.get());
        assertEquals(0, server.refused.get());
    }

    @Test
    void rotationKeyIsAcceptedAndAnswersUnderTheKeyTheClientProved() throws Exception {
        setUp(20, 64L << 20, OLD);
        var got = fetch(OLD, "t", 0, 1 << 20);
        assertStagedEqualsLeader(got);
    }

    @Test
    void wrongKeyIsRefusedSilently() throws Exception {
        setUp(20, 64L << 20, null);
        assertThrows(IOException.class, () -> fetch(OTHER, "t", 0, 1 << 20));
        assertEquals(1, server.refused.get());
        assertEquals(0, server.bytesSent.get());
        assertFalse(Files.exists(dir.resolve("staging").resolve(SnapshotStore.name(0))));
    }

    /** Builds the raw hello frame as the client does. */
    static ByteBuffer[] hello(byte[] key, byte[] nonce, long ts, String topic, long minIndex) {
        var tb = topic.getBytes(StandardCharsets.UTF_8);
        var head = ByteBuffer.allocate(4 + 1 + 16 + 8 + 1).putInt(SnapshotTransfer.MAGIC).put(SnapshotTransfer.VERSION).put(nonce)
                .putLong(ts).put((byte) tb.length).array();
        var tail = ByteBuffer.allocate(8).putLong(minIndex).array();
        return new ByteBuffer[] {ByteBuffer.wrap(head), ByteBuffer.wrap(tb), ByteBuffer.wrap(tail),
            ByteBuffer.wrap(SnapshotTransfer.tag(key, head, tb, tail))};
    }

    /** Sends a raw hello; returns the reply status byte, or -1 when the server closed without answering. */
    int send(ByteBuffer[] frame) throws IOException {
        try (var sc = SocketChannel.open(server.address())) {
            sc.socket().setSoTimeout(5000);
            sc.write(frame);
            var in = new DataInputStream(sc.socket().getInputStream());
            int b = in.read();
            return b;
        }
    }

    @Test
    void replayedHelloIsRefused() throws Exception {
        setUp(20, 64L << 20, null);
        var nonce = new byte[16];
        nonce[0] = 42;
        assertEquals(0, send(hello(KEY, nonce, now.get(), "t", 0)), "first use answered");
        assertEquals(-1, send(hello(KEY, nonce, now.get(), "t", 0)), "the same nonce again: no answer");
        assertEquals(1, server.refused.get());
    }

    @Test
    void staleOrFutureTimestampIsRefused() throws Exception {
        setUp(20, 64L << 20, null);
        assertEquals(-1, send(hello(KEY, new byte[16], now.get() - SnapshotTransfer.SKEW_MS - 1, "t", 0)));
        var n2 = new byte[16];
        n2[1] = 1;
        assertEquals(-1, send(hello(KEY, n2, now.get() + SnapshotTransfer.SKEW_MS + 1, "t", 0)));
        assertEquals(2, server.refused.get());
    }

    @Test
    void pathTraversalTopicIsRefusedEvenWhenAuthenticated() throws Exception {
        setUp(20, 64L << 20, null);
        int k = 0;
        for (var t : List.of("..", "../t", "t/../../etc", ".", "t\u0000", "a b")) {
            var n = new byte[16];
            n[0] = (byte) ++k;
            assertEquals(-1, send(hello(KEY, n, now.get(), t, 0)), t);
        }
        assertEquals(k, server.refused.get());
        assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(new MsgSnapshot("../x", 1, 1, 0, 0,
                MsgSnapshot.MIN_CHUNK, null, List.of(new MsgSnapshot.Seg(0, 0, 0, -1, new byte[0][]))).encode()));
    }

    @Test
    void oversizedManifestIsRefused() throws Exception {
        setUp(100, 64L << 20, null);
        var e = assertThrows(IOException.class, () -> fetch(KEY, "t", 0, 64));
        assertTrue(e.getMessage().contains("manifest"), e.getMessage());
    }

    @Test
    void corruptChunkIsCaughtByItsDigest() throws Exception {
        setUp(100, 64L << 20, null);
        // Flip a byte in a pinned file after the manifest was digested (a disk error, or a tampered peer).
        var seg = pinned.segments().get(1);
        var pinFile = dir.resolve("snap").resolve("t").resolve("7").resolve(SnapshotStore.name(seg.base()));
        try (var ch = FileChannel.open(pinFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var one = ByteBuffer.allocate(1);
            ch.read(one, 100);
            one.flip();
            byte b = one.get();
            ch.write(ByteBuffer.wrap(new byte[] {(byte) (b ^ 0x5A)}), 100);
        }
        var e = assertThrows(IOException.class, () -> fetch(KEY, "t", 0, 1 << 20));
        assertTrue(e.getMessage().contains("SHA-256"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("staging").resolve(SnapshotStore.name(seg.base())))
                && Files.size(dir.resolve("staging").resolve(SnapshotStore.name(seg.base()))) > 0
                && java.util.Arrays.equals(Files.readAllBytes(pinFile), Files.readAllBytes(dir.resolve("staging").resolve(SnapshotStore.name(seg.base())))),
                "the corrupt chunk was never written");
    }

    /** A peer that holds the key cannot crash the client either: wrong-length chunks and forged manifests are refused. */
    @Test
    void forgedManifestAndOversizedChunkAreRefused() throws Exception {
        setUp(10, 64L << 20, null);
        try (var fake = ServerSocketChannel.open()) {
            fake.bind(new InetSocketAddress("127.0.0.1", 0));
            var addr = (InetSocketAddress) fake.getLocalAddress();
            for (var mode : List.of("forged", "oversized")) {
                var t = Thread.ofVirtual().start(() -> {
                    try (var sc = fake.accept()) {
                        var in = new DataInputStream(sc.socket().getInputStream());
                        var head = new byte[30];
                        in.readFully(head);
                        var nonceC = java.util.Arrays.copyOfRange(head, 5, 21);
                        var tb = new byte[head[29] & 0xFF];
                        in.readFully(tb);
                        in.readLong();
                        in.readFully(new byte[32]);
                        var manifest = pinned.encode();
                        var reply = ByteBuffer.allocate(29).put((byte) 0).put(new byte[16]).putLong(pinned.index())
                                .putInt(manifest.length).array();
                        var mac = SnapshotTransfer.tag(mode.equals("forged") ? OTHER : KEY, new byte[] {'M'}, nonceC, reply, manifest);
                        sc.write(new ByteBuffer[] {ByteBuffer.wrap(reply), ByteBuffer.wrap(manifest), ByteBuffer.wrap(mac)});
                        in.readFully(new byte[20 + 32]);
                        sc.write(ByteBuffer.allocate(5).put((byte) 0).putInt(MsgSnapshot.MAX_CHUNK + 1).flip());
                    } catch (IOException ignored) {
                        // the client hung up, as it should
                    }
                });
                var e = assertThrows(IOException.class, () -> SnapshotTransfer.fetch(addr, KEY, "t", 0, dir.resolve("staging2"),
                        1 << 20, now::get));
                assertTrue(e.getMessage().contains(mode.equals("forged") ? "MAC" : "expected"), e.getMessage());
                t.join(5000);
            }
        }
    }

    /** Constraint 7: one token bucket paces every transfer of a server. */
    @Test
    void installIsBandwidthLimited() throws Exception {
        setUp(700, 64 << 10, null); // ~150 KiB at 64 KiB/s with one second of burst: at least ~1.3 s
        long t0 = System.nanoTime();
        var got = fetch(KEY, "t", 0, 1 << 20);
        double secs = (System.nanoTime() - t0) / 1e9;
        assertTrue(got.dataBytes() > 140_000, "bytes: " + got.dataBytes());
        double floor = (got.dataBytes() - (64 << 10)) / (double) (64 << 10);
        assertTrue(secs >= floor * 0.9, "took " + secs + " s for " + got.dataBytes() + " bytes; floor " + floor);
    }

    @Test
    void noPinIsAnAuthenticatedNo() throws Exception {
        setUp(10, 64L << 20, null);
        var e = assertThrows(SnapshotTransfer.Unavailable.class, () -> fetch(KEY, "t", 8, 1 << 20));
        assertTrue(e.getMessage().contains("no snapshot"));
        assertThrows(SnapshotTransfer.Unavailable.class, () -> fetch(KEY, "nope", 0, 1 << 20));
    }
}
