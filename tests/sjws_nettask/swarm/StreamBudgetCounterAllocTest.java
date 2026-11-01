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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * S2c PR 8 counters-only: prove the hot-path metric increment allocates ~0 B/op without load.
 *
 * <p>A JFR recording under real load needs the bench lock and a queue slot — not this test.
 * {@link ThreadMXBean#getThreadAllocatedBytes(long)} around a warmed LongAdder increment is the
 * allocation proof for the counters PR.
 */
class StreamBudgetCounterAllocTest {

    private static final int WARMUP = 200_000;
    private static final int N = 1_000_000;
    /** Allow a small constant (JIT / TLAB noise); must stay well under 1 B/op. */
    private static final long MAX_TOTAL_BYTES = 1024L;

    @Test
    void nodeRefusalIncrementAllocatesNearlyNothing() {
        var bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof ThreadMXBean, "need com.sun.management.ThreadMXBean");
        var tx = (ThreadMXBean) bean;
        assumeTrue(tx.isThreadAllocatedMemorySupported(), "thread allocated-memory unsupported");
        tx.setThreadAllocatedMemoryEnabled(true);

        var budget = StreamBudget.unbounded();
        for (int i = 0; i < WARMUP; i++) {
            budget.nodeRefused();
        }

        long tid = Thread.currentThread().threadId();
        long before = tx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < N; i++) {
            budget.nodeRefused();
        }
        long after = tx.getThreadAllocatedBytes(tid);
        long allocated = after - before;
        double perOp = allocated / (double) N;
        System.out.printf(
                "StreamBudgetCounterAllocTest: N=%d allocated=%d B (%.4f B/op) limit=%d B%n",
                N, allocated, perOp, MAX_TOTAL_BYTES);
        assertTrue(allocated < MAX_TOTAL_BYTES,
                "hot-path nodeRefused() must allocate ~0 B/op; allocated=" + allocated
                        + " B over " + N + " increments (" + perOp + " B/op)");
        assertTrue(budget.nodeRefusals() >= WARMUP + N);
    }

    @Test
    void creditRefusalIncrementAllocatesNearlyNothing() {
        var bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof ThreadMXBean, "need com.sun.management.ThreadMXBean");
        var tx = (ThreadMXBean) bean;
        assumeTrue(tx.isThreadAllocatedMemorySupported(), "thread allocated-memory unsupported");
        tx.setThreadAllocatedMemoryEnabled(true);

        var credit = new StreamCredit(8, 64 * 1024L);
        for (int i = 0; i < WARMUP; i++) {
            credit.refused();
        }

        long tid = Thread.currentThread().threadId();
        long before = tx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < N; i++) {
            credit.refused();
        }
        long after = tx.getThreadAllocatedBytes(tid);
        long allocated = after - before;
        double perOp = allocated / (double) N;
        System.out.printf(
                "StreamCreditCounterAllocTest: N=%d allocated=%d B (%.4f B/op) limit=%d B%n",
                N, allocated, perOp, MAX_TOTAL_BYTES);
        assertTrue(allocated < MAX_TOTAL_BYTES,
                "hot-path refused() must allocate ~0 B/op; allocated=" + allocated
                        + " B over " + N + " increments (" + perOp + " B/op)");
        assertTrue(credit.refusals() >= WARMUP + N);
    }
}
