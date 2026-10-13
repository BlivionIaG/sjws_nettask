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

    private final CpConfig config;
    private final CpHostDeps deps;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private CpStore store;
    private AutoCloseable watch;
    private AutoCloseable admin;
    private volatile boolean closed;

    private CpHost(CpConfig config, CpHostDeps deps) {
        this.config = config;
        this.deps = deps;
    }

    /** Declared as {@link CpReloadable} so callers link no cp type. */
    public static CpReloadable start(CpConfig config, CpHostDeps deps) throws IOException {
        var host = new CpHost(config, deps);
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
            store = CpStore.open(config.keys().stateDir(), keys, deps.clock(), "urn:sjws:admin:root",
                    CpLaunch.nodeId(self), CpLaunch.voters(config), deps.transport(config));
        }
        store.start();
        events.add("store");
        Object tls;
        try {
            tls = deps.listeners().prepareTls();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("control role: listener TLS not ready: " + e.getMessage(), e);
        }
        watch = deps.listeners().openWatch(store, resolve(config.watchListen()), tls);
        events.add("watch");
        admin = deps.listeners().openAdmin(store, resolve(config.adminListen()), tls);
        events.add("admin");
        events.add("ready");
    }

    private static InetSocketAddress resolve(InetSocketAddress address) {
        return address.isUnresolved() ? new InetSocketAddress(address.getHostString(), address.getPort()) : address;
    }

    /** The store, for tests and status. */
    public CpStore store() {
        return store;
    }

    /** {@code /readyz} plus {@code leaderReady()}; a follower is never ready by design (NodeLifecycle). */
    public boolean ready() {
        var s = store;
        return !closed && s != null && s.ready(true);
    }

    /** Start and stop steps in order, for tests. */
    List<String> events() {
        return List.copyOf(events);
    }

    @Override
    public String reload() {
        if (closed || store == null) {
            return "control role not running";
        }
        Object tls;
        try {
            tls = deps.listeners().prepareTls();
        } catch (java.security.GeneralSecurityException | IOException | RuntimeException e) {
            events.add("reload-failed");
            return "listener TLS reload failed: " + e.getMessage();
        }
        KeyRing keys;
        try {
            keys = deps.loadKeys(config.keys());
        } catch (CpKeyException | RuntimeException e) {
            events.add("reload-failed");
            return "key ring reload failed: " + e.getMessage();
        }
        deps.listeners().swapTls(tls);
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
        var failures = new ArrayList<String>();
        quiet(admin, "admin", failures);
        quiet(watch, "watch", failures);
        quiet(store, "store", failures);
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
