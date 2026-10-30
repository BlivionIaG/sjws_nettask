package sjws_nettask.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestBody;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.messaging.ledger.LedgerBudgetTestHooks;
import sjws_nettask.messaging.ledger.LedgerWriterBudget;
import sjws_nettask.swarm.wire.Frame;

/** #333: past the ledger writer budget, swarm submit is 503; existing pools keep working. */
class LedgerBudgetAdmissionTest {

    @TempDir Path dir;
    Swarm swarm;
    SwarmHttp http;

    static final SwarmGate ALLOW_ALL = new SwarmGate() {
        @Override public boolean isAdmin(Agent agent) { return false; }
        @Override public Agent authenticate(Request request) { return new Agent("test"); }
        @Override public boolean allows(Agent agent, String pool) { return true; }
        @Override public long maxRequestBytes() { return SwarmHttp.MAX_REQUEST; }
    };

    @BeforeEach
    void open() {
        LedgerBudgetTestHooks.reset();
        // One pool opens task + dlq (2 writers) and must be able to materialize both; a second pool is refused.
        long one = LedgerWriterBudget.fullCostBytes(sjws_nettask.messaging.ledger.LedgerWriter.DEFAULT_QUEUE);
        LedgerBudgetTestHooks.install(LedgerWriterBudget.ofBytes(one * 2));
        this.swarm = Swarm.open(this.dir, "budget-test", 3,
                new sjws_nettask.messaging.delivery.RetryPolicy(5, 0, 0), System::currentTimeMillis,
                ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
        this.http = new SwarmHttp(this.swarm, 4, ALLOW_ALL);
    }

    @AfterEach
    void close() {
        if (this.swarm != null) this.swarm.close();
        LedgerBudgetTestHooks.reset();
    }

    @Test
    void submitPastBudgetIs503AndExistingPoolStillWorks() {
        var ok = post("/swarm/v1/tasks/pool-a", task("hello"));
        assertEquals(202, ok.status(), text(ok));

        var refused = post("/swarm/v1/tasks/pool-b", task("nope"));
        assertEquals(503, refused.status(), text(refused));
        // #335 B2/N4: fixed generic detail — no charged/budget/source from e.getMessage().
        var body = text(refused);
        assertTrue(body.toLowerCase().contains("busy") || body.contains("ledger writer budget exhausted"), body);
        assertFalse(body.contains("charged"), body);
        assertFalse(body.contains("heapFraction"), body);
        assertFalse(body.contains("need "), body);
        var retry = refused.headers().get("retry-after");
        if (retry == null) retry = refused.headers().get("Retry-After");
        assertTrue(retry != null && !retry.isBlank(), "Retry-After required");

        var again = post("/swarm/v1/tasks/pool-a", task("still-ok"));
        assertEquals(202, again.status(), text(again));
        var claim = post("/swarm/v1/tasks/pool-a:claim?max=2&waitMs=200", new byte[0]);
        assertTrue(claim.status() == 200 || claim.status() == 204, text(claim));
        assertTrue(LedgerWriterBudget.shared().refusals() >= 1);
    }

    Response post(String target, byte[] data) {
        return this.http.handle(new Request("POST", target, RequestHeaders.NONE, null, false, null, body(data)));
    }

    Response post(String target, Frame f) {
        return post(target, f.encode());
    }

    static RequestBody body(byte[] data) {
        return new RequestBody() {
            int pos;
            @Override public long length() { return data.length; }
            @Override public int read(byte[] into, int off, int len) {
                if (this.pos >= data.length) return -1;
                int n = Math.min(len, data.length - this.pos);
                System.arraycopy(data, this.pos, into, off, n);
                this.pos += n;
                return n;
            }
        };
    }

    static Frame task(String s) {
        return new Frame(Frame.Type.TASK, 0, s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] bytes(Response r) {
        if (r.body() instanceof Body.Bytes b) {
            var d = b.data().duplicate();
            var out = new byte[d.remaining()];
            d.get(out);
            return out;
        }
        return new byte[0];
    }

    static String text(Response r) {
        return new String(bytes(r), StandardCharsets.UTF_8);
    }
}
