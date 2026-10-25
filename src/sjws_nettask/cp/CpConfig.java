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
package sjws_nettask.cp;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import sjws_nettask.config.Durations;

/**
 * CONTROL-role settings (control-plane-hosting.md §4). Precedence is JVM property {@code sjws.cp.*}, then
 * environment {@code SJWS_CP_*}, then the YAML {@code cp:} block, like {@code OptionalModules}. The role itself
 * is not here: it stays {@code --role} / {@code WEB_SERVER_ROLE} / {@code node.mode} ({@link NodeLifecycle#role}).
 *
 * <p>Some keys are read from the config file only, never from a property or the environment:
 * {@code peers}, {@code rootPubSha256}, {@code insecureSkipPermissionChecks}, {@code minBundleSerial},
 * {@code maxClockDriftRatio} and {@code leaseSafetyMargin}. Setting {@code insecureSkipPermissionChecks}
 * through a property or the environment is refused rather than ignored, so a wrong deployment fails loudly.
 */
public record CpConfig(String nodeId, List<Peer> peers, InetSocketAddress watchListen, InetSocketAddress adminListen,
        CpKeyConfig keys, double maxClockDriftRatio, Duration leaseSafetyMargin, List<String> warnings) {

    /** Keys of the YAML {@code cp:} block. {@code tls} belongs to Security's listener config and is not read here. */
    static final List<String> YAML_KEYS = List.of("nodeId", "peers", "watch", "admin", "stateDir", "keyDir", "rootPub",
            "rootPubSha256", "insecureSkipPermissionChecks", "minBundleSerial", "maxClockDriftRatio",
            "leaseSafetyMargin", "tls");

    /**
     * One voter. {@code wire} is the Raft wire id and must be the same on every voter; it defaults to the
     * 1-based position in {@code cp.peers}, so list the peers in the same order everywhere or set it.
     * {@code san} is the certificate identity (a URI or DNS subject alternative name, exact match) this voter's
     * {@code cp-peer} certificate must carry; required for every entry when {@code cp.tls} is set (#175), null
     * otherwise.
     */
    public record Peer(int wire, String id, InetSocketAddress address, boolean witness, String san) {
        public Peer {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(address, "address");
            if (wire < 1) {
                throw new IllegalArgumentException("cp.peers wire must be positive");
            }
        }

        /** A voter with no pinned certificate identity (loopback development links only). */
        public Peer(int wire, String id, InetSocketAddress address, boolean witness) {
            this(wire, id, address, witness, null);
        }
    }

    /**
     * A pinned SAN: printable ASCII without spaces or {@code *}, at most 255 characters. Matching is exact, so a
     * {@code *} would never act as a wildcard; it is refused as a likely mistake.
     */
    static final java.util.regex.Pattern SAN = java.util.regex.Pattern.compile("[\\x21-\\x29\\x2B-\\x7E]{1,255}");

    public CpConfig {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(keys, "keys");
        peers = List.copyOf(peers);
        warnings = List.copyOf(warnings);
    }

    /** This voter's entry, or null for a single-voter (dev) control plane with no {@code cp.peers}. */
    public Peer self() {
        for (var peer : peers) {
            if (peer.id().equals(nodeId)) {
                return peer;
            }
        }
        return null;
    }

    /** True when this process is the witness voter (vote-only; never loads {@link KeyRing}). */
    public boolean selfIsWitness() {
        var self = self();
        return self != null && self.witness();
    }

    public static CpConfig fromSystem(Map<String, Object> yaml, Path base) {
        return parse(System::getProperty, System.getenv(), yaml, base);
    }

    /**
     * @param yaml the raw {@code cp:} block, or null
     * @param base directory for relative paths in the file, or null
     * @throws IllegalArgumentException naming the offending key
     */
    public static CpConfig parse(Function<String, String> properties, Map<String, String> env,
            Map<String, Object> yaml, Path base) {
        var y = yaml == null ? Map.<String, Object>of() : yaml;
        var unknown = new ArrayList<String>();
        for (var key : y.keySet()) {
            if (!YAML_KEYS.contains(key)) {
                unknown.add("cp." + key);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown key " + String.join(", ", unknown));
        }
        if (properties.apply("sjws.cp.insecureSkipPermissionChecks") != null
                || env.get("SJWS_CP_INSECURE_SKIP_PERMISSION_CHECKS") != null) {
            throw new IllegalArgumentException(
                    "cp.insecureSkipPermissionChecks is accepted only in the config file, not a property or env");
        }
        var warnings = new ArrayList<String>();
        String nodeId = required(pick(properties, env, y, "nodeId", "NODE_ID"), "cp.nodeId");
        Path stateDir = path(required(pick(properties, env, y, "stateDir", "STATE_DIR"), "cp.stateDir"), base);
        Path keyDir = path(required(pick(properties, env, y, "keyDir", "KEY_DIR"), "cp.keyDir"), base);
        Path rootPub = path(required(pick(properties, env, y, "rootPub", "ROOT_PUB"), "cp.rootPub"), base);
        var watch = address(required(pickNamed(properties, env, nested(y, "watch"), "listen", "sjws.cp.watch.listen",
                "SJWS_CP_WATCH_LISTEN"), "cp.watch.listen"), "cp.watch.listen");
        var admin = address(required(pickNamed(properties, env, nested(y, "admin"), "listen", "sjws.cp.admin.listen",
                "SJWS_CP_ADMIN_LISTEN"), "cp.admin.listen"), "cp.admin.listen");
        String pin = text(y.get("rootPubSha256"), "cp.rootPubSha256");
        boolean insecure = false;
        if (y.containsKey("insecureSkipPermissionChecks")) {
            if (!(y.get("insecureSkipPermissionChecks") instanceof Boolean b)) {
                throw new IllegalArgumentException("cp.insecureSkipPermissionChecks must be true or false");
            }
            insecure = b;
        }
        if (insecure) {
            warnings.add("WARN: cp.insecureSkipPermissionChecks is ON: key file owner/mode checks are skipped."
                    + " Development only.");
        }
        long minSerial = 0L;
        if (y.containsKey("minBundleSerial")) {
            minSerial = wholeNumber(y.get("minBundleSerial"), "cp.minBundleSerial");
        }
        double ratio = CpTiming.DEFAULT_DRIFT_RATIO;
        if (y.containsKey("maxClockDriftRatio")) {
            if (!(y.get("maxClockDriftRatio") instanceof Number n)) {
                throw new IllegalArgumentException("cp.maxClockDriftRatio must be a number");
            }
            ratio = n.doubleValue();
            if (!(ratio >= 0.0 && ratio <= 0.5)) {
                throw new IllegalArgumentException("cp.maxClockDriftRatio must be between 0 and 0.5");
            }
        }
        Duration margin = CpTiming.DEFAULT_MARGIN;
        if (y.containsKey("leaseSafetyMargin")) {
            var raw = y.get("leaseSafetyMargin");
            if (raw instanceof Number) {
                throw new IllegalArgumentException("cp.leaseSafetyMargin needs a unit, e.g. 10ms");
            }
            try {
                margin = Durations.parse(text(raw, "cp.leaseSafetyMargin"), "cp.leaseSafetyMargin");
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
        var peers = peers(y.get("peers"));
        validateVoters(nodeId, peers, warnings);
        if (y.get("tls") != null) {
            requirePinnedSans(peers);
        }
        var keys = new CpKeyConfig(keyDir, rootPub, stateDir, pin, insecure, minSerial);
        return new CpConfig(nodeId, peers, watch, admin, keys, ratio, margin, warnings);
    }

    /**
     * Voter counts (§6.3, #142): 3 or 5 full voters, or 2 full + 1 witness. A bare 2-voter set is refused.
     * No {@code peers} at all is a single-voter dev control plane and logs a warning.
     */
    static void validateVoters(String nodeId, List<Peer> peers, List<String> warnings) {
        if (peers.isEmpty()) {
            warnings.add("WARN: cp.peers is empty: single-voter control plane, no failover. Development only.");
            return;
        }
        var ids = new HashSet<String>();
        var wires = new HashSet<Integer>();
        var sans = new HashSet<String>();
        int full = 0;
        int witnesses = 0;
        boolean selfListed = false;
        for (var peer : peers) {
            if (!ids.add(peer.id())) {
                throw new IllegalArgumentException("cp.peers: duplicate id " + peer.id());
            }
            if (!wires.add(peer.wire())) {
                throw new IllegalArgumentException("cp.peers: duplicate wire " + peer.wire());
            }
            if (peer.san() != null && !sans.add(peer.san())) {
                throw new IllegalArgumentException("cp.peers: duplicate san " + peer.san());
            }
            if (peer.witness()) {
                witnesses++;
            } else {
                full++;
            }
            selfListed |= peer.id().equals(nodeId);
        }
        if (!selfListed) {
            throw new IllegalArgumentException("cp.peers does not list cp.nodeId " + nodeId);
        }
        boolean ok = (witnesses == 0 && (full == 3 || full == 5)) || (witnesses == 1 && full == 2);
        if (!ok) {
            throw new IllegalArgumentException("cp.peers needs 3 or 5 voters, or 2 voters plus 1 witness; got " + full
                    + " voter(s) and " + witnesses + " witness(es)");
        }
    }

    /**
     * With {@code cp.tls} set, every {@code cp.peers} entry must pin its certificate identity ({@code san}): the
     * voter links bind each certificate to exactly one {@code cp.peers} id (#175).
     */
    static void requirePinnedSans(List<Peer> peers) {
        for (int i = 0; i < peers.size(); i++) {
            if (peers.get(i).san() == null) {
                throw new IllegalArgumentException("cp.peers[" + i + "].san is required with cp.tls: each voter's"
                        + " cp-peer certificate is bound to its cp.peers id (URI or DNS SAN, e.g. urn:sjws:node:"
                        + peers.get(i).id() + ")");
            }
        }
    }

    private static List<Peer> peers(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("cp.peers must be a list");
        }
        var out = new ArrayList<Peer>(list.size());
        int index = 0;
        for (var item : list) {
            index++;
            String where = "cp.peers[" + (index - 1) + "]";
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(where + " must be a mapping");
            }
            for (var key : map.keySet()) {
                if (!Set.of("id", "address", "witness", "wire", "san").contains(key)) {
                    throw new IllegalArgumentException("Unknown key " + where + "." + key);
                }
            }
            String id = required(text(map.get("id"), where + ".id"), where + ".id");
            var address = address(required(text(map.get("address"), where + ".address"), where + ".address"),
                    where + ".address");
            boolean witness = false;
            if (map.containsKey("witness")) {
                if (!(map.get("witness") instanceof Boolean b)) {
                    throw new IllegalArgumentException(where + ".witness must be true or false");
                }
                witness = b;
            }
            int wire = index;
            if (map.containsKey("wire")) {
                long w = wholeNumber(map.get("wire"), where + ".wire");
                if (w < 1 || w > 65_535) {
                    throw new IllegalArgumentException(where + ".wire must be 1..65535");
                }
                wire = (int) w;
            }
            String san = text(map.get("san"), where + ".san");
            if (san != null && !SAN.matcher(san).matches()) {
                throw new IllegalArgumentException(where + ".san must be a URI or DNS name: printable ASCII, no"
                        + " spaces or '*', at most 255 characters (matched exactly; no wildcards)");
            }
            out.add(new Peer(wire, id, address, witness, san));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> y, String key) {
        var raw = y.get(key);
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("cp." + key + " must be a mapping");
        }
        for (var k : map.keySet()) {
            if (!"listen".equals(k)) {
                throw new IllegalArgumentException("Unknown key cp." + key + "." + k);
            }
        }
        return (Map<String, Object>) map;
    }

    private static String pick(Function<String, String> properties, Map<String, String> env, Map<String, Object> y,
            String yamlKey, String envSuffix) {
        return pickNamed(properties, env, y, yamlKey, "sjws.cp." + yamlKey, "SJWS_CP_" + envSuffix);
    }

    private static String pickNamed(Function<String, String> properties, Map<String, String> env,
            Map<String, Object> y, String yamlKey, String property, String envName) {
        var value = properties.apply(property);
        if (value == null || value.isBlank()) {
            value = env.get(envName);
        }
        if (value == null || value.isBlank()) {
            value = text(y.get(yamlKey), "cp." + yamlKey);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String text(Object raw, String key) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String s)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return s;
    }

    private static long wholeNumber(Object raw, String key) {
        if (!(raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException(key + " must be a whole number");
        }
        return ((Number) raw).longValue();
    }

    private static String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required for the control role");
        }
        return value.trim();
    }

    private static Path path(String value, Path base) {
        var p = Path.of(value);
        return p.isAbsolute() || base == null ? p : base.resolve(p);
    }

    static InetSocketAddress address(String value, String key) {
        URI uri;
        try {
            uri = new URI("cp://" + value.trim());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(key + ": expected host:port");
        }
        if (uri.getHost() == null || uri.getPort() < 0 || uri.getPath() != null && !uri.getPath().isEmpty()
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(key + ": expected host:port");
        }
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return InetSocketAddress.createUnresolved(host, uri.getPort());
    }
}
