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

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import sjws_nettask.core.HandlerContributor;

/**
 * The single entry point for optional modules (docs/design/agent-swarm.md §3.3, item 3). The swarm is
 * referenced only as a class name string and loaded reflectively when enabled, so with the swarm off no
 * {@code sjws_nettask.swarm} class is loaded or verified. Nothing on a request path calls this class.
 * An enabled module that implements {@link HandlerContributor} is handed to the server before it
 * accepts, so the module can mount handlers. Service providers are discovered beside that list.
 * <p>
 * Gate, first one set wins: system property {@value #SWARM_PROPERTY}, environment variable {@value #SWARM_ENV},
 * then the YAML key {@code swarm.enabled} (S1). Off when none is set.
 */
final class OptionalModules {

    static final String SWARM_PROPERTY = "sjws.swarm.enabled";
    static final String SWARM_ENV = "SJWS_SWARM_ENABLED";
    static final String SWARM_CLASS = "sjws_nettask.swarm.SwarmModule";

    /** What starts when nothing is enabled: no allocation per call, nothing to close. */
    static final OptionalModule NONE = () -> { };

    private OptionalModules() {
    }

    static boolean swarmEnabled(Function<String, String> properties, Map<String, String> env) {
        return swarmEnabled(properties, env, null);
    }

    /** {@code yaml} is {@code swarm.enabled} from the config file, or null when the file did not set it. */
    static boolean swarmEnabled(Function<String, String> properties, Map<String, String> env, Boolean yaml) {
        String value = properties.apply(SWARM_PROPERTY);
        if (value == null) {
            value = env.get(SWARM_ENV);
        }
        if (value == null) {
            return Boolean.TRUE.equals(yaml);
        }
        return "true".equalsIgnoreCase(value.trim());
    }

    /** Starts the enabled modules; {@link #NONE} when none is. A module that fails to load is logged and skipped. */
    static OptionalModule start(Function<String, String> properties, Map<String, String> env, PrintStream err) {
        return start(properties, env, null, err);
    }

    static OptionalModule start(Function<String, String> properties, Map<String, String> env, Boolean yaml,
            PrintStream err) {
        if (!swarmEnabled(properties, env, yaml)) {
            return NONE;
        }
        return load(SWARM_CLASS, err);
    }

    static OptionalModule start(PrintStream err) {
        return start(System::getProperty, System.getenv(), null, err);
    }

    static OptionalModule start(Boolean yaml, PrintStream err) {
        return start(System::getProperty, System.getenv(), yaml, err);
    }

    /**
     * The enabled module when it mounts handlers, otherwise an empty list. Does not load service
     * providers; the server does that while it compiles the terminal.
     */
    static List<HandlerContributor> contributors(OptionalModule module) {
        if (module instanceof HandlerContributor contributor) {
            return List.of(contributor);
        }
        return List.of();
    }

    static OptionalModule load(String className, PrintStream err) {
        try {
            var type = Class.forName(className).asSubclass(OptionalModule.class);
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            err.println("WARN: optional module " + className + " not started: " + e);
            return NONE;
        }
    }
}
