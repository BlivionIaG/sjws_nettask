package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.gateway.GatewayFilters;

/** ip-acl on the real HTTP/1.1 accept loop (loopback is in the allow list). */
class IpAclHttp1Test {

    @TempDir
    Path root;

    SjwsServer server;

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
        }
    }

    @Test
    void loopbackIsAdmittedAndADenyListCanRefuseIt() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var allow = GatewayFilters.install(RequestFilters.create())
                .add(MiddlewareSpec.of("ip-acl", 0, null, Map.of("allow", "127.0.0.0/8, ::1/128")));
        this.server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), allow);
        var ok = HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000);
        assertEquals(200, ok.statusCode());
        assertEquals("hello", ok.bodyText());
        this.server.close();

        var deny = GatewayFilters.install(RequestFilters.create())
                .add(MiddlewareSpec.of("ip-acl", 0, null, Map.of("deny", "127.0.0.0/8, ::1/128")));
        this.server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), deny);
        var refused = HttpTestSupport.get(this.server.tcpPort(), "/hello.txt", 4000);
        assertEquals(403, refused.statusCode());
    }
}
