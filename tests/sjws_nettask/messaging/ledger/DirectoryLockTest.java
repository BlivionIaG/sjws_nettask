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
package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;

/** One process per ledger directory (#236 follow-up): a second open fails fast; close or process death frees it. */
class DirectoryLockTest {

    @TempDir Path dir;

    /** Child entry point: {@code hold <dir> <topic>} opens a partition, prints "open", waits for stdin EOF;
     *  {@code try <dir> <topic>} opens once and exits 3 if the directory is locked, 0 if it opened. */
    public static void main(String[] args) throws Exception {
        try (var p = Partition.open(Path.of(args[1]), args[2], RetryPolicy.DEFAULT, System::currentTimeMillis)) {
            if (args[0].equals("hold")) {
                p.produce(0, 0, new byte[] {1});
                System.out.println("open");
                System.out.flush();
                while (System.in.read() >= 0) { /* until the parent closes stdin */ }
            }
        } catch (LedgerDirectoryLockedException e) {
            System.out.println("locked: " + e.getMessage());
            System.exit(3);
        }
    }

    private static Process child(String mode, Path dir, String topic) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), DirectoryLockTest.class.getName(),
                mode, dir.toString(), topic).redirectErrorStream(true).start();
    }

    @Test
    void secondOpenInThisJvmFailsFastAndCloseReleases() throws Exception {
        var log = SegmentedLog.open(dir);
        var e = assertThrows(LedgerDirectoryLockedException.class, () -> SegmentedLog.open(dir));
        assertEquals(dir, e.dir());
        assertTrue(e.getMessage().contains("already in use"), e.getMessage());
        log.close();
        log.close(); // idempotent
        try (var again = SegmentedLog.open(dir)) {
            assertEquals(0, again.nextOffset());
        }
    }

    @Test
    void partitionHoldsTheLockOnItsTopicAndDlqUntilClose() throws Exception {
        var p = Partition.open(dir, "t", RetryPolicy.DEFAULT, System::currentTimeMillis);
        assertThrows(LedgerDirectoryLockedException.class, () -> SegmentedLog.open(dir.resolve("t")));
        assertThrows(LedgerDirectoryLockedException.class, () -> SegmentedLog.open(dir.resolve("t" + Partition.DLQ_SUFFIX)));
        assertThrows(java.io.IOException.class, () -> Partition.open(dir, "t", RetryPolicy.DEFAULT, System::currentTimeMillis));
        // the failed second open must not have released or leaked anything: the first still works, then reopen works
        assertEquals(0, p.produce(1, 1, new byte[] {7}).offset());
        p.close();
        try (var q = Partition.open(dir, "t", RetryPolicy.DEFAULT, System::currentTimeMillis)) {
            assertEquals(1, q.produce(1, 2, new byte[] {8}).offset());
        }
    }

    @Test
    void failedOpenReleasesTheLock() throws Exception {
        var d = dir.resolve("bad");
        Files.createDirectories(d);
        Files.write(d.resolve(String.format("%020d.log", 5)), new byte[] {1, 2, 3}); // log not starting at 0, junk
        try {
            SegmentedLog.open(d).close();
        } catch (Exception ignored) {
            // whether recovery accepts it or not, the lock must be free afterwards
        }
        SegmentedLog.open(d).close();
    }

    @Test
    void secondProcessFailsFastWhileAnotherHoldsTheDirectory() throws Exception {
        var holder = child("hold", dir, "t");
        try (var in = new BufferedReader(new InputStreamReader(holder.getInputStream()))) {
            assertEquals("open", in.readLine());
            // this JVM is the second process
            var e = assertThrows(java.io.IOException.class,
                    () -> Partition.open(dir, "t", RetryPolicy.DEFAULT, System::currentTimeMillis));
            assertTrue(e instanceof LedgerDirectoryLockedException
                    || e.getCause() instanceof LedgerDirectoryLockedException, String.valueOf(e));
            // a third, separate JVM is refused too
            var other = child("try", dir, "t");
            assertTrue(other.waitFor(60, TimeUnit.SECONDS));
            assertEquals(3, other.exitValue(), new String(other.getInputStream().readAllBytes()));
            // a different topic in the same parent directory is independent
            Partition.open(dir, "u", RetryPolicy.DEFAULT, System::currentTimeMillis).close();
        } finally {
            holder.getOutputStream().close(); // clean close in the child
            assertTrue(holder.waitFor(60, TimeUnit.SECONDS));
        }
        assertEquals(0, holder.exitValue());
        try (var p = Partition.open(dir, "t", RetryPolicy.DEFAULT, System::currentTimeMillis)) {
            assertEquals(1, p.produce(0, 1, new byte[] {2}).offset());
        }
    }

    @Test
    void killedHolderLeavesNoStaleLock() throws Exception {
        var holder = child("hold", dir, "t");
        try (var in = new BufferedReader(new InputStreamReader(holder.getInputStream()))) {
            assertEquals("open", in.readLine());
            holder.destroyForcibly(); // SIGKILL: the OS drops the lock
            assertTrue(holder.waitFor(60, TimeUnit.SECONDS));
        }
        Partition.open(dir, "t", RetryPolicy.DEFAULT, System::currentTimeMillis).close();
    }
}
