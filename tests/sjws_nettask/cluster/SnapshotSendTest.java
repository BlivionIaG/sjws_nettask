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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** PR-5: a data follower behind the leader's compacted log catches up through a chunked snapshot. */
@Timeout(30)
class SnapshotSendTest {

    private static final GroupId GROUP = new GroupId("cp/send");
    private static final SiteId SITE = new SiteId("local");

    private static Member member(int wire) {
        return new Member(new NodeId(wire, "n" + wire), SITE, new InetSocketAddress("127.0.0.1", 21_000 + wire), 1L,
                MemberState.ALIVE, false);
    }

    private static GroupOptions opts() {
        return new GroupOptions(Durability.STRICT, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000)
                .withoutAutoSnapshot();
    }

    private static ByteBuffer bytes(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
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

    private static void rounds(FakeClock clock, List<ReplicatedNode> nodes, int n) {
        for (int i = 0; i < n; i++) {
            clock.advance(Duration.ofMillis(40));
            ReplicatedNode.driveAll(nodes);
        }
    }

    @Test
    void laggingFollowerCatchesUpFromAChunkedSnapshotAndThenFromTheLog() throws Exception {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1), member(2), member(3));
        var voters = new LinkedHashSet<NodeId>();
        members.forEach(m -> voters.add(m.id()));
        var nodes = new ArrayList<ReplicatedNode>();
        var recorders = new ArrayList<Recorder>();
        var logs = new ArrayList<MemoryLogStorage>();
        for (var m : members) {
            var log = new MemoryLogStorage();
            var rec = new Recorder();
            var node = ReplicatedNode.open(m.id(), members, voters, QuorumPolicy.majority(), clock, log, net);
            node.openGroup(GROUP, opts(), rec);
            nodes.add(node);
            recorders.add(rec);
            logs.add(log);
        }
        rounds(clock, nodes, 5);
        ReplicatedNode leader = null;
        for (var n : nodes) {
            if (n.openGroup(GROUP, opts(), null).isLeader()) {
                leader = n;
            }
        }
        assertNotNull(leader);
        int li = nodes.indexOf(leader);
        leader.openGroup(GROUP, opts(), recorders.get(li));
        int lag = (li + 1) % 3;
        var lagging = nodes.get(lag);
        lagging.openGroup(GROUP, opts(), recorders.get(lag));
        for (var n : nodes) {
            if (n != lagging) {
                net.cut(lagging.self(), n.self());
            }
        }
        long last = 0;
        for (int i = 0; i < 20; i++) {
            var f = leader.append(GROUP, List.of(bytes("e" + i)));
            ReplicatedNode.driveAll(nodes);
            last = f.get().index();
        }
        var snaps = leader.snapshots(GROUP);
        var image = snaps.take(last);
        assertTrue(image.data().length > 20);
        snaps.truncateAndCompact(last, s -> null).get();
        leader.snapshotChunkBytes(GROUP, 7); // force many chunks
        for (var n : nodes) {
            if (n != lagging) {
                net.heal(lagging.self(), n.self());
            }
        }
        rounds(clock, nodes, 20);
        assertEquals(1L, leader.snapshotsSent(GROUP));
        assertEquals(1L, lagging.snapshotsInstalled(GROUP));
        var got = recorders.get(lag).restored;
        assertEquals(1, got.size());
        assertEquals(last, got.get(0).lastIncludedIndex());
        assertArrayEquals(image.data(), got.get(0).data());
        var after = leader.append(GROUP, List.of(bytes("tail")));
        rounds(clock, nodes, 3);
        assertEquals(last + 1, after.get().index());
        assertTrue(recorders.get(lag).committed.contains("tail"));
        assertEquals(1L, lagging.snapshotsInstalled(GROUP), "the log path, not another snapshot");
        assertEquals(logs.get(li).lastIndex(GROUP), logs.get(lag).lastIndex(GROUP));
    }

    /** A crafted leader: corrupt CRC, an oversized image and out-of-order chunks are refused. */
    @Test
    void corruptOversizedOrOutOfOrderChunksAreNotInstalled() throws Exception {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var a = member(1);
        var b = member(2);
        var members = List.of(a, b);
        var voters = new LinkedHashSet<NodeId>(List.of(a.id(), b.id()));
        var rec = new Recorder();
        var follower = ReplicatedNode.open(b.id(), members, voters, QuorumPolicy.majority(), clock,
                new MemoryLogStorage(), net);
        follower.openGroup(GROUP, opts().withMaxSnapshotBytes(64), rec);
        var replies = new CopyOnWriteArrayList<ByteBuffer>();
        var fake = net.attach(a.id(), (from, frame) -> {
            if (frame.type() == RaftGroup.SNAP_RESP) {
                replies.add(ByteBuffer.wrap(frame.body()).order(ByteOrder.BIG_ENDIAN));
            }
        });
        var image = "application-state".getBytes(StandardCharsets.UTF_8);
        int good = new LogSnapshot(GROUP, 9, 2, image).crc32c(); // covers index, term and image

        fake.send(b.id(), chunk(a, 5, 9, 2, image, good ^ 1, 0, image.length));
        follower.drive();
        assertEquals(0L, follower.snapshotsInstalled(GROUP), "bad CRC");
        var bad = replies.get(replies.size() - 1);
        assertEquals(0, bad.get(12), "not done");
        assertEquals(0, bad.getInt(21), "asks again from offset 0");

        fake.send(b.id(), chunk(a, 5, 9, 2, new byte[100], good, 0, 10));
        follower.drive();
        assertEquals(0L, follower.snapshotsInstalled(GROUP), "over maxSnapshotBytes");

        fake.send(b.id(), chunk(a, 5, 9, 2, image, good, 4, 4));
        follower.drive();
        assertEquals(0L, follower.snapshotsInstalled(GROUP), "chunk before offset 0");

        fake.send(b.id(), chunk(a, 5, 9, 2, image, good, 0, 8));
        fake.send(b.id(), chunk(a, 5, 9, 2, image, good, 8, image.length - 8));
        follower.drive();
        follower.drive();
        assertEquals(1L, follower.snapshotsInstalled(GROUP));
        assertEquals(1, rec.restored.size());
        assertArrayEquals(image, rec.restored.get(0).data());
        var done = replies.get(replies.size() - 1);
        assertEquals(1, done.get(12));
        assertEquals(9L, done.getLong(13));
    }

    private static SimTransport.Frame chunk(Member leader, long term, long index, long imageTerm, byte[] image,
            int crc32c, int offset, int len) {
        return chunk(leader, term, index, imageTerm, image, crc32c, index, offset, len);
    }

    private static SimTransport.Frame chunk(Member leader, long term, long index, long imageTerm, byte[] image,
            int crc32c, long leaderCommit, int offset, int len) {
        var body = ByteBuffer.allocate(8 + 4 + 8 + 8 + 4 + 4 + 8 + 4 + 4 + len).order(ByteOrder.BIG_ENDIAN);
        body.putLong(term);
        body.putInt(leader.id().wire());
        body.putLong(index);
        body.putLong(imageTerm);
        body.putInt(image.length);
        body.putInt(crc32c);
        body.putLong(leaderCommit);
        body.putInt(offset);
        body.putInt(len);
        body.put(image, offset, len);
        return new SimTransport.Frame(GROUP, RaftGroup.SNAP_CHUNK, body.array());
    }


    /** #288 Security B2: SNAP_CHUNK must bind the body's leader wire to the transport peer, as TIMEOUT_NOW does. */
    @Test
    void snapChunkWhoseBodyNamesAnotherPeerThanItsSenderIsIgnored() throws Exception {
        var net = new SimTransport();
        var a = member(1);
        var b = member(2);
        var c = member(3);
        var follower = ReplicatedNode.open(b.id(), List.of(a, b, c), new LinkedHashSet<>(List.of(a.id(), b.id(), c.id())),
                QuorumPolicy.majority(), new FakeClock(0L), new MemoryLogStorage(), net);
        var rec = new Recorder();
        follower.openGroup(GROUP, opts(), rec);
        var fakeA = net.attach(a.id(), (from, frame) -> { });
        var fakeC = net.attach(c.id(), (from, frame) -> { });
        var image = "state".getBytes(StandardCharsets.UTF_8);
        int crc = new LogSnapshot(GROUP, 9, 2, image).crc32c();
        // Establish term/leader via a legitimate chunk from a naming a (may install).
        fakeA.send(b.id(), chunk(a, 5, 9, 2, image, crc, 0, image.length));
        follower.drive();
        long installed = follower.snapshotsInstalled(GROUP);
        rec.restored.clear();
        // Spoof: arrives from c, body claims leader is a.
        fakeC.send(b.id(), chunk(a, 5, 11, 2, "other".getBytes(StandardCharsets.UTF_8),
                new LogSnapshot(GROUP, 11, 2, "other".getBytes(StandardCharsets.UTF_8)).crc32c(), 0,
                "other".length()));
        follower.drive();
        assertEquals(installed, follower.snapshotsInstalled(GROUP), "spoofed SNAP_CHUNK must not install");
        assertTrue(rec.restored.isEmpty(), "no onSnapshotRestored from a non-leader sender");
    }

    @Test
    void theCrcCoversIndexAndTermNotOnlyTheImage() throws Exception {
        var net = new SimTransport();
        var a = member(1);
        var b = member(2);
        var follower = ReplicatedNode.open(b.id(), List.of(a, b), new LinkedHashSet<>(List.of(a.id(), b.id())),
                QuorumPolicy.majority(), new FakeClock(0L), new MemoryLogStorage(), net);
        follower.openGroup(GROUP, opts(), new Recorder());
        var fake = net.attach(a.id(), (from, frame) -> { });
        var image = "state".getBytes(StandardCharsets.UTF_8);
        var crc = new java.util.zip.CRC32C();
        crc.update(image);
        fake.send(b.id(), chunk(a, 5, 9, 2, image, (int) crc.getValue(), 0, image.length)); // image-only CRC
        follower.drive();
        assertEquals(0L, follower.snapshotsInstalled(GROUP));
        fake.send(b.id(), chunk(a, 5, 10, 2, image, new LogSnapshot(GROUP, 9, 2, image).crc32c(), 0, image.length));
        follower.drive();
        assertEquals(0L, follower.snapshotsInstalled(GROUP));
        fake.send(b.id(), chunk(a, 5, 9, 2, image, new LogSnapshot(GROUP, 9, 2, image).crc32c(), 0, image.length));
        follower.drive();
        assertEquals(1L, follower.snapshotsInstalled(GROUP));
    }

    /** A restarted voter's gate opens only once it has the leader's commit point, not at the image index. */
    @Test
    void installingAnImageBelowTheLeadersCommitKeepsTheRestartGateClosed() throws Exception {
        for (long leaderCommit : new long[] {12, 9}) {
            var net = new SimTransport();
            var a = member(1);
            var b = member(2);
            var log = new MemoryLogStorage();
            log.append(GROUP, 1, 1, new byte[] {RaftGroup.USER, 'x'});
            var follower = ReplicatedNode.open(b.id(), List.of(a, b), new LinkedHashSet<>(List.of(a.id(), b.id())),
                    QuorumPolicy.majority(), new FakeClock(0L), log, net);
            follower.openGroup(GROUP, opts(), new Recorder());
            assertTrue(follower.recovering(GROUP));
            var fake = net.attach(a.id(), (from, frame) -> { });
            var image = "state".getBytes(StandardCharsets.UTF_8);
            fake.send(b.id(), chunk(a, 5, 9, 2, image, new LogSnapshot(GROUP, 9, 2, image).crc32c(), leaderCommit, 0,
                    image.length));
            follower.drive();
            assertEquals(1L, follower.snapshotsInstalled(GROUP));
            assertEquals(leaderCommit > 9, follower.recovering(GROUP), "leaderCommit " + leaderCommit);
        }
    }
}

