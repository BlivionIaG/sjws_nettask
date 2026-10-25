package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.messaging.delivery.Partition.ForwardOutcome;
import sjws_nettask.messaging.ledger.Durability;

/**
 * #236 PR 1: the owner side of a forwarded produce. Dedup is keyed by {@code (authenticated origin, producerId)},
 * seqs apply strictly in order from 1 (go-back-N), a seq out of the window is TOO_OLD (a failure, never success),
 * and the state survives replay and snapshots. Local {@link Partition#produce} is untouched.
 */
class ForwardedProduceTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    final List<Partition> open = new ArrayList<>();
    static final RetryPolicy POLICY = new RetryPolicy(3, 100, 1_000);
    static final int LIVE = Partition.DEFAULT_LIVE_PRODUCERS;
    static final long IDLE = Partition.DEFAULT_PRODUCER_IDLE_MS;

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    Partition open(String topic) throws IOException {
        var p = Partition.open(dir, topic, POLICY, now::get, Durability.ASYNC, 64 * 1024);
        open.add(p);
        return p;
    }

    @AfterEach void closeAll() throws IOException { for (var p : open) p.close(); }

    static Partition.ForwardedResult put(Partition p, int origin, long pid, long seq) {
        return p.produceFrom(origin, pid, seq, b(origin + "/" + pid + "/" + seq), null, LIVE, IDLE);
    }

    @Test
    void inOrderSeqsAppendAndRepeatsAreDuplicatesWithTheOriginalOffset() throws Exception {
        var p = open("t");
        var r1 = put(p, 2, 77, 1);
        var r2 = put(p, 2, 77, 2);
        assertEquals(ForwardOutcome.APPENDED, r1.outcome());
        assertEquals(ForwardOutcome.APPENDED, r2.outcome());
        assertEquals(r1.offset(), r1.durable().get());
        var again = put(p, 2, 77, 1);
        assertEquals(ForwardOutcome.DUPLICATE, again.outcome());
        assertEquals(r1.offset(), again.offset());
        assertEquals(2, p.counts().enqueuedTotal());
    }

    @Test
    void aGapIsOutOfOrderWithTheExpectedSeqAndAppendsNothing() throws Exception {
        var p = open("t");
        var first = put(p, 2, 77, 3);
        assertEquals(ForwardOutcome.OUT_OF_ORDER, first.outcome());
        assertEquals(1, first.expectedSeq());
        put(p, 2, 77, 1);
        var gap = put(p, 2, 77, 4);
        assertEquals(ForwardOutcome.OUT_OF_ORDER, gap.outcome());
        assertEquals(2, gap.expectedSeq());
        assertEquals(-1, gap.offset());
        assertEquals(1, p.counts().enqueuedTotal());
    }

    @Test
    void aSeqOutsideTheWindowIsTooOldNotADuplicate() throws Exception {
        var p = open("t");
        for (long s = 1; s <= DeliveryState.DEDUP_WINDOW + 1; s++) put(p, 2, 77, s);
        var old = put(p, 2, 77, 1);
        assertEquals(ForwardOutcome.TOO_OLD, old.outcome());
        assertEquals(-1, old.offset());
        assertEquals(ForwardOutcome.DUPLICATE, put(p, 2, 77, 2).outcome());
    }

    @Test
    void theSameProducerIdFromAnotherOriginIsAnotherProducer() throws Exception {
        var p = open("t");
        put(p, 2, 77, 1);
        // a peer reusing node 2's producerId cannot get node 2's next write dropped as a duplicate (T20)
        assertEquals(ForwardOutcome.APPENDED, put(p, 3, 77, 1).outcome());
        assertEquals(ForwardOutcome.APPENDED, put(p, 2, 77, 2).outcome());
        // and a local producer with that id is separate from both
        assertFalse(p.produce(77, 1, b("local")).duplicate());
        assertEquals(4, p.counts().enqueuedTotal());
    }

    @Test
    void theLiveProducerCapAnswersBusyUntilOneIsIdleThenEvictsIt() throws Exception {
        var p = open("t");
        assertEquals(ForwardOutcome.APPENDED, p.produceFrom(2, 1, 1, b("a"), null, 2, 1_000).outcome());
        now.addAndGet(10);
        assertEquals(ForwardOutcome.APPENDED, p.produceFrom(2, 2, 1, b("b"), null, 2, 1_000).outcome());
        assertEquals(ForwardOutcome.BUSY, p.produceFrom(2, 3, 1, b("c"), null, 2, 1_000).outcome());
        // the cap is per origin
        assertEquals(ForwardOutcome.APPENDED, p.produceFrom(3, 3, 1, b("c"), null, 2, 1_000).outcome());
        now.addAndGet(1_000);
        assertEquals(ForwardOutcome.APPENDED, p.produceFrom(2, 3, 1, b("c"), null, 2, 1_000).outcome());
        // producer 1 (the oldest) was evicted: its next seq has no history, so it is out of order, never re-applied
        var evicted = p.produceFrom(2, 1, 2, b("a2"), null, 2, 1_000);
        assertEquals(ForwardOutcome.OUT_OF_ORDER, evicted.outcome());
        assertEquals(1, evicted.expectedSeq());
    }

    @Test
    void forwardedStateSurvivesReplayAndSnapshots() throws Exception {
        var p = open("t");
        for (long s = 1; s <= 5; s++) put(p, 2, 77, s);
        put(p, 9, -5, 1);
        p.produce(42, 1, b("local"));
        String before = p.canonicalState();
        assertTrue(before.contains(" remote=2/77:5@"), before);
        p.close();
        open.clear();
        var replayed = open("t");
        assertEquals(before, replayed.canonicalState());
        assertEquals(ForwardOutcome.DUPLICATE, put(replayed, 2, 77, 3).outcome());
        assertEquals(ForwardOutcome.APPENDED, put(replayed, 2, 77, 6).outcome());
        replayed.snapshotNow().get();
        String snapped = replayed.canonicalState();
        replayed.close();
        open.clear();
        var fromSnap = open("t");
        assertTrue(fromSnap.recoveredFromSnapshot() >= 0, "recovered from the version-2 snapshot");
        assertEquals(snapped, fromSnap.canonicalState());
        assertEquals(ForwardOutcome.DUPLICATE, put(fromSnap, 2, 77, 6).outcome());
    }

    @Test
    void snapshotsWithoutForwardedProducersStayVersionOne() {
        var st = new DeliveryState(POLICY);
        st.apply(0, new LedgerEntry.Enqueue(5, 1, 1, "", -1, 0, "", b("x")));
        var raw = st.serialize();
        assertEquals(DeliveryState.SNAP_VERSION, java.nio.ByteBuffer.wrap(raw, 4, 4).getInt());
        st.apply(1, new LedgerEntry.Enqueue(5, 1, 1, "", -1, 0, "", b("y"), 3));
        var v2 = st.serialize();
        assertEquals(DeliveryState.SNAP_VERSION_REMOTE, java.nio.ByteBuffer.wrap(v2, 4, 4).getInt());
        assertEquals(st.canonical(), DeliveryState.deserialize(v2, POLICY).canonical());
        // an unknown snapshot version is refused, as an older build refuses version 2
        var v3 = v2.clone();
        java.nio.ByteBuffer.wrap(v3).putInt(4, 3);
        var c = new java.util.zip.CRC32C();
        c.update(v3, 0, v3.length - 4);
        java.nio.ByteBuffer.wrap(v3).putInt(v3.length - 4, (int) c.getValue());
        assertThrows(IllegalArgumentException.class, () -> DeliveryState.deserialize(v3, POLICY));
    }

    @Test
    void localEnqueueBytesAreUnchangedAndForwardedOnesUseANewTag() {
        var local = LedgerEntry.encode(new LedgerEntry.Enqueue(7, 9, 11, "", -1, 0, "", b("pay")));
        assertEquals(LedgerEntry.ENQUEUE_T, local[0]);
        assertEquals(1 + 24 + 2 + 12 + 2 + 4 + 3, local.length, "type-1 layout as on master");
        var fwd = LedgerEntry.encode(new LedgerEntry.Enqueue(7, 9, 11, "", -1, 0, "", b("pay"), 513));
        assertEquals(LedgerEntry.ENQUEUE_FROM_T, fwd[0]);
        assertEquals(local.length + 2, fwd.length);
        var back = (LedgerEntry.Enqueue) LedgerEntry.decode(fwd);
        assertEquals(513, back.origin());
        assertEquals(9, back.seq());
        assertEquals(0, ((LedgerEntry.Enqueue) LedgerEntry.decode(local)).origin());
        // type 8 with origin 0 is malformed; a type this build does not know is refused, never misread
        var zero = fwd.clone();
        zero[1] = 0;
        zero[2] = 0;
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.decode(zero));
        var unknown = fwd.clone();
        unknown[0] = 9;
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.decode(unknown));
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerEntry.Enqueue(1, 1, 1, "", -1, 0, "", b("x"), 0x10000));
    }

    @Test
    void argumentsAreChecked() throws Exception {
        var p = open("t");
        assertThrows(IllegalArgumentException.class, () -> p.produceFrom(0, 1, 1, b("x"), null, LIVE, IDLE));
        assertThrows(IllegalArgumentException.class, () -> p.produceFrom(0x10000, 1, 1, b("x"), null, LIVE, IDLE));
        assertThrows(IllegalArgumentException.class, () -> p.produceFrom(2, 0, 1, b("x"), null, LIVE, IDLE));
        assertThrows(IllegalArgumentException.class, () -> p.produceFrom(2, 1, 0, b("x"), null, LIVE, IDLE));
        assertThrows(IllegalArgumentException.class, () -> p.produceFrom(2, 1, 1, b("x"), null, 0, IDLE));
        assertThrows(IllegalArgumentException.class, () -> p.produceFrom(2, 1, 1, b("x"), null, 1, -1));
    }

    @Test
    void forwardedMessagesAreDeliveredLikeLocalOnes() throws Exception {
        var p = open("t");
        p.subscribe("g", true);
        put(p, 2, 77, 1);
        var got = p.poll("g", "c", 10, 1_000);
        assertEquals(1, got.size());
        assertEquals(77, got.get(0).producerId());
        assertArrayEquals(b("2/77/1"), got.get(0).payload());
    }
}
