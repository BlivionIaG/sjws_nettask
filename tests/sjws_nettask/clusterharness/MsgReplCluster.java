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
package sjws_nettask.clusterharness;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import sjws_nettask.messaging.ledger.RecordBatch;

/**
 * HA messaging as deployed (gate G3): two data nodes and a witness, each a separate {@code sjws_nettask.Sjws_nettask}
 * JVM started from its own config file ({@code ha.messaging}, {@code ha.members}), with real TCP Raft links, file
 * logs and the on-disk messaging ledger under one directory per node. Every Raft link runs through a
 * {@link TcpLinkProxy} in this JVM (each node's config lists the proxy as the peer's address), so
 * {@link #partition} cuts links without touching the processes. {@link #kill} is {@code kill -9}.
 */
final class MsgReplCluster implements AutoCloseable {

    static final String TOPIC = "orders";
    static final String KEY = "0123456789abcdef0123456789abcdef";
    static final List<String> NODES = List.of("a", "b", "w");
    static final Duration START_TIMEOUT = Duration.ofSeconds(40);

    private final Path dir;
    private final Map<String, Integer> raft = new HashMap<>();
    private final Map<String, Integer> http = new HashMap<>();
    /** {@code from + ">" + to} to the proxy for that direction. */
    private final Map<String, TcpLinkProxy> links = new LinkedHashMap<>();
    private final Map<String, Process> processes = new HashMap<>();
    private final Map<String, Integer> starts = new HashMap<>();
    final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2)).build();

    /** Kills the child JVMs if this test JVM ends without {@link #close} (a killed surefire fork, SIGTERM). */
    private final Thread reaper = new Thread(() -> this.processes.values().forEach(Process::destroyForcibly),
            "msgrepl-cluster-reaper");

    MsgReplCluster(Path dir) throws IOException {
        Runtime.getRuntime().addShutdownHook(this.reaper);
        this.dir = dir;
        for (var n : NODES) {
            this.raft.put(n, freePort());
            this.http.put(n, freePort());
        }
        for (var from : NODES) {
            for (var to : NODES) {
                if (!from.equals(to)) {
                    this.links.put(from + ">" + to, new TcpLinkProxy(this.raft.get(to)));
                }
            }
        }
    }

    static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static boolean data(String node) {
        return !"w".equals(node);
    }

    int httpPort(String node) {
        return this.http.get(node);
    }

    /** {@code node}'s config: its own Raft address is its listener, every peer is the proxy for that direction. */
    String config(String node, String mode) {
        var sb = new StringBuilder();
        sb.append("ha:\n  enabled: true\n  messaging: ").append(mode).append("\n  node_id: ").append(node)
                .append("\n  election_timeout: 1s\n  heartbeat: 100ms\n  topics: [").append(TOPIC)
                .append("]\n  state_dir: ").append(this.dir.resolve(node + "/raft-state")).append("\n  members:\n");
        for (var m : NODES) {
            int port = m.equals(node) ? this.raft.get(m) : this.links.get(node + ">" + m).port();
            sb.append("    - { id: ").append(m).append(", address: \"127.0.0.1:").append(port).append('"');
            if (data(m)) {
                sb.append(", http_url: \"http://127.0.0.1:").append(this.http.get(m)).append('"');
            } else {
                sb.append(", witness: true");
            }
            sb.append(" }\n");
        }
        return sb.toString();
    }

    void startAll(String mode) throws Exception {
        for (var n : NODES) {
            launch(n, mode);
        }
        for (var n : NODES) {
            awaitStarted(n);
        }
    }

    void start(String node, String mode) throws Exception {
        launch(node, mode);
        awaitStarted(node);
    }

    private Path log(String node) {
        return this.dir.resolve(node + "-" + this.starts.getOrDefault(node, 0) + ".log");
    }

    private void launch(String node, String mode) throws IOException {
        if (this.processes.containsKey(node) && this.processes.get(node).isAlive()) {
            throw new IllegalStateException(node + " is running");
        }
        this.starts.merge(node, 1, Integer::sum);
        var base = Files.createDirectories(this.dir.resolve(node));
        var root = Files.createDirectories(base.resolve("html"));
        var file = base.resolve("sjws.yaml");
        Files.writeString(file, config(node, mode));
        var javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var cmd = new ArrayList<>(List.of(javaBin, "-Xmx192m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1"));
        if (data(node)) {
            cmd.add("-Dsjws.messaging.enabled=true");
            cmd.add("-Dsjws.messaging.dir=" + base.resolve("messaging"));
        }
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), "sjws_nettask.Sjws_nettask",
                "--config=" + file, "--state-dir=" + base.resolve("state"), Integer.toString(this.http.get(node)),
                root.toString()));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log(node).toFile());
        for (var env : new String[] {"WEB_SERVER_DIR", "WEB_SERVER_PORT", "WEB_SERVER_CONFIG", "WEB_SERVER_ROLE",
                "SJWS_CP_NODE_ID", "SJWS_SWARM_ENABLED", "SJWS_MESSAGING_ENABLED", "SJWS_MESSAGING_DIR"}) {
            pb.environment().remove(env);
        }
        pb.environment().put("WEB_SERVER_HA_CLUSTER_KEY", KEY);
        this.processes.put(node, pb.start());
    }

    private void awaitStarted(String node) throws Exception {
        var p = this.processes.get(node);
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        String text = "";
        while (System.nanoTime() < deadline) {
            text = Files.readString(log(node));
            if (text.contains("Server started:")) {
                return;
            }
            if (!p.isAlive()) {
                break;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException(node + " did not start:\n" + text);
    }

    /**
     * Starts {@code node} expecting it to refuse: waits for the process to exit and returns its exit code; the log is
     * {@link #logText}.
     */
    int startExpectingExit(String node, String mode) throws Exception {
        launch(node, mode);
        var p = this.processes.get(node);
        if (!p.waitFor(START_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException(node + " did not exit:\n" + logText(node));
        }
        return p.exitValue();
    }

    /**
     * A produce to {@code node} that is known to be open when this returns: the headers and all but the last body
     * byte are written, so the server is still reading it. Killing the node now leaves the client an unknown outcome
     * (here: never stored). Close the socket to finish.
     */
    java.net.Socket holdOpen(String node, byte[] body) throws IOException {
        var s = new java.net.Socket("127.0.0.1", this.http.get(node));
        s.setSoTimeout(10_000);
        var out = s.getOutputStream();
        out.write(("POST /messaging/v1/topics/" + TOPIC + "/records HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: "
                + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body, 0, body.length - 1);
        out.flush();
        return s;
    }

    String logText(String node) throws IOException {
        return Files.readString(log(node));
    }

    /** {@code kill -9}: no shutdown hook, no flush. */
    void kill(String node) throws InterruptedException {
        var p = this.processes.get(node);
        p.destroyForcibly();
        p.waitFor(10, TimeUnit.SECONDS);
    }

    /** SIGTERM and wait: the server's shutdown hook runs. */
    void stop(String node) throws InterruptedException {
        var p = this.processes.get(node);
        p.destroy();
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }
    }

    boolean alive(String node) {
        var p = this.processes.get(node);
        return p != null && p.isAlive();
    }

    /** Cuts both directions between {@code x} and {@code y}. */
    void partition(String x, String y) {
        this.links.get(x + ">" + y).cut();
        this.links.get(y + ">" + x).cut();
    }

    void heal() {
        for (var l : this.links.values()) {
            l.heal();
        }
    }

    String url(String node, String query) {
        return "http://127.0.0.1:" + this.http.get(node) + "/messaging/v1/topics/" + TOPIC + "/records" + query;
    }

    HttpResponse<String> post(String url, byte[] body) throws IOException, InterruptedException {
        return this.client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The data node that answers a confirmed leader read, if any. */
    Optional<String> leader() {
        for (var n : NODES) {
            if (!data(n) || !alive(n)) {
                continue;
            }
            try {
                var r = this.client.send(HttpRequest.newBuilder(URI.create(url(n, "?offset=0&maxBytes=1")))
                        .timeout(Duration.ofSeconds(2)).header("read-consistency", "leader").GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 200) {
                    return Optional.of(n);
                }
            } catch (IOException e) {
                // not reachable: not the leader
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    String awaitLeader(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var l = leader();
            if (l.isPresent()) {
                return l.get();
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("no leader within " + timeout);
    }

    /**
     * Every record of the topic as {@code node} serves it, in offset order. {@code leaderRead} asks for a confirmed
     * leader read; otherwise it is the node's local applied log (what a follower serves).
     */
    List<String> records(String node, boolean leaderRead) throws IOException, InterruptedException {
        return entries(node, leaderRead).stream().map(Entry::id).toList();
    }

    /** One stored record: its offset as the server reports it (batch base offset plus position) and its text. */
    record Entry(long offset, String id) {
    }

    /** As {@link #records}, with each record's offset. */
    List<Entry> entries(String node, boolean leaderRead) throws IOException, InterruptedException {
        var out = new ArrayList<Entry>();
        long offset = 0;
        while (true) {
            var b = HttpRequest.newBuilder(URI.create(url(node, "?offset=" + offset + "&maxBytes=4194304")))
                    .timeout(Duration.ofSeconds(8)).GET();
            if (leaderRead) {
                b.header("read-consistency", "leader");
            }
            var r = this.client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) {
                throw new IOException(node + " fetch at " + offset + ": " + r.statusCode());
            }
            var in = ByteBuffer.wrap(r.body());
            long next = offset;
            while (in.hasRemaining()) {
                int len = in.getInt(in.position());
                RecordBatch batch;
                try {
                    batch = RecordBatch.decode(in.slice(in.position(), 4 + len));
                } catch (Exception e) {
                    throw new IOException("corrupt batch from " + node, e);
                }
                long at = batch.baseOffset();
                for (var rec : batch.records()) {
                    out.add(new Entry(at++, new String(rec, StandardCharsets.UTF_8)));
                }
                next = batch.nextOffset();
                in.position(in.position() + 4 + len);
            }
            if (next == offset) {
                return out;
            }
            offset = next;
        }
    }

    /** One record per POST: 4-byte length, then the text. */
    static byte[] frame(String text) {
        var b = text.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + b.length).putInt(b.length).put(b).array();
    }

    @Override
    public void close() {
        try {
            Runtime.getRuntime().removeShutdownHook(this.reaper);
        } catch (IllegalStateException e) {
            // already shutting down: the hook runs anyway
        }
        for (var p : this.processes.values()) {
            p.destroyForcibly();
        }
        for (var p : this.processes.values()) {
            try {
                p.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (var l : this.links.values()) {
            l.close();
        }
    }
}
