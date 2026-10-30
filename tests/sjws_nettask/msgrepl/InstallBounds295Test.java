package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * #295: install size bound while streaming, manifest compare before chunks, and (with the cluster test) bounded
 * divergent-pin retries. Each assertion is chosen so removing its production fix makes the test fail.
 */
@Timeout(60)
class InstallBounds295Test {

    static final byte[] KEY = SnapshotTransfer.deriveKey("cluster-key-0123456789abcdef-0123".getBytes(StandardCharsets.UTF_8));

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

    void setUp(int batches) throws IOException {
        log = SegmentedLog.open(dir.resolve("ledger"), 4096);
        for (int i = 0; i < batches; i++) {
            log.append(List.of(("r" + i + "-" + "y".repeat(200)).getBytes(StandardCharsets.UTF_8)), i);
        }
        log.force();
        store = new SnapshotStore(dir.resolve("snap"));
        pinned = store.pin("t", log, 7, 2, log.nextOffset(), 0,
                List.of(new MsgSnapshot.Section(MsgSnapshot.APP_STATE, 0, "state".getBytes(StandardCharsets.UTF_8))),
                MsgSnapshot.MIN_CHUNK);
        server = new SnapshotTransfer.Server(new InetSocketAddress("127.0.0.1", 0), KEY, null, store, 64L << 20, now::get);
    }

    /**
     * #295 (a): {@code max_install_bytes} is refused on the authenticated manifest before any chunk is requested, so
     * staging stays empty and the server sends no chunk bodies. Mutation: remove the manifest-size check in
     * {@link SnapshotTransfer#fetch} — this test then fails (chunksSent &gt; 0 / staging non-empty, or no Oversized).
     */
    @Test
    void oversizedInstallIsRefusedBeforeAnyChunkHitsDisk() throws Exception {
        setUp(80);
        assertTrue(pinned.dataBytes() > 1024, "fixture must exceed the tiny bound");
        long beforeChunks = server.chunksSent.get();
        var staging = dir.resolve("staging-over");
        var e = assertThrows(SnapshotTransfer.Oversized.class,
                () -> SnapshotTransfer.fetch(server.address(), KEY, "t", 7, staging, 1 << 20, now::get,
                        64L << 20, MsgSnapshot.MIN_CHUNK, 1024L, null));
        assertTrue(e.getMessage().contains("max_install_bytes"), e.getMessage());
        assertEquals(beforeChunks, server.chunksSent.get(), "no chunk body requested after oversized manifest");
        assertEquals(0, server.bytesSent.get(), "no segment bytes transferred");
        if (Files.isDirectory(staging)) {
            try (var stream = Files.list(staging)) {
                assertEquals(0, stream.count(), "staging must not keep partial segment files");
            }
        }
    }

    /**
     * #295 (c): a divergent pin is refused after the authenticated manifest and before any chunk body is fetched.
     * Mutation: move the {@code accept} check below the chunk loop — this test then fails on chunksSent.
     */
    @Test
    void divergentManifestIsRefusedBeforeFetchingChunks() throws Exception {
        setUp(40);
        long beforeChunks = server.chunksSent.get();
        var staging = dir.resolve("staging-div");
        var e = assertThrows(SnapshotTransfer.Divergent.class,
                () -> SnapshotTransfer.fetch(server.address(), KEY, "t", 7, staging, 1 << 20, now::get,
                        64L << 20, MsgSnapshot.MIN_CHUNK, 0L, got -> false));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
        assertEquals(beforeChunks, server.chunksSent.get(), "divergent pin must not fetch chunk bodies");
        assertEquals(0, server.bytesSent.get());
        if (Files.isDirectory(staging)) {
            try (var stream = Files.list(staging)) {
                assertFalse(stream.anyMatch(f -> f.getFileName().toString().endsWith(".log")),
                        "no segment file written for a divergent pin");
            }
        }
    }

    /** Matching accept + generous bound still ships the pin (sanity for the new fetch overload). */
    @Test
    void matchingImageUnderTheBoundStillFetches() throws Exception {
        setUp(20);
        var got = SnapshotTransfer.fetch(server.address(), KEY, "t", 7, dir.resolve("staging-ok"), 1 << 20, now::get,
                64L << 20, MsgSnapshot.MIN_CHUNK, pinned.dataBytes(), g -> RaftReplica.sameSnapshotImage(g, pinned));
        assertEquals(pinned.index(), got.index());
        assertTrue(server.bytesSent.get() > 0);
    }

    /** Default is four ledger segments; {@code 0} remains the unlimited opt-in at the Snapshots layer. */
    @Test
    void defaultMaxInstallBytesIsFourLedgerSegments() {
        assertEquals(4L << 30, Snapshots.DEFAULT_MAX_INSTALL_BYTES);
        assertEquals(4L * SegmentedLog.DEFAULT_SEGMENT_BYTES, Snapshots.DEFAULT_MAX_INSTALL_BYTES);
        assertEquals(Snapshots.DEFAULT_MAX_INSTALL_BYTES, Snapshots.defaults().maxInstallBytes());
        assertEquals(16, Snapshots.DEFAULT_MAX_INSTALL_PIN_ROUNDS);
        // Explicit unlimited construction still allowed (tests / operators setting 0).
        var unlimited = new Snapshots(10_000, 1_000, MsgSnapshot.MIN_CHUNK, 64L << 20,
                java.time.Duration.ofMillis(200), 0);
        assertEquals(0, unlimited.maxInstallBytes());
    }
}
