package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void doublesUpToMaximumThenResets() {
        var b = new Backoff(10, 100);
        assertTrue(b.idle());
        assertEquals(10, b.next());
        assertFalse(b.idle());
        assertEquals(20, b.next());
        assertEquals(40, b.next());
        assertEquals(80, b.next());
        assertEquals(100, b.next());
        assertEquals(100, b.next());
        b.reset();
        assertTrue(b.idle());
        assertEquals(10, b.next());
    }

    @Test
    void acceptLoopBackoffIsBounded() {
        // A persistent EMFILE costs at most one accept() attempt per BACKOFF_MAX_MS.
        var b = new Backoff(ClientManager.BACKOFF_INITIAL_MS, ClientManager.BACKOFF_MAX_MS);
        long total = 0;
        for (int i = 0; i < 20; i++) {
            total += b.next();
        }
        assertTrue(ClientManager.BACKOFF_INITIAL_MS > 0);
        assertEquals(ClientManager.BACKOFF_MAX_MS, b.next());
        assertTrue(total > 10_000, "20 consecutive failures should take >10 s, took " + total + " ms");
    }
}
