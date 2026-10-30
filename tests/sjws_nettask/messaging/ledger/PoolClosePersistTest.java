package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;

class PoolClosePersistTest {
    @TempDir Path dir;

    @Test
    void partitionProduceThenClosePersistsForReopen() throws Exception {
        System.setProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY, "2");
        SharedLedgerPool.resetWriterPoolForTests();
        var p = Partition.open(dir, "t", RetryPolicy.DEFAULT, System::currentTimeMillis);
        var r = p.produce(1, 1, new byte[] {7});
        assertEquals(0, r.offset());
        // Wait like a careful client — and also rely on close() draining without wait.
        r.durable().get();
        p.close();
        try (var q = Partition.open(dir, "t", RetryPolicy.DEFAULT, System::currentTimeMillis)) {
            assertEquals(1, q.produce(1, 2, new byte[] {8}).offset());
        }
    }

    @Test
    void partitionProduceThenCloseWithoutAwaitingFutureStillPersists() throws Exception {
        System.setProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY, "2");
        SharedLedgerPool.resetWriterPoolForTests();
        var p = Partition.open(dir, "t2", RetryPolicy.DEFAULT, System::currentTimeMillis);
        assertEquals(0, p.produce(1, 1, new byte[] {7}).offset());
        // Do NOT await durable — close must drain (DirectoryLockTest contract).
        p.close();
        try (var q = Partition.open(dir, "t2", RetryPolicy.DEFAULT, System::currentTimeMillis)) {
            assertEquals(1, q.produce(1, 2, new byte[] {8}).offset());
        }
    }

    @Test
    void rawWriterCloseDrainsPooledAppend() throws Exception {
        System.setProperty(SharedLedgerPool.WRITER_THREADS_PROPERTY, "2");
        SharedLedgerPool.resetWriterPoolForTests();
        Path d = dir.resolve("raw");
        try (var log = SegmentedLog.open(d)) {
            var w = new LedgerWriter(log, Durability.ASYNC);
            assertTrue(w.usesSharedWriter());
            var f = w.append(List.of(new byte[] {1}));
            // close without awaiting
            w.close();
            f.get(); // should complete successfully from drain, not exceptionally
        }
        try (var log = SegmentedLog.open(d)) {
            assertEquals(1, log.nextOffset());
        }
    }
}
