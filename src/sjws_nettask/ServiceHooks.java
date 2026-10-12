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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Lifecycle callbacks for a control or data process. Standalone leaves this unset, so the
 * request path never calls it. The implementation lives in {@code sjws_nettask.service} and is
 * loaded only when {@code --bootstrap} is set.
 */
public interface ServiceHooks {

    /** Pid file is held and the listener is open. May publish the status file. */
    void onListening(SjwsServer server, Path pidFile, Duration shutdownTimeout) throws IOException;

    /**
     * After drain, while the pid file is still held. Must not throw; the pid file is released
     * afterwards either way.
     */
    void onShutdown();

    /** One startup log line that contains no secrets, or null. */
    String startupLine();
}
