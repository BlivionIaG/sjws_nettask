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

import java.lang.reflect.Proxy;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide reload hooks. {@link #run()} rebuilds each registered server's immutable
 * configuration snapshot and swaps it in. Daemon mode ignores {@code SIGHUP} and does not expose a
 * hook of its own; its handler should call {@link #run()} instead of ignoring the signal.
 * <p>
 * {@link #installSighup()} is optional and is not called from {@code main}: a foreground process
 * must still die on {@code SIGHUP}. The registry itself is the interface. A {@code SIGHUP} the
 * process already ignores stays ignored, and {@link #installSighup()} then returns false.
 */
public final class ReloadHook {

    private static final CopyOnWriteArrayList<Runnable> HOOKS = new CopyOnWriteArrayList<>();

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
     * {@code sun.misc.Signal}, or when this process already ignores {@code SIGHUP} (inherited from
     * the parent, or set earlier in this VM). In that case the call changes nothing: the VM will
     * not replace an ignored {@code SIGHUP}. Not used by {@code main}.
     */
    public static boolean installSighup() {
        try {
            var signalClass = Class.forName("sun.misc.Signal");
            var handlerClass = Class.forName("sun.misc.SignalHandler");
            var signal = signalClass.getConstructor(String.class).newInstance("HUP");
            var handler = Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class<?>[] {handlerClass},
                    (proxy, method, args) -> {
                        if ("handle".equals(method.getName())) {
                            run();
                        }
                        return null;
                    });
            var previous = signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, handler);
            // Ignored at startup (no controlling terminal, GitHub Actions) or via SIG_IGN. handle()
            // returns SIG_IGN and leaves the disposition unchanged.
            return previous != handlerClass.getField("SIG_IGN").get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }
}
