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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned Raft snapshot image for {@link CpStore} (#341). Encodes every piece of applied
 * {@code cp-config} state needed after {@code lastIncludedIndex}: activated history, optional staged
 * proposal YAML, RBAC policy, revoke list, rollout selectors/stage, join tokens, and the bounded
 * {@link IdempotencyTable}. Install replaces state; it does not merge.
 *
 * <p>Crash safety of the durable file is {@code FileLogStorage}'s (temp + fsync + atomic rename). This
 * codec only runs after Raft has assembled a full image with a matching CRC32C.
 */
public final class CpStateSnapshot {

    /** ASCII magic {@code CPS1}. */
    static final int MAGIC = 0x43505331;
    /** Encoding version; bump when the layout changes. */
    static final int FORMAT = 1;
    /** Matches {@link CpStore}'s retained activated versions. */
    static final int MAX_HISTORY = 8;
    static final int MAX_POLICY = 10_000;
    static final int MAX_REVOKED = 100_000;
    static final int MAX_SELECTORS = 10_000;
    static final int MAX_YAML_BYTES = 8 * 1024 * 1024;
    static final int MAX_SAN_BYTES = 512;

    private CpStateSnapshot() {
    }

    /** Decoded bag; {@link CpStore} installs it atomically after re-parsing YAML. */
    public static final class Image {
        public final List<SignedSnapshot> history = new ArrayList<>();
        public Long stagedVersion;
        public String stagedYaml;
        public final Map<String, sjws_nettask.controlplane.Role> policy = new LinkedHashMap<>();
        public final List<String> revoked = new ArrayList<>();
        public final List<String> selectors = new ArrayList<>();
        public int stage;
        public final JoinTokens joins = new JoinTokens();
        public IdempotencyTable idempotency = new IdempotencyTable();
    }

    /** Encodes the live store state (SnapshotTaker / delivery thread). */
    static byte[] write(CpStore store) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            data.writeInt(MAGIC);
            data.writeInt(FORMAT);
            store.writeStateSnapshot(data);
        }
        return out.toByteArray();
    }

    /** Decodes an image; refuses a bad magic/format or oversize section. */
    static Image read(byte[] raw) throws IOException {
        if (raw == null || raw.length < 8) {
            throw new IOException("cp snapshot: truncated");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(raw))) {
            int magic = in.readInt();
            int format = in.readInt();
            if (magic != MAGIC || format != FORMAT) {
                throw new IOException("cp snapshot: magic/format " + Integer.toHexString(magic) + "/" + format
                        + " (want CPS1/" + FORMAT + ")");
            }
            var image = new Image();
            readInto(in, image);
            if (in.available() != 0) {
                throw new IOException("cp snapshot: trailing bytes");
            }
            return image;
        }
    }

    static void readInto(DataInputStream in, Image image) throws IOException {
        int hist = in.readInt();
        if (hist < 0 || hist > MAX_HISTORY) {
            throw new IOException("cp snapshot: history " + hist);
        }
        for (int i = 0; i < hist; i++) {
            image.history.add(readSigned(in));
        }
        boolean hasStaged = in.readBoolean();
        if (hasStaged) {
            image.stagedVersion = in.readLong();
            image.stagedYaml = readBounded(in, MAX_YAML_BYTES);
        }
        int policies = in.readInt();
        if (policies < 0 || policies > MAX_POLICY) {
            throw new IOException("cp snapshot: policy " + policies);
        }
        var roles = sjws_nettask.controlplane.Role.values();
        for (int i = 0; i < policies; i++) {
            var san = readBounded(in, MAX_SAN_BYTES);
            int ord = in.readInt();
            if (ord < 0 || ord >= roles.length) {
                throw new IOException("cp snapshot: role " + ord);
            }
            image.policy.put(san, roles[ord]);
        }
        int rev = in.readInt();
        if (rev < 0 || rev > MAX_REVOKED) {
            throw new IOException("cp snapshot: revoked " + rev);
        }
        for (int i = 0; i < rev; i++) {
            image.revoked.add(readBounded(in, MAX_SAN_BYTES));
        }
        int sel = in.readInt();
        if (sel < 0 || sel > MAX_SELECTORS) {
            throw new IOException("cp snapshot: selectors " + sel);
        }
        for (int i = 0; i < sel; i++) {
            image.selectors.add(readBounded(in, 256));
        }
        image.stage = in.readInt();
        if (image.stage < 0 || image.stage > image.selectors.size()) {
            throw new IOException("cp snapshot: stage " + image.stage);
        }
        image.joins.readSnapshot(in);
        image.idempotency = IdempotencyTable.read(in);
    }

    private static SignedSnapshot readSigned(DataInputStream in) throws IOException {
        int schema = in.readInt();
        long version = in.readLong();
        long epoch = in.readLong();
        long raftIndex = in.readLong();
        long parent = in.readLong();
        byte kind = in.readByte();
        long rollback = in.readLong();
        long issued = in.readLong();
        long expires = in.readLong();
        var projection = readBounded(in, 256);
        var keyId = readBounded(in, 256);
        var hash = new byte[32];
        in.readFully(hash);
        var nonce = new byte[16];
        in.readFully(nonce);
        var signature = readBytes(in, 4096);
        var yaml = readBounded(in, MAX_YAML_BYTES);
        return new SignedSnapshot(schema, version, epoch, raftIndex, parent, kind, projection, hash, issued, expires,
                nonce, keyId, signature, rollback, yaml);
    }

    static void writeSigned(DataOutputStream out, SignedSnapshot snapshot) throws IOException {
        out.writeInt(snapshot.schema());
        out.writeLong(snapshot.version());
        out.writeLong(snapshot.epoch());
        out.writeLong(snapshot.raftIndex());
        out.writeLong(snapshot.parentVersion());
        out.writeByte(snapshot.kind());
        out.writeLong(snapshot.rollbackOf());
        out.writeLong(snapshot.issuedAtMillis());
        out.writeLong(snapshot.expiresAtMillis());
        writeBounded(out, snapshot.projectionId());
        writeBounded(out, snapshot.keyId());
        out.write(snapshot.canonicalHash());
        out.write(snapshot.nonce());
        writeBytes(out, snapshot.signature());
        writeBounded(out, snapshot.canonicalYaml());
    }

    static void writeBounded(DataOutputStream out, String s) throws IOException {
        var raw = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(raw.length);
        out.write(raw);
    }

    static String readBounded(DataInputStream in, int max) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > max) {
            throw new IOException("cp snapshot: string length " + len + " (max " + max + ")");
        }
        var raw = new byte[len];
        in.readFully(raw);
        return new String(raw, StandardCharsets.UTF_8);
    }

    static void writeBytes(DataOutputStream out, byte[] raw) throws IOException {
        out.writeInt(raw.length);
        out.write(raw);
    }

    static byte[] readBytes(DataInputStream in, int max) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > max) {
            throw new IOException("cp snapshot: bytes length " + len);
        }
        var raw = new byte[len];
        in.readFully(raw);
        return raw;
    }
}
