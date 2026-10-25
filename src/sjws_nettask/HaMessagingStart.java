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
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.HaMessagingSettings;

/**
 * Composition-root seam for HA messaging (gate G1). When {@code ha.messaging} does not resolve to replication this
 * returns the modules untouched and names no class outside {@code sjws_nettask.config}; otherwise it loads
 * {@value #CLASS} by name, which opens the Raft side and installs it into the messaging module before the server
 * mounts it.
 */
final class HaMessagingStart {

    static final String CLASS = "sjws_nettask.msgrepl.HaMessaging";

    private HaMessagingStart() {
    }

    /**
     * @return {@code modules} when replication stays off; otherwise the modules plus the HA node, closed after them
     * @throws OptionalModule.Refused when the node's role and the enabled modules disagree (exit 64)
     * @throws IOException when the Raft transport or state cannot be opened
     */
    static OptionalModule install(ConfigLoader.Loaded loaded, OptionalModule modules, PrintStream err)
            throws IOException {
        HaMessagingSettings settings = loaded.haMessaging();
        if (!settings.replicate()) {
            return modules;
        }
        AutoCloseable handle;
        try {
            var start = Class.forName(CLASS).getMethod("start", HaMessagingSettings.class, Path.class, byte[].class,
                    byte[].class, Object.class, PrintStream.class);
            handle = (AutoCloseable) start.invoke(null, settings, settings.stateDirOr(loaded.document().stateDir()),
                    loaded.haClusterKey(), loaded.haClusterKeyPrevious(), messaging(modules), err);
        } catch (InvocationTargetException e) {
            switch (e.getCause()) {
                case IOException io -> throw io;
                case IllegalArgumentException refused -> throw new OptionalModule.Refused(refused.getMessage());
                case RuntimeException other -> throw other;
                default -> throw new IllegalStateException(e.getCause());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("HA messaging bootstrap missing: " + e, e);
        }
        OptionalModule ha = () -> {
            try {
                handle.close();
            } catch (Exception e) {
                err.println("WARN: ha messaging: close: " + e);
            }
        };
        return new OptionalModules.Both(ha, modules);
    }

    /** The messaging module among the started ones, or null. Matched by class name: nothing here loads it. */
    static OptionalModule messaging(OptionalModule module) {
        if (module instanceof OptionalModules.Both both) {
            var first = messaging(both.first());
            return first != null ? first : messaging(both.second());
        }
        return module.getClass().getName().equals(OptionalModules.MESSAGING_CLASS) ? module : null;
    }
}
