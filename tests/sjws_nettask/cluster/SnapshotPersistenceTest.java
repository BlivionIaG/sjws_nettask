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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Security #205 review: durable snapshots before any log prefix delete, install validation, size limit. */
@Timeout(30)
class SnapshotPersistenceTest {

    private static final GroupId GROUP = new GroupId("cp/snap");
    private static final Compactor KEEP = snapshot -> null;

    @TempDir
    Path dir;

    private static final class Node implements AutoCloseable {
        final FakeClock clock = new FakeClock(0L);
        final LogStorage storage;
        final ReplicatedNode node;
        final ReplicationGroup group;
        final SnapshotControl snaps;
        final List<String> delivered = new CopyOnWriteArrayList<>();
        final List<LogSnapshot> restored = new CopyOnWriteArrayList<>();

        Node(LogStorage storage, GroupOptions opts) {
            this.storage = storage;
            var me = new Member(new NodeId(1, "n1"), new SiteId("local"), new InetSocketAddress("127.0.0.1", 23_601),
                    1L, MemberState.ALIVE, false);
            node = ReplicatedNode.open(me.id(), List.of(me), java.util.Set.of(me.id()), QuorumPolicy.majority(), clock,
                    storage, new SimTransport());
            group = node.openGroup(GROUP, opts, new GroupListener() {
                @Override
                public void onCommitted(CommittedEntry entry) {
                    delivered.add(StandardCharsets.UTF_8.decode(entry.entry()).toString());
                }

                @Override
                public void onSnapshotRestored(LogSnapshot snapshot) {
                    restored.add(snapshot);
                }
            });
            snaps = node.snapshots(GROUP);
            clock.advance(Duration.ofSeconds(1)); // past the restart recovery window
            node.drive();
            node.drive();
            assertTrue(group.isLeader());
        }

        void append(String... texts) throws Exception {
            for (var text : texts) {
                var f = node.append(GROUP, List.of(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8))));
                node.drive();
                f.get(5, TimeUnit.SECONDS);
            }
        }

        @Override
        public void close() {
            node.close();
        }
    }

    private static GroupOptions opts() {
        return new GroupOptions(Durability.STRICT, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000)
                .withoutAutoSnapshot();
    }

    @Test
    void compactedStateSurvivesARestart() throws Exception {
        var storage = new FileLogStorage(dir);
        long through;
        try (var n = new Node(storage, opts())) {
            n.snaps.setSnapshotTaker((group, index) -> ("state@" + index).getBytes(StandardCharsets.UTF_8));
            n.append("a", "b", "c");
            through = n.group.commitIndex();
            n.snaps.take(through);
            n.snaps.truncateAndCompact(through, KEEP).get(5, TimeUnit.SECONDS);
            assertNull(storage.read(GROUP, 1), "prefix deleted on disk");
        }
        var snap = dir.resolve("cp_snap.snap");
        assertTrue(Files.exists(snap));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(snap)));
        storage.close();
        var reopened = new FileLogStorage(dir);
        try (var n = new Node(reopened, opts())) {
            n.append("d");
            assertEquals(1, n.restored.size(), "the image is handed to the application on open");
            assertEquals(through, n.restored.get(0).lastIncludedIndex());
            assertArrayEquals(("state@" + through).getBytes(StandardCharsets.UTF_8), n.restored.get(0).data());
            assertEquals(List.of("d"), n.delivered, "covered entries are not replayed, later ones are");
            assertTrue(n.group.commitIndex() > through);
        }
        reopened.close();
    }

    /** A storage that keeps entries but no snapshots. */
    private static final class NoSnapshots implements LogStorage {
        final MemoryLogStorage inner = new MemoryLogStorage();

        @Override
        public void append(GroupId group, long index, long term, byte[] data) {
            inner.append(group, index, term, data);
        }

        @Override
        public byte[] read(GroupId group, long index) {
            return inner.read(group, index);
        }

        @Override
        public long termAt(GroupId group, long index) {
            return inner.termAt(group, index);
        }

        @Override
        public long lastIndex(GroupId group) {
            return inner.lastIndex(group);
        }

        @Override
        public void truncateSuffix(GroupId group, long fromIndex) {
            inner.truncateSuffix(group, fromIndex);
        }

        @Override
        public void truncatePrefix(GroupId group, long throughIndex) {
            inner.truncatePrefix(group, throughIndex);
        }

        @Override
        public void force(GroupId group) {
            inner.force(group);
        }

        @Override
        public int forces(GroupId group) {
            return inner.forces(group);
        }

        @Override
        public long witnessIndex(GroupId group) {
            return inner.witnessIndex(group);
        }

        @Override
        public long witnessTerm(GroupId group) {
            return inner.witnessTerm(group);
        }

        @Override
        public void noteWitness(GroupId group, long index, long term) {
            inner.noteWitness(group, index, term);
        }
    }

    @Test
    void withoutDurableSnapshotsNoPrefixIsDeleted() throws Exception {
        var storage = new NoSnapshots();
        try (var n = new Node(storage, opts())) {
            n.append("a", "b");
            long commit = n.group.commitIndex();
            n.snaps.take(commit);
            var failure = assertThrows(ExecutionException.class, () -> n.snaps.truncateAndCompact(commit, KEEP).get());
            assertTrue(failure.getCause().getMessage().contains("no durable snapshot"), failure.getCause().getMessage());
            assertNotNull(storage.read(GROUP, 1), "nothing deleted");
            assertThrows(IllegalStateException.class,
                    () -> n.snaps.install(new LogSnapshot(GROUP, commit + 5, 1, new byte[] {1})));
            assertNotNull(storage.read(GROUP, 1), "nothing deleted by a refused install");
        }
    }

    @Test
    void corruptOrLinkedSnapshotFileRefusesToStart() throws Exception {
        var storage = new FileLogStorage(dir);
        storage.saveSnapshot(GROUP, new LogSnapshot(GROUP, 3, 1, new byte[] {1, 2, 3}));
        assertEquals(3L, storage.loadSnapshot(GROUP, 1024).lastIncludedIndex());
        var file = dir.resolve("cp_snap.snap");
        var bytes = Files.readAllBytes(file);
        bytes[FileLogStorage.SNAP_HEADER_BYTES] ^= 1; // flip one image bit
        Files.write(file, bytes);
        var crc = assertThrows(java.io.IOException.class, () -> storage.loadSnapshot(GROUP, 1024));
        assertTrue(crc.getMessage().contains("CRC32C"), crc.getMessage());
        var opened = assertThrows(java.io.UncheckedIOException.class, () -> new Node(storage, opts()));
        assertTrue(opened.getMessage().contains("durable snapshot"), opened.getMessage());
        storage.saveSnapshot(GROUP, new LogSnapshot(GROUP, 3, 1, new byte[100]));
        assertThrows(java.io.IOException.class, () -> storage.loadSnapshot(GROUP, 10), "over the size limit");
        var real = dir.resolve("elsewhere.snap");
        Files.move(file, real);
        Files.createSymbolicLink(file, real);
        assertThrows(java.io.IOException.class, () -> storage.loadSnapshot(GROUP, 1024), "symlink refused");
        Files.delete(file);
        Files.move(real, file);
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(java.io.IOException.class, () -> storage.loadSnapshot(GROUP, 1024), "mode 0644 refused");
        storage.close();
    }

    @Test
    void installValidatesBeforeChangingAnything() throws Exception {
        var storage = new MemoryLogStorage();
        try (var n = new Node(storage, opts().withMaxSnapshotBytes(16))) {
            n.append("a", "b", "c");
            long commit = n.group.commitIndex();
            long term = n.group.term();
            n.snaps.install(new LogSnapshot(GROUP, 2, term, new byte[] {9}));
            var stale = assertThrows(IllegalArgumentException.class,
                    () -> n.snaps.install(new LogSnapshot(GROUP, 1, term, new byte[] {8})));
            assertTrue(stale.getMessage().contains("stale"), stale.getMessage());
            var same = assertThrows(IllegalArgumentException.class,
                    () -> n.snaps.install(new LogSnapshot(GROUP, 2, term, new byte[] {8})));
            assertTrue(same.getMessage().contains("stale"), same.getMessage());
            var conflict = assertThrows(IllegalArgumentException.class,
                    () -> n.snaps.install(new LogSnapshot(GROUP, commit, term + 7, new byte[] {7})));
            assertTrue(conflict.getMessage().contains("conflicts"), conflict.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> n.snaps.install(new LogSnapshot(GROUP, commit, term, new byte[17])), "over maxSnapshotBytes");
            var good = new LogSnapshot(GROUP, commit, term, new byte[] {6});
            assertThrows(IllegalArgumentException.class, () -> n.snaps.install(good, good.crc32c() ^ 1),
                    "bad CRC refused");
            assertEquals(2L, storage.loadSnapshot(GROUP, 16).lastIncludedIndex(), "refusals changed nothing");
            n.snaps.install(good, good.crc32c());
            assertEquals(commit, storage.loadSnapshot(GROUP, 16).lastIncludedIndex(), "durable before the delete");
        }
    }

    @Test
    void oversizedTakerImageIsSkippedAndCounted() throws Exception {
        try (var n = new Node(new MemoryLogStorage(), opts().withMaxSnapshotBytes(8))) {
            n.snaps.setSnapshotTaker((group, index) -> new byte[9]);
            n.append("a");
            var failure = assertThrows(IllegalStateException.class, () -> n.snaps.take(n.group.commitIndex()));
            assertTrue(String.valueOf(failure.getCause()).contains("maxSnapshotBytes"), String.valueOf(failure));
            n.snaps.setSnapshotTaker((group, index) -> new byte[8]);
            assertEquals(8, n.snaps.take(n.group.commitIndex()).data().length, "at the limit is fine");
            // The taker task fails take()'s future before it counts the skip under the group lock, so
            // reading the counter straight after the throw races the delivery thread. Takers run in
            // order on that one thread: once the second take() has returned, the first task is done.
            assertEquals(1L, n.node.skippedSnapshots(GROUP), "the oversized image is counted, the good one is not");
        }
    }
}
