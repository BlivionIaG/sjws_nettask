package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;

class IpAclFilterTest {

    @Test
    void allowListAdmitsOnlyListedAddresses() {
        var filter = (IpAclFilter) IpAclFilter.create(GatewayTestSupport.spec("ip-acl",
                java.util.Map.of("allow", "127.0.0.1, 10.0.0.0/8")));
        assertEquals(200, call(filter, "127.0.0.1"));
        assertEquals(200, call(filter, "10.1.2.3"));
        assertEquals(403, call(filter, "11.0.0.1"));
        assertEquals(403, call(filter, "192.168.1.1"));
        assertEquals(403, call(filter, null));
    }

    @Test
    void denyListAndDenyWinsWhenBothAreSet() {
        var denyOnly = (IpAclFilter) IpAclFilter.create(GatewayTestSupport.spec("ip-acl",
                java.util.Map.of("deny", "10.0.0.0/8\n192.0.2.1")));
        assertEquals(403, call(denyOnly, "10.9.0.1"));
        assertEquals(403, call(denyOnly, "192.0.2.1"));
        assertEquals(200, call(denyOnly, "11.0.0.1"));

        var both = (IpAclFilter) IpAclFilter.create(GatewayTestSupport.spec("ip-acl",
                java.util.Map.of("allow", "10.0.0.0/8", "deny", "10.0.0.1")));
        assertEquals(200, call(both, "10.0.0.2"));
        assertEquals(403, call(both, "10.0.0.1"), "deny wins");
        assertEquals(403, call(both, "11.0.0.1"));
    }

    @Test
    void ipv6AndHeadAndNullPeer() {
        var filter = (IpAclFilter) IpAclFilter.create(GatewayTestSupport.spec("ip-acl",
                java.util.Map.of("allow", "::1/128, 2001:db8::/32")));
        assertTrue(filter.allowed(peer("::1")));
        assertTrue(filter.allowed(peer("2001:db8::1")));
        assertFalse(filter.allowed(peer("2001:db9::1")));
        assertFalse(filter.allowed(null));
        var head = filter.filter(new Request("HEAD", "/", RequestHeaders.NONE, peer("::1"), false),
                GatewayTestSupport::ok);
        assertEquals(200, head.status());
        var missed = filter.filter(new Request("HEAD", "/", RequestHeaders.NONE, peer("fe80::1"), false),
                GatewayTestSupport::ok);
        assertEquals(403, missed.status());
        assertTrue(missed.headOnly());
    }

    @Test
    void rejectsBadConfig() {
        assertThrows(IllegalArgumentException.class,
                () -> IpAclFilter.create(GatewayTestSupport.spec("ip-acl", java.util.Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> IpAclFilter.create(GatewayTestSupport.spec("ip-acl", java.util.Map.of("allow", " "))));
        assertThrows(IllegalArgumentException.class,
                () -> IpAclFilter.create(GatewayTestSupport.spec("ip-acl", java.util.Map.of("allow", "example.com"))));
        assertThrows(IllegalArgumentException.class,
                () -> IpAclFilter.create(GatewayTestSupport.spec("ip-acl", java.util.Map.of("deny", "10.0.0.0/99"))));
        var emptyAllow = assertThrows(IllegalArgumentException.class,
                () -> IpAclFilter.create(GatewayTestSupport.spec("ip-acl",
                        java.util.Map.of("allow", "# last entry removed\n", "deny", "10.0.0.1"))));
        assertTrue(emptyAllow.getMessage().contains("allow is empty"), emptyAllow.getMessage());
    }

    @Test
    void factoryIsInstalled() {
        var filters = GatewayFilters.install(sjws_nettask.core.RequestFilters.create())
                .add(GatewayTestSupport.spec("ip-acl", java.util.Map.of("allow", "127.0.0.1")));
        var ok = filters.wrapping(GatewayTestSupport::ok)
                .handle(new Request("GET", "/", RequestHeaders.NONE, peer("127.0.0.1"), false));
        assertEquals(200, ok.status());
        var miss = filters.wrapping(GatewayTestSupport::ok)
                .handle(new Request("GET", "/", RequestHeaders.NONE, peer("1.2.3.4"), false));
        assertEquals(403, miss.status());
    }

    private static int call(IpAclFilter filter, String host) {
        var request = new Request("GET", "/", RequestHeaders.NONE, host == null ? null : peer(host), false);
        return GatewayTestSupport.call(filter, request).status();
    }

    private static InetSocketAddress peer(String host) {
        return new InetSocketAddress(host, 9);
    }
}
