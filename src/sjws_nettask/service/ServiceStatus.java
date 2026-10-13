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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sidecar of the pid file ({@code name.pid.status}). It carries the role, the control-plane
 * connection, the applied config version, and the authoritative {@code drain-ms} budget
 * {@code --stop} waits for. A pending or disconnected connection is not a drain: this class never
 * stops the process. A file whose pid or start time does not match the pid file is ignored,
 * so a recycled pid cannot inherit another node's version.
 */
public final class ServiceStatus {

    /** No recorded budget; {@code --stop} uses the 30s default plus the 5s margin. */
    public static final long NO_BUDGET = -1L;
    /** A corrupt or hostile budget cannot make {@code --stop} wait longer than this. */
    public static final long MAX_BUDGET_MS = 600_000L;
    private static final int MAX_BYTES = 4096;

    private ServiceStatus() {
    }

    public static Path beside(Path pidFile) {
        return pidFile.resolveSibling(pidFile.getFileName().toString() + ".status");
    }

    public static void write(Path pidFile, long pid, long startedMillis, String role, String dataRole,
            String connection, long version, long drainBudgetMs) throws IOException {
        var body = new StringBuilder();
        body.append("pid=").append(pid).append('\n');
        body.append("start=").append(startedMillis).append('\n');
        body.append("role=").append(role).append('\n');
        if (dataRole != null && !dataRole.isEmpty()) {
            body.append("data-role=").append(dataRole).append('\n');
        }
        body.append("connection=").append(ConnectionState.canonical(connection)).append('\n');
        body.append("config-version=").append(version < 0 ? 0 : version).append('\n');
        if (drainBudgetMs >= 0) {
            body.append("drain-ms=").append(Math.min(drainBudgetMs, MAX_BUDGET_MS)).append('\n');
        }
        AtomicFiles.write(beside(pidFile), body.toString().getBytes(StandardCharsets.UTF_8), PosixMode.STATUS);
    }

    /** HA attached, role still standalone: record the drain budget without changing {@code --status} text. */
    public static void writeStandaloneDrain(Path pidFile, long pid, long startedMillis, long drainBudgetMs)
            throws IOException {
        write(pidFile, pid, startedMillis, NodeRole.STANDALONE.wire(), "", ConnectionState.PENDING.wire(), 0,
                drainBudgetMs);
    }

    /**
     * Extra fields for a live control or data process. Empty for standalone, a missing file, or a
     * file that names a different pid or start time.
     */
    public static String runningSuffix(Path pidFile, long pid, long startedMillis) {
        var view = read(pidFile);
        if (view == null || !view.matches(pid, startedMillis) || NodeRole.STANDALONE.wire().equals(view.role)) {
            return "";
        }
        var sb = new StringBuilder();
        sb.append("role=").append(view.role);
        if (!view.dataRole.isEmpty()) {
            sb.append(" data-role=").append(view.dataRole);
        }
        sb.append(" connection=").append(view.connection);
        sb.append(" config-version=").append(view.version);
        return sb.toString();
    }

    /** Milliseconds {@code --stop} should add, or {@link #NO_BUDGET} when this process has no HA drain. */
    public static long drainBudget(Path pidFile, long pid, long startedMillis) {
        if (pidFile == null) {
            return NO_BUDGET;
        }
        var view = read(pidFile);
        if (view == null || !view.matches(pid, startedMillis) || view.drainMs < 0) {
            return NO_BUDGET;
        }
        return Math.min(view.drainMs, MAX_BUDGET_MS);
    }

    /** Deletes the sidecar when it still names this process. A newer file is left in place. */
    public static void deleteIfMatch(Path pidFile, long pid, long startedMillis) {
        if (pidFile == null) {
            return;
        }
        var view = read(pidFile);
        if (view == null || !view.matches(pid, startedMillis)) {
            return;
        }
        try {
            Files.deleteIfExists(beside(pidFile));
        } catch (IOException e) {
            // a leftover file is ignored unless its pid and start time match a later read
        }
    }

    private static View read(Path pidFile) {
        if (pidFile == null) {
            return null;
        }
        Path path = beside(pidFile);
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) {
                return null;
            }
            return View.parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** Parsed sidecar. Unknown keys are ignored. Unsafe values are dropped, not echoed. */
    record View(long pid, long started, String role, String dataRole, String connection, long version, long drainMs) {

        boolean matches(long pid, long startedMillis) {
            return this.pid == pid && this.started == startedMillis;
        }

        static View parse(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            long pid = Long.MIN_VALUE;
            long started = Long.MIN_VALUE;
            String role = null;
            String dataRole = "";
            String connection = ConnectionState.DISCONNECTED.wire();
            long version = 0;
            long drain = NO_BUDGET;
            boolean sawPid = false;
            boolean sawStart = false;
            for (var line : text.split("\n", -1)) {
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    return null;
                }
                var key = line.substring(0, eq);
                var value = line.substring(eq + 1);
                switch (key) {
                    case "pid" -> {
                        pid = longOrSentinel(value);
                        sawPid = pid != Long.MIN_VALUE;
                    }
                    case "start" -> {
                        started = longOrSentinel(value);
                        sawStart = started != Long.MIN_VALUE;
                    }
                    case "role" -> {
                        var parsed = NodeRole.parse(value);
                        role = parsed == null ? null : parsed.wire();
                    }
                    case "data-role" -> {
                        var parsed = DataPlaneRole.parse(value);
                        dataRole = parsed == null ? "" : parsed.wire();
                    }
                    case "connection" -> connection = ConnectionState.canonical(value);
                    case "config-version" -> {
                        long v = longOrSentinel(value);
                        version = v < 0 ? 0 : v;
                    }
                    case "drain-ms" -> {
                        long v = longOrSentinel(value);
                        drain = v < 0 ? NO_BUDGET : v;
                    }
                    default -> {
                        // forward compatible; never printed
                    }
                }
            }
            if (!sawPid || !sawStart || role == null) {
                return null;
            }
            return new View(pid, started, role, dataRole, connection, version, drain);
        }

        private static long longOrSentinel(String value) {
            if (value == null || !value.matches("-?[0-9]{1,18}")) {
                return Long.MIN_VALUE;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return Long.MIN_VALUE;
            }
        }
    }
}
