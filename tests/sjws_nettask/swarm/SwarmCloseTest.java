package sjws_nettask.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.delivery.LeaseResult;

/**
 * #181: {@link Swarm#close} ends every thread the swarm started (the reply poller, the ack executor, the stream shard
 * reader, the stream sweep, its lease keepers) before it returns, is idempotent and safe against concurrent work, and
 * never leaves an ack future hanging.
 */
@Timeout(120)
class SwarmCloseTest {

    @TempDir Path dir;

    static final List<String> PREFIXES = List.of("sjws-reply-ack", "sjws-stream-", "swarm-");
    static final byte[] BODY = "x".getBytes(StandardCharsets.UTF_8);

    /** Live threads named like the ones a swarm starts (literal names: the test also runs against the old code). */
    static Set<Thread> swarmThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && PREFIXES.stream().anyMatch(t.getName()::startsWith))
                .collect(Collectors.toSet());
    }

    static List<String> names(Set<Thread> ts) {
        return ts.stream().map(Thread::getName).sorted().toList();
    }

    /** The given threads that are still alive (after a short grace for an executor thread that just returned). */
    static List<String> stillAlive(Set<Thread> ts) throws InterruptedException {
        for (var t : ts) {
            t.join(500);
        }
        return names(ts.stream().filter(Thread::isAlive).collect(Collectors.toSet()));
    }

    /** A swarm with every thread it can own running: a call answered (poller + ack), streams, sweep, a keeper. */
    static Swarm busy(Path d, String id) throws Exception {
        var s = Swarm.open(d, id, 1);
        var streams = s.enableStreams(TokenStreams.Config.DEFAULT);
        new SwarmHttp(s, 4, StreamHttpTest.BY_HEADER); // schedules the stream sweep
        s.leaseKeeper(5_000, (t, r) -> { }).start(1_000);
        var call = s.replies().call("p", "text/plain", BODY, TaskEnvelope.NO_DEADLINE, "");
        var task = s.tasks().claim("p", "w", 1, 5_000).get(0);
        assertEquals(LeaseResult.OK, s.tasks().reply(task, 9, 1, "text/plain", BODY).get(10, TimeUnit.SECONDS));
        call.get(10, TimeUnit.SECONDS);
        streams.open(new byte[16]); // starts the shard reader
        return s;
    }

    @Test
    void closeEndsEverySwarmThreadBeforeItReturns() throws Exception {
        var before = swarmThreads();
        var s = busy(this.dir, "n1");
        var running = swarmThreads();
        var started = new java.util.HashSet<>(running);
        started.removeAll(before);
        var all = names(running);
        for (var expected : List.of("sjws-reply-ack", "sjws-stream-sweep", "sjws-stream-n1",
                "swarm-lease-keeper", "swarm-reply.n1")) {
            assertTrue(all.stream().anyMatch(n -> n.startsWith(expected)), expected + " not running: " + all);
        }
        s.close();
        // Every swarm-named thread that started with this swarm ends with close. The static ack executor and sweeper
        // of the old code lived on (and were shared by every later swarm of the process). Threads that were already
        // running belong to other swarms of this JVM (other test classes' unclosed task services).
        assertEquals(List.of(), stillAlive(started), "threads left after Swarm.close()");
        s.close(); // idempotent
        assertTrue(s.topics().closed());
        assertTrue(started.size() >= 3, "this swarm's own threads: " + names(started));
    }

    @Test
    void openCloseLoopDoesNotGrowThreads() throws Exception {
        var before = swarmThreads();
        for (int i = 0; i < 100; i++) {
            busy(this.dir.resolve("s" + i), "n" + i).close();
        }
        var after = swarmThreads();
        after.removeAll(before);
        assertEquals(List.of(), stillAlive(after), "threads left after 100 open/close cycles");
    }

    /** Acks racing close: every reply future completes or fails, none hangs; a second, concurrent close is safe. */
    @Test
    void acksInFlightAtCloseCompleteOrFail() throws Exception {
        var before = swarmThreads();
        var s = Swarm.open(this.dir, "n1", 1);
        int n = 2_000;
        for (int i = 0; i < n; i++) {
            s.tasks().submit("p", 1, i + 1, TaskServiceTest.env("t" + i, "reply.n1", TaskEnvelope.NO_DEADLINE));
        }
        var tasks = new ArrayList<Task>();
        while (tasks.size() < n) {
            tasks.addAll(s.tasks().claim("p", "w", 256, 60_000));
        }
        var futures = new java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<LeaseResult>>();
        var half = new CountDownLatch(1);
        var replier = Thread.ofPlatform().name("swarmclosetest-replier").start(() -> {
            long seq = 1;
            for (var t : tasks) {
                try {
                    futures.add(s.tasks().reply(t, 9, seq++, "text/plain", BODY));
                } catch (RuntimeException e) {
                    futures.add(CompletableFuture.failedFuture(e)); // refused synchronously: the swarm is closing
                }
                if (futures.size() == n / 4) {
                    half.countDown();
                }
            }
        });
        assertTrue(half.await(30, TimeUnit.SECONDS));
        var second = Thread.ofPlatform().name("swarmclosetest-closer").start(s::close);
        s.close();
        second.join(30_000);
        replier.join(30_000);
        assertTrue(!second.isAlive() && !replier.isAlive());
        int ok = 0, failed = 0;
        for (var f : futures) {
            try {
                f.get(10, TimeUnit.SECONDS); // a hang is a TimeoutException: the test fails
                ok++;
            } catch (java.util.concurrent.ExecutionException e) {
                failed++;
            }
        }
        assertEquals(n, ok + failed);
        assertTrue(ok > 0, "some acks ran before close");
        var left = swarmThreads();
        left.removeAll(before);
        assertEquals(List.of(), stillAlive(left), "a thread outlived close");
    }

    /**
     * Codex P2: deregistration must not use tryLock. Holding {@code lifecycle} (as register or close does)
     * while the caller closes a keeper used to skip the remove; the closed keeper stayed in {@code onClose}
     * until swarm close. Asserts the hook is gone and the keeper thread is dead even under that contention.
     */
    @Test
    void keeperCloseDeregistersEvenWhenLifecycleLockIsHeld() throws Exception {
        var before = swarmThreads();
        var s = Swarm.open(this.dir, "n-p2", 1);
        var k = s.leaseKeeper(5_000, (t, r) -> { }).start(1_000);
        assertTrue(closeHookCount(s) >= 1, "keeper registered a close hook");

        var lifecycle = lifecycleLock(s);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Thread.ofPlatform().name("swarmclosetest-hold-lifecycle").start(() -> {
            lifecycle.lock();
            try {
                held.countDown();
                assertTrue(release.await(30, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lifecycle.unlock();
            }
        });
        assertTrue(held.await(10, TimeUnit.SECONDS), "holder took lifecycle");

        k.close(); // removeOnClose must succeed even though lifecycle is held
        assertEquals(0, closeHookCount(s), "closed keeper must leave onClose even under lifecycle contention");
        var keeperThreads = swarmThreads().stream()
                .filter(t -> t.getName().startsWith("swarm-lease-keeper"))
                .filter(t -> !before.contains(t))
                .collect(Collectors.toSet());
        assertEquals(List.of(), stillAlive(keeperThreads), "keeper thread must end on close");

        release.countDown();
        holder.join(10_000);
        assertTrue(!holder.isAlive());
        s.close();
        var left = swarmThreads();
        left.removeAll(before);
        assertEquals(List.of(), stillAlive(left), "threads left after Swarm.close()");
    }

    static ReentrantLock lifecycleLock(Swarm s) throws Exception {
        Field f = Swarm.class.getDeclaredField("lifecycle");
        f.setAccessible(true);
        return (ReentrantLock) f.get(s);
    }

    static int closeHookCount(Swarm s) throws Exception {
        Field f = Swarm.class.getDeclaredField("onClose");
        f.setAccessible(true);
        return ((java.util.Set<?>) f.get(s)).size();
    }

}
