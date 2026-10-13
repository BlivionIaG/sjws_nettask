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
package sjws_nettask.clusterharness;

import java.time.Duration;
import sjws_nettask.ServiceHooks;
import sjws_nettask.SjwsServer;
import sjws_nettask.clusterharness.ClusterHarness.DataRole;
import sjws_nettask.cp.CpStore;
import sjws_nettask.cp.WatchServer;
import sjws_nettask.service.NodeEnrollment;

/**
 * One data-role node ({@link DataRole#DATA}) joined to an in-process control plane through the
 * real watch: {@code ServiceLaunch} opens {@code DataWatch}, which long-polls {@code WatchServer}
 * and applies each activated envelope with {@code SjwsServer.applyRemote}. Nothing here attaches
 * a binding: {@link #connection()} and {@link #configVersion()} read the status the watch reported.
 * {@link NodeFixtures#connected(java.nio.file.Path)} is the only constructor.
 */
public final class ConnectedDataNode implements AutoCloseable {

    /** How long {@link #push} waits for the node to apply the activated version. */
    static final Duration APPLY_TIMEOUT = Duration.ofSeconds(20);

    private final CpStore store;
    private final WatchServer watch;
    private final SjwsServer server;
    private final ServiceHooks hooks;

    private final java.nio.file.Path documentRoot;

    ConnectedDataNode(CpStore store, WatchServer watch, SjwsServer server, ServiceHooks hooks,
            java.nio.file.Path documentRoot) {
        this.documentRoot = documentRoot;
        this.store = store;
        this.watch = watch;
        this.server = server;
        this.hooks = hooks;
    }

    /** Always {@link DataRole#DATA}. */
    public DataRole role() {
        return DataRole.DATA;
    }

    /** {@code connected} once the watch is up. Not {@code pending}. */
    public String connection() {
        var enrollment = NodeEnrollment.current();
        var binding = enrollment == null ? null : enrollment.binding();
        return binding == null ? "pending" : binding.connectionState();
    }

    /** Applied desired-config version. {@code 0} before the first push lands. */
    public long configVersion() {
        var enrollment = NodeEnrollment.current();
        var binding = enrollment == null ? null : enrollment.binding();
        return binding == null ? 0L : binding.configVersion();
    }

    /** Content listener. Ephemeral, assigned by the kernel. */
    public int httpPort() {
        return this.server.tcpPort();
    }

    /**
     * Absolute document root the node starts with. A pushed document must name an existing root:
     * the node validates it inside {@code applyRemote} and rejects it otherwise (fail closed).
     */
    public java.nio.file.Path documentRoot() {
        return this.documentRoot;
    }

    /** Watch listener of the in-process control plane. */
    public int watchPort() {
        return this.watch.port();
    }

    /** Waits until {@link #connection()} is {@code connected}. */
    void awaitConnected(Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (!"connected".equals(connection())) {
            if (System.nanoTime() > end) {
                throw new IllegalStateException("data node did not connect: " + connection());
            }
            pause();
        }
    }

    /**
     * Validate, propose, and activate canonical YAML on the live control plane, then wait for the
     * data node to apply it. Returns the version the data node has applied.
     */
    public long push(String canonicalYaml) {
        var proposed = this.store.propose(canonicalYaml);
        if (proposed.status() != 200) {
            throw new IllegalStateException("propose: " + proposed.status() + " " + proposed.body());
        }
        var activated = this.store.activate(System.currentTimeMillis(), 15 * 60_000L);
        if (activated.status() != 200) {
            throw new IllegalStateException("activate: " + activated.status() + " " + activated.body());
        }
        long version = this.store.activated().version();
        long end = System.nanoTime() + APPLY_TIMEOUT.toNanos();
        while (configVersion() != version) {
            if (System.nanoTime() > end) {
                throw new IllegalStateException("data node did not apply version " + version + "; at "
                        + configVersion() + ", " + connection());
            }
            pause();
        }
        return version;
    }

    private static void pause() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    @Override
    public void close() {
        this.hooks.onShutdown();
        this.server.close();
        this.watch.close();
        this.store.close();
    }
}
