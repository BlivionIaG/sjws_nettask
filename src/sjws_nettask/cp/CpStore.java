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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterTransport;
import sjws_nettask.cluster.Durability;
import sjws_nettask.cluster.FileLogStorage;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.GroupOptions;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.QuorumPolicy;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.ReplicationGroup;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.VoterSet;
import sjws_nettask.config.ConfigCodec;
import sjws_nettask.config.ConfigDiff;
import sjws_nettask.config.ConfigDocument;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.ConfigValidator;
import sjws_nettask.config.MessagingSettings;

/**
 * The {@code cp-config} log (one voter, or one of 3 or 5), the signed snapshot history, and the admin operations.
 * The log directory is {@code <stateDir>/cp-config} and is not a messaging ledger.
 */
public final class CpStore implements AutoCloseable {

    public static final GroupId GROUP = CpGroup.GROUP;
    private static final byte PROPOSE = 1;
    private static final byte SIGNED = 5;
    private static final byte RBAC = 4;
    private static final byte REVOKE = 6;
    private static final byte ROLLOUT = 7;
    private static final byte ADVANCE = 8;
    /** Wraps a mutation so a retried Idempotency-Key is not applied twice after replay. */
    private static final byte IDEM = 9;
    private static final byte JOIN_ISSUE = 10;
    private static final byte JOIN_CONSUME = 11;
    private static final int HISTORY = 8;

    private final ReplicatedNode node;
    /** The voter's log, closed with the store so a restart can reopen the same directory. */
    private FileLogStorage ownedStorage;
    private ReplicationGroup group;
    private ControlLeadership leadership;
    /** Swapped as a whole by {@link #useKeys} (SIGHUP, control-plane-hosting.md §2.4). */
    private volatile KeyRing keys;
    private final Map<String, sjws_nettask.controlplane.Role> policy = new LinkedHashMap<>();
    private sjws_nettask.controlplane.Rbac rights;
    private sjws_nettask.controlplane.AuditLog audit;
    private final List<String> revoked = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> selectors = new ArrayList<>();
    private final List<SignedSnapshot> history = new ArrayList<>();
    private final List<ConfigDocument> documents = new ArrayList<>();
    private final Map<String, Cached> replay = new LinkedHashMap<>();
    private final Map<String, Reported> reported = new LinkedHashMap<>();
    private final JoinTokens joins = new JoinTokens();
    private java.nio.file.Path auditFile;
    private Staged staged;
    private int stage;

    /** Newest activated envelope, for watch threads; written only where {@code history} grows. */
    private volatile SignedSnapshot latest;
    /** Guards the watch wait and the state watch threads touch: {@code reported} and {@code joins}. */
    private final java.util.concurrent.locks.ReentrantLock watchLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition activatedChanged = watchLock.newCondition();

    private CpStore(ReplicatedNode node, KeyRing keys) {
        this.node = node;
        this.keys = keys;
    }

    /** Opens a store whose first admin is the mTLS identity {@code urn:sjws:admin:root}. */
    public static CpStore open(java.nio.file.Path stateDir, KeyRing keys, Clock clock) throws IOException {
        return open(stateDir, keys, clock, "urn:sjws:admin:root");
    }

    /**
     * @param adminSan URI SAN or subject DN of the first admin certificate. There is no bearer token.
     */
    public static CpStore open(java.nio.file.Path stateDir, KeyRing keys, Clock clock, String adminSan)
            throws IOException {
        var id = new NodeId(1, "cp-1");
        var member = new Member(id, new SiteId("local"), new InetSocketAddress("127.0.0.1", 21001), 1L,
                MemberState.ALIVE, false);
        var storage = new FileLogStorage(stateDir.resolve("cp-config"));
        var node = ReplicatedNode.open(id, List.of(member), Set.of(id), QuorumPolicy.majority(), clock, storage,
                new SimTransport());
        return attach(node, id, stateDir, keys, adminSan);
    }

    /**
     * One full voter of a static {@code cp-config} voter set (control-plane-hosting.md §1 and §6): 3 or 5
     * full voters, or 2 full voters plus a witness ({@link CpWitness}), majority over all of them, every voter
     * on the same {@code transport}. Each full voter applies the committed log, so a follower answers watches
     * from its applied state; writes still need {@link ControlLeadership#leaderReady()}.
     */
    public static CpStore open(java.nio.file.Path stateDir, KeyRing keys, Clock clock, String adminSan, NodeId self,
            VoterSet voters, ClusterTransport transport) throws IOException {
        int full = 0;
        int witnesses = 0;
        for (var member : voters.members()) {
            if (!voters.voters().contains(member.id())) {
                continue;
            }
            if (member.witness()) {
                witnesses++;
                if (member.id().equals(self)) {
                    throw new IllegalArgumentException("a witness voter is hosted by CpWitness, not CpStore");
                }
            } else {
                full++;
            }
        }
        boolean ok = (witnesses == 0 && (full == 3 || full == 5)) || (witnesses == 1 && full == 2);
        if (!ok) {
            throw new IllegalArgumentException("cp-config needs 3 or 5 voters, or 2 voters plus 1 witness, not "
                    + full + " + " + witnesses + " witness");
        }
        var storage = new FileLogStorage(stateDir.resolve("cp-config"));
        var node = ReplicatedNode.open(self, voters, clock, storage, transport);
        var store = attach(node, self, stateDir, keys, adminSan);
        store.ownedStorage = storage;
        return store;
    }

    private static CpStore attach(ReplicatedNode node, NodeId id, java.nio.file.Path stateDir, KeyRing keys,
            String adminSan) throws IOException {
        var options = groupOptions();
        var store = new CpStore(node, keys);
        store.auditFile = stateDir.resolve("audit.jsonl");
        store.audit = new sjws_nettask.controlplane.AuditLog(store.auditFile, java.time.Clock.systemUTC(), false);
        store.policy.put(canonicalSan(adminSan), sjws_nettask.controlplane.Role.ADMIN);
        store.rebuildRights();
        store.group = node.openGroup(GROUP, options, entry -> store.onCommitted(entry.entry()));
        store.leadership = ControlLeadership.attach(node, GROUP, store.group, id);
        return store;
    }

    /** The {@code cp-config} group timings; the CONTROL start checks them with {@link CpTiming#check}. */
    public static GroupOptions groupOptions() {
        return CpGroup.options();
    }

    public ControlLeadership leadership() {
        return leadership;
    }

    /** Status binding for {@link sjws_nettask.service.NodeEnrollment#attach}. systemd active is not readiness. */
    public sjws_nettask.service.ControlPlaneBinding binding() {
        long version = activated() == null ? 0L : activated().version();
        var lead = leadership;
        if (lead == null) {
            return new StatusBinding("pending", 0L);
        }
        return StatusBinding.control(lead.isLeader(), lead.leaderReady(), version);
    }

    /** Control node: {@code /readyz} and {@code leaderReady()}. Neither systemd nor {@code isLeader()} alone. */
    public boolean ready(boolean readyzOk) {
        return NodeLifecycle.controlReady(readyzOk, leadership != null && leadership.leaderReady());
    }

    /** The control role does not bind the content port. */
    public boolean bindsContentPort() {
        return NodeLifecycle.bindsContentPort(NodeLifecycle.Startup.CONTROL, false);
    }

    public static NodeLifecycle.Startup resolveRole(String cli, String env, String yaml) {
        return NodeLifecycle.role(cli, env, yaml);
    }

    public NodeLifecycle.DataReadiness dataPlane(boolean controlPlaneUp, boolean hasLastGood,
            boolean pastMaxStaleness) {
        return NodeLifecycle.dataPlane(controlPlaneUp, hasLastGood, pastMaxStaleness);
    }

    /**
     * Issues a one-shot join token (replicated). Only the SHA-256 of {@code secret} is stored.
     */
    public Outcome issueJoinToken(String tokenId, byte[] secret, long nowMillis, long ttlMillis) {
        var bad = JoinTokens.checkIssue(tokenId, secret, ttlMillis);
        if (bad != null) {
            return Outcome.error(400, bad);
        }
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        long expires = nowMillis + ttlMillis;
        try {
            append(joinEntry(JOIN_ISSUE, tokenId, JoinTokens.sha256(secret), expires, nowMillis));
        } catch (ExecutionException e) {
            return Outcome.error(503, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.error(503, "log");
        }
        return Outcome.ok("{\"token\":" + LastGoodFiles.jsonString(tokenId) + ",\"expiresAtMillis\":" + expires + "}", 0);
    }

    /**
     * Presents a join token. The first successful use burns it through a replicated consume entry
     * (atomic: of two concurrent first uses one wins, the other is "used"). A retry from the node that
     * joined, with the same token, returns the recorded join ({@code replay}) without consuming again.
     */
    public JoinTokens.Result presentJoin(String nodeId, String tokenId, byte[] secret, long nowMillis) {
        var hash = secret == null || secret.length < 16 ? null : JoinTokens.sha256(secret);
        var decision = joins.evaluate(nodeId, tokenId, hash, nowMillis);
        if (!decision.accepted() || decision.replay()) {
            return decision;
        }
        if (!leadership.leaderReady()) {
            return new JoinTokens.Result(null, false, "leader not ready");
        }
        try {
            append(joinEntry(JOIN_CONSUME, nodeId, tokenId, hash, nowMillis));
        } catch (ExecutionException e) {
            return new JoinTokens.Result(null, false, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new JoinTokens.Result(null, false, "log");
        }
        var join = joins.joined(nodeId);
        if (join != null && join.tokenId().equals(tokenId)) {
            return new JoinTokens.Result(join, false, null);
        }
        return new JoinTokens.Result(null, false, "used");
    }

    private static ByteBuffer joinEntry(byte type, String a, String b, long n1, long n2) {
        var ra = a.getBytes(StandardCharsets.UTF_8);
        var rb = b.getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(1 + 4 + ra.length + 4 + rb.length + 16);
        buffer.put(type);
        put(buffer, ra);
        put(buffer, rb);
        buffer.putLong(n1);
        buffer.putLong(n2);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer joinEntry(byte type, String nodeId, String tokenId, String hash, long now) {
        var rn = nodeId.getBytes(StandardCharsets.UTF_8);
        var rt = tokenId.getBytes(StandardCharsets.UTF_8);
        var rh = hash.getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(1 + 12 + rn.length + rt.length + rh.length + 8);
        buffer.put(type);
        put(buffer, rn);
        put(buffer, rt);
        put(buffer, rh);
        buffer.putLong(now);
        buffer.flip();
        return buffer;
    }

    /**
     * Watch credential. A node that has joined keeps authenticating with the same token id and secret
     * until it is revoked (the one-shot rule applies to joining, not to every long poll); any other
     * presentation goes through {@link #presentJoin}, which consumes an issued token once. Only the
     * secret's SHA-256 is held; a revoked node is refused before any comparison. Interim until the
     * node-to-CP mTLS replaces this credential.
     */
    public JoinTokens.Result authenticateWatch(String nodeId, String tokenId, byte[] secret, long nowMillis) {
        if (nodeId != null && isRevoked(nodeId)) {
            return new JoinTokens.Result(null, false, "revoked");
        }
        var join = nodeId == null ? null : joins.joined(nodeId);
        if (join != null && JoinTokens.isHash(join.secretHash()) && secret != null && secret.length >= 16 && join.tokenId().equals(tokenId)
                && java.security.MessageDigest.isEqual(join.secretHash().getBytes(StandardCharsets.US_ASCII),
                        JoinTokens.sha256(secret).getBytes(StandardCharsets.US_ASCII))) {
            return new JoinTokens.Result(join, true, null);
        }
        return presentJoin(nodeId, tokenId, secret, nowMillis);
    }

    /**
     * The activated envelope once its version is above {@code afterVersion}, waiting up to
     * {@code waitMillis}; null on timeout or interrupt (interrupt flag kept). Watch threads only.
     */
    public SignedSnapshot awaitActivated(long afterVersion, long waitMillis) {
        long left = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMillis));
        watchLock.lock();
        try {
            while (true) {
                var now = latest;
                if (now != null && now.version() > afterVersion) {
                    return now;
                }
                if (left <= 0L) {
                    return null;
                }
                left = activatedChanged.awaitNanos(left);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            watchLock.unlock();
        }
    }

    /** True when {@code nodeId} is on the revoke denylist. */
    public boolean isRevoked(String nodeId) {
        return revoked.contains(nodeId);
    }

    private void publishLatest(SignedSnapshot snapshot) {
        watchLock.lock();
        try {
            latest = snapshot;
            activatedChanged.signalAll();
        } finally {
            watchLock.unlock();
        }
    }

    public void publishBinding(sjws_nettask.service.NodeEnrollment enrollment) throws java.io.IOException {
        enrollment.attach(binding());
    }

    public sjws_nettask.controlplane.Rbac rights() {
        return rights;
    }

    public sjws_nettask.controlplane.Rbac.Decision authorize(sjws_nettask.controlplane.Rbac.Credential credential,
            sjws_nettask.controlplane.Permission permission, String target) {
        long version = activated() == null ? 0L : activated().version();
        return rights.authorize(credential, permission, target, version);
    }

    public KeyRing keys() {
        return keys;
    }

    /** Installs a fully loaded ring; the caller has already verified it. Never a half-loaded ring. */
    void useKeys(KeyRing fresh) {
        this.keys = java.util.Objects.requireNonNull(fresh, "keys");
    }

    public void drive() {
        node.drive();
    }

    public void start() {
        node.begin(Duration.ofMillis(50));
    }

    public boolean adminEnrolled() {
        return policy.containsValue(sjws_nettask.controlplane.Role.ADMIN);
    }

    public List<SignedSnapshot> history() {
        return List.copyOf(history);
    }

    public SignedSnapshot activated() {
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    public Outcome propose(String yaml) {
        return propose(yaml, null);
    }

    /** {@code key} is the Idempotency-Key. A repeat of the same request returns the first outcome. */
    public Outcome propose(String yaml, String key) {
        var fp = fingerprint("POST", "/cp/v1/config", yaml);
        var hit = replayed(key, fp);
        if (hit != null) {
            return hit;
        }
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        if (forbidden(yaml)) {
            return Outcome.error(400, "rbac and trust are not part of a proposal");
        }
        if (staged != null) {
            return Outcome.error(409, "a proposal is already staged");
        }
        ConfigLoader.Loaded loaded;
        try {
            loaded = parse(yaml);
        } catch (IOException e) {
            return Outcome.error(400, "config");
        }
        if (!loaded.ok() || loaded.document() == null) {
            var message = loaded.errors().isEmpty() ? "configuration rejected" : loaded.errors().get(0);
            return Outcome.error(400, message);
        }
        var errors = ConfigValidator.validate(loaded.document());
        if (!errors.isEmpty()) {
            return Outcome.error(400, errors.get(0));
        }
        if (!loaded.document().messaging().assignment().isEmpty()) {
            return Outcome.error(400, "assignment is computed by the control plane");
        }
        var previous = documents.isEmpty() ? MessagingSettings.disabled()
                : documents.get(documents.size() - 1).messaging();
        var destructive = MessagingSettings.destructiveErrors(previous, loaded.document().messaging());
        if (!destructive.isEmpty()) {
            return Outcome.error(400, destructive.get(0));
        }
        long version = (history.isEmpty() ? 0 : history.get(history.size() - 1).version()) + 1;
        var canonical = ConfigCodec.text(copyVersion(loaded.document(), version));
        var outcome = Outcome.ok("{\"version\":" + version + ",\"staged\":true}", version);
        try {
            append(proposeEntry(version, canonical), key, outcome, fp);
        } catch (ExecutionException e) {
            return Outcome.error(503, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.error(503, "log");
        }
        if (staged == null || staged.version != version) {
            return Outcome.error(503, "propose did not commit");
        }
        return outcome;
    }

    public Outcome activate(long nowMillis, long ttlMillis) {
        return activate(nowMillis, ttlMillis, null);
    }

    public Outcome activate(long nowMillis, long ttlMillis, String key) {
        var fp = fingerprint("POST", "/cp/v1/activate", "");
        var hit = replayed(key, fp);
        if (hit != null) {
            return hit;
        }
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        var pending = staged;
        if (pending == null) {
            return Outcome.error(409, "nothing staged");
        }
        return commitSigned(pending.document, 0L, nowMillis, ttlMillis, key, fp);
    }

    public Outcome rollback(long toVersion, long nowMillis, long ttlMillis) {
        return rollback(toVersion, nowMillis, ttlMillis, null);
    }

    public Outcome rollback(long toVersion, long nowMillis, long ttlMillis, String key) {
        var fp = fingerprint("POST", "/cp/v1/rollback", Long.toString(toVersion));
        var hit = replayed(key, fp);
        if (hit != null) {
            return hit;
        }
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        ConfigDocument document = null;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).version() == toVersion) {
                document = documents.get(i);
            }
        }
        if (document == null) {
            return Outcome.error(404, "version");
        }
        return commitSigned(document, toVersion, nowMillis, ttlMillis, key, fp);
    }

    public Outcome diff(long from, long to, boolean http3Running) {
        var left = document(from);
        var right = document(to);
        if (left == null || right == null) {
            return Outcome.error(404, "version");
        }
        var changes = ConfigDiff.between(left, right, http3Running);
        var json = new StringBuilder();
        json.append("{\"changes\":[");
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            var change = changes.get(i);
            json.append("{\"section\":").append(LastGoodFiles.jsonString(change.section())).append(",\"effect\":\"")
                    .append(change.effect()).append("\",\"detail\":").append(LastGoodFiles.jsonString(change.detail()))
                    .append('}');
        }
        json.append("]}");
        return Outcome.ok(json.toString(), 0);
    }

    public Outcome grant(String san, sjws_nettask.controlplane.Role role) {
        return grant(san, role, null);
    }

    public Outcome grant(String san, sjws_nettask.controlplane.Role role, String key) {
        var fp = fingerprint("POST", "/cp/v1/rbac", (san == null ? "" : san) + "\n" + (role == null ? "" : role.name()));
        var hit = replayed(key, fp);
        if (hit != null) {
            return hit;
        }
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        try {
            san = canonicalSan(san);
        } catch (IllegalArgumentException e) {
            return Outcome.error(400, "san");
        }
        var outcome = Outcome.ok("{\"san\":" + LastGoodFiles.jsonString(san) + ",\"role\":\"" + role.name().toLowerCase()
                + "\"}", 0);
        try {
            append(rbacEntry(san, role), key, outcome, fp);
        } catch (ExecutionException e) {
            return Outcome.error(503, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.error(503, "log");
        }
        return outcome;
    }

    public Outcome revoke(String nodeId) {
        return revoke(nodeId, null);
    }

    public Outcome revoke(String nodeId, String key) {
        var fp = fingerprint("POST", "/cp/v1/nodes/" + nodeId + "/revoke", "");
        var hit = replayed(key, fp);
        if (hit != null) {
            return hit;
        }
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        var outcome = Outcome.ok("{\"revoked\":" + LastGoodFiles.jsonString(nodeId) + "}", 0);
        try {
            append(bytes(REVOKE, nodeId), key, outcome, fp);
        } catch (ExecutionException e) {
            return Outcome.error(503, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.error(503, "log");
        }
        return outcome;
    }

    public Outcome rollout(List<String> stages) {
        return rollout(stages, null);
    }

    public Outcome rollout(List<String> stages, String key) {
        var fp = fingerprint("POST", "/cp/v1/rollout", stages == null ? "" : String.join("\n", stages));
        var hit = replayed(key, fp);
        if (hit != null) {
            return hit;
        }
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        var outcome = Outcome.ok("{\"stages\":" + stages.size() + "}", 0);
        try {
            append(rolloutEntry(stages), key, outcome, fp);
        } catch (ExecutionException e) {
            return Outcome.error(503, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.error(503, "log");
        }
        return outcome;
    }

    public Outcome advance() {
        return advance(null);
    }

    public Outcome advance(String key) {
        var fp = fingerprint("POST", "/cp/v1/rollout/advance", "");
        var hit = replayed(key, fp);
        if (hit != null) {
            return hit;
        }
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        int next = stage < selectors.size() ? stage + 1 : stage;
        var outcome = Outcome.ok("{\"stage\":" + next + "}", 0);
        try {
            append(ByteBuffer.wrap(new byte[] {ADVANCE}), key, outcome, fp);
        } catch (ExecutionException e) {
            return Outcome.error(503, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.error(503, "log");
        }
        return outcome;
    }

    public String healthJson() {
        var leader = leadership.leaderId().map(LastGoodFiles::jsonString).orElse("null");
        return "{\"leader\":" + leader + ",\"term\":" + leadership.term() + ",\"leaderReady\":"
                + leadership.leaderReady() + ",\"voters\":1}";
    }

    /**
     * A data node's last heartbeat. {@code health} is {@code READY}, {@code SUSPECT}, or {@code DEAD}.
     * Lag is the activated version minus the applied version.
     */
    public void reportNode(String id, Map<String, String> labels, String health, long appliedVersion) {
        if (id == null || id.isBlank() || "cp-1".equals(id)) {
            return;
        }
        var known = switch (health == null ? "" : health) {
            case "READY", "SUSPECT", "DEAD" -> health;
            default -> "SUSPECT";
        };
        long applied = Math.max(0L, appliedVersion);
        watchLock.lock();
        try {
            reported.put(id, new Reported(id, labels == null ? Map.of() : Map.copyOf(labels), known, applied));
        } finally {
            watchLock.unlock();
        }
    }

    public String nodesJson() {
        var leader = leadership.isLeader() ? "leader" : "follower";
        long desired = activated() == null ? 0L : activated().version();
        var json = new StringBuilder();
        json.append("{\"nodes\":[");
        nodeJson(json, "cp-1", leader, Map.of("role", "control"), "READY", desired, 0L, revoked.contains("cp-1"));
        List<Reported> nodes;
        watchLock.lock();
        try {
            nodes = List.copyOf(reported.values());
        } finally {
            watchLock.unlock();
        }
        for (var node : nodes) {
            json.append(',');
            nodeJson(json, node.id, "data", node.labels, node.health, node.applied,
                    Math.max(0L, desired - node.applied), revoked.contains(node.id));
        }
        json.append("]}");
        return json.toString();
    }

    public String rolloutJson() {
        var json = new StringBuilder();
        json.append("{\"stage\":").append(stage).append(",\"selectors\":[");
        for (int i = 0; i < selectors.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(LastGoodFiles.jsonString(selectors.get(i)));
        }
        json.append("]}");
        return json.toString();
    }

    /** A retained snapshot. History keeps the last {@code HISTORY} activated versions. */
    public Outcome configVersion(long version) {
        for (var snapshot : history) {
            if (snapshot.version() == version) {
                return Outcome.ok(configBody(snapshot), version);
            }
        }
        return Outcome.error(404, "version");
    }

    /**
     * The same checks as {@link #propose(String)} and no log entry. {@code ok} is false when the
     * document would be rejected.
     */
    public Outcome validate(String yaml) {
        var errors = new ArrayList<String>();
        if (yaml != null && forbidden(yaml)) {
            errors.add("rbac and trust are not part of a proposal");
        }
        ConfigLoader.Loaded loaded = null;
        if (errors.isEmpty()) {
            try {
                loaded = parse(yaml == null ? "" : yaml);
            } catch (IOException e) {
                errors.add("config");
            }
        }
        if (errors.isEmpty() && (loaded == null || !loaded.ok() || loaded.document() == null)) {
            errors.add(loaded == null || loaded.errors().isEmpty() ? "configuration rejected" : loaded.errors().get(0));
        }
        if (errors.isEmpty()) {
            errors.addAll(ConfigValidator.validate(loaded.document()));
        }
        if (errors.isEmpty() && !loaded.document().messaging().assignment().isEmpty()) {
            errors.add("assignment is computed by the control plane");
        }
        if (errors.isEmpty()) {
            var previous = documents.isEmpty() ? MessagingSettings.disabled()
                    : documents.get(documents.size() - 1).messaging();
            errors.addAll(MessagingSettings.destructiveErrors(previous, loaded.document().messaging()));
        }
        var json = new StringBuilder();
        json.append("{\"ok\":").append(errors.isEmpty()).append(",\"errors\":[");
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(LastGoodFiles.jsonString(errors.get(i)));
        }
        json.append("]}");
        return Outcome.ok(json.toString(), 0);
    }

    /**
     * Local security event (for example a refused admin handshake), written to this node's audit
     * log. Not replicated: it records what this listener saw. Never throws.
     */
    public void recordSecurityEvent(String actor, String action, String target, String outcome) {
        var log = audit;
        if (log == null) {
            return;
        }
        try {
            log.append(actor, action, target, outcome, 0);
        } catch (java.io.IOException | RuntimeException e) {
            System.getLogger(CpStore.class.getName()).log(System.Logger.Level.WARNING,
                    "audit write failed for " + action + ": " + e.getMessage());
        }
    }

    /** Audit lines with {@code seq} greater than {@code afterSeq}, capped at {@code limit}. */
    public String auditJson(long afterSeq, int limit) throws java.io.IOException {
        int cap = limit <= 0 || limit > 100 ? 100 : limit;
        var kept = new ArrayList<String>();
        if (auditFile != null && java.nio.file.Files.isRegularFile(auditFile)) {
            for (var line : java.nio.file.Files.readAllLines(auditFile)) {
                if (line.isBlank()) {
                    continue;
                }
                if (seqOf(line) <= afterSeq) {
                    continue;
                }
                kept.add(line);
            }
        }
        if (kept.size() > cap) {
            kept = new ArrayList<>(kept.subList(kept.size() - cap, kept.size()));
        }
        var json = new StringBuilder();
        json.append("{\"entries\":[");
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(kept.get(i));
        }
        json.append("]}");
        return json.toString();
    }

    public String configJson() {
        var current = activated();
        if (current == null) {
            return "{\"activated\":null}";
        }
        return configBody(current);
    }

    public sjws_nettask.controlplane.Role roleOfSan(String san) {
        try {
            return policy.get(canonicalSan(san));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public List<String> revoked() {
        return List.copyOf(revoked);
    }

    public List<String> selectors() {
        return List.copyOf(selectors);
    }

    public int stage() {
        return stage;
    }

    @Override
    public void close() {
        node.close();
        if (ownedStorage != null) {
            try {
                ownedStorage.close();
            } catch (IOException e) {
                System.getLogger(CpStore.class.getName()).log(System.Logger.Level.WARNING,
                        "cp log close failed: " + e.getMessage());
            }
        }
    }

    private Outcome commitSigned(ConfigDocument source, long rollbackOf, long nowMillis, long ttlMillis, String key,
            String fingerprint) {
        long version = (history.isEmpty() ? 0 : history.get(history.size() - 1).version()) + 1;
        var signedDocument = rollbackOf > 0 ? source : copyVersion(source, version);
        long raftIndex = group.lastApplied() + 1;
        var signed = keys.sign(signedDocument, version, leadership.term(), raftIndex, nowMillis, nowMillis + ttlMillis,
                rollbackOf);
        var outcome = Outcome.ok("{\"version\":" + version + ",\"raftIndex\":" + raftIndex + ",\"canonicalHash\":"
                + LastGoodFiles.jsonString(signed.canonicalHashHex()) + "}", version);
        try {
            var committed = append(signedEntry(signed), key, outcome, fingerprint);
            if (committed != raftIndex) {
                return Outcome.error(503, "raft index");
            }
        } catch (ExecutionException e) {
            return Outcome.error(503, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.error(503, "log");
        }
        staged = null;
        var current = activated();
        if (current == null || current.version() != version) {
            return Outcome.error(503, "activate did not commit");
        }
        return outcome;
    }

    private void onCommitted(ByteBuffer entry) {
        var body = entry.duplicate();
        body.order(java.nio.ByteOrder.BIG_ENDIAN);
        if (!body.hasRemaining()) {
            return;
        }
        byte type = body.get();
        if (type == IDEM) {
            try {
                var idemKey = readString(body);
                int status = body.getInt();
                var response = readString(body);
                var fp = readString(body);
                replay.put(idemKey, new Cached(fp, new Outcome(status, response, 0)));
            } catch (java.nio.BufferUnderflowException e) {
                return; // a truncated marker must not stall apply
            }
            if (!body.hasRemaining()) {
                return;
            }
            type = body.get();
        }
        switch (type) {
            case PROPOSE -> {
                long version = body.getLong();
                var yaml = readString(body);
                ConfigLoader.Loaded loaded;
                try {
                    loaded = parse(yaml);
                } catch (IOException e) {
                    return;
                }
                if (loaded.ok() && loaded.document() != null) {
                    staged = new Staged(version, loaded.document());
                }
            }
            case SIGNED -> {
                var snapshot = readSigned(body);
                if (snapshot.raftIndex() <= 0) {
                    return;
                }
                ConfigLoader.Loaded loaded;
                try {
                    loaded = parse(snapshot.canonicalYaml());
                } catch (IOException e) {
                    return;
                }
                if (!loaded.ok() || loaded.document() == null) {
                    return;
                }
                history.add(snapshot);
                documents.add(loaded.document());
                while (history.size() > HISTORY) {
                    history.remove(0);
                    documents.remove(0);
                }
                publishLatest(snapshot);
                staged = null;
            }
            case RBAC -> {
                var san = readString(body);
                var role = sjws_nettask.controlplane.Role.values()[body.getInt()];
                var next = new LinkedHashMap<>(policy);
                next.put(san, role);
                if (next.containsValue(sjws_nettask.controlplane.Role.ADMIN)) {
                    policy.clear();
                    policy.putAll(next);
                    rebuildRights();
                }
            }
            case REVOKE -> revoked.add(readString(body));
            case JOIN_ISSUE -> {
                var tokenId = readString(body);
                var hash = readString(body);
                long expires = body.getLong();
                long now = body.getLong();
                joins.issue(tokenId, hash, expires, now);
            }
            case JOIN_CONSUME -> {
                var nodeId = readString(body);
                var tokenId = readString(body);
                var hash = readString(body);
                joins.consume(nodeId, tokenId, hash, body.getLong());
            }
            case ROLLOUT -> {
                selectors.clear();
                int count = body.getInt();
                for (int i = 0; i < count; i++) {
                    selectors.add(readString(body));
                }
                stage = 0;
            }
            case ADVANCE -> {
                if (stage < selectors.size()) {
                    stage++;
                }
            }
            default -> {
                // Unknown markers are ignored so a newer entry cannot stall apply.
            }
        }
    }

    private static String configBody(SignedSnapshot snapshot) {
        return "{\"version\":" + snapshot.version() + ",\"canonicalHash\":"
                + LastGoodFiles.jsonString(snapshot.canonicalHashHex()) + ",\"yaml\":"
                + LastGoodFiles.jsonString(snapshot.canonicalYaml()) + "}";
    }

    private static void nodeJson(StringBuilder json, String id, String role, Map<String, String> labels, String health,
            long applied, long lag, boolean revoked) {
        json.append("{\"id\":").append(LastGoodFiles.jsonString(id)).append(",\"role\":")
                .append(LastGoodFiles.jsonString(role)).append(",\"labels\":{");
        var first = true;
        for (var label : labels.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(LastGoodFiles.jsonString(label.getKey())).append(':')
                    .append(LastGoodFiles.jsonString(label.getValue()));
        }
        json.append("},\"health\":").append(LastGoodFiles.jsonString(health)).append(",\"appliedVersion\":")
                .append(applied).append(",\"lag\":").append(lag).append(",\"revoked\":").append(revoked).append('}');
    }

    private long append(ByteBuffer entry) throws InterruptedException, ExecutionException {
        return node.append(GROUP, List.of(entry)).get().index();
    }

    private long append(ByteBuffer entry, String key, Outcome success, String fp)
            throws InterruptedException, ExecutionException {
        if (key == null || key.isBlank() || success == null) {
            return append(entry);
        }
        return append(wrap(entry, key, success.status(), success.body(), fp));
    }

    private ConfigDocument document(long version) {
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).version() == version) {
                return documents.get(i);
            }
        }
        return null;
    }

    private static ConfigDocument copyVersion(ConfigDocument document, long version) {
        return ConfigDocument.of(document.server(), document.acme(), document.routing(), document.middleware(),
                document.access(), document.node(), document.schemaVersion(), version, document.configFile(),
                document.messaging()).withStateDir(document.stateDir());
    }

    private static ConfigLoader.Loaded parse(String yaml) throws IOException {
        var file = java.nio.file.Files.createTempFile("cp-propose-", ".yaml");
        try {
            java.nio.file.Files.writeString(file, yaml);
            return ConfigLoader.load(new String[] {"--config=" + file.toAbsolutePath()}, Map.of());
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static boolean forbidden(String yaml) {
        for (var line : yaml.split("\n", -1)) {
            var trimmed = line.stripLeading();
            if (trimmed.startsWith("rbac:") || trimmed.startsWith("trust:")) {
                return true;
            }
        }
        return false;
    }

    private static ByteBuffer proposeEntry(long version, String yaml) {
        var raw = yaml.getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(1 + 8 + 4 + raw.length);
        buffer.put(PROPOSE);
        buffer.putLong(version);
        buffer.putInt(raw.length);
        buffer.put(raw);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer signedEntry(SignedSnapshot snapshot) {
        var projection = snapshot.projectionId().getBytes(StandardCharsets.UTF_8);
        var keyId = snapshot.keyId().getBytes(StandardCharsets.UTF_8);
        var yaml = snapshot.canonicalYaml().getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(1 + 4 + 8 * 8 + 1 + 4 + projection.length + 4 + keyId.length + 32 + 16 + 4
                + snapshot.signature().length + 4 + yaml.length);
        buffer.put(SIGNED);
        buffer.putInt(snapshot.schema());
        buffer.putLong(snapshot.version());
        buffer.putLong(snapshot.epoch());
        buffer.putLong(snapshot.raftIndex());
        buffer.putLong(snapshot.parentVersion());
        buffer.put(snapshot.kind());
        buffer.putLong(snapshot.rollbackOf());
        buffer.putLong(snapshot.issuedAtMillis());
        buffer.putLong(snapshot.expiresAtMillis());
        put(buffer, projection);
        put(buffer, keyId);
        buffer.put(snapshot.canonicalHash());
        buffer.put(snapshot.nonce());
        put(buffer, snapshot.signature());
        put(buffer, yaml);
        buffer.flip();
        return buffer;
    }

    private static SignedSnapshot readSigned(ByteBuffer body) {
        int schema = body.getInt();
        long version = body.getLong();
        long epoch = body.getLong();
        long raftIndex = body.getLong();
        long parent = body.getLong();
        byte kind = body.get();
        long rollback = body.getLong();
        long issued = body.getLong();
        long expires = body.getLong();
        var projection = readString(body);
        var keyId = readString(body);
        var hash = new byte[32];
        body.get(hash);
        var nonce = new byte[16];
        body.get(nonce);
        var signature = readBytes(body);
        var yaml = readString(body);
        return new SignedSnapshot(schema, version, epoch, raftIndex, parent, kind, projection, hash, issued, expires,
                nonce, keyId, signature, rollback, yaml);
    }

    private void rebuildRights() {
        var used = new java.util.HashSet<String>();
        var ops = new ArrayList<sjws_nettask.controlplane.Operator>();
        for (var entry : policy.entrySet()) {
            ops.add(new sjws_nettask.controlplane.Operator(operatorId(entry.getKey(), used), entry.getValue(),
                    entry.getKey(), true));
        }
        this.rights = new sjws_nettask.controlplane.Rbac(ops, audit);
    }

    private static String canonicalSan(String san) {
        return new sjws_nettask.controlplane.Operator("n", sjws_nettask.controlplane.Role.VIEWER, san, false)
                .certSubject();
    }

    private static String operatorId(String san, java.util.Set<String> used) {
        int colon = san.lastIndexOf(':');
        var tail = colon >= 0 && colon < san.length() - 1 ? san.substring(colon + 1) : san;
        var base = tail.toLowerCase().replaceAll("[^a-z0-9_-]", "");
        if (base.isEmpty() || !Character.isLetterOrDigit(base.charAt(0))) {
            base = "n" + base;
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        var id = base;
        var n = 2;
        while (!used.add(id)) {
            id = base + n;
            n++;
        }
        return id;
    }

    private static ByteBuffer rbacEntry(String san, sjws_nettask.controlplane.Role role) {
        var raw = san.getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(1 + 4 + raw.length + 4);
        buffer.put(RBAC);
        buffer.putInt(raw.length);
        buffer.put(raw);
        buffer.putInt(role.ordinal());
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer bytes(byte type, String text) {
        var raw = text.getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(1 + 4 + raw.length);
        buffer.put(type);
        buffer.putInt(raw.length);
        buffer.put(raw);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer rolloutEntry(List<String> stages) {
        int size = 1 + 4;
        var encoded = new ArrayList<byte[]>();
        for (var stage : stages) {
            var raw = stage.getBytes(StandardCharsets.UTF_8);
            encoded.add(raw);
            size += 4 + raw.length;
        }
        var buffer = ByteBuffer.allocate(size);
        buffer.put(ROLLOUT);
        buffer.putInt(encoded.size());
        for (var raw : encoded) {
            buffer.putInt(raw.length);
            buffer.put(raw);
        }
        buffer.flip();
        return buffer;
    }

    private static void put(ByteBuffer buffer, byte[] raw) {
        buffer.putInt(raw.length);
        buffer.put(raw);
    }

    private static byte[] readBytes(ByteBuffer body) {
        int len = body.getInt();
        var raw = new byte[len];
        body.get(raw);
        return raw;
    }

    private static String readString(ByteBuffer body) {
        return new String(readBytes(body), StandardCharsets.UTF_8);
    }

    private Outcome replayed(String key, String fp) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.length() > 128 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
            return Outcome.error(400, "idempotency key");
        }
        var cached = replay.get(key);
        if (cached == null) {
            return null;
        }
        if (!cached.fingerprint.equals(fp)) {
            return Outcome.error(409, "idempotency key reused");
        }
        return cached.outcome;
    }

    private static String fingerprint(String method, String path, String body) {
        return method + "\n" + path + "\n" + (body == null ? "" : body);
    }

    private static long seqOf(String line) {
        int at = line.indexOf("\"seq\":");
        if (at < 0) {
            return 0L;
        }
        int i = at + "\"seq\":".length();
        int end = i;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        if (end == i) {
            return 0L;
        }
        return Long.parseLong(line.substring(i, end));
    }

    private static ByteBuffer wrap(ByteBuffer entry, String key, int status, String response, String fp) {
        var payload = new byte[entry.remaining()];
        entry.duplicate().get(payload);
        var keyRaw = key.getBytes(StandardCharsets.UTF_8);
        var responseRaw = response.getBytes(StandardCharsets.UTF_8);
        var fpRaw = fp.getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(1 + 4 + keyRaw.length + 4 + 4 + responseRaw.length + 4 + fpRaw.length
                + payload.length);
        buffer.put(IDEM);
        buffer.putInt(keyRaw.length);
        buffer.put(keyRaw);
        buffer.putInt(status);
        buffer.putInt(responseRaw.length);
        buffer.put(responseRaw);
        buffer.putInt(fpRaw.length);
        buffer.put(fpRaw);
        buffer.put(payload);
        buffer.flip();
        return buffer;
    }

    private record Staged(long version, ConfigDocument document) {
    }

    private record Cached(String fingerprint, Outcome outcome) {
    }

    private record Reported(String id, Map<String, String> labels, String health, long applied) {
    }

    public record Outcome(int status, String body, long version) {
        public static Outcome ok(String body, long version) {
            return new Outcome(200, body, version);
        }

        public static Outcome error(int status, String message) {
            return new Outcome(status, "{\"error\":" + LastGoodFiles.jsonString(message) + "}", 0);
        }
    }
}
