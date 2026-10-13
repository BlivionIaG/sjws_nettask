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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;

/**
 * {@code <stateDir>/config/floor.json}, {@code trust.json}, and {@code last-good.json}. The floor
 * file is written first. Each file is mode {@code 0600}, moved atomically, and fsynced.
 */
public final class LastGoodFiles {

    private LastGoodFiles() {
    }

    public static Path configDir(Path stateDir) {
        return stateDir.resolve("config");
    }

    public static void write(Path stateDir, long maxVersion, long maxEpoch, String keyId, List<TrustKey> keys,
            long versionFloor, byte[] trustSignature, SignedSnapshot snapshot) throws IOException {
        var dir = configDir(stateDir);
        Files.createDirectories(dir);
        var floor = "{\"maxVersion\":" + maxVersion + ",\"maxEpoch\":" + maxEpoch + ",\"keyId\":"
                + jsonString(keyId) + "}";
        atomic(dir.resolve("floor.json"), floor);
        var trust = new StringBuilder();
        trust.append("{\"versionFloor\":").append(versionFloor).append(",\"signature\":")
                .append(jsonString(Base64.getEncoder().encodeToString(trustSignature))).append(",\"keys\":[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                trust.append(',');
            }
            var key = keys.get(i);
            trust.append("{\"keyId\":").append(jsonString(key.keyId())).append(",\"publicKey\":")
                    .append(jsonString(Base64.getEncoder().encodeToString(key.publicKey()))).append(",\"notAfter\":")
                    .append(key.notAfterMillis()).append(",\"versionMin\":").append(key.versionMin())
                    .append(",\"versionMax\":").append(key.versionMax()).append('}');
        }
        trust.append("]}");
        atomic(dir.resolve("trust.json"), trust.toString());
        atomic(dir.resolve("last-good.json"), snapshotJson(snapshot));
        fsyncDir(dir);
    }

    /** The signed envelope as the JSON object stored in {@code last-good.json} and sent on the watch. */
    static String snapshotJson(SignedSnapshot snapshot) {
        var last = new StringBuilder();
        last.append("{\"schema\":").append(snapshot.schema()).append(",\"version\":").append(snapshot.version())
                .append(",\"raftIndex\":").append(snapshot.raftIndex()).append(",\"canonicalHash\":")
                .append(jsonString(snapshot.canonicalHashHex())).append(",\"keyId\":")
                .append(jsonString(snapshot.keyId())).append(",\"epoch\":").append(snapshot.epoch())
                .append(",\"parentVersion\":").append(snapshot.parentVersion()).append(",\"kind\":")
                .append(snapshot.kind()).append(",\"projectionId\":").append(jsonString(snapshot.projectionId()))
                .append(",\"issuedAtMillis\":").append(snapshot.issuedAtMillis()).append(",\"expiresAtMillis\":")
                .append(snapshot.expiresAtMillis()).append(",\"nonce\":")
                .append(jsonString(Base64.getEncoder().encodeToString(snapshot.nonce()))).append(",\"signature\":")
                .append(jsonString(Base64.getEncoder().encodeToString(snapshot.signature()))).append(",\"rollbackOf\":")
                .append(snapshot.rollbackOf()).append(",\"canonicalYaml\":").append(jsonString(snapshot.canonicalYaml()))
                .append('}');
        return last.toString();
    }

    public static void writePin(Path pin, byte[] rootPublic, long versionFloor) throws IOException {
        var parent = pin.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var body = ByteBuffer.allocate(4 + 4 + rootPublic.length + 8);
        body.put("SJWS".getBytes(StandardCharsets.US_ASCII));
        body.putInt(rootPublic.length);
        body.put(rootPublic);
        body.putLong(versionFloor);
        atomicBytes(pin, body.array());
    }

    public static Pin readPin(Path pin) throws IOException {
        var raw = Files.readAllBytes(pin);
        var body = ByteBuffer.wrap(raw);
        var magic = new byte[4];
        body.get(magic);
        if (!"SJWS".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IOException("pin magic");
        }
        int len = body.getInt();
        if (len < 1 || len > body.remaining()) {
            throw new IOException("pin key");
        }
        var key = new byte[len];
        body.get(key);
        long floor = body.getLong();
        return new Pin(key, floor);
    }

    public record Pin(byte[] rootPublic, long versionFloor) {
    }

    static String jsonString(String raw) {
        var out = new StringBuilder(raw.length() + 2);
        out.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    static String readString(String json, String key) throws IOException {
        var marker = "\"" + key + "\":";
        int at = json.indexOf(marker);
        if (at < 0) {
            throw new IOException("missing " + key);
        }
        int i = at + marker.length();
        while (i < json.length() && json.charAt(i) == ' ') {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            throw new IOException(key);
        }
        var out = new StringBuilder();
        for (int p = i + 1; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                if (p + 1 >= json.length()) {
                    throw new IOException(key);
                }
                char n = json.charAt(++p);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '\\', '"' -> out.append(n);
                    case 'u' -> {
                        if (p + 4 >= json.length()) {
                            throw new IOException(key);
                        }
                        int code = Integer.parseInt(json.substring(p + 1, p + 5), 16);
                        out.append((char) code);
                        p += 4;
                    }
                    default -> throw new IOException(key);
                }
            } else {
                out.append(c);
            }
        }
        throw new IOException(key);
    }

    static long readLong(String json, String key) throws IOException {
        var marker = "\"" + key + "\":";
        int at = json.indexOf(marker);
        if (at < 0) {
            throw new IOException("missing " + key);
        }
        int i = at + marker.length();
        while (i < json.length() && json.charAt(i) == ' ') {
            i++;
        }
        int end = i;
        if (end < json.length() && (json.charAt(end) == '-' || Character.isDigit(json.charAt(end)))) {
            end++;
        }
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == i) {
            throw new IOException(key);
        }
        return Long.parseLong(json.substring(i, end));
    }

    private static void atomic(Path target, String text) throws IOException {
        atomicBytes(target, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void atomicBytes(Path target, byte[] bytes) throws IOException {
        var parent = target.toAbsolutePath().getParent();
        Path tmp = null;
        try {
            if (parent != null && parent.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                tmp = Files.createTempFile(parent, ".cp-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                tmp = Files.createTempFile(parent == null ? Path.of(".") : parent, ".cp-", ".tmp");
            }
            try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } finally {
            if (tmp != null) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private static void fsyncDir(Path dir) {
        try (var channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            // A directory fsync is best-effort. The file fsync already happened.
        }
    }
}
