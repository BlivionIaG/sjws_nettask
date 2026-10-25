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

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.cluster.TcpTransport;
import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.swarm.peer.PeerConfig;
import sjws_nettask.swarm.peer.PeerLink;
import sjws_nettask.swarm.peer.PeerStartup;

/**
 * #236 gate G3: the cost of one forwarded hop. Two {@link PeerLink}s in one JVM on loopback; node b forwards
 * closed-loop produces of {@code bytes} to node a, which applies them to a real {@link Partition} and answers.
 * The baseline is the same produce applied locally ({@code Partition.produce}); the hop cost is the difference.
 *
 * <pre>
 * java -XX:+UseZGC -Xms1g -Xmx1g -cp target/classes bench/PeerHopBench.java &lt;dir&gt; plain|tls &lt;ops&gt; &lt;bytes&gt; [certDir]
 * </pre>
 * {@code tls} reads {@code certDir/{a,b}.pem, {a,b}.key, ca.pem} (bench/peer-hop-certs.sh makes them) and goes
 * through {@link PeerStartup}, i.e. the production mTLS path with pinned SANs.
 */
public class PeerHopBench {

    public static void main(String[] args) throws Exception {
        var dir = Files.createDirectories(Path.of(args[0]));
        var mode = args[1];
        int ops = Integer.parseInt(args[2]);
        int bytes = Integer.parseInt(args[3]);
        var payload = new byte[bytes];
        var policy = new RetryPolicy(3, 100, 1_000);

        // local baseline: same partition code, no hop
        try (var local = Partition.open(dir.resolve("local"), "fwd.local", policy, System::currentTimeMillis)) {
            var lat = new long[ops];
            for (int i = 0; i < ops / 2; i++) {
                local.produce(1, i + 1, payload, Durability.NONE);
            }
            for (int i = 0; i < ops; i++) {
                long t = System.nanoTime();
                local.produce(1, ops + i + 1, payload, Durability.NONE);
                lat[i] = System.nanoTime() - t;
            }
            report("local", lat);
        }

        int pa;
        int pb;
        try (var s1 = new ServerSocket(0); var s2 = new ServerSocket(0)) {
            pa = s1.getLocalPort();
            pb = s2.getLocalPort();
        }
        var map = "a=1@127.0.0.1:" + pa + ",b=2@127.0.0.1:" + pb;
        var parts = new ConcurrentHashMap<String, Partition>();
        PeerLink.Owner owner = (from, topic) -> parts.computeIfAbsent(topic, t -> {
            try {
                return Partition.open(dir.resolve("owner"), t, policy, System::currentTimeMillis);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        var key = "peer-hop-bench-key-0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        PeerLink a;
        PeerLink b;
        if (mode.equals("tls")) {
            var certs = Path.of(args[4]);
            a = PeerStartup.open(PeerStartup.check(props(map, "a", certs)::get, Map.of("SJWS_SWARM_PEER_KEY",
                    new String(key, StandardCharsets.US_ASCII)), "a"), owner, PeerLink.Limits.DEFAULT);
            b = PeerStartup.open(PeerStartup.check(props(map, "b", certs)::get, Map.of("SJWS_SWARM_PEER_KEY",
                    new String(key, StandardCharsets.US_ASCII)), "b"), PeerLink.Owner.NONE, PeerLink.Limits.DEFAULT);
        } else {
            var ck = ClusterKey.of(key, null);
            a = PeerLink.open(PeerConfig.parse(map, "a"), ck, TcpTransport.SocketFactory.PLAIN, owner,
                    PeerLink.Limits.DEFAULT);
            b = PeerLink.open(PeerConfig.parse(map, "b"), ck, TcpTransport.SocketFactory.PLAIN, PeerLink.Owner.NONE,
                    PeerLink.Limits.DEFAULT);
        }
        try (a; b) {
            for (int i = 0; i < Math.max(20_000, ops / 2); i++) {
                check(b.produce("a", "fwd.t", payload, Durability.NONE, 5_000).get(10, TimeUnit.SECONDS));
            }
            var lat = new long[ops];
            for (int i = 0; i < ops; i++) {
                long t = System.nanoTime();
                check(b.produce("a", "fwd.t", payload, Durability.NONE, 5_000).get(10, TimeUnit.SECONDS));
                lat[i] = System.nanoTime() - t;
            }
            report("forward-" + mode, lat);
        } finally {
            for (var p : parts.values()) {
                p.close();
            }
        }
    }

    static Map<String, String> props(String map, String self, Path certs) {
        var p = new HashMap<String, String>();
        p.put("sjws.swarm.peers", map);
        p.put("sjws.swarm.peer.tls.cert", certs.resolve(self + ".pem").toString());
        p.put("sjws.swarm.peer.tls.key", certs.resolve(self + ".key").toString());
        p.put("sjws.swarm.peer.tls.ca", certs.resolve("ca.pem").toString());
        return p;
    }

    static void check(PeerLink.Result r) {
        if (!r.ok()) {
            throw new IllegalStateException("forwarded produce failed: " + r.outcome());
        }
    }

    static void report(String name, long[] lat) {
        var s = lat.clone();
        Arrays.sort(s);
        double mean = Arrays.stream(s).average().orElse(0) / 1000.0;
        System.out.printf("%s ops=%d mean_us=%.1f p50_us=%.1f p99_us=%.1f p999_us=%.1f max_us=%.1f%n", name, s.length,
                mean, s[s.length / 2] / 1000.0, s[(int) (s.length * 0.99)] / 1000.0, s[(int) (s.length * 0.999)] / 1000.0,
                s[s.length - 1] / 1000.0);
    }
}
