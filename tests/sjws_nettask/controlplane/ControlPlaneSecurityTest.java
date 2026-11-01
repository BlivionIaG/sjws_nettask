package sjws_nettask.controlplane;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.AccessSettings;
import sjws_nettask.core.AccessRules;

/** Distributable access policies, RBAC decisions (allowed and denied) and audit tamper detection. */
class ControlPlaneSecurityTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tmp;

    static AccessPolicyDocument doc(long v, Scope s) {
        return new AccessPolicyDocument(v, s, new AccessSettings(List.of("/private"),
                List.of("hide:*.bak", "allow:/.well-known/acme-challenge"), false, null, false));
    }

    // ---- serialized form ----

    @Test
    void encodingIsDeterministicAndRoundTrips() {
        var d = doc(7, Scope.label("env", "prod"));
        var text = d.encode();
        assertEquals(text, doc(7, Scope.label("env", "prod")).encode());
        assertEquals(d, AccessPolicyDocument.decode(text));
        assertTrue(text.startsWith("sjws-access-policy 1\nversion 7\nscope label:env=prod\n"));
        var owner = new AccessPolicyDocument(3, Scope.route("api"), new AccessSettings(List.of(), List.of(), true, "0", true));
        assertEquals(owner, AccessPolicyDocument.decode(owner.encode()));
        assertEquals(Scope.ALL, AccessPolicyDocument.decode(AccessPolicyDocument.of(1, Scope.ALL, List.of(), List.of()).encode()).scope());
    }

    @Test
    void decodeRejectsTamperingAndInvalidContent() {
        var text = doc(7, Scope.ALL).encode();
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyDocument.decode(text.replace("/private", "/privatf")));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyDocument.decode(text.substring(0, text.length() - 1)));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyDocument.decode("version 1\n"));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyDocument.decode("x".repeat(AccessPolicyDocument.MAX_BYTES + 1)));
        // valid digest but invalid rule / field / order: re-sign a hand-made body
        for (var body : List.of(
                "sjws-access-policy 1\nversion 1\nscope *\ndotfiles false\nowner -\nworld-readable false\nrule nope:/x\n",
                "sjws-access-policy 1\nversion 0\nscope *\ndotfiles false\nowner -\nworld-readable false\n",
                "sjws-access-policy 1\nversion x\nscope *\ndotfiles false\nowner -\nworld-readable false\n",
                "sjws-access-policy 1\nversion 1\nscope bad\ndotfiles false\nowner -\nworld-readable false\n",
                "sjws-access-policy 1\nversion 1\nscope *\ndotfiles maybe\nowner -\nworld-readable false\n",
                "sjws-access-policy 1\nversion 1\nscope *\ndotfiles false\nowner -\nworld-readable false\nrule allow:/a\ndeny /b\n",
                "sjws-access-policy 1\nversion 1\nscope *\ndotfiles false\nowner -\nworld-readable false\nrule allow:/a \n",
                "sjws-access-policy 1\nversion 01\nscope *\ndotfiles false\nowner -\nworld-readable false\n",
                "sjws-access-policy 2\n")) {
            var signed = body + "sha256 " + AccessPolicyDocument.sha256(body) + "\n";
            assertThrows(IllegalArgumentException.class, () -> AccessPolicyDocument.decode(signed), body);
        }
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyDocument.of(1, Scope.ALL, List.of("/a\nrule allow:/**"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AccessPolicyDocument(1, Scope.ALL, new AccessSettings(List.of(), List.of(), false, "-", false)));
    }

    @Test
    void scopes() {
        assertTrue(Scope.ALL.appliesToNode(Map.of()));
        assertTrue(Scope.parse("label:env=prod").appliesToNode(Map.of("env", "prod")));
        assertFalse(Scope.parse("label:env=prod").appliesToNode(Map.of("env", "dev")));
        assertFalse(Scope.parse("route:api").appliesToNode(Map.of("env", "prod")));
        assertEquals("route:api", Scope.parse("route:api").toString());
        for (var bad : List.of("label:env", "label:=x", "label:a=", "label:a=b c", "route:", "route:a/b", "node:x")) {
            assertThrows(IllegalArgumentException.class, () -> Scope.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new Scope(Scope.Kind.ALL, "x", ""));
        assertThrows(IllegalArgumentException.class, () -> new Scope(Scope.Kind.ROUTE, "a", "b"));
    }

    @Test
    void seamsToTheConfigModel() {
        var server = sjws_nettask.ServerConfig.defaults(tmp);
        var access = new AccessSettings(List.of("/private"), List.of(), false, null, false);
        var cfg = sjws_nettask.config.ConfigDocument.of(server, sjws_nettask.config.ConfigDocument.defaults(server).acme(),
                sjws_nettask.config.ConfigDocument.defaults(server).routing(), List.of(), access,
                new sjws_nettask.config.NodeSettings(sjws_nettask.config.NodeSettings.Mode.DATA, Map.of("zone", "eu:1")),
                1, 12, null);
        var d = AccessPolicyDocument.fromConfig(cfg, Scope.label("zone", "eu:1"));
        assertEquals(12, d.version());
        var live = LivePolicy.forNode(cfg.node(), AccessRules.DEFAULT);
        assertEquals(LivePolicy.Result.APPLIED, live.apply(d.encode()));
        assertEquals(403, live.check("private", 0));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyDocument.fromConfig(
                sjws_nettask.config.ConfigDocument.defaults(server), Scope.ALL), "unversioned local config");
    }

    // ---- hot swap ----

    @Test
    void livePolicySwapsAtomicallyAndRefusesReplayAndForeignScopes() throws IOException {
        var live = new LivePolicy(Map.of("env", "prod"), AccessRules.DEFAULT);
        assertEquals(0, live.check("private/x", 0));
        assertFalse(live.checksFiles());
        assertEquals(0, live.checkFile(tmp));
        assertEquals(LivePolicy.Result.APPLIED, live.apply(doc(2, Scope.label("env", "prod")).encode()));
        assertEquals(403, live.check("private/x", 0));
        assertEquals(404, live.check("a/b.bak", 0));
        assertEquals(2, live.active().version());
        assertEquals(LivePolicy.Result.STALE, live.apply(doc(2, Scope.ALL)));
        assertEquals(LivePolicy.Result.STALE, live.apply(AccessPolicyDocument.of(1, Scope.ALL, List.of(), List.of())));
        assertEquals(LivePolicy.Result.NOT_FOR_THIS_NODE, live.apply(doc(9, Scope.label("env", "dev"))));
        assertThrows(IllegalArgumentException.class, () -> live.apply("garbage"));
        assertEquals(2, live.active().version(), "invalid push keeps the active policy");
        assertEquals(LivePolicy.Result.APPLIED, live.apply(AccessPolicyDocument.of(3, Scope.ALL, List.of(), List.of())));
        assertEquals(0, live.check("private/x", 0));
    }

    @Test
    void concurrentPushesNeverGoBackwards() throws Exception {
        var live = new LivePolicy(Map.of(), AccessRules.DEFAULT);
        var threads = new ArrayList<Thread>();
        for (int t = 0; t < 4; t++) {
            int base = t;
            threads.add(Thread.ofPlatform().start(() -> {
                for (int v = 1 + base; v <= 400; v += 4) {
                    live.apply(AccessPolicyDocument.of(v, Scope.ALL, List.of(), List.of()));
                }
            }));
        }
        for (var t : threads) {
            t.join();
        }
        assertEquals(400, live.active().version());
    }

    // ---- RBAC ----

    Rbac rbac(Path log, List<Operator> ops) throws IOException {
        return new Rbac(ops, new AuditLog(log, CLOCK, true));
    }

    static Rbac.ClientCert cert(String s) {
        return new Rbac.ClientCert(s);
    }

    @Test
    void rolesAndDenyByDefault() throws IOException {
        var log = tmp.resolve("audit.jsonl");
        var r = rbac(log, List.of(new Operator("root", Role.ADMIN, "urn:sjws:admin:root", true),
                new Operator("view", Role.VIEWER, "CN=Viewer,O=Sjws", true),
                new Operator("ops", Role.OPERATOR, "CN=Ops Bot, O=Sjws", true),
                new Operator("gone", Role.ADMIN, "CN=gone", false)));
        var admin = cert("urn:sjws:admin:root");
        var viewer = cert("cn=viewer, o=sjws");
        assertTrue(r.authorize(viewer, Permission.READ, "status", 0).allowed(), "DN match is canonical");
        for (var p : List.of(Permission.EDIT_ROUTES, Permission.ROLLOUT, Permission.ROLLBACK, Permission.NODE_DRAIN, Permission.MANAGE_OPERATORS)) {
            assertFalse(r.authorize(viewer, p, "x", 0).allowed(), p.name());
        }
        var ops = cert("cn=Ops Bot,o=Sjws");
        assertTrue(r.authorize(ops, Permission.ROLLOUT, "v5", 5).allowed());
        assertFalse(r.authorize(ops, Permission.MANAGE_OPERATORS, "x", 0).allowed());
        assertTrue(r.authorize(admin, Permission.MANAGE_OPERATORS, "x", 0).allowed());
        // unauthenticated paths: unknown, disabled, malformed, case-changed SAN, null
        for (var c : List.of(cert("CN=gone"), cert("not a dn ==="), cert(null), cert("CN=stranger"),
                cert("urn:sjws:admin:ROOT"), cert("urn:bad san"), cert("urn:sjws:admin:root2"), cert(""))) {
            var d = r.authorize(c, Permission.READ, "status", 0);
            assertFalse(d.allowed(), String.valueOf(c));
            assertEquals("unauthenticated", d.reason());
        }
        var audit = Files.readString(log);
        assertTrue(audit.contains("\"actor\":\"view\",\"action\":\"ROLLOUT\",\"target\":\"x\",\"outcome\":\"denied: role VIEWER lacks ROLLOUT\""));
        assertTrue(audit.contains("\"actor\":\"ops\",\"action\":\"ROLLOUT\",\"target\":\"v5\",\"outcome\":\"allowed\",\"version\":5"));
        assertTrue(audit.contains("\"actor\":\"unknown-cert:CN=stranger\""));
        assertTrue(AuditLog.verify(log).ok());
    }

    @Test
    void operatorManagementKeepsAnAdminAndIsAudited() throws IOException {
        var log = tmp.resolve("a.jsonl");
        var r = rbac(log, List.of(new Operator("root", Role.ADMIN, "CN=root", true),
                new Operator("ops", Role.OPERATOR, "CN=ops", true)));
        var admin = cert("CN=root");
        var ops = cert("CN=ops");
        assertFalse(r.putOperator(ops, new Operator("ops", Role.ADMIN, "CN=ops", true)).allowed(), "no self-promotion");
        assertTrue(r.putOperator(admin, new Operator("eve", Role.VIEWER, "urn:sjws:admin:eve", true)).allowed());
        assertTrue(r.authorize(cert("urn:sjws:admin:eve"), Permission.READ, "s", 0).allowed());
        var last = r.removeOperator(admin, "root");
        assertFalse(last.allowed());
        assertEquals("would leave no enabled admin", last.reason());
        assertFalse(r.putOperator(admin, new Operator("root", Role.ADMIN, "CN=root", false)).allowed());
        assertTrue(r.removeOperator(admin, "eve").allowed());
        assertFalse(r.authorize(cert("urn:sjws:admin:eve"), Permission.READ, "s", 0).allowed(), "revoked at once");
        assertFalse(r.removeOperator(ops, "root").allowed());
        var d = r.authorize(ops, Permission.ROLLBACK, "v3", 3);
        r.complete(d, true, 6, "re-issued v3 as v6");
        assertThrows(IllegalArgumentException.class, () -> r.complete(r.authorize(ops, Permission.MANAGE_OPERATORS, "x", 0), true, 0, null));
        assertTrue(Files.readString(log).contains("\"outcome\":\"ok: re-issued v3 as v6\",\"version\":6"));
        assertTrue(AuditLog.verify(log).ok());
        assertThrows(IllegalArgumentException.class, () -> new Rbac(List.of(
                new Operator("a", Role.ADMIN, "CN=a", true), new Operator("a", Role.ADMIN, "CN=b", true)),
                new AuditLog(tmp.resolve("dup"), CLOCK, false)));
    }

    @Test
    void operatorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Operator("Bad Id", Role.ADMIN, "CN=a", true));
        assertThrows(IllegalArgumentException.class, () -> new Operator(null, Role.ADMIN, "CN=a", true));
        assertThrows(IllegalArgumentException.class, () -> new Operator("a", Role.ADMIN, null, true), "mTLS identity required");
        assertThrows(IllegalArgumentException.class, () -> new Operator("a", Role.ADMIN, "===", true));
        assertThrows(IllegalArgumentException.class, () -> new Operator("a", Role.ADMIN, "urn:bad san", true));
        assertThrows(IllegalArgumentException.class, () -> new Operator("a.b", Role.ADMIN, "CN=a", true));
        assertThrows(NullPointerException.class, () -> new Operator("a", null, "CN=a", true));
        assertTrue(new Operator("a", Role.ADMIN, "CN=a", false).toString().contains("disabled"));
        assertEquals(Permission.values().length, Role.ADMIN.permissions().size());
    }

    @Test
    void deniedWhenAuditCannotBeWritten() throws IOException {
        var audit = new AuditLog(tmp.resolve("c.jsonl"), CLOCK, false);
        var r = new Rbac(List.of(new Operator("root", Role.ADMIN, "CN=root", true)), audit);
        audit.close();
        var d = r.authorize(cert("CN=root"), Permission.READ, "s", 0);
        assertFalse(d.allowed());
        assertEquals("audit unavailable", d.reason());
    }

    // ---- audit tampering ----

    List<String> writeLog(Path log) throws IOException {
        try (var a = new AuditLog(log, CLOCK, false)) {
            a.append("root", "ROLLOUT", "x".repeat(1000), "allowed", 1);
            a.append("ops", "NODE_DRAIN", "node \"7\"\\\n", "denied: x", 1);
            a.append("root", "ROLLBACK", "v1", "ok", 2);
            assertFalse(Files.readString(log).contains("x".repeat(AuditLog.MAX_FIELD + 1)));
            assertEquals(3, a.head().seq());
        }
        return Files.readAllLines(log);
    }

    @Test
    void auditChainResumesAcrossReopen() throws IOException {
        var log = tmp.resolve("r.jsonl");
        writeLog(log);
        try (var a = new AuditLog(log, CLOCK, false)) {
            assertEquals(3, a.head().seq());
            a.append(null, "READ", null, "denied: unauthenticated", 0);
        }
        var v = AuditLog.verify(log);
        assertTrue(v.ok(), v.problem());
        assertEquals(4, v.entries());
        Files.writeString(tmp.resolve("empty"), "");
        assertTrue(AuditLog.verify(tmp.resolve("empty")).ok());
    }

    @Test
    void auditTamperingIsDetected() throws IOException {
        var log = tmp.resolve("t.jsonl");
        var lines = writeLog(log);
        assertTrue(lines.get(1).contains("node \\\"7\\\"\\\\\\u000a"), lines.get(1));
        record Case(String name, List<String> lines, String problem, int line) {
        }
        var edited = new ArrayList<>(lines);
        edited.set(1, edited.get(1).replace("denied: x", "allowed"));
        var deleted = new ArrayList<>(lines);
        deleted.remove(1);
        var swapped = new ArrayList<>(lines);
        swapped.set(0, lines.get(1));
        swapped.set(1, lines.get(0));
        var rechained = new ArrayList<>(lines); // seq fixed up but prev not
        rechained.remove(1);
        rechained.set(1, rechained.get(1).replace("\"seq\":3", "\"seq\":2"));
        var junk = new ArrayList<>(lines);
        junk.add(1, "{\"seq\":2}");
        for (var c : List.of(new Case("edit", edited, "hash mismatch (entry edited)", 2),
                new Case("delete", deleted, "sequence gap", 2), new Case("swap", swapped, "sequence gap", 1),
                new Case("rechain", rechained, "prev does not match the previous hash", 2),
                new Case("junk", junk, "malformed entry", 2))) {
            Files.write(log, c.lines());
            var v = AuditLog.verify(log);
            assertFalse(v.ok(), c.name());
            assertEquals(c.problem(), v.problem(), c.name());
            assertEquals(c.line(), v.badLine(), c.name());
            assertThrows(IOException.class, () -> new AuditLog(log, CLOCK, false), "refuses to extend " + c.name());
        }
        Files.writeString(log, String.join("\n", lines)); // torn: no final newline
        assertEquals("torn last line", AuditLog.verify(log).problem());
    }
}
