package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class ReloadHookTest {

    @Test
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
    void registerRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> ReloadHook.register(null));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void sighupRunsTheHookWhenTheJdkExposesIt() throws Exception {
        var seen = new AtomicInteger();
        var hook = ReloadHook.register(seen::incrementAndGet);
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(ReloadHook.installSighup(),
                    "sun.misc.Signal is hidden from this JDK");
            var pid = ProcessHandle.current().pid();
            var kill = new ProcessBuilder("kill", "-HUP", Long.toString(pid)).start();
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
}
