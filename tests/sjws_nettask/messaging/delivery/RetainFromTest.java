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
package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;

/** Retain-from pins (agent-graphs.md §4.3, §11.3): retention never frees history at or above the lowest pin. */
class RetainFromTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    static final long SEG = 4096;

    Partition open() throws IOException {
        var p = Partition.open(dir, "shard", new RetryPolicy(3, 100, 1_000), now::get, Durability.ASYNC, SEG);
        p.retention(new RetentionPolicy(0, 0)); // delete as soon as allowed, never compact: isolate the pin rule
        return p;
    }

    /** Produces and fully consumes n messages, waiting per append so segments seal deterministically. */
    static void churn(Partition p, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            p.produce(0, 0, ("m" + i + "y".repeat(100)).getBytes(StandardCharsets.UTF_8)).durable().get();
            for (var d : p.poll("runner", "r", 1, 1000)) assertTrue(p.ack("runner", d.offset()));
        }
    }

    @Test
    void pinHoldsHistoryThatTheDeliveryWatermarkWouldFree() throws Exception {
        try (var p = open()) {
            p.subscribe("runner", true);
            churn(p, 200);
            long checkpoint = p.snapshotNow().get() / 2; // a checkpoint floor in the middle of consumed history
            p.retainFrom("graph-runner", checkpoint);
            churn(p, 1);
            p.snapshotNow().get();
            var r = p.maintain();
            assertEquals(checkpoint, r.lowWatermark(), "the pin caps the low watermark");
            long start = diskStart(dir.resolve("shard")); // the open partition holds the directory lock
            assertTrue(start <= checkpoint, "history from the pin is still on disk: start " + start);
            assertTrue(r.deletedSegments() > 0, "below the pin the normal rules still free segments");
            // moving the pin forward frees more
            long before = p.logBytes();
            p.retainFrom("graph-runner", p.snapshotNow().get());
            assertTrue(p.maintain().deletedSegments() > 0);
            assertTrue(p.logBytes() < before);
        }
    }

    @Test
    void pinsAreDurableAndTheLowestWins() throws Exception {
        try (var p = open()) {
            p.subscribe("runner", true);
            churn(p, 100);
            assertEquals(Long.MAX_VALUE, p.retainFloor());
            p.retainFrom("a", 50);
            p.retainFrom("b", 20);
            p.retainFrom("a", 60); // moves a's pin
            p.retainFrom("a", 60); // no-op
            assertEquals(Map.of("a", 60L, "b", 20L), p.retainPins());
            assertEquals(20, p.retainFloor());
        }
        try (var q = open()) { // survives restart before the owner re-pins
            assertEquals(Map.of("a", 60L, "b", 20L), q.retainPins());
            q.snapshotNow().get();
            assertEquals(20, q.maintain().lowWatermark());
            q.releaseRetain("b");
            q.releaseRetain("missing");
            assertEquals(60, q.retainFloor());
        }
        try (var q = open()) {
            assertEquals(Map.of("a", 60L), q.retainPins());
            q.releaseRetain("a");
            assertEquals(Long.MAX_VALUE, q.retainFloor());
        }
        try (var q = open()) {
            assertEquals(Map.of(), q.retainPins());
        }
    }

    @Test
    void validationAndCorruptPinFile() throws Exception {
        try (var p = open()) {
            assertThrows(IllegalArgumentException.class, () -> p.retainFrom("", 1));
            assertThrows(IllegalArgumentException.class, () -> p.retainFrom(null, 1));
            assertThrows(IllegalArgumentException.class, () -> p.retainFrom("a=b", 1));
            assertThrows(IllegalArgumentException.class, () -> p.retainFrom("a\nb", 1));
            assertThrows(IllegalArgumentException.class, () -> p.retainFrom("a", -1));
        }
        var f = dir.resolve("shard").resolve(Partition.PINS_FILE);
        Files.writeString(f, "a=12\n\n");
        try (var p = open()) { assertEquals(Map.of("a", 12L), p.retainPins()); }
        Files.writeString(f, "garbage\n");
        assertThrows(IOException.class, this::open);
        Files.writeString(f, "a=notanumber\n");
        assertThrows(IOException.class, this::open);
    }

    /** Lowest segment base offset on disk, read from file names: the live partition holds the directory lock. */
    static long diskStart(java.nio.file.Path d) throws java.io.IOException {
        try (var s = java.nio.file.Files.list(d)) {
            return s.map(f -> f.getFileName().toString()).filter(n -> n.matches("\\d{20}\\.log"))
                    .mapToLong(n -> Long.parseLong(n.substring(0, 20))).min().orElse(0);
        }
    }
}
