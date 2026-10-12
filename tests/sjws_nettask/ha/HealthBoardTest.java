package sjws_nettask.ha;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import sjws_nettask.core.Request;

class HealthBoardTest {

    @Test
    void probesArePrecomputedAndReadinessTracksTheFlags() {
        var board = new HealthBoard(false);
        assertSame(board.health(false), board.health(false));
        assertSame(board.ready(false), board.ready(false));
        assertEquals(200, board.health(false).status());
        assertEquals(503, board.ready(false).status(), "no snapshot yet");
        assertTrue(board.health(true).headOnly());
        assertFalse(board.health(false).headOnly());

        board.setSnapshotLoaded(true);
        assertTrue(board.readyNow());
        assertEquals(200, board.ready(false).status());
        assertEquals("ok\n", new String(bytes(board.ready(false))));

        board.setDraining(true);
        assertEquals(503, board.ready(false).status());
        board.setDraining(false);
        assertEquals(200, board.ready(false).status());

        board.setRequireQuorum(true);
        board.setQuorumOk(false);
        assertEquals(503, board.ready(true).status());
        board.setQuorumOk(true);
        assertEquals(200, board.ready(false).status());

        board.setAlive(false);
        assertEquals(503, board.health(false).status());
        assertEquals("unavailable\n", new String(bytes(board.health(false))));
        board.setAlive(true);
        assertEquals(200, board.health(false).status());
    }

    @Test
    void handlerServesProbesWithoutCallingTheFileHandler() {
        var board = new HealthBoard(false);
        board.setSnapshotLoaded(true);
        var holder = new SnapshotHolder<sjws_nettask.core.Handler>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        holder.publish(new Snapshot<>(1, 1L, request -> {
            calls.incrementAndGet();
            return board.ready(false);
        }));
        var handler = new HaHandler(holder, board);
        assertEquals(200, handler.handle(new Request("GET", "/healthz")).status());
        assertEquals(200, handler.handle(new Request("GET", "/healthz?x=1")).status());
        assertEquals(200, handler.handle(new Request("HEAD", "/readyz")).status());
        assertTrue(handler.handle(new Request("HEAD", "/readyz")).headOnly());
        assertEquals(200, handler.handle(new Request("GET", "/index.html")).status());
        assertEquals(1, calls.get());
        assertTrue(HaHandler.isProbe("/healthz#frag", "/healthz"));
        assertFalse(HaHandler.isProbe("/healthz-extra", "/healthz"));
        assertFalse(HaHandler.isProbe("/health", "/healthz"));

        var unloaded = new HealthBoard(false);
        var empty = new SnapshotHolder<sjws_nettask.core.Handler>();
        assertEquals(503, new HaHandler(empty, unloaded).handle(new Request("GET", "/file")).status());
    }

    private static byte[] bytes(sjws_nettask.core.Response response) {
        var body = (sjws_nettask.core.Body.Bytes) response.body();
        var dup = body.data().duplicate();
        var out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }
}
