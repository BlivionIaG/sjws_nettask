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
import java.nio.file.Path;
import java.time.Duration;

import sjws_nettask.ServiceHooks;
import sjws_nettask.SjwsServer;
import sjws_nettask.Sjws_nettask;

/**
 * Loads a bootstrap file into {@link ServiceHooks}. Invoked only when {@code --bootstrap} is set,
 * so a standalone process does not load this class. No watch thread is started: the binding stays
 * pending until the control-plane workstream calls {@link NodeEnrollment#attach}.
 */
public final class ServiceLaunch {

    private ServiceLaunch() {
    }

    public static ServiceHooks prepare(Path bootstrapFile, String expectedRole) throws IOException {
        return new Hooks(Bootstrap.load(bootstrapFile, expectedRole));
    }

    private static final class Hooks implements ServiceHooks {
        private final Bootstrap bootstrap;
        private NodeEnrollment enrollment;

        private Hooks(Bootstrap bootstrap) {
            this.bootstrap = bootstrap;
        }

        @Override
        public void onListening(SjwsServer server, Path pidFile, Duration shutdownTimeout) throws IOException {
            long budget = Sjws_nettask.drainBudgetMillis(server, shutdownTimeout);
            this.enrollment = NodeEnrollment.open(this.bootstrap, pidFile, budget);
        }

        @Override
        public void onShutdown() {
            var open = this.enrollment;
            if (open != null) {
                open.close();
            }
        }

        @Override
        public String startupLine() {
            return this.bootstrap.startupLine();
        }
    }
}
