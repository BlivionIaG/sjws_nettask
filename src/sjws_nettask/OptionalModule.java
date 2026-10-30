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

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * An optional module started by the composition root before the server accepts and closed when the process
 * stops serving. Modules are loaded only through {@link OptionalModules}, by name, so a disabled module's
 * classes are never loaded (docs/design/agent-swarm.md §3.3).
 */
public interface OptionalModule extends AutoCloseable {

    /**
     * Thrown by a module's constructor when its configuration must stop the process from starting (not just skip the
     * module), for example token streaming enabled on a node configured to run with other processes.
     */
    final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message);
        }
    }

    /**
     * What the composition root knows about the deployment before modules start. {@link #multiProcess()} is why this
     * process runs alongside other processes (a {@code --bootstrap} node, or {@code cp.peers} in the config file), or
     * null for a standalone process; read by single-process-only features (token streaming, agent-swarm.md §5.2).
     */
    final class Launch {
        private static volatile String multiProcess;

        private Launch() {
        }

        public static String multiProcess() {
            return multiProcess;
        }

        static void multiProcess(String reason) {
            multiProcess = reason;
        }
    }

    /**
     * S2c PR 8: register a Prometheus-text appendix for {@code /metrics}. Called once after the server
     * starts. Off modules leave the default no-op so nothing is loaded or allocated.
     */
    default void attachPrometheus(Consumer<Supplier<String>> sink) {
    }

    /** Closing never throws; a module logs its own failures. */
    @Override
    void close();
}

