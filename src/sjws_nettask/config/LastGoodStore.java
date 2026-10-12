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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * The last config a data node applied, at {@code <stateDir>/config/last-good.json}. The file is
 * mode 0600 and the {@code config} directory is mode 0700 when the filesystem has POSIX
 * permissions. The bytes are a JSON envelope around the canonical YAML (no key password). The
 * temp file and its directory are fsynced before the atomic rename. A data-plane process whose
 * config file is missing boots from this file. Standalone and control do not write it.
 * <p>
 * An older sibling {@code <name>.last-good} is still recognized, so a node can boot once from a
 * file written beside the config.
 */
public final class LastGoodStore {

    private static final Set<PosixFilePermission> OWNER_RW = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_RWX = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private LastGoodStore() {
    }

    /**
     * The daemon's data directory: the working directory, where the default pid file and log file
     * are written. {@code --state-dir}, {@code WEB_SERVER_STATE_DIR} and {@code node.state_dir}
     * override it.
     */
    public static Path defaultStateDir() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    /** {@code <stateDir>/config/last-good.json}. */
    public static Path file(Path stateDir) {
        return stateDir.resolve("config").resolve("last-good.json");
    }

    /**
     * Sibling of {@code configFile} from builds that wrote beside the file. A path that already
     * ends in {@code .last-good} is returned as-is.
     */
    public static Path beside(Path configFile) {
        var name = configFile.getFileName().toString();
        if (name.endsWith(".last-good")) {
            return configFile;
        }
        var sibling = Path.of(name + ".last-good");
        var parent = configFile.getParent();
        return parent == null ? sibling : parent.resolve(sibling);
    }

    /** No-op unless the document is {@link NodeSettings.Mode#DATA}. Standalone writes nothing. */
    public static void write(ConfigDocument document) throws IOException {
        if (document.node().mode() != NodeSettings.Mode.DATA || document.stateDir() == null) {
            return;
        }
        write(file(document.stateDir()), document);
    }

    public static void write(Path path, ConfigDocument document) throws IOException {
        var target = path.toAbsolutePath();
        var parent = target.getParent();
        if (parent != null) {
            ensureDirectory(parent);
        }
        var text = envelope(document);
        Path tmp = null;
        try {
            tmp = temp(parent == null ? Path.of(".") : parent);
            writeDurable(tmp, text);
            force(parent);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
            own(target);
            force(parent);
        } catch (UnsupportedOperationException e) {
            Files.writeString(target, text);
        } finally {
            if (tmp != null) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    /**
     * Canonical YAML when {@code path} is a data-plane last-good file (JSON envelope or the older
     * YAML sibling). Null when the file is missing, not data mode, or the envelope hash does not
     * match its body.
     */
    public static String dataPlaneYaml(Path path) {
        try {
            var raw = Files.readString(path);
            if (raw.isBlank()) {
                return null;
            }
            if (raw.charAt(0) == '{') {
                var envelope = Envelope.parse(raw);
                if (envelope == null || envelope.canonical == null || envelope.canonicalHash == null) {
                    return null;
                }
                if (!envelope.canonicalHash.equals(ConfigCodec.sha256(envelope.canonical))) {
                    return null;
                }
                return dataMode(envelope.canonical) ? envelope.canonical : null;
            }
            return dataMode(raw) ? raw : null;
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    /**
     * True when the file parses and {@code node.mode} is {@code data}. Any read or parse error is
     * false, so a corrupt file is not a boot source.
     */
    public static boolean dataPlane(Path path) {
        return dataPlaneYaml(path) != null;
    }

    /** Signature stored in a JSON envelope, or null when there is none or the file is legacy YAML. */
    public static byte[] signature(Path path) {
        try {
            var raw = Files.readString(path);
            if (raw.isBlank() || raw.charAt(0) != '{') {
                return null;
            }
            var envelope = Envelope.parse(raw);
            if (envelope == null || envelope.signature == null || envelope.signature.isEmpty()) {
                return null;
            }
            return Base64.getDecoder().decode(envelope.signature);
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    private static boolean dataMode(String yaml) {
        try {
            var root = YamlValues.parse(yaml, "last-good");
            if (!(root.get("node") instanceof Object node)) {
                return false;
            }
            var map = YamlValues.mapping(node, "node");
            return map.get("mode") instanceof String mode && mode.trim().equalsIgnoreCase("data");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String envelope(ConfigDocument document) {
        var canonical = document.canonicalText() != null ? document.canonicalText() : ConfigCodec.text(document);
        var signature = document.signature() == null ? "" : Base64.getEncoder().encodeToString(document.signature());
        var out = new StringBuilder(canonical.length() + 128);
        out.append("{\"schema\":").append(document.schemaVersion());
        out.append(",\"version\":").append(document.bundleVersion());
        out.append(",\"canonicalHash\":");
        json(out, document.canonicalHash());
        out.append(",\"signature\":");
        json(out, signature);
        out.append(",\"canonical\":");
        json(out, canonical);
        out.append("}\n");
        return out.toString();
    }

    private static void ensureDirectory(Path dir) throws IOException {
        var configDir = dir.getFileName() != null && dir.getFileName().toString().equals("config");
        if (!Files.isDirectory(dir)) {
            var parent = dir.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                create(parent, false);
            }
            create(dir, true);
        } else if (configDir && posix(dir)) {
            try {
                Files.setPosixFilePermissions(dir, OWNER_RWX);
            } catch (UnsupportedOperationException e) {
                // advertised, but this store cannot change a directory mode
            }
        }
    }

    private static void create(Path dir, boolean restrict) throws IOException {
        try {
            if (posix(dir)) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_RWX));
            } else {
                Files.createDirectories(dir);
            }
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(dir);
        }
        if (restrict && posix(dir)) {
            try {
                Files.setPosixFilePermissions(dir, OWNER_RWX);
            } catch (UnsupportedOperationException e) {
                // the directory exists; the mode stays whatever the store applied
            }
        }
    }

    private static void writeDurable(Path tmp, String text) throws IOException {
        try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            var bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
    }

    /** Directory entry durable before and after the rename. A filesystem that cannot force is ignored. */
    private static void force(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // zip and some network stores have no directory force
        }
    }

    private static void own(Path target) {
        if (!posix(target)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(target, OWNER_RW);
        } catch (IOException | UnsupportedOperationException e) {
            // the temp file was already created 0600 when the store could do that
        }
    }

    private static Path temp(Path directory) throws IOException {
        if (posix(directory)) {
            try {
                return Files.createTempFile(directory, ".cfg-", ".tmp",
                        PosixFilePermissions.asFileAttribute(OWNER_RW));
            } catch (UnsupportedOperationException e) {
                // the view was advertised, but this store cannot set a file mode
            }
        }
        return Files.createTempFile(directory, ".cfg-", ".tmp");
    }

    private static boolean posix(Path path) {
        var probe = path;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            probe = path;
        }
        return probe.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static void json(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
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
    }

    /** The JSON object this store writes. A foreign shape returns null. */
    private static final class Envelope {
        int schema;
        long version;
        String canonicalHash;
        String signature;
        String canonical;

        static Envelope parse(String json) {
            var parser = new Parser(json);
            parser.skipWs();
            if (parser.peek() != '{') {
                return null;
            }
            parser.pos++;
            var envelope = new Envelope();
            boolean any = false;
            while (true) {
                parser.skipWs();
                if (parser.peek() == '}') {
                    break;
                }
                if (any) {
                    parser.expect(',');
                    parser.skipWs();
                }
                any = true;
                var key = parser.string();
                parser.skipWs();
                parser.expect(':');
                parser.skipWs();
                switch (key) {
                    case "schema" -> envelope.schema = (int) parser.number();
                    case "version" -> envelope.version = parser.number();
                    case "canonicalHash" -> envelope.canonicalHash = parser.string();
                    case "signature" -> envelope.signature = parser.string();
                    case "canonical" -> envelope.canonical = parser.string();
                    default -> parser.skipValue();
                }
            }
            return envelope.canonical == null ? null : envelope;
        }
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        int peek() {
            return this.pos < this.text.length() ? this.text.charAt(this.pos) : -1;
        }

        void skipWs() {
            while (this.pos < this.text.length() && this.text.charAt(this.pos) <= ' ') {
                this.pos++;
            }
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected " + c);
            }
            this.pos++;
        }

        long number() {
            int start = this.pos;
            if (peek() == '-') {
                this.pos++;
            }
            if (peek() < '0' || peek() > '9') {
                throw new IllegalArgumentException("number");
            }
            while (peek() >= '0' && peek() <= '9') {
                this.pos++;
            }
            return Long.parseLong(this.text.substring(start, this.pos));
        }

        String string() {
            expect('"');
            var out = new StringBuilder();
            while (this.pos < this.text.length()) {
                char c = this.text.charAt(this.pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (this.pos >= this.text.length()) {
                    break;
                }
                char esc = this.text.charAt(this.pos++);
                switch (esc) {
                    case '"', '\\', '/' -> out.append(esc);
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (this.pos + 4 > this.text.length()) {
                            throw new IllegalArgumentException("unicode");
                        }
                        out.append((char) Integer.parseInt(this.text.substring(this.pos, this.pos + 4), 16));
                        this.pos += 4;
                    }
                    default -> throw new IllegalArgumentException("escape");
                }
            }
            throw new IllegalArgumentException("string");
        }

        void skipValue() {
            skipWs();
            if (peek() == '"') {
                string();
                return;
            }
            if (peek() == '-' || (peek() >= '0' && peek() <= '9')) {
                number();
                return;
            }
            throw new IllegalArgumentException("value");
        }
    }
}
