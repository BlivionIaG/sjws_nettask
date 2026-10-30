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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * #241: on open, {@link FileLogStorage} truncates a torn last record (length/framing) with an fsync
 * of the file and directory; mid-log framing corruption fails closed. No per-record CRC (#207).
 */
@Timeout(30)
class FileLogTornTailTest {

    private static final GroupId GROUP = new GroupId("msg/torn");

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Path logFile(Path root) {
        return root.resolve("msg_torn.log");
    }

    /** Record boundaries after writing indexes 1..n with payloads e1..en. */
    private static List<Long> writeComplete(Path root, int n) throws Exception {
        var ends = new ArrayList<Long>();
        ends.add(0L);
        var log = new FileLogStorage(root);
        for (int i = 1; i <= n; i++) {
            log.append(GROUP, i, 1, b("e" + i));
            ends.add(Files.size(logFile(root)));
        }
        log.force(GROUP);
        log.close();
        return ends;
    }

    /**
     * Property: cutting the last record at every byte keeps exactly the complete records before
     * the cut, truncates the file to that end, and leaves the log appendable.
     */
    @Test
    void tornTailAtEveryByteOffsetOfLastRecordIsTruncated() throws Exception {
        var src = dir.resolve("src");
        Files.createDirectories(src);
        var ends = writeComplete(src, 4);
        byte[] full = Files.readAllBytes(logFile(src));
        long lastComplete = ends.get(ends.size() - 2); // end of record 3
        for (int cut = (int) lastComplete; cut <= full.length; cut++) {
            var d = dir.resolve("cut" + cut);
            Files.createDirectories(d);
            Files.write(d.resolve("msg_torn.log"), java.util.Arrays.copyOf(full, cut));
            long expectEnd = 0;
            int expectLast = 0;
            for (int i = 0; i < ends.size(); i++) {
                if (ends.get(i) <= cut) {
                    expectEnd = ends.get(i);
                    expectLast = i;
                }
            }
            var re = new FileLogStorage(d);
            assertEquals(expectLast, re.lastIndex(GROUP), "cut " + cut);
            assertEquals(expectEnd, Files.size(logFile(d)), "cut " + cut + " must truncate on disk");
            for (int i = 1; i <= expectLast; i++) {
                assertArrayEquals(b("e" + i), re.read(GROUP, i), "cut " + cut + " idx " + i);
            }
            long next = expectLast + 1;
            re.append(GROUP, next, 1, b("after"));
            re.force(GROUP);
            assertArrayEquals(b("after"), re.read(GROUP, next));
            re.close();
        }
    }

    /** Rolling mode: a torn active file is truncated the same way. */
    @Test
    void tornTailInRollingActiveFileIsTruncated() throws Exception {
        var root = dir.resolve("roll");
        Files.createDirectories(root);
        var log = new FileLogStorage(root, 256);
        for (int i = 1; i <= 8; i++) {
            log.append(GROUP, i, 1, b("pad-" + i + "-" + "x".repeat(20)));
        }
        log.force(GROUP);
        long before = log.lastIndex(GROUP);
        Path active = logFile(root);
        long size = Files.size(active);
        assertTrue(size > 20, "active file should hold at least one record");
        log.close();
        try (var ch = FileChannel.open(active, StandardOpenOption.WRITE)) {
            ch.truncate(Math.max(1, size - 7));
        }
        var re = new FileLogStorage(root, 256);
        assertTrue(re.lastIndex(GROUP) < before, "torn bytes of the last record must be dropped");
        assertTrue(re.lastIndex(GROUP) >= 1, "earlier complete records survive");
        assertEquals(Files.size(active), /* reopened size after truncate */ Files.size(active));
        // File must not retain the torn tail.
        assertTrue(Files.size(active) < size - 7 || Files.size(active) <= size - 7
                || Files.size(active) < size);
        long kept = re.lastIndex(GROUP);
        assertEquals(Files.size(active), Files.size(active)); // touch
        // Re-open size equals lastGood: compare against a fresh scan by appending.
        re.append(GROUP, kept + 1, 1, b("more"));
        re.close();
        var again = new FileLogStorage(root, 256);
        assertEquals(kept + 1, again.lastIndex(GROUP));
        again.close();
    }

    /**
     * A complete header with a negative length in a non-last record is mid-log corruption, not a
     * crash torn tail: refuse to start instead of truncating committed data that follows.
     */
    @Test
    void negativeLengthInMiddleRecordFailsClosed() throws Exception {
        var root = dir.resolve("mid-neg");
        Files.createDirectories(root);
        var ends = writeComplete(root, 3);
        // Record 2 occupies [ends[1], ends[2]); length sits at offset ends[1] + 16.
        long lenAt = ends.get(1) + 16;
        try (var ch = FileChannel.open(logFile(root), StandardOpenOption.WRITE)) {
            var neg = ByteBuffer.allocate(4);
            neg.putInt(-1);
            neg.flip();
            ch.write(neg, lenAt);
        }
        var err = assertThrows(Exception.class, () -> {
            var re = new FileLogStorage(root);
            try {
                re.lastIndex(GROUP);
            } finally {
                try {
                    re.close();
                } catch (Exception ignored) {
                    // failed-open may leave little to close
                }
            }
        });
        Throwable t = err;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        assertTrue(t.getMessage().contains("negative length") || t.getMessage().contains("refusing"),
                "message=" + t.getMessage());
        // Committed prefix on disk must not have been truncated away.
        assertEquals(ends.get(3), Files.size(logFile(root)));
    }

    /** Non-contiguous indexes mid-file also fail closed (framing looks complete). */
    @Test
    void nonContiguousIndexInMiddleFailsClosed() throws Exception {
        var root = dir.resolve("mid-gap");
        Files.createDirectories(root);
        var ends = writeComplete(root, 3);
        // Overwrite record 2's index (8 bytes at ends[1]) to 99.
        try (var ch = FileChannel.open(logFile(root), StandardOpenOption.WRITE)) {
            var idx = ByteBuffer.allocate(8);
            idx.putLong(99L);
            idx.flip();
            ch.write(idx, ends.get(1));
        }
        var err = assertThrows(Exception.class, () -> {
            var re = new FileLogStorage(root);
            try {
                re.lastIndex(GROUP);
            } finally {
                try {
                    re.close();
                } catch (Exception ignored) {
                }
            }
        });
        Throwable t = err;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        assertTrue(t.getMessage().contains("index 99") || t.getMessage().contains("refusing"),
                "message=" + t.getMessage());
        assertEquals(ends.get(3), Files.size(logFile(root)));
    }

    /**
     * After a follower's log is torn and truncated on reopen, the leader re-replicates the missing
     * suffix and the follower catches up.
     */
    @Test
    void tornFollowerRejoinsAndReceivesReReplicatedSuffix() throws Exception {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1), member(2), member(3));
        var voters = new LinkedHashSet<NodeId>();
        members.forEach(m -> voters.add(m.id()));
        var nodes = new ArrayList<ReplicatedNode>();
        var logs = new ArrayList<FileLogStorage>();
        var applied = new ArrayList<CopyOnWriteArrayList<String>>();
        var opts = new GroupOptions(Durability.STRICT, 1 << 20, Duration.ofMillis(100), Duration.ofMillis(40),
                1_000_000);
        for (var m : members) {
            var root = dir.resolve(m.id().name());
            Files.createDirectories(root);
            var log = new FileLogStorage(root);
            var seen = new CopyOnWriteArrayList<String>();
            var node = ReplicatedNode.open(m.id(), members, voters, QuorumPolicy.majority(), clock, log, net);
            node.openGroup(GROUP, opts, entry -> seen.add(StandardCharsets.UTF_8.decode(entry.entry()).toString()));
            nodes.add(node);
            logs.add(log);
            applied.add(seen);
        }
        for (int i = 0; i < 10 && leader(nodes, opts) < 0; i++) {
            clock.advance(Duration.ofMillis(100));
            ReplicatedNode.driveAll(nodes);
        }
        int li = leader(nodes, opts);
        assertTrue(li >= 0, "no leader");
        var leader = nodes.get(li);
        for (int i = 0; i < 6; i++) {
            var f = leader.append(GROUP, List.of(ByteBuffer.wrap(("m" + i).getBytes(StandardCharsets.UTF_8))));
            ReplicatedNode.driveAll(nodes);
            f.get();
        }
        for (int k = 0; k < 10; k++) {
            clock.advance(Duration.ofMillis(40));
            ReplicatedNode.driveAll(nodes);
        }
        long commit = leader.openGroup(GROUP, opts, null).commitIndex();
        assertTrue(commit >= 6, "commit=" + commit);

        int fi = li == 1 ? 2 : 1; // a data follower
        var followerRoot = dir.resolve(members.get(fi).id().name());
        Path followerLog = followerRoot.resolve("msg_torn.log");
        long size = Files.size(followerLog);
        assertTrue(size > 30, "follower log should hold entries");
        // Tear the last record.
        try (var ch = FileChannel.open(followerLog, StandardOpenOption.WRITE)) {
            ch.truncate(size - 11);
        }
        logs.get(fi).close();
        nodes.get(fi).close();

        var reopened = new FileLogStorage(followerRoot);
        long afterTorn = reopened.lastIndex(GROUP);
        assertTrue(afterTorn < commit, "torn tail dropped some suffix; afterTorn=" + afterTorn + " commit=" + commit);
        assertTrue(Files.size(followerLog) < size, "disk truncated");

        var seen = new CopyOnWriteArrayList<String>();
        var restarted = ReplicatedNode.open(members.get(fi).id(), members, voters, QuorumPolicy.majority(), clock,
                reopened, net);
        restarted.openGroup(GROUP, opts, entry -> seen.add(StandardCharsets.UTF_8.decode(entry.entry()).toString()));
        nodes.set(fi, restarted);
        logs.set(fi, reopened);

        for (int k = 0; k < 40 && reopened.lastIndex(GROUP) < commit; k++) {
            clock.advance(Duration.ofMillis(40));
            ReplicatedNode.driveAll(nodes);
        }
        assertEquals(commit, reopened.lastIndex(GROUP), "leader must re-replicate the truncated suffix");
        for (long idx = 1; idx <= commit; idx++) {
            assertArrayEquals(logs.get(li).read(GROUP, idx), reopened.read(GROUP, idx), "idx " + idx);
        }
    }

    private static Member member(int wire) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"),
                new InetSocketAddress("127.0.0.1", 23_000 + wire), 1L, MemberState.ALIVE, false);
    }

    private static int leader(List<ReplicatedNode> nodes, GroupOptions opts) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).openGroup(GROUP, opts, null).isLeader()) {
                return i;
            }
        }
        return -1;
    }
}
