/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestBody;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.swarm.wire.Frame;

class SwarmGateTest {

    static final String ALICE = "alice-token-0123456789abcdef";
    static final String BOB = "bob-token-0123456789abcdefgh";

    @TempDir
    Path dir;
    Swarm swarm;
    SwarmHttp http;
    TokenGate gate;

    @BeforeEach
    void open() {
        this.swarm = Swarm.open(this.dir.resolve("s"), "gate-test", 3);
        this.gate = new TokenGate(List.of("# agents", "alice " + ALICE + " a,b", "", "bob " + BOB + " *  # all"), 4096);
        this.http = new SwarmHttp(this.swarm, 4, this.gate);
    }

    @AfterEach
    void close() {
        this.swarm.close();
    }

    Response post(String target, String token, byte[] data, boolean knownLength) {
        var fields = new HashMap<String, List<String>>();
        if (token != null) {
            fields.put("authorization", List.of(token));
        }
        var body = SwarmHttpTest.body(data);
        RequestBody b = knownLength ? body : new RequestBody() {
            @Override
            public long length() {
                return -1;
            }

            @Override
            public int read(byte[] into, int off, int len) throws java.io.IOException {
                return body.read(into, off, len);
            }
        };
        return this.http.handle(new Request("POST", target, RequestHeaders.of(fields), null, false, null, b));
    }

    static byte[] task() {
        return new Frame(Frame.Type.TASK, 0, "x".getBytes(StandardCharsets.UTF_8)).encode();
    }

    @Test
    void denyByDefault() {
        var r = post("/swarm/v1/tasks/a", null, task(), true);
        assertEquals(401, r.status());
        assertEquals("Bearer realm=\"sjws-swarm\"", r.headers().get("www-authenticate"));
        assertEquals(401, post("/swarm/v1/tasks/a", "Bearer wrong-token-0123456789", task(), true).status());
        assertEquals(401, post("/swarm/v1/tasks/a", "Basic " + ALICE, task(), true).status());
        assertEquals(401, post("/swarm/v1/tasks/a", "Bearer ", task(), true).status());
        // nothing was enqueued by the refused requests
        assertEquals(204, post("/swarm/v1/tasks/a:claim", "Bearer " + ALICE, new byte[0], true).status());
        // GET is refused as unauthenticated before the method check: no endpoint discovery without a credential
        assertEquals(401, this.http.handle(new Request("GET", "/swarm/v1/tasks/a")).status());
    }

    @Test
    void perAgentPoolAllowlist() {
        assertEquals(202, post("/swarm/v1/tasks/a", "Bearer " + ALICE, task(), true).status());
        assertEquals(202, post("/swarm/v1/tasks/b", "bearer " + ALICE, task(), true).status());
        var denied = post("/swarm/v1/tasks/c", "Bearer " + ALICE, task(), true);
        assertEquals(403, denied.status());
        assertFalse(SwarmHttpTest.text(denied).contains(ALICE));
        assertEquals(202, post("/swarm/v1/tasks/c", "Bearer " + BOB, task(), true).status());
        // leases are authorized on the pool inside the lease id
        assertEquals(403, post("/swarm/v1/leases/c~0~1:ack", "Bearer " + ALICE, new byte[0], true).status());
        var leases = SwarmHttpTest.leases(post("/swarm/v1/tasks/a:claim", "Bearer " + ALICE, new byte[0], true));
        assertEquals(1, leases.size());
        assertEquals(204, post("/swarm/v1/leases/" + leases.get(0).id() + ":ack", "Bearer " + ALICE, new byte[0], true)
                .status());
        assertFalse(this.gate.allows(null, "a"));
        assertFalse(this.gate.allows(new SwarmGate.Agent("mallory"), "a"));
    }

    @Test
    void requestSizeCap() {
        var big = new Frame(Frame.Type.TASK, 0, new byte[5000]).encode();
        assertEquals(413, post("/swarm/v1/tasks/a", "Bearer " + ALICE, big, true).status());
        // unknown length (chunked): cut off while reading
        assertEquals(400, post("/swarm/v1/tasks/a", "Bearer " + ALICE, big, false).status());
        assertEquals(202, post("/swarm/v1/tasks/a", "Bearer " + ALICE, task(), false).status());
        assertThrows(IllegalArgumentException.class, () -> new TokenGate(List.of("a " + ALICE + " *"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenGate(List.of("a " + ALICE + " *"), SwarmHttp.MAX_REQUEST + 1L));
    }

    @Test
    void failClosedWhenNothingOrSomethingInvalidIsConfigured() throws Exception {
        var err = new ByteArrayOutputStream();
        var out = new PrintStream(err, true, StandardCharsets.UTF_8);
        assertSame(SwarmGate.DENY_ALL, TokenGate.configured(k -> null, Map.of(), out));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("rejects every request"));
        assertSame(SwarmGate.DENY_ALL, TokenGate.configured(k -> null, Map.of(TokenGate.TOKENS_ENV, " "), out));
        assertSame(SwarmGate.DENY_ALL,
                TokenGate.configured(k -> null, Map.of(TokenGate.FILE_ENV, this.dir.resolve("missing").toString()), out));
        assertSame(SwarmGate.DENY_ALL, TokenGate.configured(k -> null, Map.of(TokenGate.TOKENS_ENV, "# none"), out));
        assertSame(SwarmGate.DENY_ALL, TokenGate.configured(k -> null, Map.of(TokenGate.TOKENS_ENV, "a tinytok *"), out));
        assertSame(SwarmGate.DENY_ALL,
                TokenGate.configured(k -> null, Map.of(TokenGate.TOKENS_ENV, "a " + ALICE + " *;a " + BOB + " *"), out));
        assertSame(SwarmGate.DENY_ALL, TokenGate.configured(k -> null, Map.of(TokenGate.TOKENS_ENV, "a " + ALICE), out));
        assertSame(SwarmGate.DENY_ALL, TokenGate.configured(k -> null, Map.of(TokenGate.TOKENS_ENV, "a " + ALICE + " x,,y"), out));
        assertSame(SwarmGate.DENY_ALL, TokenGate.configured(
                k -> TokenGate.MAX_PROPERTY.equals(k) ? "lots" : null, Map.of(TokenGate.TOKENS_ENV, "a " + ALICE + " *"), out));
        var deny = SwarmGate.DENY_ALL;
        assertNull(deny.authenticate(new Request("POST", "/swarm/v1/tasks/a")));
        assertFalse(deny.allows(new SwarmGate.Agent("x"), "a"));
        assertEquals(0, deny.maxRequestBytes());
        assertEquals("SwarmGate[deny-all]", deny.toString());
        var log = err.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains(ALICE) || log.contains(BOB) || log.contains("tinytok"), log);
    }

    @Test
    void tokensFromAFileOrTheEnvironmentAndNeverInLogsOrToString() throws Exception {
        var file = this.dir.resolve("tokens");
        Files.writeString(file, "alice " + ALICE + " a\n");
        var err = new ByteArrayOutputStream();
        var out = new PrintStream(err, true, StandardCharsets.UTF_8);
        var fromFile = TokenGate.configured(k -> TokenGate.FILE_PROPERTY.equals(k) ? file.toString() : null, Map.of(), out);
        var fromEnv = TokenGate.configured(k -> null, Map.of(TokenGate.TOKENS_ENV, "alice " + ALICE + " a;bob " + BOB + " *",
                TokenGate.MAX_ENV, "2048"), out);
        var fromFileEnv = TokenGate.configured(k -> null, Map.of(TokenGate.FILE_ENV, file.toString()), out);
        for (var g : List.of(fromFile, fromEnv, fromFileEnv)) {
            assertTrue(g instanceof TokenGate, g.toString());
            var req = new Request("POST", "/x", RequestHeaders.of(Map.of("authorization", List.of("Bearer " + ALICE))));
            assertNotNull(g.authenticate(req));
            assertEquals("alice", g.authenticate(req).id());
            assertFalse(g.toString().contains(ALICE));
        }
        assertEquals(2048, fromEnv.maxRequestBytes());
        assertEquals(TokenGate.DEFAULT_MAX, fromFile.maxRequestBytes());
        // every response and every log line of a full exchange is free of the secret
        var r = post("/swarm/v1/tasks/zzz", "Bearer " + ALICE, task(), true);
        var log = err.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("token gate with 2 agent(s)"), log);
        for (var text : List.of(log, SwarmHttpTest.text(r), r.headers().toString(), this.gate.toString())) {
            assertFalse(text.contains(ALICE) || text.contains(BOB), text);
        }
    }
}
