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
package sjws_nettask.acme;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP-01 tokens. The request path calls {@link #get}, one volatile read of an immutable map.
 * Issuance updates the map off that path.
 */
public final class ChallengeBoard {

    private final AtomicReference<Map<String, String>> tokens = new AtomicReference<>(Map.of());

    public String get(String token) {
        return this.tokens.get().get(token);
    }

    public void put(String token, String authorization) {
        this.tokens.updateAndGet(current -> {
            var copy = new LinkedHashMap<>(current);
            copy.put(token, authorization);
            return Map.copyOf(copy);
        });
    }

    public void remove(String token) {
        this.tokens.updateAndGet(current -> {
            if (!current.containsKey(token)) {
                return current;
            }
            var copy = new LinkedHashMap<>(current);
            copy.remove(token);
            return Map.copyOf(copy);
        });
    }

    public void clear() {
        this.tokens.set(Map.of());
    }
}
