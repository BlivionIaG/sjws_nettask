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
package sjws_nettask.swarm.peer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.SiteId;

/**
 * The static swarm peer map (#236 §9.5, R11 discovery: static, no gossip): {@code sjws.swarm.peers} /
 * {@code SJWS_SWARM_PEERS} as comma-separated {@code <node>=<wire>@<host>:<port>[#<san>]}. Every entry pins a node
 * name, a wire id (1..65535) and the certificate identity its mTLS connections must present; the SAN defaults to
 * {@code urn:sjws:node:<node>}. This node must be listed under its own {@code sjws.swarm.node}.
 *
 * @param self this node's entry
 * @param nodes every entry, this node included, in configuration order
 */
public record PeerConfig(Node self, List<Node> nodes) {

    public static final String PROPERTY = "sjws.swarm.peers";
    public static final String ENV = "SJWS_SWARM_PEERS";
    /** The URI SAN prefix a node certificate carries its node name under (as for the CP links). */
    public static final String SAN_PREFIX = "urn:sjws:node:";
    /** Node names: the same safe set #234 builds topic names from. */
    static final Pattern NODE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /** One pinned peer. */
    public record Node(String name, int wire, InetSocketAddress address, String san) {
        public NodeId id() {
            return new NodeId(wire, name);
        }
    }

    public PeerConfig {
        nodes = List.copyOf(nodes);
    }

    /** More than one node is listed, so the peer transport (and mTLS) is required. */
    public boolean multiNode() {
        return nodes.size() > 1;
    }

    /** The node pinned to {@code wire}, or null. */
    public Node byWire(int wire) {
        for (var n : nodes) {
            if (n.wire() == wire) {
                return n;
            }
        }
        return null;
    }

    /** The node whose pinned SAN is {@code san}, or null. */
    public Node bySan(String san) {
        for (var n : nodes) {
            if (n.san().equals(san)) {
                return n;
            }
        }
        return null;
    }

    /** The node named {@code name}, or null. */
    public Node byName(String name) {
        for (var n : nodes) {
            if (n.name().equals(name)) {
                return n;
            }
        }
        return null;
    }

    /** {@link sjws_nettask.cluster.TcpTransport}'s member list (one site; addresses as pinned). */
    public List<Member> members() {
        var out = new ArrayList<Member>();
        for (var n : nodes) {
            out.add(new Member(n.id(), new SiteId("swarm"), n.address(), 1L, MemberState.ALIVE, false));
        }
        return out;
    }

    /**
     * Parses and checks the peer map. Refuses (with the offending entry, never a secret) an empty or malformed
     * entry, an unsafe node name, a wire outside 1..65535, a duplicate name, wire, address or SAN, and a map that
     * does not list {@code selfName}.
     */
    public static PeerConfig parse(String raw, String selfName) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(PROPERTY + " is empty");
        }
        var nodes = new LinkedHashMap<String, Node>();
        var wires = new HashSet<Integer>();
        var addresses = new HashSet<InetSocketAddress>();
        var sans = new HashSet<String>();
        for (var part : raw.split(",")) {
            var entry = part.trim();
            var node = entry(entry);
            if (nodes.putIfAbsent(node.name(), node) != null || !wires.add(node.wire())
                    || !addresses.add(node.address()) || !sans.add(node.san())) {
                throw new IllegalArgumentException(PROPERTY + ": duplicate node, wire, address or SAN in '" + entry + "'");
            }
        }
        var self = nodes.get(selfName);
        if (self == null) {
            throw new IllegalArgumentException(PROPERTY + " does not list this node (sjws.swarm.node=" + selfName + ")");
        }
        return new PeerConfig(self, new ArrayList<>(nodes.values()));
    }

    private static Node entry(String entry) {
        int eq = entry.indexOf('=');
        int at = entry.indexOf('@');
        int hash = entry.indexOf('#');
        int colon = entry.lastIndexOf(':', (hash < 0 ? entry.length() : hash) - 1);
        int end = hash < 0 ? entry.length() : hash;
        if (eq < 1 || at < eq + 2 || colon < at + 2 || colon > end - 2) {
            throw new IllegalArgumentException(PROPERTY + ": expected <node>=<wire>@<host>:<port>[#<san>], got '" + entry + "'");
        }
        var name = entry.substring(0, eq);
        if (!NODE.matcher(name).matches()) {
            throw new IllegalArgumentException(PROPERTY + ": unsafe node name in '" + entry + "'");
        }
        int wire;
        int port;
        try {
            wire = Integer.parseInt(entry.substring(eq + 1, at));
            port = Integer.parseInt(entry.substring(colon + 1, end));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PROPERTY + ": bad wire or port in '" + entry + "'");
        }
        if (wire < 1 || wire > 0xFFFF || port < 1 || port > 0xFFFF) {
            throw new IllegalArgumentException(PROPERTY + ": wire and port must be 1..65535 in '" + entry + "'");
        }
        var host = entry.substring(at + 1, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        var san = hash < 0 ? SAN_PREFIX + name : entry.substring(hash + 1);
        if (san.isBlank()) {
            throw new IllegalArgumentException(PROPERTY + ": empty SAN in '" + entry + "'");
        }
        return new Node(name, wire, new InetSocketAddress(host, port), san);
    }

    /** Settings lookup used by {@link PeerStartup}: property first, then environment. */
    static String setting(java.util.function.Function<String, String> props, Map<String, String> env, String property,
            String envName) {
        var v = props.apply(property);
        if (v == null || v.isBlank()) {
            v = env.get(envName);
        }
        return v == null || v.isBlank() ? null : v.trim();
    }
}
