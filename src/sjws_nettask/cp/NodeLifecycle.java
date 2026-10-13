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
package sjws_nettask.cp;

/**
 * Process lifecycle that the daemon starts and the control plane decides. systemd {@code active}
 * is not an input: readiness is {@code /readyz}, and a control node also needs {@code leaderReady()}.
 */
public final class NodeLifecycle {

    /** Default {@code drain-ms}. HA's 5 s delay plus 15 s drain fits inside it. */
    public static final long DRAIN_BUDGET_MS = 30_000L;

    private NodeLifecycle() {
    }

    public enum Startup {
        STANDALONE, CONTROL, DATA
    }

    /**
     * Command line, then environment, then YAML. A blank later source does not fill an earlier one.
     * None of the three means {@link Startup#STANDALONE}.
     */
    public static Startup role(String cli, String env, String yaml) {
        var chosen = first(cli, first(env, yaml));
        if (chosen == null) {
            return Startup.STANDALONE;
        }
        return switch (chosen) {
            case "control" -> Startup.CONTROL;
            case "data" -> Startup.DATA;
            case "standalone" -> Startup.STANDALONE;
            default -> throw new IllegalArgumentException("role");
        };
    }

    /** The control role binds a content port only when the operator set one. */
    public static boolean bindsContentPort(Startup role, boolean explicitContentListener) {
        if (role == Startup.CONTROL) {
            return explicitContentListener;
        }
        return true;
    }

    /** SIGHUP reads the local file. It does not open the watch. */
    public static boolean sighupPullsFromControlPlane() {
        return false;
    }

    /**
     * @param ready {@code /readyz} would be 200 for a reason other than leadership
     * @param leaderReady election {@code Noop} has been applied
     */
    public static boolean controlReady(boolean ready, boolean leaderReady) {
        return ready && leaderReady;
    }

    public record DataReadiness(boolean ready, boolean serving, boolean retrying, boolean drain) {
    }

    /**
     * Control plane down with a verified last-good: keep serving and keep retrying. No last-good:
     * not ready, not serving, still retrying. Past max-staleness: not ready, still serving, no drain.
     */
    public static DataReadiness dataPlane(boolean controlPlaneUp, boolean hasLastGood, boolean pastMaxStaleness) {
        if (!hasLastGood) {
            return new DataReadiness(false, false, true, false);
        }
        if (pastMaxStaleness) {
            return new DataReadiness(false, true, !controlPlaneUp, false);
        }
        return new DataReadiness(true, true, !controlPlaneUp, false);
    }

    /**
     * Budget recorded for {@code --stop}. A drain that cannot finish inside {@link #DRAIN_BUDGET_MS}
     * is refused so the daemon does not SIGKILL a live drain.
     */
    public static long drainBudgetMillis(boolean haAttached, long drainDelayMs, long drainTimeoutMs) {
        if (haAttached) {
            long delay = Math.max(0L, drainDelayMs);
            long timeout = Math.max(0L, drainTimeoutMs);
            if (delay + timeout + 1_000L > DRAIN_BUDGET_MS) {
                throw new IllegalArgumentException("drain exceeds drain-ms budget of " + DRAIN_BUDGET_MS + " ms");
            }
        }
        return DRAIN_BUDGET_MS;
    }

    private static String first(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }
}
