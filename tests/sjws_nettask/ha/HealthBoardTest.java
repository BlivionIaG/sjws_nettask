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
        assertEquals("unavailable\n", new String(bytes(board.ready(false))));
        assertEquals("text/plain; charset=utf-8", board.ready(false).headers().get("content-type"));
        assertEquals("no-store", board.ready(false).headers().get("cache-control"));
        assertEquals("1", board.ready(false).headers().get("retry-after"));
        assertEquals("1", board.ready(true).headers().get("retry-after"));
        assertTrue(board.health(true).headOnly());
        assertFalse(board.health(false).headOnly());
        assertEquals("no-store", board.health(false).headers().get("cache-control"));
        assertNull(board.health(false).headers().get("retry-after"));

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
        var pre = new HaHandler(empty, unloaded).handle(new Request("GET", "/file"));
        assertEquals(503, pre.status());
        assertEquals("unavailable\n", new String(bytes(pre)));
        assertEquals("no-store", pre.headers().get("cache-control"));
        assertEquals("1", pre.headers().get("retry-after"));
        assertEquals("text/plain; charset=utf-8", pre.headers().get("content-type"));
    }

    @Test
    void dataPlaneSupplierGatesReadiness() {
        var board = new HealthBoard(false);
        board.setSnapshotLoaded(true);
        assertTrue(board.readyNow());
        board.setDataPlaneReady(() -> false);
        assertFalse(board.readyNow());
        assertEquals(503, board.ready(false).status());
        board.setDataPlaneReady(() -> true);
        assertTrue(board.readyNow());
        assertEquals(200, board.ready(false).status());
        board.setDataPlaneReady(null);
        assertTrue(board.readyNow());
    }

    private static byte[] bytes(sjws_nettask.core.Response response) {
        var body = (sjws_nettask.core.Body.Bytes) response.body();
        var dup = body.data().duplicate();
        var out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }
}
