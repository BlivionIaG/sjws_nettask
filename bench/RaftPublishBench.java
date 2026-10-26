import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.cluster.Durability;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.GroupOptions;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.MemoryLogStorage;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.QuorumPolicy;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.ReplicationGroup;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.TcpTransport;

/**
 * Replicated publish on 3 in-process voters over TcpTransport (loopback), memory logs, RELAXED, background
 * Raft loops ({@code begin}). Runs unchanged against master and against #182 (lag metrics are read by
 * reflection and print n/a where they do not exist).
 *
 *   java -cp target/classes bench/RaftPublishBench.java publish  <producers> <seconds> [tickMicros]
 *       closed loop: each producer appends one 256 B entry and waits for its future. Reports ops/s, p50/p99/p99.9.
 *   java -cp target/classes bench/RaftPublishBench.java overload <producers> <seconds> <callbackMicros> [tickMicros]
 *       leader callback spins callbackMicros per entry; producers append open loop (no wait) with at most
 *       512 outstanding each. Samples deliveryLag/maxDeliveryLag every 200 ms; counts ApplyLagException.
 *
 * With {@code -Dbench.cpPeerTls=<dir>} the voters talk over the CONTROL voter links instead of plain sockets:
 * {@code cp.CpPeerSockets} over {@code cp-peer} mTLS 1.3, with {@code <dir>/ca.pem} and {@code <dir>/n<w>.pem},
 * {@code <dir>/n<w>.key} (certificate with URI SAN {@code urn:sjws:node:n<w>}, PKCS#8 key) for wires 1..3. The
 * sockets are built by reflection, pinning each voter's SAN when the build supports it (#175), so the same file runs
 * against builds before and after that change.
 */
public class RaftPublishBench {
    static final GroupId GROUP = new GroupId("bench");

    public static void main(String[] a) throws Exception {
        String mode = a[0];
        int producers = Integer.parseInt(a[1]);
        double secs = Double.parseDouble(a[2]);
        long cbMicros = mode.equals("overload") ? Long.parseLong(a[3]) : 0;
        long tickMicros = Long.parseLong(a.length > (mode.equals("overload") ? 4 : 3) ? a[mode.equals("overload") ? 4 : 3] : "1000");

        var key = ClusterKey.of("raft-bench-key-0123456789abcdef".getBytes(), null);
        var members = new ArrayList<Member>();
        var voters = new LinkedHashSet<NodeId>();
        for (int w = 1; w <= 3; w++) {
            var m = new Member(new NodeId(w, "n" + w), new SiteId("s"), new InetSocketAddress("127.0.0.1", freePort()), 1L,
                    MemberState.ALIVE, false);
            members.add(m);
            voters.add(m.id());
        }
        var options = new GroupOptions(Durability.RELAXED, 1 << 20, Duration.ofMillis(300), Duration.ofMillis(50), 1_000_000_000L);
        var nodes = new ArrayList<ReplicatedNode>();
        var transports = new ArrayList<TcpTransport>();
        var handles = new ArrayList<ReplicationGroup>();
        var applied = new AtomicLong();
        for (var m : members) {
            var tlsDir = System.getProperty("bench.cpPeerTls");
            var t = tlsDir == null ? TcpTransport.open(m.id(), members, key, Clock.SYSTEM, 4096)
                    : TcpTransport.open(m.id(), members, key, Clock.SYSTEM, 4096, cpPeerSockets(tlsDir, m, members));
            transports.add(t);
            var node = ReplicatedNode.open(m.id(), members, voters, QuorumPolicy.majority(), Clock.SYSTEM, new MemoryLogStorage(), t);
            nodes.add(node);
        }
        // Listeners: count entries; in overload the (eventual) leader's callback spins.
        long cbNanos = cbMicros * 1000;
        for (var node : nodes) {
            handles.add(node.openGroup(GROUP, options, e -> {
                applied.incrementAndGet();
                if (cbNanos > 0) {
                    long until = System.nanoTime() + cbNanos;
                    while (System.nanoTime() < until) {
                        Thread.onSpinWait();
                    }
                }
            }));
        }
        for (var node : nodes) {
            node.begin(Duration.ofNanos(tickMicros * 1000));
        }
        ReplicatedNode leader = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (leader == null && System.nanoTime() < deadline) {
            for (int i = 0; i < nodes.size(); i++) {
                if (handles.get(i).isLeader()) {
                    leader = nodes.get(i);
                }
            }
            Thread.sleep(10);
        }
        if (leader == null) {
            throw new IllegalStateException("no leader");
        }
        Method lag = method("deliveryLag"), maxLag = method("maxDeliveryLag");
        var payload = new byte[256];
        var lead = leader;
        // warm-up 2 s closed loop
        warm(lead, payload, producers);
        if (mode.equals("publish")) {
            publish(lead, payload, producers, secs);
        } else {
            overload(lead, payload, producers, secs, lag, maxLag, applied);
        }
        if (System.getProperty("bench.cpPeerTls") != null) {
            printCounters(transports);
        }
        for (var node : nodes) {
            node.close();
        }
        for (var t : transports) {
            t.close();
        }
        System.exit(0);
    }

    static void warm(ReplicatedNode lead, byte[] payload, int producers) throws Exception {
        long end = System.nanoTime() + 2_000_000_000L;
        var ts = new ArrayList<Thread>();
        for (int p = 0; p < producers; p++) {
            ts.add(Thread.ofPlatform().start(() -> {
                while (System.nanoTime() < end) {
                    try {
                        lead.append(GROUP, List.of(ByteBuffer.wrap(payload))).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // ignore during warm-up
                    }
                }
            }));
        }
        for (var t : ts) {
            t.join();
        }
    }

    static void publish(ReplicatedNode lead, byte[] payload, int producers, double secs) throws Exception {
        long end = System.nanoTime() + (long) (secs * 1e9);
        var lats = new long[producers][];
        var counts = new int[producers];
        var errors = new AtomicLong();
        var ts = new ArrayList<Thread>();
        long t0 = System.nanoTime();
        for (int p = 0; p < producers; p++) {
            int id = p;
            lats[id] = new long[1 << 16];
            ts.add(Thread.ofPlatform().start(() -> {
                while (System.nanoTime() < end) {
                    long s = System.nanoTime();
                    try {
                        lead.append(GROUP, List.of(ByteBuffer.wrap(payload))).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        continue;
                    }
                    if (counts[id] == lats[id].length) {
                        lats[id] = Arrays.copyOf(lats[id], counts[id] * 2);
                    }
                    lats[id][counts[id]++] = System.nanoTime() - s;
                }
            }));
        }
        for (var t : ts) {
            t.join();
        }
        double took = (System.nanoTime() - t0) / 1e9;
        int n = 0;
        for (int c : counts) {
            n += c;
        }
        var all = new long[n];
        int o = 0;
        for (int p = 0; p < producers; p++) {
            System.arraycopy(lats[p], 0, all, o, counts[p]);
            o += counts[p];
        }
        Arrays.sort(all);
        System.out.printf("publish producers=%d ops=%d ops/s=%.0f p50=%.1fus p99=%.1fus p99.9=%.1fus errors=%d%n", producers, n,
                n / took, pct(all, 0.50), pct(all, 0.99), pct(all, 0.999), errors.get());
    }

    static void overload(ReplicatedNode lead, byte[] payload, int producers, double secs, Method lag, Method maxLag,
            AtomicLong applied) throws Exception {
        long end = System.nanoTime() + (long) (secs * 1e9);
        var accepted = new AtomicLong();
        var lagRefused = new AtomicLong();
        var otherErrors = new AtomicLong();
        var ts = new ArrayList<Thread>();
        long applied0 = applied.get();
        for (int p = 0; p < producers; p++) {
            ts.add(Thread.ofPlatform().start(() -> {
                var outstanding = new java.util.concurrent.Semaphore(512);
                while (System.nanoTime() < end) {
                    try {
                        if (!outstanding.tryAcquire(50, TimeUnit.MILLISECONDS)) {
                            continue;
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                    CompletableFuture<?> f;
                    try {
                        f = lead.append(GROUP, List.of(ByteBuffer.wrap(payload)));
                    } catch (RuntimeException e) {
                        outstanding.release();
                        classify(e, lagRefused, otherErrors);
                        continue;
                    }
                    f.whenComplete((r, e) -> {
                        outstanding.release();
                        if (e == null) {
                            accepted.incrementAndGet();
                        } else {
                            classify(e, lagRefused, otherErrors);
                        }
                    });
                }
            }));
        }
        long t0 = System.nanoTime();
        var sampler = new StringBuilder();
        while (System.nanoTime() < end) {
            Thread.sleep(200);
            sampler.append(String.format(" %.1fs:%s/%s", (System.nanoTime() - t0) / 1e9, call(lag, lead), call(maxLag, lead)));
        }
        for (var t : ts) {
            t.join();
        }
        double took = (System.nanoTime() - t0) / 1e9;
        System.out.printf("overload producers=%d accepted/s=%.0f applied/s(all nodes)=%.0f applyLagRefused=%d otherErrors=%d%n",
                producers, accepted.get() / took, (applied.get() - applied0) / took, lagRefused.get(), otherErrors.get());
        System.out.println("lag/maxLag samples:" + sampler);
    }

    static void classify(Throwable e, AtomicLong lag, AtomicLong other) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().equals("ApplyLagException")) {
                lag.incrementAndGet();
                return;
            }
        }
        other.incrementAndGet();
    }

    static Method method(String name) {
        try {
            return ReplicatedNode.class.getMethod(name, GroupId.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    static String call(Method m, ReplicatedNode node) {
        if (m == null) {
            return "n/a";
        }
        try {
            return String.valueOf(m.invoke(node, GROUP));
        } catch (ReflectiveOperationException e) {
            return "err";
        }
    }

    static double pct(long[] sorted, double q) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        return sorted[Math.min(sorted.length - 1, (int) (q * sorted.length))] / 1000.0;
    }

    /** {@code cp.CpPeerSockets} for {@code self}: pinned (#175) when the build has the pinning constructor. */
    static TcpTransport.SocketFactory cpPeerSockets(String dir, Member self, List<Member> members) throws Exception {
        var d = java.nio.file.Path.of(dir);
        var wire = self.id().wire();
        var config = new sjws_nettask.cp.CpListenerConfig("cp-peer", d.resolve("n" + wire + ".pem"),
                d.resolve("n" + wire + ".key"), d.resolve("ca.pem"), null, true);
        var tls = sjws_nettask.cp.CpTls.serverContext(config);
        var cls = Class.forName("sjws_nettask.cp.CpPeerSockets");
        Object sockets;
        try {
            var ctor = cls.getDeclaredConstructor(sjws_nettask.cp.CpServerTls.class, List.class, String.class);
            var peerCtor = sjws_nettask.cp.CpConfig.Peer.class.getConstructor(int.class, String.class,
                    InetSocketAddress.class, boolean.class, String.class);
            var peers = new ArrayList<Object>();
            for (var m : members) {
                peers.add(peerCtor.newInstance(m.id().wire(), m.id().name(), m.address(), false,
                        "uri:urn:sjws:node:" + m.id().name()));
            }
            ctor.setAccessible(true);
            sockets = ctor.newInstance(tls, peers, self.id().name());
            System.err.println("bench: cp-peer mTLS, pinned SANs");
        } catch (NoSuchMethodException unpinned) {
            var ctor = cls.getDeclaredConstructor(sjws_nettask.cp.CpServerTls.class);
            ctor.setAccessible(true);
            sockets = ctor.newInstance(tls);
            System.err.println("bench: cp-peer mTLS, no pinning in this build");
        }
        PEER_SOCKETS.add(sockets);
        var delegate = (TcpTransport.SocketFactory) sockets;
        // Counts outbound dials (TcpTransport calls dial or connect once per outbound connection attempt).
        return (TcpTransport.SocketFactory) java.lang.reflect.Proxy.newProxyInstance(
                TcpTransport.SocketFactory.class.getClassLoader(), new Class<?>[] {TcpTransport.SocketFactory.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dial") || method.getName().equals("connect")) {
                        DIALS.incrementAndGet();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    static final List<Object> PEER_SOCKETS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final AtomicLong DIALS = new AtomicLong();

    /**
     * One line of identity and connection counters, summed over the voters: all expected 0 except dials, which is
     * one per outbound link (6 for 3 voters) plus any reconnects or dials retried while the peers start.
     */
    static void printCounters(List<TcpTransport> transports) {
        long mismatches = 0, heldOff = 0, handshakeFailures = 0;
        for (var t : transports) {
            mismatches += t.originMismatches();
            heldOff += t.originHeldOff();
            handshakeFailures += t.handshakeFailures();
        }
        String refused = "n/a";
        try {
            long r = 0;
            for (var s : PEER_SOCKETS) {
                var m = s.getClass().getDeclaredMethod("identityRefused");
                m.setAccessible(true);
                r += (long) m.invoke(s);
            }
            refused = String.valueOf(r);
        } catch (ReflectiveOperationException absent) {
            // a build without #175 has no identityRefused counter
        }
        long links = (long) transports.size() * (transports.size() - 1);
        System.out.printf("counters: originMismatches=%d originHeldOff=%d identityRefused=%s handshakeFailures=%d"
                + " dials=%d reconnects=%d%n", mismatches, heldOff, refused, handshakeFailures, DIALS.get(),
                DIALS.get() - links);
    }

    static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
