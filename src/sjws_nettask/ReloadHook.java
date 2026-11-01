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
package sjws_nettask;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Proxy;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide reload hooks. {@link #run()} rebuilds each registered server's immutable
 * configuration snapshot and swaps it in. The daemon child calls {@link #installSighup()} so
 * {@code SIGHUP} is reload. A foreground process does not: it must still die on {@code SIGHUP}.
 * The child is also started with {@code setsid} when that binary exists, so closing the terminal
 * does not deliver {@code SIGHUP} at all.
 * <p>
 * When this process inherited an ignored {@code SIGHUP} (no controlling terminal, GitHub Actions),
 * HotSpot's {@code Signal.handle} returns {@code SIG_IGN} and leaves the disposition unchanged.
 * Only then does {@link #installSighup()} restore {@code SIG_DFL} via FFM and call {@code handle}
 * again. An unconditional restore would drop HotSpot's own hangup handler (shutdown hooks) on the
 * normal path. If the restore is unavailable, {@link #installSighup()} returns false and leaves
 * the disposition unchanged. On a data node that has accepted a pushed config, the server's hook
 * reloads bootstrap settings only and leaves that config in place.
 */
public final class ReloadHook {

    private static final CopyOnWriteArrayList<Runnable> HOOKS = new CopyOnWriteArrayList<>();

    /** POSIX {@code SIGHUP}. Same value on Linux, macOS and the BSDs we run. */
    private static final int SIGHUP = 1;

    /** How many times {@link #restoreDefaultHangup()} was entered. Package-visible for tests. */
    static final AtomicInteger RESTORE_ATTEMPTS = new AtomicInteger();

    /** Daemon/server WARN path uses the JDK system logger (same facility as CP / HA). Tests may replace. */
    static System.Logger LOG = System.getLogger(ReloadHook.class.getName());

    private ReloadHook() {
    }

    /**
     * @return a handle that removes this hook; closing it twice is harmless
     */
    public static AutoCloseable register(Runnable hook) {
        if (hook == null) {
            throw new IllegalArgumentException("hook");
        }
        HOOKS.add(hook);
        return () -> HOOKS.remove(hook);
    }

    /** Runs every hook. A hook that throws is logged and does not stop the others. */
    public static void run() {
        for (var hook : HOOKS.toArray(Runnable[]::new)) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                System.err.println("ERROR: reload hook failed: " + e.getMessage());
            }
        }
    }

    /**
     * Points {@code SIGHUP} at {@link #run()}. Returns false when the JDK does not expose
     * {@code sun.misc.Signal}, or when this process already ignores {@code SIGHUP} and the
     * disposition cannot be restored (no FFM native access, or a non-POSIX host). {@code main}
     * calls this only for the daemon child. The daemon child must not leave {@code SIGHUP} ignored:
     * systemd {@code ExecReload} and operators send hangup to reload.
     */
    public static boolean installSighup() {
        try {
            var signalClass = Class.forName("sun.misc.Signal");
            var handlerClass = Class.forName("sun.misc.SignalHandler");
            var signal = signalClass.getConstructor(String.class).newInstance("HUP");
            var handler = Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class<?>[] {handlerClass},
                    (proxy, method, args) -> dispatchSignal(method.getName()));
            var handle = signalClass.getMethod("handle", signalClass, handlerClass);
            var sigIgn = handlerClass.getField("SIG_IGN").get(null);
            // Install first. Only when HotSpot refused (inherited SIG_IGN) do we clear via FFM and
            // retry — an unconditional SIG_DFL would drop HotSpot's shutdown-hook hangup handler.
            var previous = handle.invoke(null, signal, handler);
            if (previous == sigIgn) {
                restoreDefaultHangup();
                previous = handle.invoke(null, signal, handler);
            }
            return previous != sigIgn;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    /**
     * Best-effort {@code signal(SIGHUP, SIG_DFL)}. Returns false when FFM native access is off,
     * the symbol is missing, or the call throws (a WARN names the cause). Does not touch HotSpot's
     * Java handler table. Only called when {@link #installSighup()} saw {@code SIG_IGN}.
     */
    static boolean restoreDefaultHangup() {
        RESTORE_ATTEMPTS.incrementAndGet();
        try {
            var linker = Linker.nativeLinker();
            var symbol = linker.defaultLookup().find("signal");
            if (symbol.isEmpty()) {
                return false;
            }
            var mh = linker.downcallHandle(symbol.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            mh.invoke(SIGHUP, MemorySegment.NULL);
            return true;
        } catch (Throwable t) {
            warnRestoreFailed(t);
            return false;
        }
    }

    /** Package-visible so tests can assert the WARN goes through {@link #LOG}. */
    static void warnRestoreFailed(Throwable t) {
        LOG.log(System.Logger.Level.WARNING, "WARN: could not restore SIGHUP default disposition: " + t);
    }

    /**
     * What the {@code SIGHUP} proxy calls. Only {@code handle} runs the hooks; the JDK also calls
     * {@code equals}, {@code hashCode} and {@code toString} on a proxy. Package-visible so a test can
     * exercise both sides when this process already ignores {@code SIGHUP} (GitHub Actions).
     */
    static Object dispatchSignal(String method) {
        if ("handle".equals(method)) {
            run();
        }
        return null;
    }
}
