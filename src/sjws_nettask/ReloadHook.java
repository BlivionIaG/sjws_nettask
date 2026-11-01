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

/**
 * Process-wide reload hooks. {@link #run()} rebuilds each registered server's immutable
 * configuration snapshot and swaps it in. The daemon child calls {@link #installSighup()} so
 * {@code SIGHUP} is reload. A foreground process does not: it must still die on {@code SIGHUP}.
 * The child is also started with {@code setsid} when that binary exists, so closing the terminal
 * does not deliver {@code SIGHUP} at all.
 * <p>
 * When this process inherited an ignored {@code SIGHUP} (no controlling terminal, GitHub Actions),
 * {@link #installSighup()} first restores the default disposition via {@code signal(SIGHUP, SIG_DFL)}
 * when the FFM linker is available, then installs the Java handler. HotSpot alone will not replace
 * {@code SIG_IGN}. If the restore is unavailable, {@link #installSighup()} returns false and leaves
 * the disposition unchanged. On a data node that has accepted a pushed config, the server's hook
 * reloads bootstrap settings only and leaves that config in place.
 */
public final class ReloadHook {

    private static final CopyOnWriteArrayList<Runnable> HOOKS = new CopyOnWriteArrayList<>();

    /** POSIX {@code SIGHUP}. Same value on Linux, macOS and the BSDs we run. */
    private static final int SIGHUP = 1;

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
            // HotSpot refuses to replace SIG_IGN. Clear it first when we can so a child that
            // inherited ignore (CI runners) still installs reload.
            restoreDefaultHangup();
            var previous = signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, handler);
            return previous != handlerClass.getField("SIG_IGN").get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    /**
     * Best-effort {@code signal(SIGHUP, SIG_DFL)}. No-ops when FFM native access is off, the
     * symbol is missing, or the call throws. Does not touch HotSpot's Java handler table.
     */
    static void restoreDefaultHangup() {
        try {
            var linker = Linker.nativeLinker();
            var symbol = linker.defaultLookup().find("signal");
            if (symbol.isEmpty()) {
                return;
            }
            var mh = linker.downcallHandle(symbol.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            mh.invoke(SIGHUP, MemorySegment.NULL);
        } catch (Throwable ignored) {
            // No native access, Windows, or an unsupported libc — leave the disposition alone.
        }
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
