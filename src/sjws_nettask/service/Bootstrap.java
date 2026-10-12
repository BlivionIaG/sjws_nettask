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
package sjws_nettask.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * First-boot file for a control or data process. It names the pin, the state directory, and the
 * path of the join token. The token secret is not a key in this file and is never a command-line
 * argument.
 * <p>
 * The control-plane watch does not exist yet. {@link #load} checks the files and holds the token.
 * It does not open a socket. {@link #markJoined()} is what the control plane calls after
 * {@code ConsumeToken} commits: it writes {@code stateDir/node.id} and only then deletes the token.
 */
public final class Bootstrap {

    static final String IDENTITY = "node.id";
    private static final Set<String> CAPABILITIES = Set.of("http1", "http2", "http3", "proxy", "static",
            "messaging");
    private static final Set<String> KEYS = Set.of("role", "data-role", "node-id", "state-dir", "pin",
            "control-plane", "listen", "peers", "capabilities", "join-token");
    private static final Set<String> IDENTITY_KEYS = Set.of("node-id", "role", "data-role");

    private final NodeRole role;
    private final DataPlaneRole dataRole;
    private final String nodeId;
    private final String controlPlane;
    private final String listen;
    private final List<String> peers;
    private final List<String> capabilities;
    private final Map<String, String> labels;
    private final Path pinFile;
    private final byte[] pin;
    private final Path stateDir;
    private final Path source;
    private final boolean joinedBefore;
    private JoinMaterial join;

    private Bootstrap(NodeRole role, DataPlaneRole dataRole, String nodeId, String controlPlane, String listen,
            List<String> peers, List<String> capabilities, Map<String, String> labels, Path pinFile, byte[] pin,
            Path stateDir, Path source, boolean joinedBefore, JoinMaterial join) {
        this.role = role;
        this.dataRole = dataRole;
        this.nodeId = nodeId;
        this.controlPlane = controlPlane;
        this.listen = listen;
        this.peers = peers;
        this.capabilities = capabilities;
        this.labels = labels;
        this.pinFile = pinFile;
        this.pin = pin;
        this.stateDir = stateDir;
        this.source = source;
        this.joinedBefore = joinedBefore;
        this.join = join;
    }

    /**
     * @param expectedRole {@code control} or {@code data} from {@code --role}, or null to take the
     *        file's role. A mismatch is refused.
     */
    public static Bootstrap load(Path file, String expectedRole) throws IOException {
        if (file == null) {
            throw new BootstrapException("bootstrap path is missing");
        }
        var source = file.toAbsolutePath();
        PosixMode.refuseSymlink(source);
        if (!Files.isRegularFile(source)) {
            throw new BootstrapException(source + " is not a file");
        }
        PosixMode.requireBootstrap(source);
        byte[] raw = TokenFiles.readCapped(source, TokenFiles.MAX_OTHER_BYTES);
        try {
            rejectEmbeddedSecret(source, raw);
            var parsed = parse(source, raw);
            return fromParsed(source, expectedRole, parsed);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    public NodeRole role() {
        return this.role;
    }

    /** Null on a control process. */
    public DataPlaneRole dataRole() {
        return this.dataRole;
    }

    public String nodeId() {
        return this.nodeId;
    }

    /** Bootstrap contact for a data node. Null on a control process. */
    public String controlPlane() {
        return this.controlPlane;
    }

    /** Optional listen address stored for the control-plane workstream. Not bound by this process. */
    public String listen() {
        return this.listen;
    }

    public List<String> peers() {
        return this.peers;
    }

    public List<String> capabilities() {
        return this.capabilities;
    }

    public Map<String, String> labels() {
        return this.labels;
    }

    public Path pinFile() {
        return this.pinFile;
    }

    /** A copy of the pinned root public key. */
    public byte[] pin() {
        return this.pin.clone();
    }

    public Path stateDir() {
        return this.stateDir;
    }

    public Path source() {
        return this.source;
    }

    /** True when {@code stateDir/node.id} already matched this node. The token file may still be absent. */
    public boolean joinedBefore() {
        return this.joinedBefore;
    }

    /** Null once the token was destroyed, or on a restart after {@link #markJoined()}. */
    public JoinMaterial join() {
        return this.join;
    }

    /**
     * Records the node identity, then deletes the token file. Called only after the control plane
     * has committed the consume. A crash before the delete leaves the token in place so the next
     * start can retry; {@code node.id} does not by itself discard a token that is still on disk.
     */
    public void markJoined() throws IOException {
        var body = identityText().getBytes(StandardCharsets.US_ASCII);
        AtomicFiles.write(this.stateDir.resolve(IDENTITY), body, PosixMode.OWNER_RW);
        var held = this.join;
        if (held != null) {
            held.destroy();
            this.join = null;
        }
    }

    /** Log line with role and node id. No paths that point at the token, and no secret. */
    public String startupLine() {
        var sb = new StringBuilder("control-plane role=").append(this.role.wire());
        if (this.dataRole != null) {
            sb.append('/').append(this.dataRole.wire());
        }
        sb.append(" node=").append(this.nodeId);
        sb.append(" connection=").append(ConnectionState.PENDING.wire());
        sb.append(" config-version=0");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Bootstrap[role=" + this.role.wire() + " node=" + this.nodeId + "]";
    }

    private String identityText() {
        var sb = new StringBuilder();
        sb.append("node-id=").append(this.nodeId).append('\n');
        sb.append("role=").append(this.role.wire()).append('\n');
        if (this.dataRole != null) {
            sb.append("data-role=").append(this.dataRole.wire()).append('\n');
        }
        return sb.toString();
    }

    private static void rejectEmbeddedSecret(Path source, byte[] raw) throws IOException {
        int i = 0;
        while (i < raw.length) {
            int start = i;
            while (i < raw.length && raw[i] != '\n') {
                i++;
            }
            int end = i;
            if (i < raw.length) {
                i++;
            }
            int from = start;
            while (from < end && (raw[from] == ' ' || raw[from] == '\t' || raw[from] == '\r')) {
                from++;
            }
            if (from >= end || raw[from] == '#') {
                continue;
            }
            int eq = from;
            while (eq < end && raw[eq] != '=') {
                eq++;
            }
            int keyEnd = eq;
            while (keyEnd > from && (raw[keyEnd - 1] == ' ' || raw[keyEnd - 1] == '\t')) {
                keyEnd--;
            }
            if (keyIs(raw, from, keyEnd, "secret") || keyIs(raw, from, keyEnd, "password")
                    || keyIs(raw, from, keyEnd, "token") || keyIs(raw, from, keyEnd, "join-secret")) {
                throw new BootstrapException(source
                        + " must not contain a secret; put the join token in its own file");
            }
        }
    }

    private static boolean keyIs(byte[] raw, int from, int to, String key) {
        if (to - from != key.length()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (raw[from + i] != (byte) key.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static Parsed parse(Path source, byte[] raw) throws IOException {
        var text = new String(raw, StandardCharsets.UTF_8);
        if (text.indexOf('\0') >= 0) {
            throw new BootstrapException(source + " contains a NUL");
        }
        var parsed = new Parsed();
        int lineNo = 0;
        for (var line : text.split("\n", -1)) {
            lineNo++;
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isBlank() || line.stripLeading().charAt(0) == '#') {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new BootstrapException(source + " line " + lineNo + " is not key=value");
            }
            var key = line.substring(0, eq).trim();
            var value = line.substring(eq + 1).trim();
            if (key.startsWith("label.")) {
                var name = key.substring("label.".length());
                if (!name.matches("[A-Za-z0-9_-]{1,32}") || !value.matches("[A-Za-z0-9._:-]{1,64}")) {
                    throw new BootstrapException(source + " has an invalid label");
                }
                if (parsed.labels.putIfAbsent(name, value) != null) {
                    throw new BootstrapException(source + " has a duplicate label");
                }
                continue;
            }
            if (!key.matches("[a-z][a-z0-9-]{0,31}")) {
                throw new BootstrapException(source + " has an invalid key");
            }
            if (parsed.values.putIfAbsent(key, value) != null) {
                throw new BootstrapException(source + " has a duplicate " + key);
            }
        }
        return parsed;
    }

    private static Bootstrap fromParsed(Path source, String expectedRole, Parsed parsed) throws IOException {
        unknown(source, parsed.values, KEYS);
        var role = requiredRole(source, parsed.values.get("role"));
        if (expectedRole != null && !expectedRole.equals(role.wire())) {
            throw new BootstrapException(source + " role is " + role.wire() + " but --role=" + safeRole(expectedRole));
        }
        var dataRole = dataRole(source, role, parsed.values.get("data-role"));
        var nodeId = requiredId(source, parsed.values.get("node-id"));
        var stateDir = absolute(source, parsed.values.get("state-dir"), "state-dir");
        var pinFile = absolute(source, parsed.values.get("pin"), "pin");
        if (pinFile.equals(source)) {
            throw new BootstrapException(source + " pin must be a different file");
        }
        if (pinFile.startsWith(stateDir) || source.startsWith(stateDir)) {
            throw new BootstrapException(source + " pin and bootstrap must be outside state-dir");
        }
        var controlPlane = endpointOrNull(source, role, parsed.values.get("control-plane"));
        var listen = optionalEndpoint(source, parsed.values.get("listen"));
        var peers = endpoints(source, parsed.values.get("peers"));
        var capabilities = capabilities(source, parsed.values.get("capabilities"));
        var identity = readIdentity(stateDir);
        boolean joined = identity != null;
        if (joined) {
            if (!identity.nodeId.equals(nodeId) || identity.role != role
                    || (role == NodeRole.DATA && identity.dataRole != dataRole)) {
                throw new BootstrapException(stateDir + " identity does not match this bootstrap");
            }
        }
        var join = loadJoin(source, parsed.values.get("join-token"), pinFile, stateDir, joined);
        var pin = readPin(pinFile);
        ensurePrivateDir(stateDir);
        return new Bootstrap(role, dataRole, nodeId, controlPlane, listen, peers, capabilities,
                Map.copyOf(parsed.labels), pinFile, pin, stateDir, source, joined, join);
    }

    private static JoinMaterial loadJoin(Path source, String tokenPath, Path pinFile, Path stateDir, boolean joined)
            throws IOException {
        if (tokenPath == null || tokenPath.isBlank()) {
            if (joined) {
                return null;
            }
            throw new BootstrapException(source + " requires join-token on first start");
        }
        var path = absolute(source, tokenPath, "join-token");
        if (path.equals(pinFile) || path.equals(source) || path.startsWith(stateDir)) {
            throw new BootstrapException(source + " join-token must be outside state-dir and not the pin");
        }
        if (!Files.exists(path)) {
            if (joined) {
                return null;
            }
            throw new BootstrapException(path + " does not exist");
        }
        return TokenFiles.readJoin(path);
    }

    private static Identity readIdentity(Path stateDir) throws IOException {
        if (!Files.exists(stateDir)) {
            return null;
        }
        if (Files.isSymbolicLink(stateDir) || !Files.isDirectory(stateDir)) {
            throw new BootstrapException(stateDir + " is not a directory");
        }
        var id = stateDir.resolve(IDENTITY);
        if (!Files.exists(id)) {
            return null;
        }
        PosixMode.refuseSymlink(id);
        byte[] raw = TokenFiles.readCapped(id, TokenFiles.MAX_TOKEN_BYTES);
        try {
            var map = parse(id, raw).values;
            unknown(id, map, IDENTITY_KEYS);
            var nodeId = requiredId(id, map.get("node-id"));
            var role = requiredRole(id, map.get("role"));
            var dataRole = dataRole(id, role, map.get("data-role"));
            return new Identity(nodeId, role, dataRole);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    private static byte[] readPin(Path pinFile) throws IOException {
        PosixMode.refuseSymlink(pinFile);
        if (!Files.isRegularFile(pinFile)) {
            throw new BootstrapException(pinFile + " is not a file");
        }
        byte[] raw = TokenFiles.readCapped(pinFile, TokenFiles.MAX_OTHER_BYTES);
        if (raw.length == 0) {
            throw new BootstrapException(pinFile + " is empty");
        }
        return raw;
    }

    private static void ensurePrivateDir(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir)) {
            throw new BootstrapException(dir + " is a symlink");
        }
        Files.createDirectories(dir);
        if (!PosixMode.supported(dir)) {
            throw new BootstrapException(dir + " cannot be mode 0700 (filesystem has no POSIX permissions)");
        }
        Files.setPosixFilePermissions(dir, PosixMode.OWNER_RWX);
        PosixMode.requirePrivateDir(dir);
    }

    private static NodeRole requiredRole(Path source, String raw) throws IOException {
        var role = NodeRole.parse(raw);
        if (role != NodeRole.CONTROL && role != NodeRole.DATA) {
            throw new BootstrapException(source + " role must be control or data");
        }
        return role;
    }

    private static DataPlaneRole dataRole(Path source, NodeRole role, String raw) throws IOException {
        if (role == NodeRole.CONTROL) {
            if (raw != null) {
                throw new BootstrapException(source + " control role does not take data-role");
            }
            return null;
        }
        var parsed = DataPlaneRole.parse(raw);
        if (parsed == null) {
            throw new BootstrapException(source + " data-role must be gateway, static, broker, or edge");
        }
        return parsed;
    }

    private static String requiredId(Path source, String raw) throws IOException {
        if (raw == null || !raw.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new BootstrapException(source + " node-id must be 1..64 of A-Z a-z 0-9 . _ : -");
        }
        return raw;
    }

    private static Path absolute(Path source, String raw, String key) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new BootstrapException(source + " requires " + key);
        }
        var path = Path.of(raw);
        if (!path.isAbsolute()) {
            throw new BootstrapException(source + " " + key + " must be an absolute path");
        }
        return path.normalize();
    }

    private static void unknown(Path source, Map<String, String> values, Set<String> allowed) throws IOException {
        for (var key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw new BootstrapException(source + " has an unknown key " + key);
            }
        }
    }

    private static String endpointOrNull(Path source, NodeRole role, String raw) throws IOException {
        if (role == NodeRole.CONTROL) {
            if (raw != null) {
                throw new BootstrapException(source + " control role does not set control-plane");
            }
            return null;
        }
        if (raw == null || raw.isBlank()) {
            throw new BootstrapException(source + " requires control-plane");
        }
        return endpoint(source, raw, "control-plane");
    }

    private static String optionalEndpoint(Path source, String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return endpoint(source, raw, "listen");
    }

    private static List<String> endpoints(Path source, String csv) throws IOException {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (var part : csv.split(",", -1)) {
            if (part.isBlank()) {
                throw new BootstrapException(source + " peers has an empty endpoint");
            }
            out.add(endpoint(source, part.trim(), "peers"));
        }
        return List.copyOf(out);
    }

    static String endpoint(Path source, String value, String key) throws IOException {
        if (value.indexOf('/') >= 0 || value.indexOf('@') >= 0 || value.indexOf('?') >= 0 || value.indexOf('#') >= 0
                || value.indexOf(' ') >= 0) {
            throw new BootstrapException(source + " " + key + " must be host:port");
        }
        String host;
        String portText;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 3 || end + 2 >= value.length() || value.charAt(end + 1) != ':') {
                throw new BootstrapException(source + " " + key + " must be [ipv6]:port");
            }
            host = value.substring(1, end);
            if (host.indexOf(':') < 0) {
                throw new BootstrapException(source + " " + key + " must be [ipv6]:port");
            }
            portText = value.substring(end + 2);
        } else {
            int colon = value.lastIndexOf(':');
            if (colon <= 0 || colon == value.length() - 1 || value.indexOf(':') != colon) {
                throw new BootstrapException(source + " " + key + " must be host:port");
            }
            host = value.substring(0, colon);
            if (!host.matches("[A-Za-z0-9._-]{1,253}") || host.contains("..")) {
                throw new BootstrapException(source + " " + key + " has an invalid host");
            }
            portText = value.substring(colon + 1);
        }
        if (!portText.matches("[0-9]{1,5}")) {
            throw new BootstrapException(source + " " + key + " has an invalid port");
        }
        int port = Integer.parseInt(portText);
        if (port < 1 || port > 65535) {
            throw new BootstrapException(source + " " + key + " has an invalid port");
        }
        return value;
    }

    private static List<String> capabilities(Path source, String csv) throws IOException {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (var part : csv.split(",", -1)) {
            var name = part.trim();
            if (!CAPABILITIES.contains(name) || out.contains(name)) {
                throw new BootstrapException(source + " has an invalid capability");
            }
            out.add(name);
        }
        return List.copyOf(out);
    }

    private static String safeRole(String role) {
        return role.matches("[a-z]{1,16}") ? role : "rejected";
    }

    private record Identity(String nodeId, NodeRole role, DataPlaneRole dataRole) {
    }

    private static final class Parsed {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Map<String, String> labels = new LinkedHashMap<>();
    }
}
