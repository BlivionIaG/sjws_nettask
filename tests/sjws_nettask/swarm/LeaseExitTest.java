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
package sjws_nettask.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.messaging.delivery.LeaseResult;
import sjws_nettask.messaging.delivery.RetryPolicy;

/** S2a exit tests for the lease features shipped in S1 (docs/design/agent-swarm.md §12, §13). Injected clock. */
class LeaseExitTest {

    static final long VISIBILITY = 1_000;

    @TempDir
    Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    Topics topics;
    TaskService tasks;

    @BeforeEach
    void open() {
        this.topics = new Topics(this.dir, new RetryPolicy(5, 0, 0), this.now::get);
        this.tasks = new TaskService(this.topics);
    }

    @AfterEach
    void close() {
        this.topics.close();
    }

    @Test
    void anExpiredLeaseIsRedeliveredWithinVisibilityPlus10ms() {
        this.tasks.submit("p", 1, 1, TaskServiceTest.env("x", "", TaskEnvelope.NO_DEADLINE));
        var first = this.tasks.claim("p", "w1", 1, VISIBILITY).get(0);
        this.now.addAndGet(VISIBILITY - 1);
        assertTrue(this.tasks.claim("p", "w2", 1, VISIBILITY).isEmpty(), "not before the lease ends");
        this.now.addAndGet(1 + 10);
        var again = this.tasks.claim("p", "w2", 1, VISIBILITY);
        assertEquals(1, again.size(), "redelivered by visibility + 10 ms");
        assertEquals(first.offset(), again.get(0).offset());
        assertEquals(first.attempt() + 1, again.get(0).attempt());
    }

    @Test
    void aStaleWorkerIsRefusedAfterTheNewHolderExtends() {
        this.tasks.submit("p", 1, 1, TaskServiceTest.env("x", "", TaskEnvelope.NO_DEADLINE));
        var stale = this.tasks.claim("p", "w1", 1, VISIBILITY).get(0);
        this.now.addAndGet(VISIBILITY + 1);
        var fresh = this.tasks.claim("p", "w2", 1, VISIBILITY).get(0);
        assertEquals(java.util.List.of(LeaseResult.OK), this.tasks.extend("p", new long[] {fresh.offset()},
                new int[] {fresh.attempt()}, VISIBILITY));
        // the stale holder can neither extend nor settle the new attempt
        assertEquals(java.util.List.of(LeaseResult.STALE_ATTEMPT), this.tasks.extend("p",
                new long[] {stale.offset()}, new int[] {stale.attempt()}, VISIBILITY));
        assertEquals(LeaseResult.STALE_ATTEMPT, this.tasks.ack(stale));
        assertEquals(LeaseResult.STALE_ATTEMPT, this.tasks.nack(stale, 0));
        assertEquals(LeaseResult.STALE_ATTEMPT, this.tasks.reject(stale, "stale"));
        // the extended lease outlives the original deadline and still belongs to the new holder
        this.now.addAndGet(VISIBILITY - 1);
        assertTrue(this.tasks.claim("p", "w3", 1, VISIBILITY).isEmpty());
        assertEquals(LeaseResult.OK, this.tasks.ack(fresh));
    }

    @Test
    void heartbeatTargetOneAppendPerPoolPerRound() {
        // swarm §13: 10k leases heartbeated every 5 s must cost <= 2k appends/s (<= 10k appends per round).
        int leases = 10_000;
        for (int i = 0; i < leases; i++) {
            this.tasks.submit("hb", 1, i + 1, TaskServiceTest.env("t", "", TaskEnvelope.NO_DEADLINE));
        }
        var held = new ArrayList<Task>();
        while (held.size() < leases) {
            held.addAll(this.tasks.claim("hb", "w", 1_000, VISIBILITY * 10));
        }
        var lost = new ArrayList<Task>();
        try (var keeper = new LeaseKeeper(this.tasks, VISIBILITY * 10, (t, r) -> lost.add(t))) {
            held.forEach(keeper::hold);
            this.now.addAndGet(5_000);
            keeper.tick();
            long appendsPerRound = keeper.batches();
            assertEquals(1, appendsPerRound, "one batched extend record per pool per round");
            assertTrue(appendsPerRound / 5.0 <= 2_000, "<= 2k appends/s at a 5 s period");
            assertTrue(lost.isEmpty(), "no lease lost");
            this.now.addAndGet(VISIBILITY * 10 - 1);
            assertTrue(this.tasks.claim("hb", "w2", 1, VISIBILITY).isEmpty(), "every lease was extended");
        }
    }
}
