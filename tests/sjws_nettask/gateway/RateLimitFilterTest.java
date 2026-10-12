package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;

class RateLimitFilterTest {

    @Test
    void tokenBucketRefillsOnTheInjectedClock() {
        var clock = new AtomicLong(1_000_000_000L);
        var spec = GatewayTestSupport.spec("rate-limit", Map.of(
                "capacity", "1", "refill-per-second", "0.5", "by", "route"));
        var quota = RateLimitFilter.TokenBucket.create(spec, clock::get);
        assertTrue(quota.tryAcquire("/a"));
        assertFalse(quota.tryAcquire("/a"));
        assertEquals(2, quota.retryAfterSeconds("/a"));
        assertTrue(quota.tryAcquire("/b"));
        clock.addAndGet(1_000_000_000L);
        assertFalse(quota.tryAcquire("/a"));
        clock.addAndGet(1_000_000_000L);
        assertTrue(quota.tryAcquire("/a"));
    }

    @Test
    void missingIdentitySharesABucketAndNewKeysStopAtTheCap() {
        var clock = new AtomicLong(5_000_000_000L);
        var spec = GatewayTestSupport.spec("rate-limit", Map.of(
                "capacity", "1", "refill-per-second", "1", "max-keys", "1", "by", "ip"));
        var filter = RateLimitFilter.create(spec, s -> RateLimitFilter.TokenBucket.create(s, clock::get));
        var first = new Request("GET", "/", RequestHeaders.NONE, new InetSocketAddress("127.0.0.1", 1), false);
        var second = new Request("GET", "/", RequestHeaders.NONE, new InetSocketAddress("127.0.0.2", 1), false);
        var anon = new Request("GET", "/");
        assertEquals(200, filter.filter(first, GatewayTestSupport::ok).status());
        var blocked = filter.filter(second, GatewayTestSupport::ok);
        assertEquals(429, blocked.status());
        assertEquals("1", blocked.headers().get("retry-after"));
        assertEquals(200, filter.filter(anon, GatewayTestSupport::ok).status());
        var again = filter.filter(new Request("HEAD", "/"), GatewayTestSupport::ok);
        assertEquals(429, again.status());
        assertTrue(again.headOnly());
        clock.addAndGet(1_000_000_000L);
        assertEquals(200, filter.filter(first, GatewayTestSupport::ok).status());
    }

    @Test
    void headerKeyAndCustomStatus() {
        var filter = RateLimitFilter.create(GatewayTestSupport.spec("rate-limit", Map.of(
                "by", "header", "header", "x-key", "capacity", "1", "refill-per-second", "100", "status", "503")));
        var a = GatewayTestSupport.request("GET", "/", Map.of("x-key", List.of("a")));
        var b = GatewayTestSupport.request("GET", "/", Map.of("x-key", List.of("b")));
        var missing = GatewayTestSupport.request("GET", "/", Map.of("x-key", List.of("")));
        assertEquals(200, filter.filter(a, GatewayTestSupport::ok).status());
        assertEquals(503, filter.filter(a, GatewayTestSupport::ok).status());
        assertEquals(200, filter.filter(b, GatewayTestSupport::ok).status());
        assertEquals(200, filter.filter(missing, GatewayTestSupport::ok).status());
        assertEquals(503, filter.filter(new Request("GET", "/"), GatewayTestSupport::ok).status());
    }

    @Test
    void factoryAndConfig() {
        var filter = RateLimitFilter.create(GatewayTestSupport.spec("rate-limit",
                Map.of("capacity", "2", "refill-per-second", "1")), spec -> key -> false);
        assertEquals(429, filter.filter(new Request("GET", "/"), GatewayTestSupport::ok).status());
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.create(GatewayTestSupport.spec("rate-limit", Map.of("by", "nope"))));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.create(GatewayTestSupport.spec("rate-limit",
                        Map.of("by", "header", "capacity", "1", "refill-per-second", "1"))));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.TokenBucket.create(MiddlewareSpec.of("rate-limit")));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.TokenBucket.refillMicros("0"));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.TokenBucket.refillMicros("1.0000001"));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.TokenBucket.refillMicros("1.2.3"));
        assertEquals(500_000L, RateLimitFilter.TokenBucket.refillMicros(".5"));
        assertEquals(1_500_000L, RateLimitFilter.TokenBucket.refillMicros("1.5"));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.create(GatewayTestSupport.spec("rate-limit",
                        Map.of("capacity", "1", "refill-per-second", "1", "status", "200"))));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.create(GatewayTestSupport.spec("rate-limit",
                        Map.of("capacity", "1", "refill-per-second", "1")), spec -> null));
    }
}
