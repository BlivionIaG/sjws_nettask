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
package sjws_nettask.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code ha.messaging: off|on|auto} and {@code ha.members} (gate G1). */
class HaMessagingSettingsTest {

    static final String KEY = "0123456789abcdef0123456789abcdef";
    static final Map<String, String> ENV = Map.of("WEB_SERVER_HA_CLUSTER_KEY", KEY);

    static final String THREE = """
              node_id: a
              members:
                - { id: a, address: "127.0.0.1:7101", http_url: "http://127.0.0.1:8101/" }
                - { id: b, address: "127.0.0.1:7102", http_url: "http://127.0.0.1:8102" }
                - { id: w, address: "127.0.0.1:7103", witness: true }
              topics: [orders, audit]
            """;

    @TempDir
    Path dir;

    private ConfigLoader.Loaded load(String yaml) throws Exception {
        var file = dir.resolve("sjws.yaml");
        Files.writeString(file, yaml);
        return ConfigLoader.load(new String[] {"--config=" + file, "--state-dir=" + dir.resolve("state")}, ENV);
    }

    private String error(String yaml) throws Exception {
        var loaded = load(yaml);
        assertFalse(loaded.ok(), "expected a refusal for:\n" + yaml);
        return loaded.errors().get(0);
    }

    @Test
    void absentIsOffAndSingleNode() throws Exception {
        var none = load("# nothing\n");
        assertTrue(none.ok(), none.errors().toString());
        assertSame(HaMessagingSettings.NONE, none.haMessaging());
        assertEquals(HaMessagingSettings.Mode.OFF, HaMessagingSettings.DEFAULT_MODE, "G1 ships off; the flip is separate");
        var haOnly = load("ha:\n  enabled: true\n");
        assertTrue(haOnly.ok(), haOnly.errors().toString());
        assertEquals(HaMessagingSettings.Mode.OFF, haOnly.haMessaging().mode());
        assertFalse(haOnly.haMessaging().replicate());
        assertNull(haOnly.haMessaging().self());
        assertFalse(haOnly.haMessaging().selfIsWitness());
        assertSame(none.haMessaging(), none.withDocument(none.document()).haMessaging());
    }

    @Test
    void parsesOffOnAutoIncludingYamlBooleans() throws Exception {
        for (var v : new String[] {"off", "\"off\"", "false", "OFF"}) {
            var l = load("ha:\n  enabled: true\n  messaging: " + v + "\n" + THREE);
            assertTrue(l.ok(), v + " " + l.errors());
            assertEquals(HaMessagingSettings.Mode.OFF, l.haMessaging().mode(), v);
            assertFalse(l.haMessaging().replicate(), "off never replicates, even with members: " + v);
        }
        for (var v : new String[] {"on", "\"on\"", "true", "On"}) {
            var l = load("ha:\n  enabled: true\n  messaging: " + v + "\n" + THREE);
            assertTrue(l.ok(), v + " " + l.errors());
            assertEquals(HaMessagingSettings.Mode.ON, l.haMessaging().mode(), v);
            assertTrue(l.haMessaging().replicate(), v);
        }
        var auto = load("ha:\n  enabled: true\n  messaging: auto\n" + THREE);
        assertTrue(auto.ok(), auto.errors().toString());
        var s = auto.haMessaging();
        assertEquals(HaMessagingSettings.Mode.AUTO, s.mode());
        assertEquals("auto", s.mode().key());
        assertTrue(s.replicate());
        assertEquals("a", s.self().id());
        assertFalse(s.selfIsWitness());
        assertEquals(3, s.members().size());
        assertEquals("http://127.0.0.1:8101", s.members().get(0).httpUrl(), "trailing slash dropped");
        assertEquals(7102, s.members().get(1).port());
        assertEquals(3, s.members().get(2).wire(), "wire defaults to the 1-based position");
        assertTrue(s.members().get(2).witness());
        assertNull(s.members().get(2).httpUrl());
        assertEquals(Set.of("orders", "audit"), s.topics());
        assertEquals(HaMessagingSettings.DEFAULT_ELECTION_TIMEOUT, s.electionTimeout());
        assertEquals(HaMessagingSettings.DEFAULT_HEARTBEAT, s.heartbeat());
        assertNull(s.stateDir());
        assertEquals(dir.resolve("x/msgrepl"), s.stateDirOr(dir.resolve("x")));
        assertTrue(error("ha:\n  messaging: sometimes\n").contains("expected off, on or auto"));
        assertTrue(error("ha:\n  messaging: 3\n").contains("expected off, on or auto"));
    }

    @Test
    void witnessTimingsStateDirAndWire() throws Exception {
        var l = load("""
                ha:
                  enabled: true
                  messaging: on
                  node_id: w
                  election_timeout: 600ms
                  heartbeat: 50ms
                  state_dir: rel/state
                  members:
                    - { id: a, address: "[::1]:7101", http_url: "https://a.example:8443", wire: 10 }
                    - { id: b, address: "localhost:7102", http_url: "http://b", wire: 11 }
                    - { id: w, address: "127.0.0.1:7103", witness: true, wire: 12 }
                """);
        assertTrue(l.ok(), l.errors().toString());
        var s = l.haMessaging();
        assertTrue(s.selfIsWitness());
        assertEquals("::1", s.members().get(0).host());
        assertEquals(10, s.members().get(0).wire());
        assertEquals(Duration.ofMillis(600), s.electionTimeout());
        assertEquals(Duration.ofMillis(50), s.heartbeat());
        assertEquals(dir.resolve("rel/state"), s.stateDir());
        assertEquals(dir.resolve("rel/state"), s.stateDirOr(dir));
        assertTrue(s.topics().isEmpty());
        assertTrue(l.warnings().stream().anyMatch(w -> w.contains("ha.topics is empty")), l.warnings().toString());
        var abs = load("ha:\n  enabled: true\n  messaging: on\n  election_timeout: 2\n  state_dir: /tmp/x/../y\n"
                + THREE);
        assertEquals(Path.of("/tmp/y"), abs.haMessaging().stateDir());
        assertEquals(Duration.ofSeconds(2), abs.haMessaging().electionTimeout());
    }

    @Test
    void twoVotersAreRefused() throws Exception {
        var pair = """
                ha:
                  enabled: true
                  messaging: %s
                  node_id: a
                  members:
                    - { id: a, address: "127.0.0.1:7101", http_url: "http://127.0.0.1:8101" }
                    - { id: b, address: "127.0.0.1:7102", http_url: "http://127.0.0.1:8102" }
                """;
        assertTrue(error(pair.formatted("on")).contains("two voters require a witness"));
        assertTrue(error(pair.formatted("auto")).contains("two voters require a witness"), "auto refuses it too");
        assertTrue(load(pair.formatted("off")).ok(), "off does not judge the cluster shape");
    }

    @Test
    void otherBadShapesAreRefused() throws Exception {
        String h = "ha:\n  enabled: true\n  messaging: on\n";
        assertTrue(error(h + "  node_id: a\n  members:\n    - { id: a, address: \"127.0.0.1:1\", http_url: \"http://x\" }\n"
                + "    - { id: w, address: \"127.0.0.1:2\", witness: true }\n").contains("needs 3 or more voters"));
        assertTrue(error(h + "  node_id: a\n  members:\n    - { id: a, address: \"127.0.0.1:1\", http_url: \"http://x\" }\n"
                + "    - { id: w, address: \"127.0.0.1:2\", witness: true }\n    - { id: v, address: \"127.0.0.1:3\", witness: true }\n")
                .contains("at most one witness"));
        assertTrue(error(h + "  node_id: a\n  members:\n    - { id: a, address: \"127.0.0.1:1\", http_url: \"http://x\" }\n"
                + "    - { id: b, address: \"127.0.0.1:2\", http_url: \"http://x\" }\n    - { id: c, address: \"127.0.0.1:3\", http_url: \"http://x\" }\n"
                + "    - { id: w, address: \"127.0.0.1:4\", witness: true }\n").contains("exactly 2 data nodes"));
        assertTrue(error(h + THREE.replace("node_id: a", "node_id: z")).contains("does not list ha.node_id z"));
        assertTrue(error(h + THREE.replace("  node_id: a\n", "")).contains("ha.node_id is required"));
        assertTrue(error(h + THREE.replace(", http_url: \"http://127.0.0.1:8102\"", "")).contains("needs http_url"));
        assertTrue(error(h + "  heartbeat: 500ms\n" + THREE).contains("not below ha.election_timeout"));
        var three = load(h.replace("on", "auto") + """
                  node_id: c
                  members:
                    - { id: a, address: "127.0.0.1:1", http_url: "http://x" }
                    - { id: b, address: "127.0.0.1:2", http_url: "http://x" }
                    - { id: c, address: "127.0.0.1:3", http_url: "http://x" }
                """);
        assertTrue(three.ok(), "three data nodes, no witness: " + three.errors());
        assertTrue(three.haMessaging().replicate());
    }

    @Test
    void onWithoutMembersFailsAndAutoWithoutMembersStaysLocal() throws Exception {
        assertTrue(error("ha:\n  enabled: true\n  messaging: on\n").contains("ha.messaging: on needs ha.members"));
        var auto = load("ha:\n  enabled: true\n  messaging: auto\n");
        assertTrue(auto.ok(), auto.errors().toString());
        assertFalse(auto.haMessaging().replicate());
        assertTrue(auto.warnings().isEmpty(), "auto with no member list is silent: " + auto.warnings());
        var autoNoHa = load("ha:\n  messaging: auto\n");
        assertFalse(autoNoHa.haMessaging().replicate());
    }

    @Test
    void membersNeedHaEnabled() throws Exception {
        assertTrue(error("ha:\n  messaging: on\n" + THREE).contains("needs ha.enabled: true"));
        var auto = load("ha:\n  messaging: auto\n" + THREE);
        assertTrue(auto.ok(), auto.errors().toString());
        assertFalse(auto.haMessaging().replicate());
        assertTrue(auto.warnings().stream().anyMatch(w -> w.contains("ha.enabled is false")), auto.warnings().toString());
    }

    static final String OFF_LOOPBACK = """
              node_id: a
              members:
                - { id: a, address: "127.0.0.1:7101", http_url: "http://127.0.0.1:8101" }
                - { id: b, address: "192.0.2.2:7102", http_url: "http://192.0.2.2:8102" }
                - { id: w, address: "127.0.0.1:7103", witness: true }
              topics: [orders]
            """;

    @Test
    void plaintextMembersOffLoopbackNeedTheExplicitOptIn() throws Exception {
        // #274 B1: the CpLaunch rule (plaintext voter links must be loopback), with an explicit opt-in.
        for (var mode : new String[] {"on", "auto"}) {
            var refused = error("ha:\n  enabled: true\n  messaging: " + mode + "\n" + OFF_LOOPBACK);
            assertTrue(refused.contains("must be loopback, not 192.0.2.2:7102"), refused);
            assertTrue(refused.contains("ha.messaging_insecure_plaintext: true"), refused);
            assertTrue(refused.contains("#277"), refused);
            assertFalse(refused.contains(KEY), refused);
            var optedIn = load("ha:\n  enabled: true\n  messaging: " + mode
                    + "\n  messaging_insecure_plaintext: true\n" + OFF_LOOPBACK);
            assertTrue(optedIn.ok(), optedIn.errors().toString());
            assertTrue(optedIn.haMessaging().replicate(), mode);
            assertTrue(optedIn.haMessaging().insecurePlaintext(), mode);
            var optedOut = error("ha:\n  enabled: true\n  messaging: " + mode
                    + "\n  messaging_insecure_plaintext: false\n" + OFF_LOOPBACK);
            assertTrue(optedOut.contains("must be loopback"), optedOut);
        }
        // Nothing replicates, so nothing is checked or resolved: off, and auto without ha.enabled.
        var off = load("ha:\n  enabled: true\n  messaging: off\n" + OFF_LOOPBACK);
        assertTrue(off.ok(), off.errors().toString());
        assertFalse(off.haMessaging().replicate());
        var autoLocal = load("ha:\n  messaging: auto\n" + OFF_LOOPBACK);
        assertTrue(autoLocal.ok(), autoLocal.errors().toString());
        assertFalse(autoLocal.haMessaging().replicate());
        // Loopback members need no opt-in, and the default is false.
        var loop = load("ha:\n  enabled: true\n  messaging: on\n" + THREE);
        assertTrue(loop.ok(), loop.errors().toString());
        assertFalse(loop.haMessaging().insecurePlaintext());
        assertFalse(HaMessagingSettings.NONE.insecurePlaintext());
        assertTrue(error("ha:\n  enabled: true\n  messaging: on\n  messaging_insecure_plaintext: yes please\n"
                + THREE).contains("ha.messaging_insecure_plaintext: expected true or false"));
        assertTrue(HaMessagingSettings.loopback("127.0.0.1"));
        assertTrue(HaMessagingSettings.loopback("::1"));
        assertTrue(HaMessagingSettings.loopback("localhost"));
        assertFalse(HaMessagingSettings.loopback("192.0.2.2"));
        assertFalse(HaMessagingSettings.loopback("0.0.0.0"));
    }

    @Test
    void badValuesAreRefused() throws Exception {
        String h = "ha:\n  enabled: true\n  messaging: off\n";
        assertTrue(error(h + "  colour: blue\n").contains("Unknown key ha.colour"));
        assertTrue(error(h + "  members: {}\n").contains("ha.members: expected a list"));
        assertTrue(error(h + "  members: [3]\n").contains("ha.members[0]: expected a mapping"));
        assertTrue(error(h + "  members: [{ id: a, address: \"x:1\", port: 3 }]\n").contains("Unknown key ha.members[0].port"));
        assertTrue(error(h + "  members: [{ id: \"a b\", address: \"x:1\" }]\n").contains("ha.members[0].id"));
        assertTrue(error(h + "  members: [{ address: \"x:1\" }]\n").contains("ha.members[0].id: expected a string"));
        for (var bad : new String[] {"x", "x:0", "x:1/p", "u@x:1", "x:1?q", "x:1#f", "[::1", ":1"}) {
            assertTrue(error(h + "  members: [{ id: a, address: \"" + bad + "\" }]\n").contains("expected host:port"), bad);
        }
        assertTrue(error(h + "  members: [{ id: a, address: \"x:1\", witness: yes please }]\n").contains("witness: expected true or false"));
        for (var bad : new String[] {"ftp://x", "x", "http://x?q", "http://x#f", "http://u@x", "http://", "http://[x"}) {
            assertTrue(error(h + "  members: [{ id: a, address: \"x:1\", http_url: \"" + bad + "\" }]\n")
                    .contains("http_url: expected an http:// or https:// URL"), bad);
        }
        for (var bad : new String[] {"0", "65536", "\"1\""}) {
            assertTrue(error(h + "  members: [{ id: a, address: \"x:1\", wire: " + bad + " }]\n").contains("wire: expected 1..65535"), bad);
        }
        assertTrue(error(h + "  members: [{ id: a, address: \"x:1\" }, { id: a, address: \"x:2\" }]\n").contains("duplicate id a"));
        assertTrue(error(h + "  members: [{ id: a, address: \"x:1\", wire: 2 }, { id: b, address: \"x:2\" }]\n").contains("duplicate wire 2"));
        for (var bad : new String[] {"\"a/b\"", "__offsets", "\".\"", "\"..\"", "\"\"", "3"}) {
            assertTrue(error(h + "  topics: [" + bad + "]\n").contains("ha.topics"), bad);
        }
        assertTrue(error(h + "  heartbeat: 0ms\n").contains("ha.heartbeat"));
        assertTrue(error(h + "  election_timeout: [1]\n").contains("ha.election_timeout: expected a string"));
        assertTrue(error(h + "  node_id: 7\n").contains("ha.node_id: expected a string"));
        assertTrue(error(h + "  state_dir: \"\"\n").contains("ha.state_dir: expected a string"));
    }

    /** #270: {@code ha.snapshot} defaults, overrides with sizes, and refusals. */
    @Test
    void snapshotBlock() throws Exception {
        var d = load("ha:\n  enabled: true\n  messaging: on\n" + THREE);
        assertTrue(d.ok(), d.errors().toString());
        assertEquals(HaMessagingSettings.Snapshot.DEFAULT, d.haMessaging().snapshot());
        var s = load("ha:\n  enabled: true\n  messaging: on\n" + THREE + "  snapshot:\n    every_entries: 500\n"
                + "    log_tail_entries: 0\n    chunk_bytes: 64k\n    install_rate: 8m\n    port_offset: 7\n"
                + "    log_segment_bytes: 1m\n").haMessaging().snapshot();
        assertEquals(new HaMessagingSettings.Snapshot(500, 0, 64 << 10, 8L << 20, 7, 1L << 20, 0), s);
        String h = "ha:\n  enabled: true\n  messaging: off\n  snapshot:\n";
        assertTrue(error(h + "    every: 1\n").contains("Unknown key ha.snapshot.every"));
        assertTrue(error(h + "    every_entries: 0\n").contains("ha.snapshot.every_entries"));
        assertTrue(error(h + "    every_entries: -3\n").contains("ha.snapshot.every_entries"));
        assertTrue(error(h + "    chunk_bytes: 1k\n").contains("ha.snapshot.chunk_bytes"));
        assertTrue(error(h + "    chunk_bytes: 17m\n").contains("ha.snapshot.chunk_bytes"));
        assertTrue(error(h + "    install_rate: 100\n").contains("ha.snapshot.install_rate"));
        assertTrue(error(h + "    port_offset: 0\n").contains("ha.snapshot.port_offset"));
        assertTrue(error(h + "    log_segment_bytes: 10\n").contains("ha.snapshot.log_segment_bytes"));
        assertTrue(error("ha:\n  enabled: true\n  messaging: off\n  snapshot: 3\n").contains("ha.snapshot"));
    }

    @Test
    void relativeStateDirNeedsABase() {
        var ex = assertThrows(ConfigException.class,
                () -> HaMessagingSettings.parse(Map.of("state_dir", "rel"), true, null));
        assertTrue(ex.getMessage().contains("relative path with no config directory"));
        assertSame(HaMessagingSettings.NONE, HaMessagingSettings.parse(null, true, null));
    }
}
