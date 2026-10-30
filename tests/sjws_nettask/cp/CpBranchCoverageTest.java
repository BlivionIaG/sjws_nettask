package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.CommittedEntry;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.controlplane.Permission;
import sjws_nettask.controlplane.Rbac;
import sjws_nettask.controlplane.Role;

/** Store apply branches the control-plane gate still misses. */
class CpBranchCoverageTest {

    @TempDir
    Path dir;

    @Test
    void applyIgnoresEmptyUnknownAndADemotionThatWouldRemoveTheLastAdmin() throws Exception {
        var clock = new FakeClock(0);
        var keys = KeyRing.generate("online-1", 1_700_000_000_000L + 86_400_000L, 0, Long.MAX_VALUE / 2);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock)) {
            clock.advance(Duration.ofMillis(100));
            store.drive();
            assertTrue(store.leadership().leaderReady());

            commit(store, ByteBuffer.allocate(0));
            var unknown = ByteBuffer.allocate(1);
            unknown.put((byte) 9);
            unknown.flip();
            commit(store, unknown);

            assertEquals(200, store.grant("urn:sjws:admin:root", Role.VIEWER).status());
            assertTrue(store.rights().authorize(new Rbac.ClientCert("urn:sjws:admin:root"), Permission.MANAGE_OPERATORS,
                    "ops", 0).allowed(), "a record that drops the last admin is not applied");

            var bad = propose(":\n  -");
            commit(store, bad);
            assertEquals(200, store.grant("urn:sjws:admin:" + "r".repeat(80), Role.OPERATOR).status());
            assertEquals(200, store.grant("urn:sjws:admin:---", Role.VIEWER).status());
            assertEquals(200, store.grant("urn:other:root", Role.VIEWER).status());
            assertEquals(200, store.rollout(java.util.List.of("edge")).status());
            assertEquals(200, store.advance().status());
            assertEquals(200, store.advance().status());
        }
    }

    private static long nextIndex = 1;

    private static void commit(CpStore store, ByteBuffer entry) throws Exception {
        Method method = CpStore.class.getDeclaredMethod("onCommitted", CommittedEntry.class);
        method.setAccessible(true);
        method.invoke(store, new CommittedEntry(CpStore.GROUP, 1L, nextIndex++, entry.duplicate()));
    }

    private static ByteBuffer propose(String yaml) {
        var raw = yaml.getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(1 + 8 + 4 + raw.length);
        buffer.put((byte) 1);
        buffer.putLong(9);
        buffer.putInt(raw.length);
        buffer.put(raw);
        buffer.flip();
        return buffer;
    }
}
