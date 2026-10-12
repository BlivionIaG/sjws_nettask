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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Format-1 JSON codec for {@link FilterDocument}. The writer emits one canonical encoding (no
 * insignificant whitespace, fixed key order, param keys sorted) so a round trip is byte-stable.
 * The reader rejects duplicate keys, trailing commas, unknown fields, non-string params, and
 * anything after the value. This is not a general JSON library.
 */
public final class FilterJson {

    private FilterJson() {
    }

    public static String write(FilterDocument document) {
        var sb = new StringBuilder(256);
        sb.append("{\"format\":").append(document.format());
        sb.append(",\"version\":").append(document.version());
        sb.append(",\"filters\":[");
        var filters = document.filters();
        for (int i = 0; i < filters.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeEntry(sb, filters.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    public static byte[] bytes(FilterDocument document) {
        return write(document).getBytes(StandardCharsets.UTF_8);
    }

    public static FilterDocument read(String json) {
        if (json == null) {
            throw new IllegalArgumentException("filter document is required");
        }
        if (json.length() > FilterDocument.MAX_BYTES) {
            throw new IllegalArgumentException("filter document exceeds " + FilterDocument.MAX_BYTES + " bytes");
        }
        var parser = new Parser(json);
        var value = parser.value();
        parser.end();
        return document(value);
    }

    public static FilterDocument read(byte[] utf8) {
        if (utf8 == null) {
            throw new IllegalArgumentException("filter document is required");
        }
        if (utf8.length > FilterDocument.MAX_BYTES) {
            throw new IllegalArgumentException("filter document exceeds " + FilterDocument.MAX_BYTES + " bytes");
        }
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return read(decoder.decode(ByteBuffer.wrap(utf8)).toString());
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("filter document is not UTF-8");
        }
    }

    private static void writeEntry(StringBuilder sb, FilterDocument.FilterEntry entry) {
        sb.append("{\"type\":");
        writeString(sb, entry.type());
        sb.append(",\"order\":").append(entry.order());
        sb.append(",\"params\":");
        writeParams(sb, entry.params());
        var scope = entry.scope();
        if (scope.route() != null || scope.labels() != null) {
            sb.append(",\"scope\":{");
            boolean wrote = false;
            if (scope.route() != null) {
                sb.append("\"route\":");
                writeString(sb, scope.route());
                wrote = true;
            }
            if (scope.labels() != null) {
                if (wrote) {
                    sb.append(',');
                }
                sb.append("\"labels\":");
                writeString(sb, scope.labels());
            }
            sb.append('}');
        }
        sb.append('}');
    }

    private static void writeParams(StringBuilder sb, Map<String, String> params) {
        sb.append('{');
        var keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            var key = keys.get(i);
            writeString(sb, key);
            sb.append(':');
            writeString(sb, params.get(key));
        }
        sb.append('}');
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u");
                        hex(sb, c);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void hex(StringBuilder sb, char c) {
        for (int shift = 12; shift >= 0; shift -= 4) {
            sb.append(Character.forDigit((c >> shift) & 0xF, 16));
        }
    }

    private static FilterDocument document(Object value) {
        var root = object(value, "document");
        requireKeys(root, List.of("format", "version", "filters"), "document");
        require(root, "format", "document");
        require(root, "version", "document");
        require(root, "filters", "document");
        int format = integer(root.get("format"), "format");
        long version = longValue(root.get("version"), "version");
        if (!(root.get("filters") instanceof List<?> list)) {
            throw new IllegalArgumentException("filters must be an array");
        }
        var entries = new ArrayList<FilterDocument.FilterEntry>(list.size());
        for (int i = 0; i < list.size(); i++) {
            entries.add(entry(list.get(i), "filters[" + i + "]"));
        }
        return new FilterDocument(format, version, entries);
    }

    private static FilterDocument.FilterEntry entry(Object value, String path) {
        var obj = object(value, path);
        requireKeys(obj, List.of("type", "order", "params", "scope"), path);
        if (!obj.containsKey("type")) {
            throw new IllegalArgumentException(path + ".type is required");
        }
        if (!(obj.get("type") instanceof String type)) {
            throw new IllegalArgumentException(path + ".type must be a string");
        }
        int order = obj.containsKey("order") ? integer(obj.get("order"), path + ".order") : 0;
        var params = params(obj.get("params"), path);
        var scope = scope(obj.get("scope"), path);
        try {
            return new FilterDocument.FilterEntry(type, order, params, scope);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage());
        }
    }

    private static Map<String, String> params(Object value, String path) {
        if (value == null) {
            return Map.of();
        }
        var obj = object(value, path + ".params");
        var params = new LinkedHashMap<String, String>(obj.size());
        for (var entry : obj.entrySet()) {
            if (!(entry.getValue() instanceof String text)) {
                throw new IllegalArgumentException(path + ".params." + entry.getKey() + " must be a string");
            }
            params.put(entry.getKey(), text);
        }
        return params;
    }

    private static FilterScope scope(Object value, String path) {
        if (value == null) {
            return FilterScope.all();
        }
        var obj = object(value, path + ".scope");
        requireKeys(obj, List.of("route", "labels"), path + ".scope");
        if (obj.isEmpty()) {
            throw new IllegalArgumentException(path + ".scope needs route, labels, or both");
        }
        String route = null;
        String labels = null;
        if (obj.containsKey("route")) {
            if (!(obj.get("route") instanceof String text)) {
                throw new IllegalArgumentException(path + ".scope.route must be a string");
            }
            route = text;
        }
        if (obj.containsKey("labels")) {
            if (!(obj.get("labels") instanceof String text)) {
                throw new IllegalArgumentException(path + ".scope.labels must be a string");
            }
            labels = text;
        }
        try {
            return FilterScope.of(route, labels);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ".scope: " + e.getMessage());
        }
    }

    private static Map<String, Object> object(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        var out = new LinkedHashMap<String, Object>(map.size());
        for (var entry : map.entrySet()) {
            out.put((String) entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static void require(Map<String, ?> obj, String key, String path) {
        if (!obj.containsKey(key)) {
            throw new IllegalArgumentException(path + "." + key + " is required");
        }
    }

    /** {@code allowed} lists every key that may appear. Presence is checked separately. */
    private static void requireKeys(Map<String, ?> obj, List<String> allowed, String path) {
        for (var key : obj.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(path + ": unknown field \"" + key + "\"");
            }
        }
    }

    private static int integer(Object value, String path) {
        long n = longValue(value, path);
        if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " is out of range");
        }
        return (int) n;
    }

    private static long longValue(Object value, String path) {
        if (!(value instanceof Long n)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        return n.longValue();
    }

    private static final class Parser {

        private final String s;
        private final int n;
        private int i;

        Parser(String s) {
            this.s = s;
            this.n = s.length();
        }

        Object value() {
            return switch (peek()) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> number();
                default -> throw error("expected a value");
            };
        }

        void end() {
            skip();
            if (this.i != this.n) {
                throw error("trailing data");
            }
        }

        private Map<String, Object> object() {
            take('{');
            var map = new LinkedHashMap<String, Object>();
            if (peek() == '}') {
                this.i++;
                return map;
            }
            while (true) {
                if (peek() != '"') {
                    throw error("expected a string key");
                }
                var key = string();
                if (map.containsKey(key)) {
                    throw error("duplicate key \"" + key + "\"");
                }
                if (take() != ':') {
                    throw error("expected ':'");
                }
                map.put(key, value());
                char c = take();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> array() {
            take('[');
            var list = new ArrayList<Object>();
            if (peek() == ']') {
                this.i++;
                return list;
            }
            while (true) {
                list.add(value());
                char c = take();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String string() {
            if (take() != '"') {
                throw error("expected a string");
            }
            var sb = new StringBuilder();
            while (this.i < this.n) {
                char c = this.s.charAt(this.i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (this.i >= this.n) {
                        throw error("bad escape");
                    }
                    char e = this.s.charAt(this.i++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(unicode());
                        default -> throw error("bad escape");
                    }
                } else if (c < 0x20) {
                    throw error("unescaped control character");
                } else {
                    sb.append(c);
                }
            }
            throw error("unterminated string");
        }

        private char unicode() {
            if (this.i + 4 > this.n) {
                throw error("bad unicode escape");
            }
            int v = 0;
            for (int k = 0; k < 4; k++) {
                int d = Character.digit(this.s.charAt(this.i++), 16);
                if (d < 0) {
                    throw error("bad unicode escape");
                }
                v = (v << 4) | d;
            }
            return (char) v;
        }

        private Long number() {
            int start = this.i;
            if (peek() == '-') {
                this.i++;
            }
            if (this.i >= this.n || !digit(this.s.charAt(this.i))) {
                throw error("bad number");
            }
            if (this.s.charAt(this.i) == '0') {
                this.i++;
                if (this.i < this.n && digit(this.s.charAt(this.i))) {
                    throw error("leading zero");
                }
            } else {
                while (this.i < this.n && digit(this.s.charAt(this.i))) {
                    this.i++;
                }
            }
            if (this.i < this.n) {
                char c = this.s.charAt(this.i);
                if (c == '.' || c == 'e' || c == 'E') {
                    throw error("integers only");
                }
            }
            var text = this.s.substring(start, this.i);
            if ("-".equals(text)) {
                throw error("bad number");
            }
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException e) {
                throw error("number out of range");
            }
        }

        private static boolean digit(char c) {
            return c >= '0' && c <= '9';
        }

        private char peek() {
            skip();
            if (this.i >= this.n) {
                throw error("unexpected end");
            }
            return this.s.charAt(this.i);
        }

        private char take() {
            char c = peek();
            this.i++;
            return c;
        }

        private char take(char expected) {
            char c = take();
            if (c != expected) {
                throw error("expected '" + expected + "'");
            }
            return c;
        }

        private void skip() {
            while (this.i < this.n) {
                char c = this.s.charAt(this.i);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                    return;
                }
                this.i++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("filter document at " + this.i + ": " + message);
        }
    }
}
