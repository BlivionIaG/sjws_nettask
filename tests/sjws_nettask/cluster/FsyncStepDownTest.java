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
package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * #242: a leader steps down after repeated consecutive fsync failures; a successful fsync resets the count.
 */
@Timeout(60)
class FsyncStepDownTest {

    private static final GroupId GROUP = new GroupId("msg/fsync242");

    private final FakeClock clock = new FakeClock(0L);
    private final SimTransport net = new SimTransport();
    private final List<ReplicatedNode> nodes = new ArrayList<>();
    private final List<ReplicationGroup> groups = new ArrayList<>();
    private final List<Faulty> storages = new ArrayList<>();
    private final List<List<String>> applied = new ArrayList<>();

    @AfterEach
    void close() {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            try {
                nodes.get(i).close();
            } catch (Exception ignored) {
            }
        }
        nodes.clear();
        groups.clear();
        storages.clear();
        applied.clear();
    }

    /** Storage that fails {@link #force} a configurable number of times. */
    static final class Faulty implements LogStorage {
        final LogStorage d;
        /** Remaining forced fsync failures; 0 = healthy. */
        volatile int failForceRemaining;

        Faulty(LogStorage d) {
            this.d = d;
        }

        @Override
        public void append(GroupId g, long index, long term, byte[] data) throws IOException {
            d.append(g, index, term, data);
        }

        @Override
        public byte[] read(GroupId g, long index) throws IOException {
            return d.read(g, index);
        }

        @Override
        public long termAt(GroupId g, long index) throws IOException {
            return d.termAt(g, index);
        }

        @Override
        public long lastIndex(GroupId g) {
            return d.lastIndex(g);
        }

        @Override
        public void truncateSuffix(GroupId g, long fromIndex) throws IOException {
            d.truncateSuffix(g, fromIndex);
        }

        @Override
        public void truncatePrefix(GroupId g, long throughIndex) throws IOException {
            d.truncatePrefix(g, throughIndex);
        }

        @Override
        public void force(GroupId g) throws IOException {
            if (failForceRemaining > 0) {
                failForceRemaining--;
                throw new IOException("injected fsync failure");
            }
            d.force(g);
        }

        @Override
        public int forces(GroupId g) {
            return d.forces(g);
        }

        @Override
        public boolean survivesRestart() {
            return d.survivesRestart();
        }

        @Override
        public long witnessIndex(GroupId g) throws IOException {
            return d.witnessIndex(g);
        }

        @Override
        public long witnessTerm(GroupId g) throws IOException {
            return d.witnessTerm(g);
        }

        @Override
        public void noteWitness(GroupId g, long index, long term) throws IOException {
            d.noteWitness(g, index, term);
        }

        @Override
        public void saveHardState(GroupId g, long term, int votedFor) throws IOException {
            d.saveHardState(g, term, votedFor);
        }

        @Override
        public boolean hasHardState(GroupId g) throws IOException {
            return d.hasHardState(g);
        }

        @Override
        public long hardStateTerm(GroupId g) throws IOException {
            return d.hardStateTerm(g);
        }

        @Override
        public int hardStateVote(GroupId g) throws IOException {
            return d.hardStateVote(g);
        }

        @Override
        public boolean persistsSnapshots() {
            return d.persistsSnapshots();
        }

        @Override
        public void saveSnapshot(GroupId g, LogSnapshot snapshot) throws IOException {
            d.saveSnapshot(g, snapshot);
        }

        @Override
        public LogSnapshot loadSnapshot(GroupId g, int maxBytes) throws IOException {
            return d.loadSnapshot(g, maxBytes);
        }
    }

    private void start(int stepDownAfter) throws IOException {
        var opts = new GroupOptions(Durability.STRICT, 1 << 20, Duration.ofMillis(100), Duration.ofMillis(40),
                Long.MAX_VALUE).withFsyncFailureStepDown(stepDownAfter);
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        for (var m : members) {
            var storage = new Faulty(new MemoryLogStorage());
            var seen = new CopyOnWriteArrayList<String>();
            var node = ReplicatedNode.open(m.id(), voters, clock, storage, net);
            groups.add(node.openGroup(GROUP, opts, entry -> {
                var copy = new byte[entry.entry().remaining()];
                entry.entry().duplicate().get(copy);
                seen.add(new String(copy, StandardCharsets.UTF_8));
            }));
            nodes.add(node);
            storages.add(storage);
            applied.add(seen);
        }
        for (int k = 0; k < 40 && leader() < 0; k++) {
            step();
        }
        assertTrue(leader() >= 0, "no leader elected");
    }

    private void startRelaxed(int stepDownAfter) throws IOException {
        // Default failback hysteresis is zero; fsync step-down must still delay campaigns (#242).
        var opts = new GroupOptions(Durability.RELAXED, 1 << 20, Duration.ofMillis(100), Duration.ofMillis(40),
                Long.MAX_VALUE).withFsyncFailureStepDown(stepDownAfter).withRelaxedSyncBound(Duration.ZERO, Long.MAX_VALUE);
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        for (var m : members) {
            var storage = new Faulty(new MemoryLogStorage());
            var seen = new CopyOnWriteArrayList<String>();
            var node = ReplicatedNode.open(m.id(), voters, clock, storage, net);
            groups.add(node.openGroup(GROUP, opts, entry -> {
                var copy = new byte[entry.entry().remaining()];
                entry.entry().duplicate().get(copy);
                seen.add(new String(copy, StandardCharsets.UTF_8));
            }));
            nodes.add(node);
            storages.add(storage);
            applied.add(seen);
        }
        for (int k = 0; k < 40 && leader() < 0; k++) {
            step();
        }
        assertTrue(leader() >= 0, "no leader elected");
    }

    private void step() {
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
    }

    private ReplicationGroup group(int i) {
        return groups.get(i);
    }

    private int leader() {
        for (int i = 0; i < 2; i++) {
            if (group(i).isLeader()) {
                return i;
            }
        }
        return -1;
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("fsync"),
                new InetSocketAddress("127.0.0.1", 20_000 + wire), 1L, MemberState.ALIVE, witness);
    }

    private static List<ByteBuffer> batch(String tag, int n) {
        var out = new ArrayList<ByteBuffer>();
        for (int i = 1; i <= n; i++) {
            out.add(ByteBuffer.wrap((tag + "-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private <T> T settle(CompletableFuture<T> future) throws Exception {
        for (int k = 0; k < 30 && !future.isDone(); k++) {
            step();
        }
        return future.get();
    }

    @Test
    void belowThresholdKeepsLeadershipAndResetsOnSuccess() throws Exception {
        start(3);
        int l = leader();
        settle(nodes.get(l).append(GROUP, batch("before", 1)));
        assertEquals(0, nodes.get(l).consecutiveFsyncFailures(GROUP));

        storages.get(l).failForceRemaining = 2;
        for (int i = 0; i < 2; i++) {
            var failed = nodes.get(l).append(GROUP, batch("bad" + i, 1));
            assertInstanceOf(UncheckedIOException.class,
                    assertThrows(ExecutionException.class, failed::get).getCause());
            assertTrue(group(l).isLeader(), "still below threshold after " + (i + 1) + " failures");
        }
        assertEquals(2, nodes.get(l).consecutiveFsyncFailures(GROUP));
        assertEquals(2, nodes.get(l).fsyncFailures(GROUP));

        // Successful fsync resets the consecutive count.
        settle(nodes.get(l).append(GROUP, batch("ok", 1)));
        assertEquals(0, nodes.get(l).consecutiveFsyncFailures(GROUP));
        assertEquals(2, nodes.get(l).fsyncFailures(GROUP), "cumulative count is kept");
        assertTrue(group(l).isLeader());
    }

    @Test
    void repeatedFsyncFailuresStepTheLeaderDown() throws Exception {
        start(3);
        int l = leader();
        settle(nodes.get(l).append(GROUP, batch("before", 1)));

        storages.get(l).failForceRemaining = 3;
        for (int i = 0; i < 2; i++) {
            var failed = nodes.get(l).append(GROUP, batch("bad" + i, 1));
            assertThrows(ExecutionException.class, failed::get);
            assertTrue(group(l).isLeader(), "failure " + (i + 1) + " must not step down yet");
        }
        var last = nodes.get(l).append(GROUP, batch("bad2", 1));
        assertThrows(ExecutionException.class, last::get);
        assertFalse(group(l).isLeader(), "third consecutive fsync failure must step down");
        assertFalse(nodes.get(l).failedClosed(GROUP), "a clean rollback must not fail the group closed");
        assertEquals(3, nodes.get(l).fsyncFailures(GROUP));

        int other = 1 - l;
        for (int k = 0; k < 60 && !group(other).isLeader(); k++) {
            step();
        }
        assertTrue(group(other).isLeader(), "the healthy data node must take over");
        settle(nodes.get(other).append(GROUP, batch("after", 1)));
    }

    @Test
    void relaxedTickFsyncFailuresAlsoStepDown() throws Exception {
        startRelaxed(2);
        int l = leader();
        settle(nodes.get(l).append(GROUP, batch("seed", 1)));
        // Arm before the append's dirty fsync runs on the next tick (RELAXED, no entry bound).
        storages.get(l).failForceRemaining = 8;
        var dirty = nodes.get(l).append(GROUP, batch("dirty", 1));
        for (int i = 0; i < 20 && group(l).isLeader(); i++) {
            step();
        }
        assertFalse(group(l).isLeader(), "tick-path fsync failures must step the leader down");
        assertFalse(nodes.get(l).failedClosed(GROUP));
        assertTrue(nodes.get(l).fsyncFailures(GROUP) >= 2, "fsyncFailures=" + nodes.get(l).fsyncFailures(GROUP));
        dirty.isDone();

        int other = 1 - l;
        for (int k = 0; k < 80 && !group(other).isLeader(); k++) {
            step();
        }
        assertTrue(group(other).isLeader(), "peer took over after tick-path step-down");
    }

    @Test
    void defaultThresholdIsThree() {
        var opts = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
        assertEquals(GroupOptions.DEFAULT_FSYNC_FAILURE_STEP_DOWN, opts.fsyncFailureStepDown());
        assertEquals(3, opts.fsyncFailureStepDown());
        assertThrows(IllegalArgumentException.class, () -> opts.withFsyncFailureStepDown(0));
    }
}
