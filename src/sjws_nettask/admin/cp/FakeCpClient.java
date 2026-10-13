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
package sjws_nettask.admin.cp;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * In-memory control plane for the console mockup and tests. Several {@link #endpoint(String)}
 * views share one state, so failover can be exercised: an endpoint can be made unavailable or a
 * follower (writes answer NOT_LEADER with the leader's name). Rollouts apply at once to matching
 * nodes that are UP; DOWN nodes stay pending until {@link #setHealth} brings them back.
 */
public final class FakeCpClient {

    private static final Pattern TOPIC = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private final ReentrantLock lock = new ReentrantLock();
    private final Clock clock;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<CpClient.ConfigBundle> versions = new ArrayList<>();
    private final Map<String, CpClient.Rollout> rollouts = new LinkedHashMap<>();
    private final Map<String, Object> idempotent = new HashMap<>();
    private final List<CpClient.AuditEntry> audit = new ArrayList<>();
    private final Map<String, String> endpointState = new HashMap<>(); // "down" | "follower"
    private String leader;
    private String actor = "admin";

    private static final class Node {
        final String id;
        final String role;
        final Map<String, String> labels;
        String health = "UP";
        long applied;

        Node(String id, String role, Map<String, String> labels) {
            this.id = id;
            this.role = role;
            this.labels = Map.copyOf(labels);
        }
    }

    public FakeCpClient(CpClient.ConfigBundle initial, Clock clock) {
        this.clock = clock;
        this.versions.add(initial.withVersion(1));
    }

    /** Three nodes over two zones and a canary, with a small config: what the demo shows. */
    public static FakeCpClient demo(Clock clock) {
        var s = new EnumMap<CpClient.Section, List<String>>(CpClient.Section.class);
        s.put(CpClient.Section.ROUTES, List.of("example.com/ -> static:./HTML", "example.com/api -> proxy:api"));
        s.put(CpClient.Section.FILTERS, List.of("observe", "security-headers"));
        s.put(CpClient.Section.ACCESS, List.of("deny:/.git", "allow:/.well-known/acme-challenge"));
        s.put(CpClient.Section.CERTS, List.of("example.com acme notAfter=2026-12-30"));
        s.put(CpClient.Section.TOPICS, List.of("orders", "orders.dlq"));
        var fake = new FakeCpClient(new CpClient.ConfigBundle(0, s), clock);
        fake.addNode("cp-1", "LEADER", Map.of("zone", "a", "tier", "edge"));
        fake.addNode("cp-2", "FOLLOWER", Map.of("zone", "b", "tier", "edge", "canary", "true"));
        fake.addNode("cp-3", "WITNESS", Map.of("zone", "c"));
        return fake;
    }

    public void addNode(String id, String role, Map<String, String> labels) {
        this.lock.lock();
        try {
            var n = new Node(id, role, labels);
            n.applied = head0();
            this.nodes.put(id, n);
            if (role.equals("LEADER")) {
                this.leader = id;
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void setHealth(String nodeId, String health) {
        this.lock.lock();
        try {
            this.nodes.get(nodeId).health = health;
            if (health.equals("UP")) {
                for (var r : List.copyOf(this.rollouts.values())) {
                    if (r.state() == CpClient.Rollout.State.RUNNING) {
                        var next = progress(r);
                        this.rollouts.put(r.id(), next);
                        if (next.state() == CpClient.Rollout.State.DONE) {
                            record("rollout-done", r.id());
                        }
                    }
                }
            }
        } finally {
            this.lock.unlock();
        }
    }

    /** {@code null} = normal, {@code "down"} = UNAVAILABLE, {@code "follower"} = NOT_LEADER on writes. */
    public void setEndpointState(String endpoint, String state) {
        this.lock.lock();
        try {
            if (state == null) {
                this.endpointState.remove(endpoint);
            } else {
                this.endpointState.put(endpoint, state);
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    /** A client view through one endpoint name. */
    public CpClient endpoint(String name) {
        return new View(name);
    }

    private long head0() {
        return this.versions.size();
    }

    /** Server-side validation, same idea as {@code ConfigSnapshot.compile} without side effects. */
    static CpClient.Validation check(CpClient.ConfigBundle b) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        for (var section : CpClient.Section.values()) {
            var name = section.name().toLowerCase(Locale.ROOT);
            var seen = new HashSet<String>();
            var list = b.entries(section);
            for (int i = 0; i < list.size(); i++) {
                var e = list.get(i);
                var where = name + "[" + (i + 1) + "] '" + e + "'";
                if (!seen.add(e)) {
                    warnings.add(where + ": duplicate");
                }
                String err = switch (section) {
                    case ROUTES -> e.contains(" -> ") && e.indexOf(" -> ") > 0 && !e.endsWith(" -> ") ? null
                            : "want 'host/prefix -> action'";
                    case FILTERS -> e.isBlank() || e.contains(" ") ? "want a filter name without spaces" : null;
                    case ACCESS -> e.startsWith("allow:/") || e.startsWith("deny:/") ? null
                            : "want 'allow:/path' or 'deny:/path'";
                    case CERTS -> e.isBlank() ? "empty cert reference" : null;
                    case TOPICS -> TOPIC.matcher(e).matches() ? null : "want [a-z0-9._-] starting with a letter or digit";
                };
                if (err != null) {
                    errors.add(where + ": " + err);
                }
            }
        }
        if (b.entries(CpClient.Section.ROUTES).isEmpty()) {
            warnings.add("routes: empty, every request falls through to the document root");
        }
        return new CpClient.Validation(errors, warnings);
    }

    private void record(String action, String detail) {
        this.audit.add(new CpClient.AuditEntry(this.audit.size() + 1, this.clock.instant(), this.actor, action, detail));
    }

    private CpClient.Rollout progress(CpClient.Rollout r) {
        var applied = new ArrayList<String>();
        for (var id : r.targets()) {
            var n = this.nodes.get(id);
            if (n.health.equals("UP")) {
                n.applied = Math.max(n.applied, r.version());
                applied.add(id);
            }
        }
        var state = applied.size() == r.targets().size() ? CpClient.Rollout.State.DONE : CpClient.Rollout.State.RUNNING;
        return new CpClient.Rollout(r.id(), r.version(), r.selector(), state, r.targets(), applied);
    }

    private final class View implements CpClient {

        private final String name;

        View(String name) {
            this.name = name;
        }

        private void reachable() throws CpException {
            if ("down".equals(FakeCpClient.this.endpointState.get(this.name))) {
                throw new CpException(CpException.Kind.UNAVAILABLE, this.name + " does not answer");
            }
        }

        private void writable() throws CpException {
            reachable();
            if ("follower".equals(FakeCpClient.this.endpointState.get(this.name))) {
                throw new CpException(CpException.Kind.NOT_LEADER, this.name + " is a follower",
                        FakeCpClient.this.leader);
            }
        }

        @SuppressWarnings("unchecked")
        private <T> T once(String key, java.util.function.Supplier<T> action) {
            if (key != null && FakeCpClient.this.idempotent.containsKey(key)) {
                return (T) FakeCpClient.this.idempotent.get(key);
            }
            var result = action.get();
            if (key != null) {
                FakeCpClient.this.idempotent.put(key, result);
            }
            return result;
        }

        @Override
        public String endpoint() {
            return this.name;
        }

        @Override
        public ClusterView cluster() throws CpException {
            lock.lock();
            try {
                reachable();
                long head = head0();
                var views = new ArrayList<NodeView>();
                for (var n : nodes.values()) {
                    views.add(new NodeView(n.id, n.role, n.labels, n.health, n.applied, head - n.applied));
                }
                return new ClusterView(leader, 1, head, views);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public long head() throws CpException {
            lock.lock();
            try {
                reachable();
                return head0();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public ConfigBundle config(long version) throws CpException {
            lock.lock();
            try {
                reachable();
                if (version < 1 || version > versions.size()) {
                    throw new CpException(CpException.Kind.NOT_FOUND, "version " + version);
                }
                return versions.get((int) version - 1);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Validation validate(ConfigBundle draft) throws CpException {
            lock.lock();
            try {
                reachable();
                return check(draft);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public long stage(ConfigBundle draft, long base, String key) throws CpException {
            lock.lock();
            try {
                writable();
                if (key != null && idempotent.containsKey(key)) {
                    return (Long) idempotent.get(key);
                }
                if (base != head0()) {
                    throw new CpException(CpException.Kind.CONFLICT,
                            "head is version " + head0() + ", draft is based on " + base);
                }
                var v = check(draft);
                if (!v.ok()) {
                    throw new CpException(CpException.Kind.INVALID, String.join("; ", v.errors()));
                }
                return once(key, () -> {
                    long g = head0() + 1;
                    versions.add(draft.withVersion(g));
                    record("stage", "version " + g + " (base " + base + ", "
                            + ConfigDiff.diff(versions.get((int) base - 1), draft).size() + " changes)");
                    return g;
                });
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Rollout rollout(long version, String selector, String key) throws CpException {
            lock.lock();
            try {
                writable();
                if (key != null && idempotent.containsKey(key)) {
                    return rollouts.get(((Rollout) idempotent.get(key)).id());
                }
                if (version < 1 || version > versions.size()) {
                    throw new CpException(CpException.Kind.NOT_FOUND, "version " + version);
                }
                LabelSelector sel;
                try {
                    sel = LabelSelector.parse(selector);
                } catch (IllegalArgumentException e) {
                    throw new CpException(CpException.Kind.INVALID, e.getMessage());
                }
                var targets = nodes.values().stream().filter(n -> sel.matches(n.labels)).map(n -> n.id).toList();
                if (targets.isEmpty()) {
                    throw new CpException(CpException.Kind.INVALID, "selector '" + sel + "' matches no node");
                }
                return once(key, () -> {
                    var id = "r" + (rollouts.size() + 1);
                    record("rollout", id + ": version " + version + " to " + sel + " " + targets);
                    var r = progress(new Rollout(id, version, sel.toString(), Rollout.State.RUNNING, targets,
                            List.of()));
                    rollouts.put(id, r);
                    return r;
                });
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Rollout rolloutStatus(String id) throws CpException {
            lock.lock();
            try {
                reachable();
                var r = rollouts.get(id);
                if (r == null) {
                    throw new CpException(CpException.Kind.NOT_FOUND, "rollout " + id);
                }
                return r;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public long rollback(long to, String key) throws CpException {
            lock.lock();
            try {
                writable();
                if (key != null && idempotent.containsKey(key)) {
                    return (Long) idempotent.get(key);
                }
                if (to < 1 || to > versions.size()) {
                    throw new CpException(CpException.Kind.NOT_FOUND, "version " + to);
                }
                return once(key, () -> {
                    long g = head0() + 1;
                    versions.add(versions.get((int) to - 1).withVersion(g));
                    record("rollback", "version " + g + " = content of " + to);
                    var r = progress(new Rollout("r" + (rollouts.size() + 1), g, "*", Rollout.State.RUNNING,
                            List.copyOf(nodes.keySet()), List.of()));
                    rollouts.put(r.id(), r);
                    return g;
                });
            } finally {
                lock.unlock();
            }
        }

        @Override
        public List<AuditEntry> audit(long afterSeq, int limit) throws CpException {
            lock.lock();
            try {
                reachable();
                return audit.stream().filter(a -> a.seq() > afterSeq).limit(Math.max(0, limit)).toList();
            } finally {
                lock.unlock();
            }
        }
    }
}
