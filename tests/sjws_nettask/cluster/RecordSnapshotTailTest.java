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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * #270: an application-built snapshot ({@link SnapshotControl#recordSnapshot}) of applied state, a truncation bound
 * that keeps a log tail, and the leader's choice between the tail and the snapshot for a lagging follower.
 */
@Timeout(30)
class RecordSnapshotTailTest {

    private static final GroupId GROUP = new GroupId("msg/tail");
    @TempDir Path dir;

    private static Member member(int wire) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"), new InetSocketAddress("127.0.0.1", 22_000 + wire),
                1L, MemberState.ALIVE, false);
    }

    private static GroupOptions opts() {
        return new GroupOptions(Durability.RELAXED, 1 << 20, Duration.ofMillis(100), Duration.ofMillis(40), 1)
                .withoutAutoSnapshot();
    }

    private static final class Recorder implements GroupListener {
        final List<String> committed = new CopyOnWriteArrayList<>();
        final List<LogSnapshot> restored = new CopyOnWriteArrayList<>();

        @Override
        public void onCommitted(CommittedEntry entry) {
            committed.add(StandardCharsets.UTF_8.decode(entry.entry()).toString());
        }

        @Override
        public void onSnapshotRestored(LogSnapshot snapshot) {
            restored.add(snapshot);
        }
    }

    final FakeClock clock = new FakeClock(0L);
    final SimTransport net = new SimTransport();
    final List<ReplicatedNode> nodes = new ArrayList<>();
    final List<Recorder> recorders = new ArrayList<>();
    final List<FileLogStorage> logs = new ArrayList<>();

    void rounds(int n) {
        for (int i = 0; i < n; i++) {
            clock.advance(Duration.ofMillis(40));
            ReplicatedNode.driveAll(nodes);
        }
    }

    ReplicatedNode start() throws Exception {
        var members = List.of(member(1), member(2), member(3));
        var voters = new LinkedHashSet<NodeId>();
        members.forEach(m -> voters.add(m.id()));
        for (var m : members) {
            var log = new FileLogStorage(dir.resolve(m.id().name()), 512);
            var rec = new Recorder();
            var node = ReplicatedNode.open(m.id(), members, voters, QuorumPolicy.majority(), clock, log, net);
            node.openGroup(GROUP, opts(), rec);
            nodes.add(node);
            recorders.add(rec);
            logs.add(log);
        }
        rounds(5);
        for (var n : nodes) if (n.openGroup(GROUP, opts(), null).isLeader()) return n;
        throw new AssertionError("no leader");
    }

    long append(ReplicatedNode leader, int from, int to) throws Exception {
        long last = 0;
        for (int i = from; i < to; i++) {
            var f = leader.append(GROUP, List.of(ByteBuffer.wrap(("e" + i).getBytes(StandardCharsets.UTF_8))));
            ReplicatedNode.driveAll(nodes);
            last = f.get().index();
        }
        rounds(2);
        return last;
    }

    void isolate(ReplicatedNode lagging, boolean cut) {
        for (var n : nodes) {
            if (n != lagging) {
                if (cut) net.cut(lagging.self(), n.self());
                else net.heal(lagging.self(), n.self());
            }
        }
    }

    /** Snapshot at {@code at}, truncate through {@code at - tail}. */
    void snapshotAndTruncate(ReplicatedNode leader, long at, long tail, byte[] image) throws Exception {
        var g = leader.openGroup(GROUP, opts(), null);
        var snaps = leader.snapshots(GROUP);
        snaps.recordSnapshot(new LogSnapshot(GROUP, at, g.term(), image));
        snaps.setTruncationBound((group, snapIndex) -> snapIndex - tail);
        assertEquals(at - tail, snaps.truncateToBound(s -> null).get());
    }

    @Test
    void briefLagCatchesUpFromTheTailAndLongLagFromTheSnapshot() throws Exception {
        var leader = start();
        int li = nodes.indexOf(leader);
        var brief = nodes.get((li + 1) % 3);
        append(leader, 0, 5);
        isolate(brief, true);
        long last = append(leader, 5, 40);
        long behind = logs.get(nodes.indexOf(brief)).lastIndex(GROUP);
        assertTrue(behind > 3 && behind < last - 30, "the isolated follower missed the appends: " + behind);
        var image = "app-image".getBytes(StandardCharsets.UTF_8);
        snapshotAndTruncate(leader, last, last - behind + 1, image); // keeps the follower's last entry and the rest
        isolate(brief, false);
        rounds(20);
        assertEquals(0L, leader.snapshotsSent(GROUP), "caught up from the log tail");
        assertEquals(last, logs.get(nodes.indexOf(brief)).lastIndex(GROUP));
        assertTrue(recorders.get(nodes.indexOf(brief)).restored.isEmpty());
        assertTrue(recorders.get(nodes.indexOf(brief)).committed.contains("e39"));

        // Now fall behind the truncated prefix: only the recorded image can bring this follower back.
        isolate(brief, true);
        long more = append(leader, 40, 120);
        var image2 = "app-image-2".getBytes(StandardCharsets.UTF_8);
        snapshotAndTruncate(leader, more, 10, image2);
        assertTrue(logs.get(li).sealedFiles(GROUP) <= 2, "the leader's log stays bounded: sealed files unlinked");
        isolate(brief, false);
        rounds(30);
        assertEquals(1L, leader.snapshotsSent(GROUP));
        var got = recorders.get(nodes.indexOf(brief)).restored;
        assertEquals(1, got.size());
        assertEquals(more, got.get(0).lastIncludedIndex());
        assertArrayEquals(image2, got.get(0).data(), "the application's own image, not the built-in one");
    }

    @Test
    void recordSnapshotRefusesWhatItCannotStandBehind() throws Exception {
        var leader = start();
        long last = append(leader, 0, 10);
        var g = leader.openGroup(GROUP, opts(), null);
        var snaps = leader.snapshots(GROUP);
        assertThrows(IllegalArgumentException.class,
                () -> snaps.recordSnapshot(new LogSnapshot(GROUP, last + 5, g.term(), new byte[1])), "past applied");
        assertThrows(IllegalArgumentException.class,
                () -> snaps.recordSnapshot(new LogSnapshot(GROUP, last, g.term() + 7, new byte[1])), "wrong term");
        assertThrows(IllegalArgumentException.class,
                () -> snaps.recordSnapshot(new LogSnapshot(new GroupId("msg/other"), last, g.term(), new byte[1])));
        assertThrows(IllegalArgumentException.class, () -> snaps.recordSnapshot(
                new LogSnapshot(GROUP, last, g.term(), new byte[GroupOptions.DEFAULT_MAX_SNAPSHOT_BYTES + 1])));
        snaps.recordSnapshot(new LogSnapshot(GROUP, last - 2, g.term(), "x".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> snaps.recordSnapshot(new LogSnapshot(GROUP, last - 3, g.term(), new byte[1])), "stale");
        // Durable: a restart restores it and delivers only the entries after it.
        int li = nodes.indexOf(leader);
        var log = logs.get(li);
        var loaded = log.loadSnapshot(GROUP, 1 << 20);
        assertNotNull(loaded);
        assertEquals(last - 2, loaded.lastIncludedIndex());
        assertArrayEquals("x".getBytes(StandardCharsets.UTF_8), loaded.data());
    }
}
