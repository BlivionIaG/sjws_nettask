package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Signal tests are ordered: delivery first, then restore-over-ignore, then dispatch. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReloadHookTest {

    @Test
    @Order(1)
    void aThrowingHookDoesNotStopTheNextAndUnregisterRemovesIt() throws Exception {
        var seen = new AtomicInteger();
        var first = ReloadHook.register(() -> {
            throw new IllegalStateException("boom");
        });
        var second = ReloadHook.register(seen::incrementAndGet);
        ReloadHook.run();
        assertEquals(1, seen.get());
        assertDoesNotThrow(() -> first.close());
        second.close();
        ReloadHook.run();
        assertEquals(1, seen.get(), "unregistered hooks do not run");
        assertDoesNotThrow(() -> second.close());
    }

    @Test
    @Order(2)
    void registerRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> ReloadHook.register(null));
    }

    @Test
    @Order(3)
    @EnabledOnOs(OS.LINUX)
    void sighupRunsTheHookWhenTheJdkExposesIt() throws Exception {
        var seen = new AtomicInteger();
        var hook = ReloadHook.register(seen::incrementAndGet);
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(ReloadHook.installSighup(),
                    "SIGHUP is already ignored, or sun.misc.Signal is hidden");
            var pid = ProcessHandle.current().pid();
            var kill = new ProcessBuilder(TestCommands.resolve("kill"), "-HUP", Long.toString(pid)).start();
            assertEquals(0, kill.waitFor());
            var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (seen.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(seen.get() >= 1);
        } finally {
            hook.close();
        }
    }

    @Test
    @Order(4)
    @EnabledOnOs(OS.LINUX)
    void installSighupRestoresAnIgnoredHangup() throws Exception {
        // CI runners and a parent with no controlling terminal often leave SIGHUP ignored.
        // The daemon child must still install reload (systemd ExecReload sends hangup).
        assertTrue(Daemon.ignoreSignal("HUP"));
        var seen = new AtomicInteger();
        var hook = ReloadHook.register(seen::incrementAndGet);
        try {
            assertTrue(ReloadHook.installSighup(),
                    "installSighup must clear an inherited SIG_IGN then attach ReloadHook");
            var pid = ProcessHandle.current().pid();
            var kill = new ProcessBuilder(TestCommands.resolve("kill"), "-HUP", Long.toString(pid)).start();
            assertEquals(0, kill.waitFor());
            var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (seen.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(seen.get() >= 1, "SIGHUP must run the hook after restoring SIG_DFL");
        } finally {
            hook.close();
        }
    }

    @Test
    @Order(5)
    void signalDispatchRunsHooksOnlyForHandle() throws Exception {
        var seen = new AtomicInteger();
        var hook = ReloadHook.register(seen::incrementAndGet);
        try {
            assertNull(ReloadHook.dispatchSignal("equals"));
            assertEquals(0, seen.get());
            assertNull(ReloadHook.dispatchSignal("handle"));
            assertEquals(1, seen.get());
            assertNull(ReloadHook.dispatchSignal(null));
            assertEquals(1, seen.get());
        } finally {
            hook.close();
        }
    }
}
