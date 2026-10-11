package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AltSvcTest {

    @Test
    void headerValueFormat() {
        assertEquals("h3=\":4433\"; ma=86400", AltSvc.headerValue(4433, Duration.ofDays(1)));
        assertEquals("h3=\":443\"; ma=0", AltSvc.headerValue(443, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> AltSvc.headerValue(0, Duration.ZERO));
    }

    @Test
    void emptyUntilAdvertisedAndAfterWithdraw() {
        var a = new AltSvc();
        assertTrue(a.get().isEmpty());
        assertNull(a.current());
        a.advertise(8443, Duration.ofSeconds(60));
        assertEquals("h3=\":8443\"; ma=60", a.get().get("alt-svc"));
        a.withdraw();
        assertTrue(a.get().isEmpty());
    }

    @Test
    void everyCallReturnsAFreshHeaderSet() {
        var a = new AltSvc();
        a.advertise(1, Duration.ZERO);
        var first = a.get();
        first.add("x-mutated", "1");
        assertNull(a.get().get("x-mutated"));
        assertNotSame(first, a.get());
    }
}
