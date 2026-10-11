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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Server settings. {@link #parse} is pure (no I/O) so it can be unit-tested.
 * Precedence, as before: defaults &lt; command line &lt; environment.
 *
 * <pre>
 * [port] [dir] [--max-connections=N] [--idle-timeout=S] [--header-timeout=S]
 * WEB_SERVER_PORT  WEB_SERVER_DIR  WEB_SERVER_MAX_CONNECTIONS  WEB_SERVER_IDLE_TIMEOUT  WEB_SERVER_HEADER_TIMEOUT
 * </pre>
 *
 * @param port TCP port (0 = ephemeral)
 * @param root document root
 * @param maxConnections connections served concurrently; extra ones wait in the backlog
 * @param idleTimeout maximum wait for a single read (keep-alive idle time)
 * @param headerTimeout time allowed to receive a complete request head (slowloris)
 */
public record ServerConfig(int port, Path root, int maxConnections, Duration idleTimeout, Duration headerTimeout) {

    public static final int DEFAULT_PORT = CONSTANTS.DEFAULT_PORT;
    public static final int DEFAULT_MAX_CONNECTIONS = 10_000;
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_HEADER_TIMEOUT = Duration.ofSeconds(10);

    public ServerConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (root == null) {
            throw new IllegalArgumentException("root is required");
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be positive");
        }
        if (idleTimeout.toMillis() < 1 || headerTimeout.toMillis() < 1
                || idleTimeout.toMillis() > Integer.MAX_VALUE || headerTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("timeouts must be between 1 ms and ~24 days");
        }
    }

    public static ServerConfig defaults(Path root) {
        return new ServerConfig(DEFAULT_PORT, root, DEFAULT_MAX_CONNECTIONS, DEFAULT_IDLE_TIMEOUT,
                DEFAULT_HEADER_TIMEOUT);
    }

    public static ServerConfig defaults(String root) {
        return defaults(Path.of(root));
    }

    public ServerConfig withPort(int newPort) {
        return new ServerConfig(newPort, root, maxConnections, idleTimeout, headerTimeout);
    }

    public ServerConfig withMaxConnections(int max) {
        return new ServerConfig(port, root, max, idleTimeout, headerTimeout);
    }

    public ServerConfig withTimeouts(Duration idle, Duration header) {
        return new ServerConfig(port, root, maxConnections, idle, header);
    }

    /** Result of {@link #parse}: the config plus human-readable warnings for ignored values. */
    public record Parsed(ServerConfig config, List<String> warnings) {
    }

    public static Parsed parse(String[] args, Map<String, String> env) {
        var warnings = new ArrayList<String>();
        int port = DEFAULT_PORT;
        String dir = CONSTANTS.DEFAULT_HTML_PATH;
        int max = DEFAULT_MAX_CONNECTIONS;
        long idle = DEFAULT_IDLE_TIMEOUT.toSeconds();
        long header = DEFAULT_HEADER_TIMEOUT.toSeconds();

        // command line: positional [port] [dir], --flags anywhere
        int positional = 0;
        for (var arg : args) {
            if (arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                var name = eq < 0 ? arg : arg.substring(0, eq);
                var value = eq < 0 ? "" : arg.substring(eq + 1);
                switch (name) {
                    case "--max-connections" -> max = number(value, max, 1, 10_000_000, arg, warnings);
                    case "--idle-timeout" -> idle = number(value, idle, 1, 86_400, arg, warnings);
                    case "--header-timeout" -> header = number(value, header, 1, 86_400, arg, warnings);
                    default -> warnings.add("Unknown option '" + arg + "'");
                }
            } else if (positional == 0) {
                positional++;
                port = (int) number(arg, port, 1, 65535, "port '" + arg + "'", warnings);
            } else if (positional == 1) {
                positional++;
                dir = arg;
            } else {
                warnings.add("Ignoring extra argument '" + arg + "'");
            }
        }

        // environment overrides
        if (env.containsKey("WEB_SERVER_PORT")) {
            port = (int) number(env.get("WEB_SERVER_PORT"), port, 1, 65535, "WEB_SERVER_PORT", warnings);
        }
        if (env.containsKey("WEB_SERVER_DIR")) {
            dir = env.get("WEB_SERVER_DIR");
        }
        if (env.containsKey("WEB_SERVER_MAX_CONNECTIONS")) {
            max = number(env.get("WEB_SERVER_MAX_CONNECTIONS"), max, 1, 10_000_000, "WEB_SERVER_MAX_CONNECTIONS", warnings);
        }
        if (env.containsKey("WEB_SERVER_IDLE_TIMEOUT")) {
            idle = number(env.get("WEB_SERVER_IDLE_TIMEOUT"), idle, 1, 86_400, "WEB_SERVER_IDLE_TIMEOUT", warnings);
        }
        if (env.containsKey("WEB_SERVER_HEADER_TIMEOUT")) {
            header = number(env.get("WEB_SERVER_HEADER_TIMEOUT"), header, 1, 86_400, "WEB_SERVER_HEADER_TIMEOUT", warnings);
        }

        var config = new ServerConfig(port, Path.of(dir), max, Duration.ofSeconds(idle), Duration.ofSeconds(header));
        return new Parsed(config, List.copyOf(warnings));
    }

    private static int number(String value, int fallback, int min, int maxValue, String what, List<String> warnings) {
        return (int) number(value, (long) fallback, min, maxValue, what, warnings);
    }

    private static long number(String value, long fallback, long min, long maxValue, String what,
            List<String> warnings) {
        if (value != null && value.matches("[0-9]{1,9}")) {
            long n = Long.parseLong(value);
            if (n >= min && n <= maxValue) {
                return n;
            }
        }
        warnings.add("Invalid " + what + " (value '" + value + "'), using " + fallback);
        return fallback;
    }
}
