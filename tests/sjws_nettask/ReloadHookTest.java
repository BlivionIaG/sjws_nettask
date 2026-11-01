package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Signal tests are ordered: delivery first (no restore), then restore-over-ignore, then dispatch. */
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
            boolean ignoredBefore = sighupIgnored();
            int restoresBefore = ReloadHook.RESTORE_ATTEMPTS.get();
            org.junit.jupiter.api.Assumptions.assumeTrue(ReloadHook.installSighup(),
                    "SIGHUP is already ignored and restore failed, or sun.misc.Signal is hidden");
            if (!ignoredBefore) {
                assertEquals(restoresBefore, ReloadHook.RESTORE_ATTEMPTS.get(),
                        "FFM restore runs only when HotSpot returned SIG_IGN");
            }
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
            int restoresBefore = ReloadHook.RESTORE_ATTEMPTS.get();
            assertTrue(ReloadHook.installSighup(),
                    "installSighup must clear an inherited SIG_IGN then attach ReloadHook");
            assertTrue(ReloadHook.RESTORE_ATTEMPTS.get() > restoresBefore,
                    "inherited SIG_IGN must trigger restoreDefaultHangup");
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


    @Test
    @Order(6)
    void restoreFailureWarnUsesSystemLogger() {
        var previous = ReloadHook.LOG;
        var seen = new java.util.ArrayList<String>();
        ReloadHook.LOG = new System.Logger() {
            @Override
            public String getName() {
                return "test.reloadhook";
            }

            @Override
            public boolean isLoggable(Level level) {
                return true;
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
                seen.add(level + "|" + msg);
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String format, Object... params) {
                seen.add(level + "|" + format);
            }
        };
        try {
            ReloadHook.warnRestoreFailed(new IllegalStateException("boom"));
            assertEquals(1, seen.size(), seen.toString());
            assertTrue(seen.get(0).startsWith("WARNING|"), seen.get(0));
            assertTrue(seen.get(0).contains("WARN: could not restore SIGHUP default disposition: "), seen.get(0));
            assertTrue(seen.get(0).contains("boom"), seen.get(0));
        } finally {
            ReloadHook.LOG = previous;
        }
    }

    /** SIGHUP is signal 1, the least significant bit of SigIgn. */
    private static boolean sighupIgnored() throws Exception {
        var status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status)) {
            return false;
        }
        for (var line : Files.readAllLines(status)) {
            if (line.startsWith("SigIgn:")) {
                var hex = line.substring("SigIgn:".length()).trim();
                return new java.math.BigInteger(hex, 16).testBit(0);
            }
        }
        return false;
    }
}
