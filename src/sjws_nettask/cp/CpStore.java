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
import sjws_nettask.config.ConfigCodec;
import sjws_nettask.config.ConfigDiff;
import sjws_nettask.config.ConfigDocument;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.ConfigValidator;

/**
 * Single-voter {@code cp-config} log, the signed snapshot history, and the admin operations.
 * The log directory is {@code <stateDir>/cp-config} and is not a messaging ledger.
 */
public final class CpStore implements AutoCloseable {

    public static final GroupId GROUP = new GroupId("cp-config");
    private static final byte PROPOSE = 1;
    private static final byte SIGNED = 5;
    private static final byte RBAC = 4;
    private static final byte REVOKE = 6;
    private static final byte ROLLOUT = 7;
    private static final byte ADVANCE = 8;
    private static final int HISTORY = 8;

    private final ReplicatedNode node;
    private ReplicationGroup group;
    private ControlLeadership leadership;
    private final KeyRing keys;
    private final String bootstrapToken;
    private final Map<String, Role> rbac = new LinkedHashMap<>();
    private final List<String> revoked = new ArrayList<>();
    private final List<String> selectors = new ArrayList<>();
    private final List<SignedSnapshot> history = new ArrayList<>();
    private final List<ConfigDocument> documents = new ArrayList<>();
    private final JoinTokens joins = new JoinTokens();
    private Staged staged;
    private int stage;
    private boolean adminEnrolled;

    private CpStore(ReplicatedNode node, KeyRing keys, String bootstrapToken) {
        this.node = node;
        this.keys = keys;
        this.bootstrapToken = bootstrapToken;
    }

    public static CpStore open(java.nio.file.Path stateDir, KeyRing keys, Clock clock) throws IOException {
        var id = new NodeId(1, "cp-1");
        var member = new Member(id, new SiteId("local"), new InetSocketAddress("127.0.0.1", 21001), 1L,
                MemberState.ALIVE, false);
        var storage = new FileLogStorage(stateDir.resolve("cp-config"));
        var node = ReplicatedNode.open(id, List.of(member), Set.of(id), QuorumPolicy.majority(), clock, storage,
                new SimTransport());
        var options = new GroupOptions(Durability.RELAXED, 1024 * 1024, Duration.ofMillis(100), Duration.ofMillis(40),
                10_000);
        var store = new CpStore(node, keys, token());
        store.group = node.openGroup(GROUP, options, entry -> store.onCommitted(entry.entry()));
        store.leadership = ControlLeadership.attach(node, GROUP, store.group, id);
        return store;
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

    /** Same node identity and token within the TTL returns the join already recorded. */
    public JoinTokens.Result presentJoin(String nodeId, String tokenId, byte[] secret, long nowMillis) {
        return joins.present(nodeId, tokenId, secret, nowMillis, JoinTokens.DEFAULT_TTL_MILLIS);
    }

    public void publishBinding(sjws_nettask.service.NodeEnrollment enrollment) throws java.io.IOException {
        enrollment.attach(binding());
    }

    public String bootstrapToken() {
        return bootstrapToken;
    }

    public KeyRing keys() {
        return keys;
    }

    public void drive() {
        node.drive();
    }

    public void start() {
        node.begin(Duration.ofMillis(50));
    }

    public boolean adminEnrolled() {
        return adminEnrolled;
    }

    public List<SignedSnapshot> history() {
        return List.copyOf(history);
    }

    public SignedSnapshot activated() {
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    public Outcome propose(String yaml) {
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
        long version = (history.isEmpty() ? 0 : history.get(history.size() - 1).version()) + 1;
        var canonical = ConfigCodec.text(copyVersion(loaded.document(), version));
        try {
            append(proposeEntry(version, canonical));
        } catch (ExecutionException e) {
            return Outcome.error(503, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.error(503, "log");
        }
        if (staged == null || staged.version != version) {
            return Outcome.error(503, "propose did not commit");
        }
        return Outcome.ok("{\"version\":" + version + ",\"staged\":true}", version);
    }

    public Outcome activate(long nowMillis, long ttlMillis) {
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        var pending = staged;
        if (pending == null) {
            return Outcome.error(409, "nothing staged");
        }
        return commitSigned(pending.document, 0L, nowMillis, ttlMillis);
    }

    public Outcome rollback(long toVersion, long nowMillis, long ttlMillis) {
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
        return commitSigned(document, toVersion, nowMillis, ttlMillis);
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

    public Outcome grant(String san, Role role) {
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        try {
            append(rbacEntry(san, role));
        } catch (ExecutionException e) {
            return Outcome.error(503, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.error(503, "log");
        }
        return Outcome.ok("{\"san\":" + LastGoodFiles.jsonString(san) + ",\"role\":\"" + role.name().toLowerCase()
                + "\"}", 0);
    }

    public Outcome revoke(String nodeId) {
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        try {
            append(bytes(REVOKE, nodeId));
        } catch (ExecutionException e) {
            return Outcome.error(503, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.error(503, "log");
        }
        return Outcome.ok("{\"revoked\":" + LastGoodFiles.jsonString(nodeId) + "}", 0);
    }

    public Outcome rollout(List<String> stages) {
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        try {
            append(rolloutEntry(stages));
        } catch (ExecutionException e) {
            return Outcome.error(503, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.error(503, "log");
        }
        return Outcome.ok("{\"stages\":" + selectors.size() + "}", 0);
    }

    public Outcome advance() {
        if (!leadership.leaderReady()) {
            return Outcome.error(503, "leader not ready");
        }
        try {
            append(ByteBuffer.wrap(new byte[] {ADVANCE}));
        } catch (ExecutionException e) {
            return Outcome.error(503, "log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.error(503, "log");
        }
        return Outcome.ok("{\"stage\":" + stage + "}", 0);
    }

    public String healthJson() {
        var leader = leadership.leaderId().map(LastGoodFiles::jsonString).orElse("null");
        return "{\"leader\":" + leader + ",\"term\":" + leadership.term() + ",\"leaderReady\":"
                + leadership.leaderReady() + ",\"voters\":1}";
    }

    public String nodesJson() {
        var leader = leadership.isLeader() ? "leader" : "follower";
        return "{\"nodes\":[{\"id\":\"cp-1\",\"role\":\"" + leader + "\",\"revoked\":"
                + revoked.contains("cp-1") + "}]}";
    }

    public String configJson() {
        var current = activated();
        if (current == null) {
            return "{\"activated\":null}";
        }
        return "{\"version\":" + current.version() + ",\"canonicalHash\":"
                + LastGoodFiles.jsonString(current.canonicalHashHex()) + ",\"yaml\":"
                + LastGoodFiles.jsonString(current.canonicalYaml()) + "}";
    }

    public Role roleOfSan(String san) {
        return rbac.get(san);
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
    }

    private Outcome commitSigned(ConfigDocument source, long rollbackOf, long nowMillis, long ttlMillis) {
        long version = (history.isEmpty() ? 0 : history.get(history.size() - 1).version()) + 1;
        var signedDocument = rollbackOf > 0 ? source : copyVersion(source, version);
        long raftIndex = group.lastApplied() + 1;
        var signed = keys.sign(signedDocument, version, leadership.term(), raftIndex, nowMillis, nowMillis + ttlMillis,
                rollbackOf);
        try {
            var committed = append(signedEntry(signed));
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
        return Outcome.ok("{\"version\":" + version + ",\"raftIndex\":" + raftIndex + ",\"canonicalHash\":"
                + LastGoodFiles.jsonString(current.canonicalHashHex()) + "}", version);
    }

    private void onCommitted(ByteBuffer entry) {
        var body = entry.duplicate();
        body.order(java.nio.ByteOrder.BIG_ENDIAN);
        if (!body.hasRemaining()) {
            return;
        }
        byte type = body.get();
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
                staged = null;
            }
            case RBAC -> {
                var san = readString(body);
                var role = Role.values()[body.getInt()];
                rbac.put(san, role);
                if (role == Role.ADMIN) {
                    adminEnrolled = true;
                }
            }
            case REVOKE -> revoked.add(readString(body));
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

    private long append(ByteBuffer entry) throws InterruptedException, ExecutionException {
        return node.append(GROUP, List.of(entry)).get().index();
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
                document.contentHash());
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

    private static ByteBuffer rbacEntry(String san, Role role) {
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

    private static String token() {
        var raw = new byte[16];
        new java.security.SecureRandom().nextBytes(raw);
        return java.util.HexFormat.of().formatHex(raw);
    }

    private record Staged(long version, ConfigDocument document) {
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
