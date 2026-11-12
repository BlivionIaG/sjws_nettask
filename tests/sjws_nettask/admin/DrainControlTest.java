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
package sjws_nettask.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;

/** Drain from the admin console (#381): non-blocking, idempotent, visible, with a fake HA drain. */
@Timeout(20)
class DrainControlTest {

    @TempDir
    Path root;

    private EmbeddedServerControl control(LogBuffer log) {
        return new EmbeddedServerControl(() -> ServerConfig.builder(this.root).port(0).build(), log);
    }

    private static void await(BooleanSupplier ready) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!ready.getAsBoolean()) {
            assertTrue(System.nanoTime() < end, "condition not met");
            Thread.sleep(5);
        }
    }

    @Test
    void drainRunsInTheBackgroundIsIdempotentAndEndsStopped() throws Exception {
        var log = new LogBuffer(50);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        try (var control = control(log)) {
            control.drainer(server -> {
                entered.countDown();
                release.await();
                server.drain(); // no HA attached: closes
            });
            assertNull(control.drainStatus());
            var none = assertThrows(ServerControl.ControlException.class, control::drain);
            assertTrue(none.getMessage().contains("not running"), none.getMessage());

            control.start();
            var tui = new AdminTui(control, log, null, false);
            tui.handle("n");
            assertTrue(tui.message().contains("drain confirm"), tui.message());
            assertNull(control.drainStatus(), "n alone only explains");

            tui.handle("drain confirm");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var op = control.drainStatus();
            assertEquals(DrainOperation.State.RUNNING, op.state());
            assertEquals("drain-1: running", tui.message());
            assertSame(op, control.drain(), "a second request returns the running drain");
            assertEquals(ServerStatus.State.RUNNING, control.status().state(), "still serving while it drains");
            var screen = tui.render();
            assertTrue(screen.contains("Drain             : drain-1 DRAINING since"), screen);
            assertThrows(ServerControl.ControlException.class, control::reload);
            assertThrows(ServerControl.ControlException.class, control::start);

            release.countDown();
            await(() -> control.drainStatus().state() != DrainOperation.State.RUNNING);
            var done = control.drainStatus();
            assertEquals(DrainOperation.State.DONE, done.state());
            assertEquals("drain-1", done.id());
            assertNotNull(done.finishedAt());
            assertNull(done.error());
            assertEquals(ServerStatus.State.STOPPED, control.status().state());
            assertTrue(tui.render().contains("drain-1 DRAINED"));
            assertTrue(String.join("\n", log.tail(10)).contains("drain-1: drained"));

            control.start(); // a finished drain does not block a new start
            assertEquals(ServerStatus.State.RUNNING, control.status().state());
        }
    }

    @Test
    void failedDrainIsReportedAndTheServerIsClosed() throws Exception {
        var log = new LogBuffer(50);
        try (var control = control(log)) {
            control.drainer(server -> {
                throw new IllegalStateException("fence ‮lost[31m");
            });
            control.start();
            var op = control.drain();
            await(() -> control.drainStatus().state() != DrainOperation.State.RUNNING);
            var end = control.drainStatus();
            assertEquals(op.id(), end.id());
            assertEquals(DrainOperation.State.FAILED, end.state());
            assertEquals("fence ‮lost[31m", end.error());
            assertEquals(ServerStatus.State.STOPPED, control.status().state());
            var tui = new AdminTui(control, log, null, true);
            var screen = tui.render();
            assertTrue(screen.contains(AdminTui.RED + "FAILED" + AdminTui.RESET + " (fence ?lost?[31m)"), screen);
            assertFalse(screen.contains("‮") || screen.contains(""), screen);
        }
    }

    @Test
    void interruptedDrainFails() throws Exception {
        try (var control = control(new LogBuffer(50))) {
            control.drainer(server -> {
                throw new InterruptedException();
            });
            control.start();
            control.drain();
            await(() -> control.drainStatus().state() != DrainOperation.State.RUNNING);
            assertEquals(DrainOperation.State.FAILED, control.drainStatus().state());
            assertEquals("interrupted", control.drainStatus().error());
        }
    }

    @Test
    void stopDuringDrainEndsTheOperationAndALateFinishChangesNothing() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var log = new LogBuffer(50);
        try (var control = control(log)) {
            control.drainer(server -> {
                entered.countDown();
                try {
                    release.await();
                } finally {
                    returned.countDown();
                }
            });
            control.start();
            var op = control.drain();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            control.stop(); // the operator can still stop hard
            assertEquals(ServerStatus.State.STOPPED, control.status().state());
            var stopped = control.drainStatus();
            assertEquals(op.id(), stopped.id());
            assertEquals(DrainOperation.State.FAILED, stopped.state());
            assertEquals("stopped during drain", stopped.error());
            assertNotNull(stopped.finishedAt());

            // start and reload are no longer refused while the old drainer is still blocked
            control.start();
            assertEquals(ServerStatus.State.RUNNING, control.status().state());
            control.reload();
            assertEquals(ServerStatus.State.RUNNING, control.status().state());

            release.countDown();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            await(() -> String.join("\n", log.tail(10)).contains(op.id() + ": drainer returned after stop"));
            assertSame(stopped, control.drainStatus(), "a late finish must not overwrite the stopped operation");
            assertEquals(ServerStatus.State.RUNNING, control.status().state(), "the new server is left alone");
            control.stop();
        }
    }

    @Test
    void controlsWithoutDrainSaySo() {
        var tui = new AdminTui(new AdminTuiTest.FakeControl(), new LogBuffer(5), null, false);
        tui.handle("drain confirm");
        assertTrue(tui.message().contains("drain is not available"), tui.message());
        assertNull(new AdminTuiTest.FakeControl().drainStatus());
        assertFalse(tui.render().contains("Drain "));
    }
}
