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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** App-supplied snapshot taker and the auto-snapshot off switch. */
@Timeout(30)
class SnapshotTakerTest {

    private static final GroupId GROUP = new GroupId("msg/3");

    private static final class Single implements AutoCloseable {
        final FakeClock clock = new FakeClock(0L);
        final ReplicatedNode node;
        final ReplicationGroup group;
        final SnapshotControl snaps;
        final List<Long> applied = new CopyOnWriteArrayList<>();

        Single(GroupOptions opts) {
            var me = new Member(new NodeId(1, "n1"), new SiteId("local"), new InetSocketAddress("127.0.0.1", 23_401),
                    1L, MemberState.ALIVE, false);
            node = ReplicatedNode.open(me.id(), List.of(me), java.util.Set.of(me.id()), QuorumPolicy.majority(), clock,
                    new MemoryLogStorage(), new SimTransport());
            group = node.openGroup(GROUP, opts, entry -> applied.add(entry.index()));
            snaps = node.snapshots(GROUP);
            clock.advance(Duration.ofMillis(200));
            node.drive();
            assertTrue(group.isLeader());
        }

        long append(String text) throws Exception {
            var f = node.append(GROUP, List.of(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8))));
            node.drive();
            return f.get(5, TimeUnit.SECONDS).index();
        }

        @Override
        public void close() {
            node.close();
        }
    }

    private static GroupOptions opts(long every) {
        return new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100), Duration.ofMillis(40), every);
    }

    @Test
    void takerSeesTheStateAtExactlyItsIndexAndReplacesTheBuiltInImage() throws Exception {
        try (var s = new Single(opts(3))) {
            var calls = new CopyOnWriteArrayList<String>();
            s.snaps.setSnapshotTaker((group, index) -> {
                long seen = s.applied.stream().filter(i -> i <= index).count();
                long after = s.applied.stream().filter(i -> i > index).count();
                calls.add(index + ":" + seen + ":" + after);
                return ("img@" + index).getBytes(StandardCharsets.UTF_8);
            });
            for (int i = 0; i < 6; i++) {
                s.append("e" + i);
            }
            assertFalse(calls.isEmpty(), "automatic snapshots went through the taker");
            for (var call : calls) {
                assertTrue(call.endsWith(":0"), "no later entry delivered before the taker ran: " + call);
            }
            long commit = s.group.commitIndex();
            var snap = s.snaps.take(commit);
            assertEquals(commit, snap.lastIncludedIndex());
            assertArrayEquals(("img@" + commit).getBytes(StandardCharsets.UTF_8), snap.data());
            assertEquals(snap.lastIncludedIndex(), s.snaps.take(commit).lastIncludedIndex(), "same index: cached");
            assertThrows(IllegalArgumentException.class, () -> s.snaps.take(commit - 1));
        }
    }

    @Test
    void autoSnapshotOffLeavesSnapshotsToTheApplication() throws Exception {
        var off = opts(1).withoutAutoSnapshot();
        assertFalse(off.autoSnapshot());
        assertTrue(opts(1).autoSnapshot());
        assertEquals(GroupOptions.NO_AUTO_SNAPSHOT, off.snapshotEveryEntries());
        try (var s = new Single(off)) {
            var count = new AtomicInteger();
            s.snaps.setSnapshotTaker((group, index) -> {
                count.incrementAndGet();
                return new byte[] {7};
            });
            for (int i = 0; i < 5; i++) {
                s.append("e" + i);
            }
            assertEquals(0, count.get(), "no automatic snapshot");
            var snap = s.snaps.take(s.group.commitIndex());
            assertEquals(1, count.get());
            assertArrayEquals(new byte[] {7}, snap.data());
        }
        try (var s = new Single(off)) {
            long index = s.append("x");
            var builtIn = s.snaps.take(index); // no taker: the built-in image still works
            assertArrayEquals("x".getBytes(StandardCharsets.UTF_8), builtIn.data());
        }
    }

    @Test
    void failingTakerSkipsTheSnapshotAndKeepsTheLog() throws Exception {
        try (var s = new Single(opts(2))) {
            var tries = new AtomicInteger();
            s.snaps.setSnapshotTaker((group, index) -> {
                if (tries.incrementAndGet() % 2 == 1) {
                    throw new java.io.IOException("disk");
                }
                return null;
            });
            for (int i = 0; i < 4; i++) {
                s.append("e" + i);
            }
            assertTrue(tries.get() >= 1);
            long commit = s.group.commitIndex();
            assertThrows(IllegalStateException.class, () -> s.snaps.take(commit));
            assertThrows(IllegalStateException.class, () -> s.snaps.take(commit), "null image");
            s.snaps.setSnapshotTaker(null);
            assertEquals(commit, s.snaps.take(commit).lastIncludedIndex(), "built-in image again");
        }
    }

    @Test
    void defaultSetterRefuses() {
        SnapshotControl bare = new SnapshotControl() {
            @Override
            public LogSnapshot take(long lastIncludedIndex) {
                return null;
            }

            @Override
            public void install(LogSnapshot snapshot) {
            }

            @Override
            public java.util.concurrent.CompletableFuture<Void> truncateAndCompact(long ackedThrough,
                    Compactor compactor) {
                return null;
            }
        };
        assertThrows(UnsupportedOperationException.class, () -> bare.setSnapshotTaker((g, i) -> new byte[0]));
    }
}
