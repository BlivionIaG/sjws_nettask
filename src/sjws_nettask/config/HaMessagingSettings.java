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
 */
public record HaMessagingSettings(Mode mode, String nodeId, List<Member> members, Duration electionTimeout,
        Duration heartbeat, Path stateDir, Set<String> topics, boolean replicate, List<String> warnings) {

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

    /** The {@code ha:} keys this class reads. */
    static final List<String> KEYS = List.of("messaging", "node_id", "members", "election_timeout", "heartbeat",
            "state_dir", "topics");

    private static final List<String> MEMBER_KEYS = List.of("id", "address", "http_url", "witness", "wire");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /** Same rule as the messaging topic names ({@code LogTopics.NAME}). */
    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** No {@code ha.messaging} and no member list. */
    public static final HaMessagingSettings NONE = new HaMessagingSettings(DEFAULT_MODE, null, List.of(),
            DEFAULT_ELECTION_TIMEOUT, DEFAULT_HEARTBEAT, null, Set.of(), false, List.of());

    public HaMessagingSettings {
        members = List.copyOf(members);
        topics = Set.copyOf(topics);
        warnings = List.copyOf(warnings);
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
        var warnings = new ArrayList<String>();
        boolean replicate = decide(mode, haEnabled, nodeId, members, election, heartbeat, topics, warnings);
        return new HaMessagingSettings(mode, nodeId, members, election, heartbeat, stateDir, topics, replicate,
                warnings);
    }

    /** The startup decision; see the class doc. */
    static boolean decide(Mode mode, boolean haEnabled, String nodeId, List<Member> members, Duration election,
            Duration heartbeat, Set<String> topics, List<String> warnings) {
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
