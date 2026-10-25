package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Concurrent step-down stress for the conditional append (#235): appender threads race leader changes driven
 * on another thread. Every batch that commits carries the term its caller expected, and no replica ever
 * applies a user entry whose Raft term differs from the term the caller checked.
 *
 * <p>The schedule is paced by the appenders, not by wall-clock luck: each leader change waits (bounded) for a
 * new append to be submitted, and after the storm the leader is left alone until an append sent in that stable
 * term commits. Without that, the driver thread could finish all 200 changes (about 40 ms on CI) before the
 * appenders got an append committed in any tenure, and the test failed with nothing committed.
 */
@Timeout(120)
class ConditionalAppendStressTest {

    private static final GroupId GROUP = new GroupId("msg/cond-stress");
    private static final GroupOptions OPTS = new GroupOptions(Durability.RELAXED, 1 << 20, Duration.ofMillis(100),
            Duration.ofMillis(40), Long.MAX_VALUE);

    /** Per leader change; 200 rounds stay well inside the class timeout even if every wait runs out. */
    private static final Duration ROUND_PACING = Duration.ofMillis(100);
    /** Wall-clock bound for an append to commit once leadership is stable; normally a few steps. */
    private static final Duration SETTLE_DEADLINE = Duration.ofSeconds(30);

    private final FakeClock clock = new FakeClock(0L);
    private final SimTransport net = new SimTransport();
    private final List<ReplicatedNode> nodes = new ArrayList<>();
    private final List<ReplicationGroup> groups = new ArrayList<>();
    // O(1) adds: a CopyOnWriteArrayList copied the whole list per committed entry, and under CPU starvation the
    // delivery thread fell so far behind that drive() spent its 5 s delivery wait on every call (class timeout).
    private final List<ConcurrentLinkedQueue<CommittedEntry>> applied = new ArrayList<>();

    private record Sent(long expected, CompletableFuture<CommittedIndex> future) {
    }

    @Test
    void appendsRacingStepDownsCommitOnlyInTheirExpectedTerm() throws Exception {
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        for (var m : members) {
            var seen = new ConcurrentLinkedQueue<CommittedEntry>();
            var node = ReplicatedNode.open(m.id(), voters, clock, new MemoryLogStorage(), net);
            groups.add(node.openGroup(GROUP, OPTS, seen::add));
            nodes.add(node);
            applied.add(seen);
        }
        for (int k = 0; k < 40 && leader() < 0; k++) {
            step();
        }
        assertTrue(leader() >= 0, "no leader elected");

        var stop = new AtomicBoolean();
        var sent = new ConcurrentLinkedQueue<Sent>();
        var sentCount = new AtomicInteger(); // ConcurrentLinkedQueue.size() is O(n); the driver polls this
        var anyCommitted = new AtomicBoolean();
        var threads = new ArrayList<Thread>();
        for (int t = 0; t < 4; t++) {
            int id = t;
            var thread = Thread.ofPlatform().name("appender-" + t).start(() -> {
                int seq = 0;
                while (!stop.get()) {
                    int l = leader();
                    if (l < 0) {
                        Thread.onSpinWait();
                        continue;
                    }
                    long expected = group(l).leaderTerm();
                    if (expected <= 0) {
                        continue;
                    }
                    var batch = new ArrayList<ByteBuffer>();
                    for (int e = 0; e < 3; e++) {
                        batch.add(ByteBuffer.wrap((expected + ":" + id + ":" + seq + ":" + e)
                                .getBytes(StandardCharsets.UTF_8)));
                    }
                    seq++;
                    // A caller acts on a term it read a moment ago; the gap lets step-downs land in between.
                    java.util.concurrent.locks.LockSupport.parkNanos(
                            java.util.concurrent.ThreadLocalRandom.current().nextLong(2_000_000));
                    var future = nodes.get(l).append(GROUP, batch, null, expected);
                    future.whenComplete((ci, error) -> {
                        if (error == null) {
                            anyCommitted.set(true);
                        }
                    });
                    sent.add(new Sent(expected, future));
                    sentCount.incrementAndGet();
                    java.util.concurrent.locks.LockSupport.parkNanos(200_000);
                }
            });
            threads.add(thread);
        }

        int changes = 0;
        int submitted = 0;
        for (int round = 0; round < 200; round++) {
            // Pace the storm on the appenders: let at least one more append in before forcing the next change, so
            // appends really race step-downs. Bounded, and not an assertion: a starved appender only weakens a round.
            submitted = awaitMoreSent(sentCount, submitted, ROUND_PACING);
            int l = leader();
            if (l >= 0) {
                int other = 1 - l;
                if (round % 2 == 0) {
                    group(l).transferLeadership(nodes.get(other).self());
                } else {
                    net.cut(nodes.get(l).self(), nodes.get(other).self());
                    net.cut(nodes.get(l).self(), nodes.get(2).self());
                    for (int k = 0; k < 30 && !group(other).isLeader(); k++) {
                        step();
                    }
                    net.heal(nodes.get(l).self(), nodes.get(other).self());
                    net.heal(nodes.get(l).self(), nodes.get(2).self());
                }
                changes++;
            }
            for (int k = 0; k < 5; k++) {
                step();
            }
        }
        // Settle: no more leader changes. Appenders keep sending in the now stable term until one of those appends
        // commits, so "something committed" no longer depends on how fast the driver thread outran them.
        boolean settled = false;
        long deadline = System.nanoTime() + SETTLE_DEADLINE.toNanos();
        while (!settled && System.nanoTime() - deadline < 0) {
            step();
            settled = anyCommitted.get();
            if (!settled) {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            }
        }
        stop.set(true);
        for (var thread : threads) {
            thread.join();
        }
        for (int k = 0; k < 60 && !sent.stream().allMatch(s -> s.future().isDone()); k++) {
            step();
        }

        var ok = new AtomicInteger();
        var refused = new AtomicInteger();
        for (var s : sent) {
            assertTrue(s.future().isDone(), "an append future never completed");
            if (s.future().isCompletedExceptionally()) {
                refused.incrementAndGet();
                continue;
            }
            var ci = s.future().join();
            assertEquals(s.expected(), ci.term(), "a batch committed outside the term its caller expected");
            ok.incrementAndGet();
        }
        assertTrue(changes >= 100, "too few leader changes: " + changes);
        assertTrue(settled, "no conditional append committed within " + SETTLE_DEADLINE + " of a stable leader");
        assertTrue(ok.get() > 0, "no conditional append ever committed");

        for (int i = 0; i < 2; i++) {
            for (var entry : applied.get(i)) {
                var copy = new byte[entry.entry().remaining()];
                entry.entry().duplicate().get(copy);
                var text = new String(copy, StandardCharsets.UTF_8);
                if (text.indexOf(':') < 0) {
                    continue;
                }
                long expected = Long.parseLong(text.substring(0, text.indexOf(':')));
                assertEquals(expected, entry.term(), "node " + i + " applied " + text + " in term " + entry.term());
            }
        }
        assertTrue(applied.get(0).size() + applied.get(1).size() > 0, "no replica applied anything");
        // Both data nodes applied the same committed sequence (one is a prefix of the other).
        var a = List.copyOf(applied.get(0));
        var b = List.copyOf(applied.get(1));
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            assertEquals(a.get(i).index(), b.get(i).index());
            assertEquals(a.get(i).term(), b.get(i).term());
        }
    }

    /** Waits until more than {@code seen} appends were submitted, or {@code max} passes; returns the new count. */
    private static int awaitMoreSent(AtomicInteger sent, int seen, Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        int now = sent.get();
        while (now <= seen && System.nanoTime() - deadline < 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            now = sent.get();
        }
        return now;
    }

    private void step() {
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
    }

    private ReplicationGroup group(int i) {
        return groups.get(i); // openGroup again would replace the listener
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
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"), new InetSocketAddress("127.0.0.1", 25_300 + wire),
                1L, MemberState.ALIVE, witness);
    }
}
