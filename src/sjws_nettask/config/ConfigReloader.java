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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import sjws_nettask.ServerConfig;
import sjws_nettask.acme.ChallengeBoard;
import sjws_nettask.tls.TlsMaterial;

/**
 * Re-reads the config file and compiles the next snapshot. Bind-time fields stay as they were;
 * a running HTTP/3 listener also keeps its idle, header and write timeouts. A validation error
 * leaves the caller holding the previous snapshot.
 */
public final class ConfigReloader {

    private final String[] args;
    private final java.util.Map<String, String> env;
    private final ServerConfig.PasswordFileReader passwords;
    /** {@code ha.messaging} as the process started; read once, so a reload only reports a change. */
    private final HaMessagingSettings haMessaging;

    public ConfigReloader(ConfigLoader.Loaded loaded) {
        this.haMessaging = loaded.haMessaging();
        this.args = loaded.args();
        this.env = loaded.env();
        this.passwords = loaded.passwords();
    }

    public ReloadResult reload(ConfigSnapshot current, boolean http3Running, ChallengeBoard board) {
        if (current.document().configFile() == null) {
            return ReloadResult.rejected(current, java.util.List.of(
                    "no config file; reload reads the file given by --config or WEB_SERVER_CONFIG"));
        }
        var loaded = ConfigLoader.load(this.args, this.env, this.passwords);
        if (!loaded.ok() || loaded.document() == null) {
            var errors = loaded.errors().isEmpty()
                    ? java.util.List.of("configuration rejected")
                    : loaded.errors();
            return ReloadResult.rejected(current, errors);
        }
        return apply(current, loaded.document(), http3Running, board, loaded.warnings(), false,
                haRestart(this.haMessaging, loaded.haMessaging()));
    }

    /**
     * {@code ha.messaging}, {@code ha.topics} and {@code ha.members} (and the other msg settings) are read once at
     * startup: a reload that changes them applies nothing there, and says so instead of staying silent.
     */
    static List<String> haRestart(HaMessagingSettings running, HaMessagingSettings fresh) {
        var out = new ArrayList<String>();
        if (running.mode() != fresh.mode() || running.replicate() != fresh.replicate()) {
            out.add("ha.messaging changed to " + fresh.mode().key() + "; still " + running.mode().key()
                    + (running.replicate() ? " (replicating)" : " (single node)") + " until restart");
        }
        if (!running.topics().equals(fresh.topics())) {
            out.add("ha.topics changed; still " + new java.util.TreeSet<>(running.topics()) + " until restart");
        }
        if (!running.members().equals(fresh.members()) || !Objects.equals(running.nodeId(), fresh.nodeId())) {
            out.add("ha.members or ha.node_id changed; still the startup member list until restart");
        }
        if (!running.electionTimeout().equals(fresh.electionTimeout()) || !running.heartbeat().equals(fresh.heartbeat())
                || !Objects.equals(running.stateDir(), fresh.stateDir())
                || running.insecurePlaintext() != fresh.insecurePlaintext()) {
            out.add("ha.election_timeout, ha.heartbeat, ha.state_dir or ha.messaging_insecure_plaintext changed;"
                    + " still the startup values until restart");
        }
        return out;
    }

    /**
     * Validates {@code incoming}, clamps bind-time fields, and compiles the next snapshot. Does
     * not swap it in. A lower non-zero {@link ConfigDocument#bundleVersion()} is rejected. On
     * failure the returned snapshot is {@code current}.
     *
     * @param keepLocalFile when true, the compiled document keeps {@code current}'s config path
     *        so a pushed bundle does not replace the user's YAML path
     */
    public ReloadResult apply(ConfigSnapshot current, ConfigDocument incoming, boolean http3Running,
            ChallengeBoard board, boolean keepLocalFile) {
        return apply(current, incoming, http3Running, board, List.of(), keepLocalFile, List.of());
    }

    private ReloadResult apply(ConfigSnapshot current, ConfigDocument incoming, boolean http3Running,
            ChallengeBoard board, List<String> warnings, boolean keepLocalFile, List<String> restartNotes) {
        var errors = ConfigValidator.validate(incoming);
        if (!errors.isEmpty()) {
            return ReloadResult.rejected(current, errors);
        }
        long currentVersion = current.document().bundleVersion();
        if (incoming.bundleVersion() > 0 && currentVersion > incoming.bundleVersion()) {
            return ReloadResult.rejected(current, List.of("bundle version " + incoming.bundleVersion()
                    + " is older than " + currentVersion));
        }
        var destructive = MessagingSettings.destructiveErrors(current.document().messaging(), incoming.messaging());
        if (!destructive.isEmpty()) {
            return ReloadResult.rejected(current, destructive);
        }
        if (!incoming.canonicalHash().isEmpty()
                && incoming.canonicalHash().equals(current.document().canonicalHash())
                && Objects.equals(incoming.server().tlsKeyPassword(), current.document().server().tlsKeyPassword())) {
            return new ReloadResult(true, current.generation(), warnings, List.of(), List.copyOf(restartNotes), current,
                    null);
        }
        var restart = new ArrayList<String>(restartNotes);
        var clamped = clamp(current.document().server(), incoming.server(), http3Running, restart);
        var node = incoming.node();
        if (current.document().node().mode() != node.mode()) {
            node = new NodeSettings(current.document().node().mode(), node.labels());
            restart.add("node.mode changed to " + incoming.node().mode().wire() + "; still "
                    + current.document().node().mode().wire() + " until restart");
        }
        TlsMaterial tls = null;
        if (clamped.tlsCert() != null) {
            try {
                tls = TlsMaterial.fromPem(clamped.tlsCert(), clamped.tlsKey(), clamped.tlsKeyPassword());
            } catch (IOException e) {
                return ReloadResult.rejected(current, List.of("tls: " + e.getMessage()));
            }
        } else if (current.document().server().tlsCert() != null) {
            restart.add("removing tls.cert leaves the current certificate in place until restart");
        }
        Path configFile = keepLocalFile || incoming.configFile() == null
                ? current.document().configFile()
                : incoming.configFile();
        Path stateDir = incoming.stateDir() != null ? incoming.stateDir() : current.document().stateDir();
        try {
            boolean unchanged = clamped.equals(incoming.server()) && node.equals(incoming.node())
                    && incoming.canonicalText() != null;
            var document = unchanged
                    ? incoming.withConfigFile(configFile).withStateDir(stateDir)
                    : ConfigDocument.of(clamped, incoming.acme(), incoming.routing(), incoming.middleware(),
                            incoming.access(), node, incoming.schemaVersion(), incoming.bundleVersion(), configFile,
                            incoming.messaging())
                            .withStateDir(stateDir);
            if (incoming.signature() != null && document.signature() == null
                    && document.canonicalHash().equals(incoming.canonicalHash())) {
                document = document.withSignature(incoming.signature());
            }
            var next = ConfigSnapshot.compile(document, board, current.generation() + 1);
            return new ReloadResult(true, next.generation(), warnings, List.of(), restart, next, tls);
        } catch (RuntimeException e) {
            return ReloadResult.rejected(current, List.of(e.getMessage()));
        }
    }

    private static ServerConfig clamp(ServerConfig old, ServerConfig fresh, boolean http3Running,
            java.util.List<String> restart) {
        var builder = fresh.toBuilder();
        keep(old.port(), fresh.port(), "server.port", builder::port, restart);
        keep(old.backlog(), fresh.backlog(), "server.backlog", builder::backlog, restart);
        keep(old.maxConnections(), fresh.maxConnections(), "server.max_connections", builder::maxConnections, restart);
        keep(old.maxConnectionsPerIp(), fresh.maxConnectionsPerIp(), "server.max_connections_per_ip",
                builder::maxConnectionsPerIp, restart);
        if (old.h3Enabled() != fresh.h3Enabled()) {
            builder.h3Enabled(old.h3Enabled());
            restart.add("http3.enabled changed; still " + old.h3Enabled() + " until restart");
        }
        keep(old.h3Port(), fresh.h3Port(), "http3.port", builder::h3Port, restart);
        if (!old.h3Host().equals(fresh.h3Host())) {
            builder.h3Host(old.h3Host());
            restart.add("http3.host changed; still " + old.h3Host() + " until restart");
        }
        keep(old.h3MaxConnections(), fresh.h3MaxConnections(), "http3.max_connections", builder::h3MaxConnections,
                restart);
        keep(old.h3MaxConnectionsPerIp(), fresh.h3MaxConnectionsPerIp(), "http3.max_connections_per_ip",
                builder::h3MaxConnectionsPerIp, restart);
        if (old.h3MinRate() != fresh.h3MinRate()) {
            builder.h3MinRate(old.h3MinRate());
            restart.add("http3.min_rate changed; still " + old.h3MinRate() + " until restart");
        }
        if (old.altSvc() != fresh.altSvc()) {
            builder.altSvc(old.altSvc());
            restart.add("http3.alt_svc changed; still " + old.altSvc() + " until restart");
        }
        if (!old.altSvcMaxAge().equals(fresh.altSvcMaxAge())) {
            builder.altSvcMaxAge(old.altSvcMaxAge());
            restart.add("http3.alt_svc_max_age changed; still " + old.altSvcMaxAge().toSeconds()
                    + "s until restart");
        }
        boolean timeouts = !old.idleTimeout().equals(fresh.idleTimeout())
                || !old.headerTimeout().equals(fresh.headerTimeout())
                || !old.writeTimeout().equals(fresh.writeTimeout());
        if (http3Running && timeouts) {
            builder.idleTimeout(old.idleTimeout()).headerTimeout(old.headerTimeout())
                    .writeTimeout(old.writeTimeout());
            restart.add("idle, header and write timeouts are fixed while HTTP/3 is running; restart to apply them");
        }
        return builder.build();
    }

    private static void keep(int oldValue, int fresh, String name, java.util.function.IntConsumer revert,
            java.util.List<String> restart) {
        if (oldValue != fresh) {
            revert.accept(oldValue);
            restart.add(name + " changed to " + fresh + "; still " + oldValue + " until restart");
        }
    }
}
