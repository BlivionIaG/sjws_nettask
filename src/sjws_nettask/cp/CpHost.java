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
import sjws_nettask.observe.ServerMetrics;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import sjws_nettask.service.CpReloadable;

/**
 * A full CONTROL-role voter (control-plane-hosting.md §1). Start order: config and timing check (done by
 * {@link CpLaunch}), {@code KeyRing.load}, {@code CpStore.open} with the peers, {@link WatchServer},
 * {@link AdminServer}, ready. Shutdown runs in reverse. Any failure closes what was opened and throws, so the
 * process exits non-zero.
 */
public final class CpHost implements CpReloadable {

    /** One listener's in-place TLS reload ({@link CpServerTls#reloadTls()}). */
    @FunctionalInterface
    interface TlsReload {
        void reloadTls() throws CpKeyException;
    }

    static final String INSECURE_WARNING = "WARN: control plane running with cp.insecureSkipPermissionChecks:"
            + " key file owner/mode checks are OFF. Development only.";

    private final CpConfig config;
    private final CpHostDeps deps;
    private final PrintStream err;
    private final Duration warnEvery;
    private final List<String> events = new CopyOnWriteArrayList<>();
    final List<TlsReload> tlsReloads = new CopyOnWriteArrayList<>();
    private CpStore store;
    /** Live when the store is open (#259); zero-cost when CP is off (host not started). */
    private final ServerMetrics metrics = new ServerMetrics();
    private sjws_nettask.cluster.ClusterTransport transport;
    private AutoCloseable watch;
    private AutoCloseable admin;
    private Thread insecureWarner;
    private volatile boolean closed;

    private CpHost(CpConfig config, CpHostDeps deps, PrintStream err, Duration warnEvery) {
        this.config = config;
        this.deps = deps;
        this.err = err;
        this.warnEvery = warnEvery;
    }

    /** Declared as {@link CpReloadable} so callers link no cp type. */
    public static CpReloadable start(CpConfig config, CpHostDeps deps, PrintStream err) throws IOException {
        return start(config, deps, err, Duration.ofHours(1));
    }

    static CpReloadable start(CpConfig config, CpHostDeps deps, PrintStream err, Duration warnEvery)
            throws IOException {
        var host = new CpHost(config, deps, err, warnEvery);
        try {
            host.open();
        } catch (IOException | RuntimeException e) {
            host.close();
            throw e;
        }
        return host;
    }

    private void open() throws IOException {
        KeyRing keys;
        try {
            keys = deps.loadKeys(config.keys());
        } catch (CpKeyException e) {
            throw new IOException("control role: key ring not loaded: " + e.getMessage(), e);
        }
        events.add("keys");
        var self = config.self();
        if (self == null) {
            store = CpStore.open(config.keys().stateDir(), keys, deps.clock());
        } else {
            var peerTls = serverTls("cp-peer");
            transport = deps.transport(config, peerTls);
            if (peerTls != null) {
                tlsReloads.add(peerTls::reloadTls);
            }
            store = CpStore.open(config.keys().stateDir(), keys, deps.clock(), "urn:sjws:admin:root",
                    CpLaunch.nodeId(self), CpLaunch.voters(config), transport);
        }
        store.start();
        // #259: sjws_cp_idempotency_evicted_total tracks the store's eviction counter while CP is on.
        metrics.setCpIdempotencyEvictions(store::idempotencyEvictions);
        store.warnInlineSecretsAtStartup();
        events.add("store");
        if (keys.insecurePermissions()) {
            startInsecureWarner();
        }
        var watchTls = serverTls("cp-watch");
        var watchAt = resolve(config.watchListen());
        if (watchTls == null) {
            watch = WatchServer.start(store, loopback(watchAt, "cp.watch.listen"), deps.clock()::currentTimeMillis,
                    (CpTls) null);
        } else {
            watch = WatchServer.startMtls(store, watchAt, deps.clock()::currentTimeMillis, watchTls);
            tlsReloads.add(watchTls::reloadTls);
        }
        events.add("watch");
        var adminTls = serverTls("cp-admin");
        var adminAt = resolve(config.adminListen());
        long now = deps.clock().currentTimeMillis();
        if (adminTls == null) {
            loopback(adminAt, "cp.admin.listen");
            admin = AdminServer.startPlainForTests(store, now, 60_000L);
        } else {
            admin = AdminServer.startMtls(store, adminAt, now, 60_000L, adminTls);
            tlsReloads.add(adminTls::reloadTls);
        }
        events.add("admin");
        events.add("ready");
    }

    private CpServerTls serverTls(String name) throws IOException {
        try {
            return deps.serverTls(name);
        } catch (CpKeyException e) {
            throw new IOException("control role: " + name + " TLS refused: " + e.getMessage(), e);
        }
    }

    /** Hourly WARN while a ring loaded with {@code insecureSkipPermissionChecks} is in use (§2.3). */
    private void startInsecureWarner() {
        err.println(INSECURE_WARNING);
        insecureWarner = Thread.ofVirtual().name("cp-insecure-warn").start(() -> {
            while (!closed) {
                try {
                    Thread.sleep(warnEvery);
                } catch (InterruptedException e) {
                    return;
                }
                var s = store;
                if (!closed && s != null && s.keys().insecurePermissions()) {
                    err.println(INSECURE_WARNING);
                }
            }
        });
    }

    private static InetSocketAddress loopback(InetSocketAddress address, String key) throws IOException {
        if (address.getAddress() == null || !address.getAddress().isLoopbackAddress()) {
            throw new IOException(key + ": a listener without TLS must be loopback, not " + address);
        }
        return address;
    }

    private static InetSocketAddress resolve(InetSocketAddress address) {
        return address.isUnresolved() ? new InetSocketAddress(address.getHostString(), address.getPort()) : address;
    }

    /** The store, for tests and status. */
    public CpStore store() {
        return store;
    }

    /**
     * Process metrics for this control host (#259). Includes {@code sjws_cp_idempotency_evicted_total} once the store
     * is open. Creating the host is what turns the series on; a process without CP never constructs {@link CpHost}.
     */
    public ServerMetrics metrics() {
        return metrics;
    }

    /** {@code /readyz} plus {@code leaderReady()}; a follower is never ready by design (NodeLifecycle). */
    public boolean ready() {
        var s = store;
        return !closed && s != null && s.ready(true);
    }

    /** True when the ring in use was loaded with {@code insecureSkipPermissionChecks} (also on /cp/v1/health). */
    public boolean insecurePermissions() {
        var s = store;
        return s != null && s.keys().insecurePermissions();
    }

    /** Start and stop steps in order, for tests. */
    List<String> events() {
        return List.copyOf(events);
    }

    /**
     * SIGHUP: {@code KeyRing.load} first (no side effects), then {@code reloadTls()} on each listener (each keeps
     * its old material on failure); the new ring is installed only if all of them succeeded.
     */
    @Override
    public String reload() {
        if (closed || store == null) {
            return "control role not running";
        }
        KeyRing keys;
        try {
            keys = deps.loadKeys(config.keys());
        } catch (CpKeyException | RuntimeException e) {
            events.add("reload-failed");
            return "key ring reload failed, keeping the current ring and TLS: " + e.getMessage();
        }
        for (var tls : tlsReloads) {
            try {
                tls.reloadTls();
            } catch (CpKeyException | RuntimeException e) {
                events.add("reload-failed");
                return "listener TLS reload failed, keeping the current ring: " + e.getMessage();
            }
        }
        store.useKeys(keys);
        events.add("reloaded");
        return null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        var warner = insecureWarner;
        if (warner != null) {
            warner.interrupt();
        }
        var failures = new ArrayList<String>();
        quiet(admin, "admin", failures);
        quiet(watch, "watch", failures);
        quiet(store, "store", failures);
        if (transport instanceof AutoCloseable owned) {
            quiet(owned, "transport", failures);
        }
        for (var failure : failures) {
            System.getLogger(CpHost.class.getName()).log(System.Logger.Level.WARNING, failure);
        }
    }

    private void quiet(AutoCloseable part, String name, List<String> failures) {
        if (part == null) {
            return;
        }
        try {
            part.close();
            events.add("close-" + name);
        } catch (Exception e) {
            failures.add("cp " + name + " close failed: " + e.getMessage());
        }
    }
}
