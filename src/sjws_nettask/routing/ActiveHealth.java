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
package sjws_nettask.routing;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Optional active health probes for a multi-endpoint upstream, off unless configured
 * (design §13). Unset means no threads, timers or allocations on the balancer.
 *
 * @param path origin-form target sent to the endpoint (not prefixed with the upstream path)
 * @param method {@code GET} or {@code HEAD}
 * @param interval cadence while the endpoint is in rotation
 * @param ejectInterval cadence after {@code eject_for} while the endpoint is ejected
 * @param timeout connect and response-head timeout for a probe
 */
public record ActiveHealth(String path, String method, Duration interval, Duration ejectInterval, Duration timeout) {

    public static final String DEFAULT_PATH = "/";
    public static final String DEFAULT_METHOD = "HEAD";
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(10);
    public static final Duration DEFAULT_EJECT_INTERVAL = Duration.ofSeconds(2);
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(1);

    /** Defaults used when the {@code active_health:} block is present but a field is omitted. */
    public static final ActiveHealth DEFAULT = new ActiveHealth(DEFAULT_PATH, DEFAULT_METHOD, DEFAULT_INTERVAL,
            DEFAULT_EJECT_INTERVAL, DEFAULT_TIMEOUT);

    public ActiveHealth {
        path = validatePath(path);
        method = validateMethod(method);
        interval = requirePositive(interval, "interval");
        ejectInterval = requirePositive(ejectInterval, "eject_interval");
        timeout = requirePositive(timeout, "timeout");
    }

    /** 2xx is a successful probe; any other status, a timeout or a connect failure is a failure. */
    public static boolean success(int status) {
        return status >= 200 && status < 300;
    }

    private static String validatePath(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty() || path.charAt(0) != '/' || path.indexOf('#') >= 0 || path.indexOf('\\') >= 0
                || path.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("active_health path must be an origin-form target such as /health");
        }
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) <= ' ' || path.charAt(i) == 127) {
                throw new IllegalArgumentException("active_health path must be an origin-form target such as /health");
            }
        }
        return path;
    }

    private static String validateMethod(String method) {
        var m = Objects.requireNonNull(method, "method").trim().toUpperCase(Locale.ROOT);
        if (!m.equals("GET") && !m.equals("HEAD")) {
            throw new IllegalArgumentException("active_health method must be GET or HEAD");
        }
        return m;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("active_health " + name + " must be > 0");
        }
        return value;
    }
}
