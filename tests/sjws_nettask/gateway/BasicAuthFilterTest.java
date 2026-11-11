package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigException;
import sjws_nettask.core.Request;

class BasicAuthFilterTest {

    @TempDir
    Path tmp;

    @Test
    void acceptsAMatchingUserAndRejectsTheRest() {
        var filter = BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth",
                Map.of("users", "alice:secret,bob:other:with:colons")));
        var challenge = GatewayTestSupport.call(filter, new Request("GET", "/"));
        assertEquals(401, challenge.status());
        assertEquals("Basic realm=\"sjws\", charset=\"UTF-8\"", challenge.headers().get("www-authenticate"));
        assertEquals("no-store", challenge.headers().get("cache-control"));

        var wrong = GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of(basic("alice", "nope")))));
        assertEquals(401, wrong.status());

        var ok = GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of(basic("alice", "secret")))));
        assertEquals(200, ok.status());
        assertEquals("ok", GatewayTestSupport.text(ok));

        var bob = GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of(basic("bob", "other:with:colons")))));
        assertEquals(200, bob.status());

        var head = GatewayTestSupport.call(filter, GatewayTestSupport.request("HEAD", "/", Map.of()));
        assertEquals(401, head.status());
        assertTrue(head.headOnly());
        assertEquals("Basic realm=\"sjws\", charset=\"UTF-8\"", head.headers().get("www-authenticate"));
    }

    @Test
    void realmIsQuotedAndSchemeParsingIsStrict() {
        var filter = BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth",
                Map.of("users", "alice:secret", "realm", "Site \"A\"")));
        assertEquals("Basic realm=\"Site \\\"A\\\"\", charset=\"UTF-8\"",
                GatewayTestSupport.call(filter, new Request("GET", "/")).headers().get("www-authenticate"));

        assertEquals(401, GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of("Bearer " + basic("alice", "secret").substring(6))))).status());
        assertEquals(200, GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of("basic  " + basic("alice", "secret").substring(6))))).status());
        assertEquals(401, GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of("Basic")))).status());
        assertEquals(401, GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of("Basic !!!")))).status());
        assertEquals(401, GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of("Basic " + Base64.getEncoder().encodeToString("nocolon".getBytes()))))
        ).status());
        assertEquals(401, GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of("Basic " + Base64.getEncoder().encodeToString(":secret".getBytes()))))
        ).status());
    }

    @Test
    void usersFileMustBeOwnerOnlyAndDoesNotKeepTheRawPassword(@TempDir Path dir) throws Exception {
        var file = dir.resolve("users");
        Files.writeString(file, "# comment\n\nalice:secret\nbob:pw\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        var filter = (BasicAuthFilter) BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth",
                Map.of("users-file", file.toString())));
        assertEquals(200, GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of(basic("alice", "secret"))))).status());
        var hash = filter.hashes()[0];
        assertEquals(32, hash.length);
        assertNotEquals("secret", new String(hash, StandardCharsets.UTF_8));
        assertNotEquals("alice:secret", new String(hash, StandardCharsets.UTF_8));

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        assertThrows(ConfigException.class, () -> BasicAuthFilter.create(
                GatewayTestSupport.spec("basic-auth", Map.of("users-file", file.toString()))));
    }

    @Test
    void rejectsBadConfig() {
        assertThrows(IllegalArgumentException.class,
                () -> BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth",
                        Map.of("users", "alice:secret", "users-file", "x"))));
        assertThrows(IllegalArgumentException.class,
                () -> BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth", Map.of("users", "alice"))));
        assertThrows(IllegalArgumentException.class,
                () -> BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth", Map.of("users", ":pw"))));
        assertThrows(IllegalArgumentException.class,
                () -> BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth", Map.of("users", "alice:"))));
        assertThrows(IllegalArgumentException.class,
                () -> BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth", Map.of("users", "  \n# only"))));
        assertThrows(IllegalArgumentException.class,
                () -> BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth",
                        Map.of("users", "a:b", "realm", "bad\nrealm"))));
        assertThrows(ConfigException.class,
                () -> BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth",
                        Map.of("users-file", tmp.resolve("missing").toString()))));
    }

    @Test
    void aPasswordMayEndWithASpace() {
        var filter = BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth",
                Map.of("users", "alice:secret ")));
        assertEquals(200, GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of(basic("alice", "secret "))))).status());
        assertEquals(401, GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of(basic("alice", "secret"))))).status());
    }

    @Test
    void onlyTheFirstAuthorizationValueIsUsed() {
        var filter = BasicAuthFilter.create(GatewayTestSupport.spec("basic-auth",
                Map.of("users", "alice:secret")));
        var firstGood = GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of(basic("alice", "secret"), basic("alice", "nope")))));
        assertEquals(200, firstGood.status());
        var firstBad = GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of(basic("alice", "nope"), basic("alice", "secret")))));
        assertEquals(401, firstBad.status());
        assertEquals("no-store", firstBad.headers().get("cache-control"));
    }

    @Test
    void factoryIsInstalled() {
        var filters = GatewayFilters.install(sjws_nettask.core.RequestFilters.create())
                .add(GatewayTestSupport.spec("basic-auth", Map.of("users", "a:b")));
        var ok = filters.wrapping(GatewayTestSupport::ok)
                .handle(GatewayTestSupport.request("GET", "/", Map.of("authorization", List.of(basic("a", "b")))));
        assertEquals(200, ok.status());
        var miss = filters.wrapping(GatewayTestSupport::ok).handle(new Request("GET", "/"));
        assertEquals(401, miss.status());
        assertEquals("Basic realm=\"sjws\", charset=\"UTF-8\"", miss.headers().get("www-authenticate"));
        assertEquals("no-store", miss.headers().get("cache-control"));
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
