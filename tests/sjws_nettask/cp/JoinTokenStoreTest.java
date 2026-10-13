package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.FakeClock;

/** One-shot join tokens through the replicated CP store: burn on first use, atomic, durable. */
class JoinTokenStoreTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;

    @TempDir
    Path dir;

    private static byte[] secret(int b) {
        var s = new byte[32];
        Arrays.fill(s, (byte) b);
        return s;
    }

    private static void elect(CpStore store, FakeClock clock) {
        clock.advance(Duration.ofMillis(100));
        store.drive();
        assertTrue(store.leadership().leaderReady());
    }

    @Test
    void firstUseBurnsTheTokenAndARetryReturnsTheSameJoin() throws Exception {
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock)) {
            elect(store, clock);
            assertEquals(400, store.issueJoinToken("bad id", secret(1), NOW, TTL).status());
            assertEquals(400, store.issueJoinToken("t1", new byte[4], NOW, TTL).status());
            assertEquals(200, store.issueJoinToken("t1", secret(1), NOW, TTL).status());

            var first = store.presentJoin("edge-1", "t1", secret(1), NOW + 1);
            assertTrue(first.accepted());
            assertFalse(first.replay());
            var retry = store.presentJoin("edge-1", "t1", secret(1), NOW + 2);
            assertTrue(retry.replay(), "lost-response retry gets the recorded join");
            assertEquals(first.join(), retry.join());
            assertEquals("used", store.presentJoin("edge-2", "t1", secret(1), NOW + 3).error());
            assertEquals("token", store.presentJoin("edge-3", "t1", secret(2), NOW + 3).error());
            assertEquals("token", store.presentJoin("edge-3", "t1", null, NOW + 3).error());
            assertEquals("used",
                    store.presentJoin("edge-1", "t1", secret(1), NOW + 1 + JoinTokens.REPLAY_GRACE_MILLIS + 1).error());
        }
        // the burn is in the replicated log: a restarted store still refuses the token
        var clock2 = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock2)) {
            elect(store, clock2);
            assertEquals("used", store.presentJoin("edge-2", "t1", secret(1), NOW + 10).error());
            assertTrue(store.presentJoin("edge-1", "t1", secret(1), NOW + 10).replay());
        }
    }

    @Test
    void concurrentFirstUsesHaveExactlyOneWinner() throws Exception {
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock)) {
            elect(store, clock);
            assertEquals(200, store.issueJoinToken("race", secret(5), NOW, TTL).status());
            var tasks = new ArrayList<Callable<JoinTokens.Result>>();
            for (int i = 0; i < 16; i++) {
                var id = "edge-" + i;
                tasks.add(() -> store.presentJoin(id, "race", secret(5), NOW + 1));
            }
            int winners = 0;
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var f : pool.invokeAll(tasks)) {
                    var r = f.get();
                    if (r.accepted()) {
                        assertFalse(r.replay());
                        winners++;
                    } else {
                        assertEquals("used", r.error());
                    }
                }
            }
            assertEquals(1, winners);
        }
    }

    @Test
    void expiredAndLeaderlessAreRefused() throws Exception {
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock)) {
            assertEquals(503, store.issueJoinToken("t", secret(1), NOW, TTL).status());
            elect(store, clock);
            assertEquals(200, store.issueJoinToken("t", secret(1), NOW, TTL).status());
            assertEquals("expired", store.presentJoin("edge-1", "t", secret(1), NOW + TTL + 1).error());
        }
    }
}
