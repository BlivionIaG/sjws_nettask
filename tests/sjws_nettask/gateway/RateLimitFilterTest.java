package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;

class RateLimitFilterTest {

    @Test
    void stripedTableRefillsOnTheInjectedClock() {
        var clock = new AtomicLong(1_000_000_000L);
        var spec = GatewayTestSupport.spec("rate-limit", Map.of(
                "capacity", "1", "refill-per-second", "0.5", "by", "route"));
        var quota = RateLimitFilter.local(spec, clock::get);
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
    void missingIdentitySharesABucketAndOneStripeIsShared() {
        var clock = new AtomicLong(5_000_000_000L);
        var spec = GatewayTestSupport.spec("rate-limit", Map.of(
                "capacity", "1", "refill-per-second", "1", "max-keys", "1", "by", "ip"));
        var filter = RateLimitFilter.create(spec, s -> RateLimitFilter.local(s, clock::get));
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
    void aFullStripeAdmitsEveryKeyUntilTheBurstIsSpent() throws Exception {
        var spec = GatewayTestSupport.spec("rate-limit", Map.of(
                "capacity", "2", "refill-per-second", "1", "max-keys", "1", "by", "ip"));
        var quota = RateLimitFilter.local(spec, () -> 1L);
        var a = InetAddress.getByName("10.0.0.1");
        var b = InetAddress.getByName("10.0.0.2");
        var c = InetAddress.getByName("10.0.0.3");
        assertTrue(quota.tryAcquireAddress(a));
        assertTrue(quota.tryAcquireAddress(b));
        assertFalse(quota.tryAcquireAddress(c));
        assertEquals(1, quota.retryAfterAddress(c));
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
        var refused = filter.filter(new Request("GET", "/"), GatewayTestSupport::ok);
        assertEquals(429, refused.status());
        assertEquals("1", refused.headers().get("retry-after"));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.create(GatewayTestSupport.spec("rate-limit", Map.of("by", "nope"))));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.create(GatewayTestSupport.spec("rate-limit",
                        Map.of("by", "header", "capacity", "1", "refill-per-second", "1"))));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.local(MiddlewareSpec.of("rate-limit"), () -> 1L));
        assertThrows(IllegalArgumentException.class, () -> RateLimitFilter.refillMicros("0"));
        assertThrows(IllegalArgumentException.class, () -> RateLimitFilter.refillMicros("1.0000001"));
        assertThrows(IllegalArgumentException.class, () -> RateLimitFilter.refillMicros("1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> RateLimitFilter.refillMicros("1a"));
        assertThrows(IllegalArgumentException.class, () -> RateLimitFilter.refillMicros("10000000000000"));
        assertEquals(500_000L, RateLimitFilter.refillMicros(".5"));
        assertEquals(1_500_000L, RateLimitFilter.refillMicros("1.5"));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.create(GatewayTestSupport.spec("rate-limit",
                        Map.of("capacity", "1", "refill-per-second", "1", "status", "200"))));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.create(GatewayTestSupport.spec("rate-limit",
                        Map.of("capacity", "1", "refill-per-second", "1")), spec -> null));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.local(GatewayTestSupport.spec("rate-limit",
                        Map.of("capacity", "0", "refill-per-second", "1")), () -> 1L));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.local(GatewayTestSupport.spec("rate-limit",
                        Map.of("capacity", "1000001", "refill-per-second", "1")), () -> 1L));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.stripes(GatewayTestSupport.spec("rate-limit",
                        Map.of("buckets", "3", "max-keys", "4"))));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.stripes(GatewayTestSupport.spec("rate-limit", Map.of("buckets", "3"))));
        assertThrows(IllegalArgumentException.class,
                () -> RateLimitFilter.stripes(GatewayTestSupport.spec("rate-limit", Map.of("max-keys", "0"))));
        assertEquals(1, RateLimitFilter.stripes(GatewayTestSupport.spec("rate-limit", Map.of("max-keys", "1"))));
        assertEquals(16384, RateLimitFilter.stripes(GatewayTestSupport.spec("rate-limit", Map.of("max-keys", "10000"))));
        assertEquals(8, RateLimitFilter.stripes(GatewayTestSupport.spec("rate-limit", Map.of("buckets", "8"))));
        assertEquals(4096, RateLimitFilter.stripes(MiddlewareSpec.of("rate-limit")));
        var fresh = RateLimitFilter.local(GatewayTestSupport.spec("rate-limit",
                Map.of("capacity", "1", "refill-per-second", "1", "by", "route")), () -> 1L);
        assertEquals(1, fresh.retryAfterSeconds("/unused"));
    }

    @Test
    void oneTokenHoldsUnderContention() throws Exception {
        var spec = GatewayTestSupport.spec("rate-limit", Map.of(
                "capacity", "1", "refill-per-second", "1", "by", "route"));
        var quota = RateLimitFilter.local(spec, () -> 1L);
        assertEquals(1, race(8, () -> quota.tryAcquire("same")));

        var capped = RateLimitFilter.local(GatewayTestSupport.spec("rate-limit", Map.of(
                "capacity", "1", "refill-per-second", "1", "max-keys", "1", "by", "route")), () -> 1L);
        var names = new String[8];
        for (int i = 0; i < names.length; i++) {
            names[i] = "k" + i;
        }
        var index = new AtomicInteger();
        assertEquals(1, race(names.length, () -> capped.tryAcquire(names[index.getAndIncrement()])));
        assertFalse(capped.tryAcquire("extra"));

        var address = RateLimitFilter.local(GatewayTestSupport.spec("rate-limit", Map.of(
                "capacity", "1", "refill-per-second", "1", "by", "ip")), () -> 1L);
        var ip = InetAddress.getByName("127.0.0.1");
        assertTrue(address.tryAcquireAddress(ip));
        assertFalse(address.tryAcquireAddress(InetAddress.getByName("127.0.0.1")));
        assertEquals(1, address.retryAfterAddress(ip));
    }

    private static int race(int threads, java.util.function.BooleanSupplier attempt) throws Exception {
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var wins = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            Thread.startVirtualThread(() -> {
                try {
                    start.await();
                    if (attempt.getAsBoolean()) {
                        wins.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return wins.get();
    }
}
