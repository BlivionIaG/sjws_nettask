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
package sjws_nettask.core;

import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Ordered, case-insensitive response header fields. Names are stored lower-case
 * (the HTTP/3 form); the HTTP/1 writer canonicalises the case on output.
 * <p>
 * Transport-specific fields (Connection, Keep-Alive, Transfer-Encoding, Content-Length, Date,
 * Server, Alt-Svc) are added by the writers and must not be put here.
 */
public final class ResponseHeaders {

    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9a-z-]+");

    private final List<String> names = new ArrayList<>();
    private final List<String> values = new ArrayList<>();

    public static ResponseHeaders of() {
        return new ResponseHeaders();
    }

    /** Appends a field (duplicates allowed). */
    public ResponseHeaders add(String name, String value) {
        var n = name.toLowerCase(Locale.ROOT);
        if (!TOKEN.matcher(n).matches()) {
            throw new IllegalArgumentException("invalid header name: " + name);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                throw new IllegalArgumentException("invalid character in header value for " + name);
            }
        }
        this.names.add(n);
        this.values.add(value);
        return this;
    }

    /** Replaces all fields with this name (keeping the position of the first one) or appends. */
    public ResponseHeaders set(String name, String value) {
        var n = name.toLowerCase(Locale.ROOT);
        int first = this.names.indexOf(n);
        if (first < 0) {
            return add(name, value);
        }
        add(name, value); // validates
        this.names.remove(this.names.size() - 1);
        this.values.set(first, this.values.remove(this.values.size() - 1));
        for (int i = this.names.size() - 1; i > first; i--) {
            if (this.names.get(i).equals(n)) {
                this.names.remove(i);
                this.values.remove(i);
            }
        }
        return this;
    }

    public ResponseHeaders remove(String name) {
        var n = name.toLowerCase(Locale.ROOT);
        for (int i = this.names.size() - 1; i >= 0; i--) {
            if (this.names.get(i).equals(n)) {
                this.names.remove(i);
                this.values.remove(i);
            }
        }
        return this;
    }

    /** @return the first value for the name, or null */
    public String get(String name) {
        int i = this.names.indexOf(name.toLowerCase(Locale.ROOT));
        return i < 0 ? null : this.values.get(i);
    }

    public boolean contains(String name) {
        return get(name) != null;
    }

    public int size() {
        return this.names.size();
    }

    public boolean isEmpty() {
        return this.names.isEmpty();
    }

    /** @return unmodifiable snapshot of (lower-case name, value) pairs in order */
    public List<Map.Entry<String, String>> entries() {
        var list = new ArrayList<Map.Entry<String, String>>(this.names.size());
        for (int i = 0; i < this.names.size(); i++) {
            list.add(new AbstractMap.SimpleImmutableEntry<>(this.names.get(i), this.values.get(i)));
        }
        return Collections.unmodifiableList(list);
    }

    public ResponseHeaders copy() {
        var c = new ResponseHeaders();
        c.names.addAll(this.names);
        c.values.addAll(this.values);
        return c;
    }

    @Override
    public String toString() {
        return entries().toString();
    }
}
