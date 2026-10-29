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

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code ha.messaging} switch and the HA messaging member list (proposal option B, gate G1). Plain data: this
 * class names no {@code sjws_nettask.cluster} or {@code sjws_nettask.msgrepl} type, so parsing it costs nothing when
 * replication stays off. {@link #replicate()} is the whole startup decision: true only when {@code ha.enabled} is
 * true and a member list of three or more voters names this node ({@code ha.node_id}). Everything else either stays
 * single-node or is refused while the config loads (exit 64).
 *
 * <ul>
 * <li>{@code off}: never replicates. A member list is parsed for syntax only.
 * <li>{@code on}: replicates, or refuses to start when the cluster is absent or invalid.
 * <li>{@code auto}: replicates with a valid cluster; with no member list it stays single-node, silently.
 * </ul>
 * An invalid member list is refused in both {@code on} and {@code auto}, a bare pair of voters included.
 *
 * <p>The member links are authenticated but not encrypted (#277). As {@code CpLaunch} refuses plaintext CP voter
 * links off loopback, a replicating node refuses (exit 64) a member list with any address that is not loopback,
 * unless {@code ha.messaging_insecure_plaintext: true} accepts plaintext on a private network.
 */
public record HaMessagingSettings(Mode mode, String nodeId, List<Member> members, Duration electionTimeout,
        Duration heartbeat, Path stateDir, Set<String> topics, boolean insecurePlaintext, boolean replicate,
        List<String> warnings, Snapshot snapshot) {

    /** {@code ha.messaging}. */
    public enum Mode {
        OFF, ON, AUTO;

        /** Lower-case, as written in the config file. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One {@code ha.members} entry.
     *
     * @param wire    wire id on the Raft transport (1-based position in the list unless set)
     * @param httpUrl base URL of this member's HTTP listener, for 307 redirects to the leader; null on the witness
     */
    public record Member(String id, int wire, String host, int port, String httpUrl, boolean witness) {
    }

    /** Default mode in this release: off. The flip to {@code auto} is a separate one-line change (gate list G1-G7). */
    public static final Mode DEFAULT_MODE = Mode.OFF;
    /** Default {@code ha.election_timeout}. */
    public static final Duration DEFAULT_ELECTION_TIMEOUT = Duration.ofSeconds(1);
    /** Default {@code ha.heartbeat}; the leader lease is two heartbeats. */
    public static final Duration DEFAULT_HEARTBEAT = Duration.ofMillis(100);
    /** Clock rate error allowed over one lease in the startup timing check (as {@code CpTiming}). */
    static final double DRIFT_RATIO = 0.01;
    /** Fixed margin in the startup timing check (as {@code CpTiming}). */
    static final Duration LEASE_MARGIN = Duration.ofMillis(10);

    /** The explicit opt-in for plaintext member links off loopback (until #277). */
    public static final String INSECURE_PLAINTEXT = "messaging_insecure_plaintext";
    /** The issue tracking encryption and sender binding on the member links; a default-on gate. */
    public static final String LINK_SECURITY_ISSUE = "#277";

    /**
     * {@code ha.snapshot} (#270): when each msg group snapshots its ledger and truncates its consensus log, and how a
     * lagging follower installs a peer's segments.
     *
     * @param everyEntries     applied entries between snapshots
     * @param logTailEntries   consensus entries kept behind a snapshot, so a briefly lagging follower catches up
     *                         from the log instead of an install
     * @param chunkBytes       install chunk size (each chunk has its own SHA-256)
     * @param installRate      install bytes per second each sending node allows, all sessions together
     * @param portOffset       the install listener's port is the member's Raft port plus this
     * @param logSegmentBytes  the consensus log rolls into a new file at this size, so truncation frees whole files
     */
    public record Snapshot(long everyEntries, long logTailEntries, int chunkBytes, long installRate, int portOffset,
            long logSegmentBytes, long maxInstallBytes) {

        /** Defaults: every 10 000 entries, 1 000 kept, 1 MiB chunks, 64 MiB/s, port + 1, 16 MiB log files, install size uncapped. */
        public static final Snapshot DEFAULT = new Snapshot(10_000, 1_000, 1 << 20, 64L << 20, 1, 16L << 20, 0);

        public Snapshot {
            if (everyEntries < 1) throw new ConfigException("ha.snapshot.every_entries: expected at least 1");
            if (logTailEntries < 0) throw new ConfigException("ha.snapshot.log_tail_entries: expected at least 0");
            if (chunkBytes < 4096 || chunkBytes > 16 << 20) {
                throw new ConfigException("ha.snapshot.chunk_bytes: expected 4k to 16m");
            }
            if (installRate < 4096) throw new ConfigException("ha.snapshot.install_rate: expected at least 4k");
            if (portOffset < 1 || portOffset > 1000) throw new ConfigException("ha.snapshot.port_offset: expected 1 to 1000");
            if (logSegmentBytes < 4096) throw new ConfigException("ha.snapshot.log_segment_bytes: expected at least 4k");
            if (maxInstallBytes < 0) throw new ConfigException("ha.snapshot.max_install_bytes: expected at least 0");
        }
    }

    /** The {@code ha:} keys this class reads. */
    static final List<String> KEYS = List.of("messaging", "node_id", "members", "election_timeout", "heartbeat",
            "state_dir", "topics", INSECURE_PLAINTEXT, "snapshot");


    private static final List<String> SNAPSHOT_KEYS = List.of("every_entries", "log_tail_entries", "chunk_bytes",
            "install_rate", "port_offset", "log_segment_bytes", "max_install_bytes");

    private static final List<String> MEMBER_KEYS = List.of("id", "address", "http_url", "witness", "wire");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /** Same rule as the messaging topic names ({@code LogTopics.NAME}). */
    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** No {@code ha.messaging} and no member list. */
    public static final HaMessagingSettings NONE = new HaMessagingSettings(DEFAULT_MODE, null, List.of(),
            DEFAULT_ELECTION_TIMEOUT, DEFAULT_HEARTBEAT, null, Set.of(), false, false, List.of(), Snapshot.DEFAULT);

    public HaMessagingSettings {
        members = List.copyOf(members);
        topics = Set.copyOf(topics);
        warnings = List.copyOf(warnings);
    }

    /** Without an explicit snapshot block (defaults) and with plaintext off. */
    public HaMessagingSettings(Mode mode, String nodeId, List<Member> members, Duration electionTimeout,
            Duration heartbeat, Path stateDir, Set<String> topics, boolean replicate, List<String> warnings) {
        this(mode, nodeId, members, electionTimeout, heartbeat, stateDir, topics, false, replicate, warnings,
                Snapshot.DEFAULT);
    }

    /** This node's entry, or null. */
    public Member self() {
        for (var m : this.members) {
            if (m.id().equals(this.nodeId)) {
                return m;
            }
        }
        return null;
    }

    /** True when this node is the witness voter: it votes on every group and stores no messages. */
    public boolean selfIsWitness() {
        var self = self();
        return self != null && self.witness();
    }

    /** {@code ha.state_dir}, or {@code fallback/msgrepl} when unset. */
    public Path stateDirOr(Path fallback) {
        return this.stateDir != null ? this.stateDir : fallback.resolve("msgrepl");
    }

    /**
     * Parses the {@code ha:} mapping (already checked for unknown keys).
     *
     * @param ha        the {@code ha:} mapping, or null when the file has none
     * @param haEnabled {@code ha.enabled}
     * @param base      config file directory, for a relative {@code state_dir}
     * @throws ConfigException on a bad value, or a cluster that {@code on}/{@code auto} must refuse
     */
    static HaMessagingSettings parse(Map<String, Object> ha, boolean haEnabled, Path base) {
        if (ha == null) {
            return NONE;
        }
        var mode = mode(ha.get("messaging"));
        String nodeId = ha.containsKey("node_id") ? id(ha.get("node_id"), "ha.node_id") : null;
        var members = members(ha.get("members"));
        var election = ha.containsKey("election_timeout")
                ? duration(ha.get("election_timeout"), "ha.election_timeout") : DEFAULT_ELECTION_TIMEOUT;
        var heartbeat = ha.containsKey("heartbeat") ? duration(ha.get("heartbeat"), "ha.heartbeat") : DEFAULT_HEARTBEAT;
        Path stateDir = null;
        if (ha.containsKey("state_dir")) {
            stateDir = path(ha.get("state_dir"), base);
        }
        var topics = topics(ha.get("topics"));
        boolean plaintext = false;
        if (ha.containsKey(INSECURE_PLAINTEXT)) {
            if (!(ha.get(INSECURE_PLAINTEXT) instanceof Boolean b)) {
                throw new ConfigException("ha." + INSECURE_PLAINTEXT + ": expected true or false");
            }
            plaintext = b;
        }
        var warnings = new ArrayList<String>();
        boolean replicate = decide(mode, haEnabled, nodeId, members, election, heartbeat, topics, plaintext,
                warnings);
        var snapshot = snapshot(ha.get("snapshot"));
        return new HaMessagingSettings(mode, nodeId, members, election, heartbeat, stateDir, topics, plaintext,
                replicate, warnings, snapshot);
    }

    private static Snapshot snapshot(Object raw) {
        if (raw == null) {
            return Snapshot.DEFAULT;
        }
        var map = YamlValues.mapping(raw, "ha.snapshot");
        YamlValues.knownKeys(map, SNAPSHOT_KEYS, "ha.snapshot");
        var d = Snapshot.DEFAULT;
        return new Snapshot(
                map.containsKey("every_entries") ? count(map.get("every_entries"), "ha.snapshot.every_entries") : d.everyEntries(),
                map.containsKey("log_tail_entries") ? count(map.get("log_tail_entries"), "ha.snapshot.log_tail_entries") : d.logTailEntries(),
                map.containsKey("chunk_bytes") ? (int) Math.min(Integer.MAX_VALUE, size(map.get("chunk_bytes"), "ha.snapshot.chunk_bytes")) : d.chunkBytes(),
                map.containsKey("install_rate") ? size(map.get("install_rate"), "ha.snapshot.install_rate") : d.installRate(),
                map.containsKey("port_offset") ? (int) Math.min(Integer.MAX_VALUE, count(map.get("port_offset"), "ha.snapshot.port_offset")) : d.portOffset(),
                map.containsKey("log_segment_bytes") ? size(map.get("log_segment_bytes"), "ha.snapshot.log_segment_bytes") : d.logSegmentBytes(),
                map.containsKey("max_install_bytes") ? size(map.get("max_install_bytes"), "ha.snapshot.max_install_bytes") : d.maxInstallBytes());
    }


    /** {@link #decide(Mode, boolean, String, List, Duration, Duration, Set, boolean, List)} without the opt-in. */
    static boolean decide(Mode mode, boolean haEnabled, String nodeId, List<Member> members, Duration election,
            Duration heartbeat, Set<String> topics, List<String> warnings) {
        return decide(mode, haEnabled, nodeId, members, election, heartbeat, topics, false, warnings);
    }

    /** The startup decision; see the class doc. */
    static boolean decide(Mode mode, boolean haEnabled, String nodeId, List<Member> members, Duration election,
            Duration heartbeat, Set<String> topics, boolean insecurePlaintext, List<String> warnings) {
        if (mode == Mode.OFF) {
            return false;
        }
        if (members.isEmpty()) {
            if (mode == Mode.ON) {
                throw new ConfigException("ha.messaging: on needs ha.members (3 or more voters, or 2 plus a witness)"
                        + " and ha.node_id naming this node");
            }
            return false; // auto without a member list: exactly today's single node
        }
        validateVoters(nodeId, members);
        checkTiming(election, heartbeat);
        if (!haEnabled) {
            if (mode == Mode.ON) {
                throw new ConfigException("ha.messaging: on needs ha.enabled: true (Raft frames are authenticated"
                        + " with ha.cluster_key)");
            }
            warnings.add("ha.members is set but ha.enabled is false: ha.messaging: auto stays single-node");
            return false;
        }
        checkPlaintext(members, insecurePlaintext);
        if (topics.isEmpty()) {
            warnings.add("ha.topics is empty: only consumer offsets are replicated; producing to any topic is"
                    + " refused until it is listed");
        }
        return true;
    }

    /**
     * Two data nodes plus one witness, or three or more data nodes. A bare pair is refused with the same words as
     * {@code VoterSet.majority} ({@code two voters require a witness}).
     */
    static void validateVoters(String nodeId, List<Member> members) {
        if (nodeId == null) {
            throw new ConfigException("ha.node_id is required with ha.members");
        }
        int witnesses = 0;
        boolean listed = false;
        for (var m : members) {
            if (m.witness()) {
                witnesses++;
            } else if (m.httpUrl() == null) {
                throw new ConfigException("ha.members: data node " + m.id() + " needs http_url");
            }
            listed |= m.id().equals(nodeId);
        }
        if (!listed) {
            throw new ConfigException("ha.members does not list ha.node_id " + nodeId);
        }
        int data = members.size() - witnesses;
        if (members.size() == 2 && witnesses == 0) {
            throw new ConfigException("ha.members: two voters require a witness (a bare pair cannot lose a node and"
                    + " still commit)");
        }
        if (members.size() < 3) {
            throw new ConfigException("ha.members needs 3 or more voters, or 2 data nodes plus a witness; got "
                    + members.size());
        }
        if (witnesses > 1) {
            throw new ConfigException("ha.members: at most one witness");
        }
        if (witnesses == 1 && data != 2) {
            throw new ConfigException("ha.members: a witness needs exactly 2 data nodes; got " + data);
        }
    }

    /**
     * Plaintext member links must be loopback (the {@code CpLaunch} rule for {@code cp.peers} without
     * {@code cp.tls}) unless {@code ha.messaging_insecure_plaintext} is true. Only reached when the node replicates,
     * so a single node resolves no address. A host that does not resolve counts as off loopback.
     */
    static void checkPlaintext(List<Member> members, boolean insecurePlaintext) {
        if (insecurePlaintext) {
            return;
        }
        for (var m : members) {
            if (!loopback(m.host())) {
                throw new ConfigException("ha.members: HA messaging member links are not encrypted ("
                        + LINK_SECURITY_ISSUE + ") and must be loopback, not " + m.host() + ":" + m.port() + "; set ha." + INSECURE_PLAINTEXT
                        + ": true to accept plaintext on a private member network; refusing to start");
            }
        }
    }

    static boolean loopback(String host) {
        try {
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /** {@code lease * (1 + 1%) + 10 ms < election_timeout}, the {@code CpTiming} rule on the msg timings. */
    static void checkTiming(Duration election, Duration heartbeat) {
        double lease = heartbeat.toNanos() * 2.0;
        double needed = lease * (1.0 + DRIFT_RATIO) + LEASE_MARGIN.toNanos();
        if (!(needed < election.toNanos())) {
            throw new ConfigException(String.format(Locale.ROOT,
                    "ha timing: lease %.1f ms (2 x ha.heartbeat) * 1.01 + 10 ms = %.1f ms is not below"
                            + " ha.election_timeout %.1f ms; refusing to start",
                    lease / 1e6, needed / 1e6, election.toNanos() / 1e6));
        }
    }

    private static long count(Object raw, String path) {
        if (!(raw instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < 0) {
            throw new ConfigException(path + ": expected a non-negative integer");
        }
        return n.longValue();
    }

    private static long size(Object raw, String path) {
        if (raw instanceof Number n) {
            return count(n, path);
        }
        return Sizes.parse(string(raw, path), Long.MAX_VALUE, path);
    }

    static Mode mode(Object raw) {
        if (raw == null) {
            return DEFAULT_MODE;
        }
        if (raw instanceof Boolean b) { // YAML 1.1 reads a bare on/off as a boolean
            return b ? Mode.ON : Mode.OFF;
        }
        if (raw instanceof String s) {
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "off":
                    return Mode.OFF;
                case "on":
                    return Mode.ON;
                case "auto":
                    return Mode.AUTO;
                default:
                    break;
            }
        }
        throw new ConfigException("ha.messaging: expected off, on or auto");
    }

    private static List<Member> members(Object raw) {
        if (raw == null) {
            return List.of();
        }
        var list = YamlValues.sequence(raw, "ha.members");
        var out = new ArrayList<Member>(list.size());
        var ids = new HashSet<String>();
        var wires = new HashSet<Integer>();
        for (int i = 0; i < list.size(); i++) {
            String where = "ha.members[" + i + "]";
            var map = YamlValues.mapping(list.get(i), where);
            YamlValues.knownKeys(map, MEMBER_KEYS, where);
            String id = id(map.get("id"), where + ".id");
            String address = string(map.get("address"), where + ".address");
            var hp = hostPort(address, where + ".address");
            boolean witness = false;
            if (map.containsKey("witness")) {
                if (!(map.get("witness") instanceof Boolean b)) {
                    throw new ConfigException(where + ".witness: expected true or false");
                }
                witness = b;
            }
            String url = map.containsKey("http_url") ? httpUrl(map.get("http_url"), where + ".http_url") : null;
            int wire = i + 1;
            if (map.containsKey("wire")) {
                if (!(map.get("wire") instanceof Integer w) || w < 1 || w > 65_535) {
                    throw new ConfigException(where + ".wire: expected 1..65535");
                }
                wire = w;
            }
            if (!ids.add(id)) {
                throw new ConfigException("ha.members: duplicate id " + id);
            }
            if (!wires.add(wire)) {
                throw new ConfigException("ha.members: duplicate wire " + wire);
            }
            out.add(new Member(id, wire, hp.host(), hp.port(), url, witness));
        }
        return out;
    }

    private static Set<String> topics(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        var out = new LinkedHashSet<String>();
        for (var t : YamlValues.sequence(raw, "ha.topics")) {
            var name = string(t, "ha.topics[]");
            if (!TOPIC.matcher(name).matches() || name.equals(".") || name.equals("..") || name.equals("__offsets")) {
                throw new ConfigException("ha.topics: invalid topic name " + name);
            }
            out.add(name);
        }
        return out;
    }

    private static String id(Object raw, String path) {
        var s = string(raw, path);
        if (!ID.matcher(s).matches()) {
            throw new ConfigException(path + ": expected 1-64 characters from A-Z a-z 0-9 . _ -");
        }
        return s;
    }

    private static String string(Object raw, String path) {
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ConfigException(path + ": expected a string");
        }
        return s.trim();
    }

    private static Duration duration(Object raw, String path) {
        if (raw instanceof Integer n) {
            return Durations.parse(Integer.toString(n), path);
        }
        return Durations.parse(string(raw, path), path);
    }

    private static Path path(Object raw, Path base) {
        var p = Path.of(string(raw, "ha.state_dir"));
        if (p.isAbsolute()) {
            return p.normalize();
        }
        if (base == null) {
            throw new ConfigException("ha.state_dir: relative path with no config directory");
        }
        return base.resolve(p).normalize();
    }

    record HostPort(String host, int port) {
    }

    /** {@code host:port} (IPv6 in brackets), as {@code cp.peers[].address}. */
    static HostPort hostPort(String value, String path) {
        URI uri;
        try {
            uri = new URI("ha://" + value);
        } catch (java.net.URISyntaxException e) {
            throw new ConfigException(path + ": expected host:port");
        }
        if (uri.getHost() == null || uri.getPort() < 1 || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new ConfigException(path + ": expected host:port");
        }
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return new HostPort(host, uri.getPort());
    }

    private static String httpUrl(Object raw, String path) {
        var s = string(raw, path);
        URI uri;
        try {
            uri = new URI(s);
        } catch (java.net.URISyntaxException e) {
            throw new ConfigException(path + ": expected an http:// or https:// URL");
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new ConfigException(path + ": expected an http:// or https:// URL");
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
