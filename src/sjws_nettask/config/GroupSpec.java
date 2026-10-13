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
package sjws_nettask.config;

import java.time.Duration;

/**
 * A consumer group declared in the config snapshot. The name is the map key on {@link TopicSpec}.
 * {@code start} is {@code earliest}, {@code latest}, or {@code offset:n}, and is used only when
 * the group is created.
 */
public record GroupSpec(String start, Duration visibilityTimeout, int maxAttempts, Duration backoffBase,
        Duration backoffMax) {
}
