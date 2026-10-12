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
package sjws_nettask.admin.cp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sjws_nettask.admin.cp.CpClient.ConfigBundle;
import sjws_nettask.admin.cp.CpClient.Section;

class CpConsoleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

    private static CpConsole console(CpClient c) {
        var n = new AtomicInteger();
        return new CpConsole(c, false, () -> "k" + n.incrementAndGet());
    }

    private static ConfigBundle bundle(Section s, String... entries) {
        return new ConfigBundle(0, Map.of(s, List.of(entries)));
    }

    // ---------- LabelSelector ----------

    @Test
    void labelSelector() {
        assertSame(LabelSelector.ALL, LabelSelector.parse(null));
        assertSame(LabelSelector.ALL, LabelSelector.parse(" * "));
        assertEquals("*", LabelSelector.ALL.toString());
        var s = LabelSelector.parse("zone=a, canary!=true");
        assertEquals("zone=a,canary!=true", s.toString());
        assertTrue(s.matches(Map.of("zone", "a")));
        assertFalse(s.matches(Map.of("zone", "a", "canary", "true")));
        assertFalse(s.matches(Map.of("zone", "b")));
        assertTrue(LabelSelector.parse("x!=1").matches(Map.of()));
        assertFalse(LabelSelector.parse("x=1").matches(Map.of()));
        for (var bad : List.of("zone", "=a", "zone=", "zone!=", "!=a")) {
            assertThrows(IllegalArgumentException.class, () -> LabelSelector.parse(bad), bad);
        }
    }

    // ---------- ConfigDiff ----------

    @Test
    void diffAddsRemovesAndReorders() {
        var base = new ConfigBundle(1, Map.of(Section.ROUTES, List.of("a -> x", "b -> y"),
                Section.TOPICS, List.of("t1", "t2"), Section.ACCESS, List.of("deny:/a")));
        assertEquals(List.of(), ConfigDiff.diff(base, base.withVersion(0)));
        var reordered = base.with(Section.ROUTES, List.of("b -> y", "a -> x")).with(Section.TOPICS, List.of("t2", "t1"));
        assertEquals(List.of("~ routes: order changed"), ConfigDiff.diff(base, reordered));
        var changed = base.with(Section.ACCESS, List.of("deny:/b")).with(Section.ROUTES, List.of("a -> x"));
        assertEquals(List.of("- routes: b -> y", "- access: deny:/a", "+ access: deny:/b"),
                ConfigDiff.diff(base, changed));
    }

    @Test
    void bundleIsImmutableAndComplete() {
        var b = bundle(Section.CERTS, "c");
        assertEquals(Section.values().length, b.sections().size());
        assertEquals(List.of(), b.entries(Section.ROUTES));
        assertThrows(UnsupportedOperationException.class, () -> b.entries(Section.CERTS).add("x"));
        assertEquals(7, b.withVersion(7).version());
    }

    // ---------- Fake validation ----------

    @Test
    void validationRules() {
        var bad = new ConfigBundle(0, Map.of(
                Section.ROUTES, List.of("no arrow", " -> x", "a -> "),
                Section.FILTERS, List.of("two words", " "),
                Section.ACCESS, List.of("permit:/x", "deny:/ok", "deny:/ok"),
                Section.CERTS, List.of(""),
                Section.TOPICS, List.of("Bad", "_x", "ok.dlq")));
        var v = FakeCpClient.check(bad);
        assertFalse(v.ok());
        assertEquals(9, v.errors().size(), v.errors().toString());
        assertEquals(List.of("access[3] 'deny:/ok': duplicate"), v.warnings());
        var empty = FakeCpClient.check(bundle(Section.TOPICS, "t"));
        assertTrue(empty.ok());
        assertTrue(empty.warnings().get(0).startsWith("routes: empty"));
    }

    // ---------- Fake CP behaviour ----------

    @Test
    void stageRolloutByLabelAndLag() throws CpException {
        var fake = FakeCpClient.demo(CLOCK);
        var c = fake.endpoint("cp-1:9443");
        assertEquals("cp-1:9443", c.endpoint());
        assertEquals(1, c.head());
        var draft = c.config(1).with(Section.TOPICS, List.of("orders", "orders.dlq", "payments"));
        long g = c.stage(draft, 1, "s1");
        assertEquals(2, g);
        assertEquals(2, c.stage(draft, 1, "s1"), "same key: same result, no new version");
        assertEquals(2, c.head());
        var r = c.rollout(2, "canary=true", "r1");
        assertEquals(List.of("cp-2"), r.targets());
        assertEquals(CpClient.Rollout.State.DONE, r.state());
        assertEquals(r, c.rollout(2, "canary=true", "r1"));
        var nodes = c.cluster().nodes();
        assertEquals(1, nodes.get(0).lag());
        assertEquals(0, nodes.get(1).lag());
        assertEquals(2, nodes.get(1).appliedVersion());
        assertEquals("cp-1", c.cluster().leader());
        assertEquals(r, c.rolloutStatus("r1"));
        var e = assertThrows(CpException.class, () -> c.stage(draft, 1, "s2"));
        assertEquals(CpException.Kind.CONFLICT, e.kind());
        assertEquals(CpException.Kind.INVALID, assertThrows(CpException.class,
                () -> c.stage(draft.with(Section.ACCESS, List.of("oops")), 2, "s3")).kind());
        assertEquals(CpException.Kind.INVALID, assertThrows(CpException.class, () -> c.rollout(2, "zone=zz", "r2")).kind());
        assertEquals(CpException.Kind.INVALID, assertThrows(CpException.class, () -> c.rollout(2, "zone", "r3")).kind());
        assertEquals(CpException.Kind.NOT_FOUND, assertThrows(CpException.class, () -> c.rollout(9, "*", "r4")).kind());
        assertEquals(CpException.Kind.NOT_FOUND, assertThrows(CpException.class, () -> c.rollout(0, "*", "r5")).kind());
        assertEquals(CpException.Kind.NOT_FOUND, assertThrows(CpException.class, () -> c.config(0)).kind());
        assertEquals(CpException.Kind.NOT_FOUND, assertThrows(CpException.class, () -> c.config(3)).kind());
        assertEquals(CpException.Kind.NOT_FOUND, assertThrows(CpException.class, () -> c.rolloutStatus("zz")).kind());
    }

    @Test
    void downNodeStaysPendingUntilBackUp() throws CpException {
        var fake = FakeCpClient.demo(CLOCK);
        var c = fake.endpoint("e");
        fake.setHealth("cp-3", "DOWN");
        long g = c.stage(c.config(1).with(Section.FILTERS, List.of("observe")), 1, null);
        var r = c.rollout(g, "*", null);
        assertEquals(CpClient.Rollout.State.RUNNING, r.state());
        assertEquals(List.of("cp-1", "cp-2"), r.applied());
        fake.setHealth("cp-3", "UP");
        assertEquals(CpClient.Rollout.State.DONE, c.rolloutStatus(r.id()).state());
        assertTrue(c.audit(0, 100).stream().anyMatch(a -> a.action().equals("rollout-done")));
        fake.setHealth("cp-3", "UP"); // no running rollout left
    }

    @Test
    void rollbackCreatesANewVersion() throws CpException {
        var fake = FakeCpClient.demo(CLOCK);
        var c = fake.endpoint("e");
        var original = c.config(1);
        c.stage(original.with(Section.ROUTES, List.of("x -> y")), 1, null);
        assertEquals(3, c.rollback(1, "b1"));
        assertEquals(3, c.rollback(1, "b1"));
        assertEquals(original.sections(), c.config(3).sections());
        assertTrue(c.cluster().nodes().stream().allMatch(n -> n.lag() == 0));
        assertEquals(CpException.Kind.NOT_FOUND, assertThrows(CpException.class, () -> c.rollback(0, null)).kind());
        assertEquals(CpException.Kind.NOT_FOUND, assertThrows(CpException.class, () -> c.rollback(4, null)).kind());
        fake.setActor("alice");
        c.rollback(2, null);
        var audit = c.audit(0, 10);
        assertEquals(List.of("stage", "rollback", "rollback"), audit.stream().map(CpClient.AuditEntry::action).toList());
        assertEquals("alice", audit.get(2).actor());
        assertEquals(CLOCK.instant(), audit.get(0).time());
        assertEquals(1, c.audit(2, 10).size());
        assertEquals(List.of(), c.audit(0, -1));
    }

    @Test
    void endpointStates() throws CpException {
        var fake = FakeCpClient.demo(CLOCK);
        var c = fake.endpoint("cp-2");
        fake.setEndpointState("cp-2", "follower");
        assertEquals(1, c.head(), "followers serve reads");
        var e = assertThrows(CpException.class, () -> c.rollback(1, null));
        assertEquals(CpException.Kind.NOT_LEADER, e.kind());
        assertEquals("cp-1", e.leaderHint());
        fake.setEndpointState("cp-2", "down");
        assertEquals(CpException.Kind.UNAVAILABLE, assertThrows(CpException.class, c::cluster).kind());
        for (CpCall call : List.<CpCall>of(c::head, () -> c.config(1), () -> c.validate(c.config(1)),
                () -> c.rolloutStatus("r1"), () -> c.audit(0, 1), () -> c.stage(null, 1, null),
                () -> c.rollout(1, "*", null))) {
            assertEquals(CpException.Kind.UNAVAILABLE, assertThrows(CpException.class, call::run).kind());
        }
        fake.setEndpointState("cp-2", null);
        assertEquals(1, c.head());
        assertNull(new CpException(CpException.Kind.FORBIDDEN, "x").leaderHint());
    }

    interface CpCall {
        Object run() throws CpException;
    }

    // ---------- FailoverCpClient ----------

    @Test
    void failoverMovesOnUnavailableAndFollowsTheLeaderHint() throws CpException {
        var fake = FakeCpClient.demo(CLOCK);
        var opened = new ArrayList<String>();
        var f = new FailoverCpClient(List.of("cp-2", "cp-3", "cp-1"), ep -> {
            opened.add(ep);
            return fake.endpoint(ep);
        });
        assertEquals("cp-2", f.endpoint());
        fake.setEndpointState("cp-2", "down");
        assertEquals(1, f.head());
        assertEquals("cp-3", f.endpoint());
        fake.setEndpointState("cp-3", "follower");
        assertEquals(2, f.stage(f.config(1).with(Section.TOPICS, List.of("t")), 1, "k1"));
        assertEquals("cp-1", f.endpoint());
        assertEquals(List.of("cp-2 unavailable -> cp-3", "cp-3 is not the leader -> cp-1"), f.events());
        assertEquals(List.of("cp-2", "cp-3", "cp-1"), opened, "each endpoint connected once");
        var r = f.rollout(2, "*", "k2");
        assertEquals(r, f.rolloutStatus(r.id()));
        assertTrue(f.validate(f.config(2)).ok());
        assertEquals(3, f.rollback(1, "k3"));
        assertEquals(3, f.audit(0, 10).size());
        assertEquals("cp-1", f.cluster().leader());
    }

    @Test
    void failoverGivesUpAndPassesOtherErrors() {
        var fake = FakeCpClient.demo(CLOCK);
        var f = new FailoverCpClient(List.of("a", "b"), fake::endpoint);
        var conflict = assertThrows(CpException.class, () -> f.stage(null, 0, "k"));
        assertEquals(CpException.Kind.CONFLICT, conflict.kind());
        fake.setEndpointState("a", "down");
        fake.setEndpointState("b", "down");
        var e = assertThrows(CpException.class, f::head);
        assertEquals(CpException.Kind.UNAVAILABLE, e.kind());
        assertTrue(e.getMessage().contains("no endpoint answered (a, b)"), e.getMessage());
        // follower whose hint is unknown, or points to itself: not retried
        var g = new FailoverCpClient(List.of("x"), ep -> new HintClient(fake.endpoint(ep), "elsewhere"));
        assertEquals(CpException.Kind.NOT_LEADER, assertThrows(CpException.class, () -> g.rollback(1, null)).kind());
        var self = new FailoverCpClient(List.of("x"), ep -> new HintClient(fake.endpoint(ep), "x"));
        assertEquals(CpException.Kind.NOT_LEADER, assertThrows(CpException.class, () -> self.rollback(1, null)).kind());
        var none = new FailoverCpClient(List.of("x"), ep -> new HintClient(fake.endpoint(ep), null));
        assertEquals(CpException.Kind.NOT_LEADER, assertThrows(CpException.class, () -> none.rollback(1, null)).kind());
        assertThrows(IllegalArgumentException.class, () -> new FailoverCpClient(List.of(), fake::endpoint));
    }

    /** Delegates reads, refuses writes with a fixed leader hint. */
    private record HintClient(CpClient d, String hint) implements CpClient {
        @Override public ClusterView cluster() throws CpException { return d.cluster(); }
        @Override public long head() throws CpException { return d.head(); }
        @Override public ConfigBundle config(long g) throws CpException { return d.config(g); }
        @Override public Validation validate(ConfigBundle b) throws CpException { return d.validate(b); }
        @Override public long stage(ConfigBundle b, long base, String k) throws CpException { throw nl(); }
        @Override public Rollout rollout(long g, String s, String k) throws CpException { throw nl(); }
        @Override public Rollout rolloutStatus(String id) throws CpException { return d.rolloutStatus(id); }
        @Override public long rollback(long g, String k) throws CpException { throw nl(); }
        @Override public List<AuditEntry> audit(long a, int l) throws CpException { return d.audit(a, l); }
        private CpException nl() { return new CpException(CpException.Kind.NOT_LEADER, "no", hint); }
    }

    @Test
    void defaultEndpointLabel() {
        assertEquals("-", new HintClient(null, null).endpoint());
    }

    // ---------- CpConsole view model ----------

    @Test
    void editDiffValidateApplyToCanaryThenPromote() throws CpException {
        var fake = FakeCpClient.demo(CLOCK);
        var con = console(fake.endpoint("cp-1"));
        con.reset();
        assertFalse(con.dirty());
        con.add(Section.ACCESS, "  deny:/private ");
        con.add(Section.ROUTES, "example.com/old -> redirect:/new");
        con.moveUp(Section.ROUTES, 3);
        con.remove(Section.TOPICS, 2);
        assertTrue(con.dirty());
        assertEquals(List.of("+ routes: example.com/old -> redirect:/new", "+ access: deny:/private",
                "- topics: orders.dlq"), con.diff());
        assertEquals("example.com/old -> redirect:/new", con.draft().entries(Section.ROUTES).get(1));
        assertTrue(con.validate().ok());
        var r = con.apply("canary=true");
        assertEquals(List.of("cp-2"), r.applied());
        assertFalse(con.dirty());
        assertEquals(3, con.draft().entries(Section.ACCESS).size());
        var p = con.promote("*");
        assertEquals(3, p.applied().size());
        assertEquals(3, con.rollback(1));
        assertThrows(IllegalArgumentException.class, () -> con.remove(Section.ROUTES, 0));
        assertThrows(IllegalArgumentException.class, () -> con.remove(Section.ROUTES, 99));
        assertThrows(IllegalArgumentException.class, () -> con.moveUp(Section.ROUTES, 1));
        assertThrows(IllegalArgumentException.class, () -> con.moveUp(Section.ROUTES, 99));
        assertThrows(IllegalStateException.class, () -> con.apply("*"));
        assertThrows(IllegalArgumentException.class, () -> con.promote("bad"));
        con.add(Section.ACCESS, "bogus");
        assertThrows(IllegalStateException.class, () -> con.apply("*"));
        assertThrows(IllegalArgumentException.class, () -> con.apply("zone"));
    }

    @Test
    void commandsAndRendering() {
        var fake = FakeCpClient.demo(CLOCK);
        var con = console(new FailoverCpClient(List.of("cp-1", "cp-2"), fake::endpoint));
        var screen = con.render();
        assertTrue(screen.startsWith("sjws control plane  via cp-1\n"), screen);
        assertTrue(con.handle("n"));
        screen = con.render();
        assertTrue(screen.contains("leader cp-1  term 1  head version 1"), screen);
        assertTrue(screen.contains("cp-2     FOLLOWER  UP     1     0    {canary=true, tier=edge, zone=b}"), screen);
        con.handle("e access");
        assertEquals(CpConsole.View.CONFIG, con.view());
        assertTrue(con.render().contains("   1  deny:/.git"));
        con.handle("e");
        con.handle("+ access deny:/secret");
        assertTrue(con.render().contains("+  3  deny:/secret"), con.render());
        assertTrue(con.render().contains("draft: 1 change(s)"));
        con.handle("+ ro example.com/x -> static:./x");
        assertTrue(con.render().contains("(ordered: first match wins)"));
        con.handle("^ routes 3");
        con.handle("- routes 1");
        con.handle("d");
        screen = con.render();
        assertTrue(screen.contains("diff version 1 -> draft"));
        assertTrue(screen.contains("- routes: example.com/ -> static:./HTML"));
        con.handle("v");
        assertTrue(con.render().contains("valid: 0 error(s)"), con.render());
        con.handle("a zone=a");
        assertTrue(con.message().startsWith("rollout r1 of version 2 to zone=a: DONE 1/1"), con.message());
        assertTrue(con.render().contains("last rollout r1: version 2 to zone=a DONE 1/1"));
        con.handle("p tier=edge");
        assertEquals("rollout r2 to tier=edge: DONE", con.message());
        con.handle("b 1");
        assertEquals("rolled back: version 3 = content of 1", con.message());
        con.handle("l");
        screen = con.render();
        assertTrue(screen.contains("rollback     version 3 = content of 1"), screen);
        fake.setEndpointState("cp-1", "down");
        con.handle("f");
        assertEquals("refreshed", con.message());
        assertTrue(con.render().contains("cp-1 unavailable -> cp-2"));
        con.handle("x");
        assertEquals("draft discarded", con.message());
        con.handle("h");
        assertTrue(con.message().startsWith("n nodes"));
        con.handle("?");
        con.handle("zzz");
        assertEquals("unknown command 'zzz' (h for help)", con.message());
        for (var bad : List.of("+ access", "- routes", "^ routes", "a", "p", "b", "- routes x", "e nope", "e t")) {
            con.handle(bad);
            assertFalse(con.message().isEmpty(), bad);
        }
        assertEquals("unknown section 't'", con.message());
        assertFalse(con.handle("q"));
    }

    @Test
    void conflictAndInvalidAndEmptyViews() {
        var fake = FakeCpClient.demo(CLOCK);
        var con = console(fake.endpoint("cp-1"));
        con.handle("+ access deny:/a");
        // someone else changes the head meanwhile
        try {
            var other = fake.endpoint("cp-1");
            other.stage(other.config(1).with(Section.TOPICS, List.of("x")), 1, null);
        } catch (CpException e) {
            fail(e);
        }
        con.handle("a *");
        assertTrue(con.message().startsWith("CONFLICT: head is version 2"), con.message());
        assertTrue(con.message().endsWith("then redo the edits)"));
        con.handle("f");
        assertTrue(con.render().contains("head version 1"), "dirty draft is kept on refresh");
        con.handle("x");
        con.handle("+ access nope");
        con.handle("v");
        var screen = con.render();
        assertTrue(screen.contains("invalid: 1 error(s), 0 warning(s)"), screen);
        assertTrue(screen.contains("E access[3] 'nope'"));
        con.handle("x");
        assertTrue(con.render().contains("draft changed since the last validation"), "reset clears the result");
        con.handle("+ access nope");
        con.handle("a *");
        assertEquals("validation failed: 1 error(s)", con.message());
        con.handle("b 9");
        assertTrue(con.message().startsWith("NOT_FOUND"));
        con.handle("x");
        con.handle("d");
        assertTrue(con.render().contains("(no changes)"));
        con.handle("- certs 1");
        con.handle("e certs");
        assertTrue(con.render().contains("(none)"));
        con.handle("+ topics Bad");
        con.handle("+ topics Bad");
        con.handle("v");
        assertTrue(con.render().contains("W topics[3] 'Bad': duplicate"), con.render());
        fake.setEndpointState("cp-1", "down");
        con.handle("n");
        assertTrue(con.render().contains("UNAVAILABLE: cp-1 does not answer"));
        con.handle("l");
        assertTrue(con.render().contains("UNAVAILABLE"));
    }

    @Test
    void colorsAndLaggingDownNodes() throws CpException {
        var fake = FakeCpClient.demo(CLOCK);
        fake.setHealth("cp-3", "DOWN");
        var con = new CpConsole(fake.endpoint("cp-1"), true);
        con.handle("+ topics new");
        con.handle("a *");
        var screen = con.render();
        assertTrue(screen.startsWith(CpConsole.CLEAR));
        assertTrue(screen.contains(CpConsole.RED + "DOWN  " + CpConsole.RESET), screen);
        assertTrue(screen.contains(CpConsole.YELLOW + "1" + CpConsole.RESET), "lag shown in yellow");
        assertTrue(screen.contains("RUNNING 2/3"));
        con.handle("+ topics x");
        con.handle("- topics 1");
        con.handle("^ topics 2");
        con.handle("d");
        screen = con.render();
        assertTrue(screen.contains(CpConsole.GREEN + "+ topics: x"));
        assertTrue(screen.contains(CpConsole.RED + "- topics: orders"));
        con.handle("x");
        con.handle("^ routes 2");
        con.handle("d");
        assertTrue(con.render().contains(CpConsole.YELLOW + "~ routes: order changed"));
    }

    @Test
    void stagedButNotRolledOutCanBePromoted() {
        var fake = FakeCpClient.demo(CLOCK);
        var con = console(fake.endpoint("cp-1"));
        con.handle("+ topics late");
        con.handle("a zone=nowhere");
        assertTrue(con.message().startsWith("INVALID: version 2 staged but not rolled out"), con.message());
        assertTrue(con.render().contains("head version 2"));
        con.handle("p zone=a");
        assertEquals("rollout r1 to zone=a: DONE", con.message());
    }

    @Test
    void sectionNames() {
        assertEquals(Section.ROUTES, CpConsole.section("ROUTES"));
        assertEquals(Section.FILTERS, CpConsole.section("fi"));
        assertThrows(IllegalArgumentException.class, () -> CpConsole.section("f"));
    }

    @Test
    void runLoopAndDefaultConstructor() throws IOException {
        var fake = FakeCpClient.demo(CLOCK);
        var bytes = new ByteArrayOutputStream();
        var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        new CpConsole(fake.endpoint("cp-1"), false).run(new BufferedReader(new StringReader("n\nq\nn\n")), out);
        assertEquals(2, bytes.toString(StandardCharsets.UTF_8).split("> ", -1).length - 1);
        new CpConsole(fake.endpoint("cp-1"), false).run(new BufferedReader(new StringReader("")), out);
    }

    // ---------- main ----------

    private static String main(String input, String... args) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int code = CpConsoleMain.run(args, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return code + "|" + bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void mainDemoAndUsage() throws IOException {
        var out = main("n\n+ topics audit\na canary=true\nq\n", "--demo", "--no-color", "--endpoints=e1,e2");
        assertTrue(out.startsWith("0|sjws control plane  via e1"), out);
        assertTrue(out.contains("rollout r1 of version 2 to canary=true: DONE 1/1"), out);
        assertTrue(main("q\n", "--demo").startsWith("0|" + CpConsole.CLEAR));
        assertTrue(main("").startsWith("64|Usage"));
        assertTrue(main("", "--endpoints=").startsWith("64|ERROR: unknown argument --endpoints="));
        assertTrue(main("", "--nope").startsWith("64|ERROR"));
    }

    // ---------- zero cost when off ----------

    @Test
    void serverNeverReferencesTheConsole() throws IOException {
        try (var walk = Files.walk(Path.of("src"))) {
            var offenders = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(Path.of("src/sjws_nettask/admin/cp")))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("admin.cp");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).toList();
            assertEquals(List.of(), offenders);
        }
    }
}
