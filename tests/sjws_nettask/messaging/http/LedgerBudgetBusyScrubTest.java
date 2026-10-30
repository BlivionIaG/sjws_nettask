package sjws_nettask.messaging.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Body;
import sjws_nettask.core.Response;
import sjws_nettask.messaging.ledger.LedgerBudgetExhaustedException;

/** #335 B2: 503 detail must not forward budget charged/source from the exception message (#246). */
class LedgerBudgetBusyScrubTest {

    @Test
    void ledgerBudgetBusyUsesGenericDetail() {
        var rich = new LedgerBudgetExhaustedException(
                "ledger writer budget exhausted (sjws.messaging.ledger.budget.heapFraction=0.25 of 4294967296): "
                        + "need 2097152 B for LedgerWriter queue/direct buffer, charged 1071906816 / 1073741824 B");
        Response r = MessagingHttp.ledgerBudgetBusy(rich);
        assertEquals(503, r.status());
        String body = text(r);
        assertTrue(body.contains("busy") || body.contains("ledger writer budget exhausted"), body);
        assertFalse(body.contains("1071906816"), body);
        assertFalse(body.contains("heapFraction"), body);
        assertFalse(body.contains("need 2097152"), body);
        assertFalse(body.contains("charged"), body);
        var retry = r.headers().get("retry-after");
        if (retry == null) retry = r.headers().get("Retry-After");
        assertTrue(retry != null && !retry.isBlank(), "Retry-After required");
    }

    static String text(Response r) {
        if (r.body() instanceof Body.Bytes b) {
            var d = b.data().duplicate();
            var out = new byte[d.remaining()];
            d.get(out);
            return new String(out, StandardCharsets.UTF_8);
        }
        return String.valueOf(r.body());
    }
}
