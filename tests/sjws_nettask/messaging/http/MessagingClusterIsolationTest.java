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
package sjws_nettask.messaging.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.core.Request;
import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;
import sjws_nettask.messaging.ledger.Durability;

/**
 * Zero-cost seam for replication (durable-log-plan §3): messaging must not reference {@code sjws_nettask.cluster}, and
 * running produce/fetch/offsets plus a delivery partition (produce, poll, ack) loads no cluster class.
 */
class MessagingClusterIsolationTest {

    @TempDir Path dir;

    @Test
    void messagingSourcesNeverReferenceTheClusterPackage() throws Exception {
        var offenders = new ArrayList<String>();
        try (var walk = Files.walk(Path.of("src/sjws_nettask/messaging"))) {
            for (var p : (Iterable<Path>) walk::iterator) {
                if (p.toString().endsWith(".java") && Files.readString(p).contains("sjws_nettask.cluster")) offenders.add(p.toString());
            }
        }
        assertTrue(offenders.isEmpty(), "messaging must not reference sjws_nettask.cluster: " + offenders);
    }

    /** Child JVM: exercises every messaging append path once. */
    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]);
        try (var topics = new LogTopics(root.resolve("log"), Durability.ASYNC)) {
            var h = new MessagingHttp(topics);
            if (MessagingHttpTest.post(h, "t", MessagingHttpTest.recs("a", "b"), "group").status() != 200) throw new AssertionError("produce");
            if (h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=0")).status() != 200) throw new AssertionError("fetch");
            MessagingHttpTest.bytes(h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=0")));
            if (LogOffsetsTest.put(h, "t", "g", 1).status() != 200) throw new AssertionError("offsets");
        }
        var p = Partition.open(root.resolve("dlv"), "q", RetryPolicy.DEFAULT, System::currentTimeMillis);
        p.produce(1, 1, new byte[] {1}).durable().get(10, TimeUnit.SECONDS);
        p.subscribe("g", true);
        var d = p.poll("g", "c", 1, 30_000);
        if (d.size() != 1) throw new AssertionError("poll");
        p.ack("g", d.get(0).offset(), d.get(0).attempt());
        p.close();
        System.out.println("CHILD-OK");
    }

    @Test
    void noClusterClassLoadsOnTheMessagingPaths() throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var proc = new ProcessBuilder(java, "-Xlog:class+load=info", "-cp", System.getProperty("java.class.path"),
                MessagingClusterIsolationTest.class.getName(), dir.toString()).redirectErrorStream(true).start();
        var out = new String(proc.getInputStream().readAllBytes());
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, proc.exitValue(), out.length() > 4000 ? out.substring(out.length() - 4000) : out);
        assertTrue(out.contains("CHILD-OK"));
        assertTrue(out.contains("sjws_nettask.messaging.ledger.PartitionAppender"), "seam loaded");
        var cluster = out.lines().filter(l -> l.contains(" sjws_nettask.cluster.")).toList();
        assertTrue(cluster.isEmpty(), "cluster classes loaded: " + cluster);
    }
}
