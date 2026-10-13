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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import sjws_nettask.config.AppliedConfig;
import sjws_nettask.config.ConfigLoader;

/**
 * Signature, freshness, and rollback checks for {@code SjwsServer.applyRemote}. A failure is a
 * reason string. The caller does not compile.
 */
public final class DataApply {

    private final byte[] rootPublic;
    private final long pinFloor;
    private final List<TrustKey> keys;
    private final byte[] trustSignature;
    private final long trustFloor;
    private final Path stateDir;
    private final LongSupplier nowMillis;
    private final Map<Long, String> retained = new LinkedHashMap<>();
    private final ArrayDeque<Long> order = new ArrayDeque<>();
    private long appliedVersion;
    private long appliedEpoch;

    private DataApply(byte[] rootPublic, long pinFloor, List<TrustKey> keys, byte[] trustSignature, long trustFloor,
            Path stateDir, LongSupplier nowMillis) {
        this.rootPublic = rootPublic;
        this.pinFloor = pinFloor;
        this.keys = List.copyOf(keys);
        this.trustSignature = trustSignature;
        this.trustFloor = trustFloor;
        this.stateDir = stateDir;
        this.nowMillis = nowMillis;
    }

    public static DataApply open(KeyRing keys, Path pin, Path stateDir, LongSupplier nowMillis) throws IOException {
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        var read = LastGoodFiles.readPin(pin);
        return new DataApply(read.rootPublic(), read.versionFloor(), keys.keys(), keys.trustSignature(),
                keys.versionFloor(), stateDir, nowMillis);
    }

    /**
     * A data node's applicator: the root key and floor come from the pinned file, the online keys
     * from the control plane. The bundle must verify under the pinned root and its floor may not be
     * below the pin's; otherwise this throws and nothing is attached (fail closed).
     */
    public static DataApply trusted(Path pin, WatchWire.Message bundle, Path stateDir, LongSupplier nowMillis)
            throws IOException {
        var read = LastGoodFiles.readPin(pin);
        var root = SnapshotCrypto.publicKey(read.rootPublic());
        if (root == null || bundle.trustFloor() < read.versionFloor() || !SnapshotCrypto.verify(root,
                SnapshotCrypto.trustInput(bundle.trustFloor(), bundle.keys()), bundle.trustSignature())) {
            throw new IOException("trust bundle does not verify under the pinned root");
        }
        return new DataApply(read.rootPublic(), read.versionFloor(), bundle.keys(), bundle.trustSignature(),
                bundle.trustFloor(), stateDir, nowMillis);
    }

    public record Decision(String error, boolean noop, String yaml) {

        public static Decision reject(String error) {
            return new Decision(error, false, "");
        }

        public static Decision same() {
            return new Decision(null, true, "");
        }

        public static Decision apply(String yaml) {
            return new Decision(null, false, yaml);
        }
    }

    public Decision decide(SignedSnapshot snapshot, AppliedConfig installed) {
        if (snapshot.kind() != SnapshotCrypto.KIND_FULL) {
            return Decision.reject("kind");
        }
        if (snapshot.parentVersion() != 0L) {
            return Decision.reject("parentVersion");
        }
        if (snapshot.canonicalHash().length != 32 || snapshot.nonce().length != 16) {
            return Decision.reject("canonicalHash");
        }
        if (!snapshot.canonicalYaml().startsWith("schema: " + snapshot.schema() + "\n")) {
            return Decision.reject("schema");
        }
        var hashed = KeyRing.hash(snapshot.canonicalYaml());
        if (!Arrays.equals(hashed, snapshot.canonicalHash())) {
            return Decision.reject("canonicalHash");
        }
        if (forbidden(snapshot.canonicalYaml())) {
            return Decision.reject("rbac");
        }
        var key = online(snapshot.keyId());
        if (key == null) {
            return Decision.reject("keyId");
        }
        var root = SnapshotCrypto.publicKey(rootPublic);
        if (root == null || !SnapshotCrypto.verify(root, SnapshotCrypto.trustInput(trustFloor, keys), trustSignature)) {
            return Decision.reject("trust");
        }
        var onlineKey = SnapshotCrypto.publicKey(key.publicKey());
        if (onlineKey == null || !SnapshotCrypto.verify(onlineKey, SnapshotCrypto.snapshotInput(snapshot),
                snapshot.signature())) {
            return Decision.reject("signature");
        }
        long now = nowMillis.getAsLong();
        if (now > key.notAfterMillis()) {
            return Decision.reject("online key");
        }
        if (snapshot.version() > Long.MAX_VALUE - SnapshotCrypto.MAX_VERSION_JUMP
                || (snapshot.version() > installed.version()
                        && snapshot.version() - installed.version() > SnapshotCrypto.MAX_VERSION_JUMP)) {
            return Decision.reject(snapshot.version() - installed.version() > SnapshotCrypto.MAX_VERSION_JUMP
                    ? "version jump" : "version");
        }
        if (snapshot.version() < key.versionMin() || snapshot.version() > key.versionMax()) {
            return Decision.reject("online key");
        }
        if (now < snapshot.issuedAtMillis() || now > snapshot.expiresAtMillis()) {
            return Decision.reject("expired");
        }
        if (snapshot.version() > Long.MAX_VALUE - SnapshotCrypto.MAX_VERSION_JUMP) {
            return Decision.reject("version");
        }
        if (snapshot.version() == appliedVersion && snapshot.version() == installed.version()
                && snapshot.canonicalHashHex().equals(retained.get(appliedVersion))) {
            // The envelope this node already applied. The installed document can hash differently
            // (node-local clamps such as node.mode), so the recorded envelope hash decides.
            return Decision.same();
        }
        long floor = Math.max(pinFloor, Math.max(trustFloor, appliedVersion));
        if (snapshot.version() <= floor && !(snapshot.version() == installed.version()
                && snapshot.canonicalHashHex().equals(installed.canonicalHash()))) {
            return Decision.reject("floor");
        }
        if (snapshot.epoch() < appliedEpoch) {
            return Decision.reject("epoch");
        }
        if (snapshot.version() == installed.version() && snapshot.canonicalHashHex().equals(installed.canonicalHash())) {
            return Decision.same();
        }
        if (snapshot.version() < installed.version()) {
            return Decision.reject("version");
        }
        if (snapshot.version() == installed.version()) {
            return Decision.reject("version");
        }
        if (snapshot.rollbackOf() > 0) {
            var retainedHash = retained.get(snapshot.rollbackOf());
            if (retainedHash == null || !retainedHash.equals(snapshot.canonicalHashHex())) {
                return Decision.reject("rollback");
            }
        }
        return Decision.apply(snapshot.canonicalYaml());
    }

    public ConfigLoader.Loaded parse(String yaml) throws IOException {
        var dir = stateDir.resolve("tmp");
        Files.createDirectories(dir);
        var file = Files.createTempFile(dir, "snap-", ".yaml");
        try {
            Files.writeString(file, yaml);
            return ConfigLoader.load(new String[] {"--config=" + file}, Map.of());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Records the applied envelope (floor, epoch, retained hash) and persists last-good. */
    public void remember(SignedSnapshot snapshot) throws IOException {
        record(snapshot);
        LastGoodFiles.write(stateDir, appliedVersion, appliedEpoch, snapshot.keyId(), keys, trustFloor, trustSignature,
                snapshot);
    }

    /** In-memory part of {@link #remember}: the anti-rollback floor holds even where nothing is written. */
    public void record(SignedSnapshot snapshot) {
        appliedVersion = snapshot.version();
        appliedEpoch = snapshot.epoch();
        retained.put(snapshot.version(), snapshot.canonicalHashHex());
        order.addLast(snapshot.version());
        while (order.size() > 8) {
            retained.remove(order.removeFirst());
        }
    }

    public String retainedHash(long version) {
        return retained.get(version);
    }

    public long appliedVersion() {
        return appliedVersion;
    }

    private TrustKey online(String keyId) {
        for (var key : keys) {
            if (key.keyId().equals(keyId)) {
                return key;
            }
        }
        return null;
    }

    private static boolean forbidden(String yaml) {
        for (var line : yaml.split("\n", -1)) {
            var trimmed = line.stripLeading();
            if (trimmed.startsWith("rbac:") || trimmed.startsWith("trust:") || trimmed.startsWith("key_password:")) {
                return true;
            }
        }
        return false;
    }

    /** Reads a snapshot back out of {@code last-good.json}. The caller still runs {@link #decide}. */
    public static SignedSnapshot readLastGood(Path stateDir) throws IOException {
        return parseSnapshot(Files.readString(LastGoodFiles.configDir(stateDir).resolve("last-good.json")));
    }

    /** Parses {@link LastGoodFiles#snapshotJson}. Fields are not verified here; {@link #decide} does that. */
    static SignedSnapshot parseSnapshot(String text) throws IOException {
        int schema = (int) LastGoodFiles.readLong(text, "schema");
        long version = LastGoodFiles.readLong(text, "version");
        long epoch = LastGoodFiles.readLong(text, "epoch");
        long parent = LastGoodFiles.readLong(text, "parentVersion");
        byte kind = (byte) LastGoodFiles.readLong(text, "kind");
        long issued = LastGoodFiles.readLong(text, "issuedAtMillis");
        long expires = LastGoodFiles.readLong(text, "expiresAtMillis");
        long rollback = LastGoodFiles.readLong(text, "rollbackOf");
        var hash = HexFormat.of().parseHex(LastGoodFiles.readString(text, "canonicalHash"));
        var nonce = Base64.getDecoder().decode(LastGoodFiles.readString(text, "nonce"));
        var signature = Base64.getDecoder().decode(LastGoodFiles.readString(text, "signature"));
        long raftIndex = LastGoodFiles.readLong(text, "raftIndex");
        return new SignedSnapshot(schema, version, epoch, raftIndex, parent, kind,
                LastGoodFiles.readString(text, "projectionId"), hash, issued, expires, nonce,
                LastGoodFiles.readString(text, "keyId"), signature, rollback,
                LastGoodFiles.readString(text, "canonicalYaml"));
    }
}
