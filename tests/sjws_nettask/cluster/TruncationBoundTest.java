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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** PR-4: the application's limit on Raft log prefix truncation. */
@Timeout(30)
class TruncationBoundTest {

    private static final GroupId GROUP = new GroupId("msg/4");

    private static final class Single implements AutoCloseable {
        final FakeClock clock = new FakeClock(0L);
        final MemoryLogStorage log = new MemoryLogStorage();
        final ReplicatedNode node;
        final ReplicationGroup group;
        final SnapshotControl snaps;

        Single(GroupOptions opts) {
            var me = new Member(new NodeId(1, "n1"), new SiteId("local"), new InetSocketAddress("127.0.0.1", 23_501),
                    1L, MemberState.ALIVE, false);
            node = ReplicatedNode.open(me.id(), List.of(me), java.util.Set.of(me.id()), QuorumPolicy.majority(), clock,
                    log, new SimTransport());
            group = node.openGroup(GROUP, opts, null);
            snaps = node.snapshots(GROUP);
            clock.advance(Duration.ofMillis(200));
            node.drive();
            assertTrue(group.isLeader());
        }

        void append(int n) throws Exception {
            for (int i = 0; i < n; i++) {
                var f = node.append(GROUP, List.of(ByteBuffer.wrap(("e" + i).getBytes(StandardCharsets.UTF_8))));
                node.drive();
                f.get(5, TimeUnit.SECONDS);
            }
        }

        @Override
        public void close() {
            node.close();
        }
    }

    private static GroupOptions manual() {
        return new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000)
                .withoutAutoSnapshot();
    }

    private static final Compactor KEEP = snapshot -> null;

    @Test
    void truncateToBoundStopsAtTheLowerOfSnapshotAndBound() throws Exception {
        try (var s = new Single(manual())) {
            s.append(10);
            long commit = s.group.commitIndex();
            s.snaps.take(commit);
            var bound = new AtomicLong(4);
            var seenSnapshot = new AtomicLong();
            s.snaps.setTruncationBound((group, snapshotIndex) -> {
                assertEquals(GROUP, group);
                seenSnapshot.set(snapshotIndex);
                return bound.get();
            });
            assertEquals(4L, s.snaps.truncateToBound(KEEP).get(5, TimeUnit.SECONDS));
            assertEquals(commit, seenSnapshot.get());
            assertNull(s.log.read(GROUP, 4), "dropped through the bound");
            assertNotNull(s.log.read(GROUP, 5), "kept past the bound");
            bound.set(Long.MAX_VALUE);
            assertEquals(commit, s.snaps.truncateToBound(KEEP).get(5, TimeUnit.SECONDS), "never past the snapshot");
            assertNull(s.log.read(GROUP, commit));
            bound.set(2);
            assertEquals(commit, s.snaps.truncateToBound(KEEP).get(5, TimeUnit.SECONDS), "a lower bound undoes nothing");
        }
    }

    @Test
    void truncateAndCompactNeverDropsPastTheBound() throws Exception {
        try (var s = new Single(manual())) {
            s.append(8);
            long commit = s.group.commitIndex();
            s.snaps.take(commit);
            s.snaps.setTruncationBound((group, snapshotIndex) -> 3);
            s.snaps.truncateAndCompact(commit, KEEP).get(5, TimeUnit.SECONDS);
            assertNull(s.log.read(GROUP, 3));
            assertNotNull(s.log.read(GROUP, 4), "the explicit call was clamped to the bound");
            s.snaps.setTruncationBound(null);
            s.snaps.truncateAndCompact(commit, KEEP).get(5, TimeUnit.SECONDS);
            assertNull(s.log.read(GROUP, commit), "without a bound the explicit call is as before");
        }
    }

    @Test
    void aFailingBoundTruncatesNothing() throws Exception {
        try (var s = new Single(manual())) {
            s.append(6);
            s.snaps.take(s.group.commitIndex());
            s.snaps.setTruncationBound((group, snapshotIndex) -> {
                throw new IllegalStateException("offsets unavailable");
            });
            var failure = assertThrows(ExecutionException.class, () -> s.snaps.truncateToBound(KEEP).get());
            assertEquals("offsets unavailable", failure.getCause().getMessage());
            assertThrows(ExecutionException.class, () -> s.snaps.truncateAndCompact(3, KEEP).get());
            assertNotNull(s.log.read(GROUP, 1), "nothing truncated");
        }
    }

    @Test
    void noBoundAndAutoSnapshotsDoNotTruncate() throws Exception {
        var auto = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 2);
        try (var s = new Single(auto)) {
            s.snaps.setTruncationBound((group, snapshotIndex) -> Long.MAX_VALUE);
            s.append(6);
            assertNotNull(s.log.read(GROUP, 1), "automatic snapshots never truncate, bound or not");
            s.snaps.setTruncationBound(null);
            assertThrows(ExecutionException.class, () -> s.snaps.truncateToBound(KEEP).get(),
                    "truncateToBound needs a bound");
        }
    }

    @Test
    void theDefaultsRefuse() {
        SnapshotControl plain = new SnapshotControl() {
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
        assertThrows(UnsupportedOperationException.class, () -> plain.setTruncationBound((g, i) -> 0));
        var failure = assertThrows(ExecutionException.class, () -> plain.truncateToBound(KEEP).get());
        assertTrue(failure.getCause() instanceof UnsupportedOperationException);
    }
}
