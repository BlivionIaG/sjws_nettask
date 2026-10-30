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
package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.clusterharness.EphemeralPorts;
import sjws_nettask.config.HaMessagingSettings;
import sjws_nettask.config.HaMessagingSettings.Member;

/** The HA messaging bootstrap on its own (gate G1). */
class HaMessagingTest {

    static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path dir;

    static int freePort() throws Exception {
        return EphemeralPorts.probe();
    }

    static HaMessagingSettings settings(String self, List<Member> members, boolean replicate) {
        return settings(self, members, false, replicate);
    }

    static HaMessagingSettings settings(String self, List<Member> members, boolean insecurePlaintext,
            boolean replicate) {
        return new HaMessagingSettings(HaMessagingSettings.Mode.ON, self, members, Duration.ofMillis(500),
                Duration.ofMillis(50), null, Set.of("t"), insecurePlaintext, replicate, List.of());
    }

    @Test
    void votersFollowTheShape() throws Exception {
        var three = List.of(new Member("a", 1, "127.0.0.1", 1, "http://a", false),
                new Member("b", 2, "127.0.0.1", 2, "http://b", false), new Member("c", 3, "127.0.0.1", 3, "http://c", false));
        var v = HaMessaging.voters(settings("a", three, true));
        assertEquals(3, v.voters().size());
        assertTrue(v.members().stream().noneMatch(m -> m.witness()));
        var withWitness = List.of(three.get(0), three.get(1), new Member("w", 9, "127.0.0.1", 9, null, true));
        var vw = HaMessaging.voters(settings("w", withWitness, true));
        assertTrue(vw.voters().contains(new NodeId(9, "w")));
        assertEquals(1, vw.members().stream().filter(m -> m.witness()).count());
        var pair = List.of(three.get(0), three.get(1));
        var refused = assertThrows(IllegalArgumentException.class, () -> HaMessaging.voters(settings("a", pair, true)));
        assertEquals("two voters require a witness", refused.getMessage(), "VoterSet still guards the pair");
    }

    @Test
    void refusesAConfigThatDoesNotReplicate() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> HaMessaging.start(settings("a", List.of(), false), dir, KEY, null, null, System.err));
        assertTrue(e.getMessage().contains("does not resolve"));
    }

    @Test
    void plaintextOffLoopbackWithoutTheOptInIsRefusedBeforeAnythingOpens() throws Exception {
        var members = List.of(new Member("a", 1, "192.0.2.1", 7101, "http://192.0.2.1", false),
                new Member("b", 2, "127.0.0.1", freePort(), "http://b", false),
                new Member("w", 3, "127.0.0.1", freePort(), null, true));
        var refused = assertThrows(IllegalArgumentException.class, () -> HaMessaging.start(
                settings("w", members, false, true), dir.resolve("w"), KEY, null, null, System.err));
        assertTrue(refused.getMessage().contains("must be loopback, not a (/192.0.2.1:7101)"), refused.getMessage());
        assertTrue(refused.getMessage().contains("ha.messaging_insecure_plaintext"), refused.getMessage());
        assertFalse(java.nio.file.Files.exists(dir.resolve("w")), "nothing opened");
    }

    @Test
    void witnessStartsWarnsAboutPlaintextOffLoopbackAndCloses() throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= EphemeralPorts.BIND_ATTEMPTS; attempt++) {
            var suffix = attempt == 1 ? "w" : ("w-" + attempt);
            try {
                var members = List.of(new Member("a", 1, "192.0.2.1", 7101, "http://192.0.2.1", false),
                        new Member("b", 2, "127.0.0.1", freePort(), "http://b", false),
                        new Member("w", 3, "127.0.0.1", freePort(), null, true));
                var err = new ByteArrayOutputStream();
                var ha = HaMessaging.start(settings("w", members, true, true), dir.resolve(suffix), KEY, KEY, null,
                        new PrintStream(err, true, StandardCharsets.UTF_8));
                try {
                    var text = err.toString(StandardCharsets.UTF_8);
                    assertTrue(text.contains("node=w (witness)"), text);
                    assertTrue(text.contains("authenticated (HMAC) but not encrypted"), text);
                    assertTrue(text.contains("ha.messaging_insecure_plaintext is set"), text);
                    assertTrue(text.contains("(#277)") && !text.contains("#175"), text);
                    assertFalse(text.contains(new String(KEY, StandardCharsets.US_ASCII)), text);
                    assertTrue(ha.toString().contains("replication on"));
                    assertEquals(new NodeId(3, "w"), ha.node().self());
                } finally {
                    ha.close();
                }
                assertTrue(java.nio.file.Files.isDirectory(dir.resolve(suffix + "/raft")));
                return;
            } catch (Exception e) {
                last = e;
                if (!EphemeralPorts.addressInUse(e) || attempt == EphemeralPorts.BIND_ATTEMPTS) {
                    throw e;
                }
                EphemeralPorts.logRetry("HaMessagingTest.witnessStarts", attempt, e);
            }
        }
        throw last;
    }
}
