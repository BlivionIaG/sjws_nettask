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
package sjws_nettask.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON subset for a JWT header and payload. Objects, arrays, strings, integers, booleans and
 * null. No floats. Duplicate keys are rejected. Not used on a path that has no JWT filter.
 */
final class MiniJson {

    private MiniJson() {
    }

    static Map<String, Object> object(String json) {
        if (json == null) {
            throw new IllegalArgumentException("json is required");
        }
        var parser = new Parser(json);
        var value = parser.value();
        parser.end();
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("json object required");
        }
        var out = new LinkedHashMap<String, Object>(map.size());
        for (var entry : map.entrySet()) {
            out.put((String) entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static final class Parser {

        private final String s;
        private final int n;
        private int i;
        private int depth;

        Parser(String s) {
            this.s = s;
            this.n = s.length();
        }

        Object value() {
            if (++this.depth > 8) {
                throw error("json too deep");
            }
            try {
                return switch (peek()) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> number();
                    default -> throw error("expected a value");
                };
            } finally {
                this.depth--;
            }
        }

        void end() {
            skip();
            if (this.i != this.n) {
                throw error("trailing data");
            }
        }

        private Map<String, Object> object() {
            this.i++;
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
                    throw error("duplicate key");
                }
                if (peek() != ':') {
                    throw error("expected ':'");
                }
                this.i++;
                map.put(key, value());
                char c = peek();
                this.i++;
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> array() {
            this.i++;
            var list = new ArrayList<Object>();
            if (peek() == ']') {
                this.i++;
                return list;
            }
            while (true) {
                list.add(value());
                char c = peek();
                this.i++;
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private Object literal(String text, Object value) {
            if (!this.s.startsWith(text, this.i)) {
                throw error("expected " + text);
            }
            this.i += text.length();
            return value;
        }

        private String string() {
            this.i++;
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
                    throw error("unescaped control");
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
            if (this.s.charAt(this.i) == '-') {
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
            try {
                return Long.valueOf(this.s.substring(start, this.i));
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
            return new IllegalArgumentException(message);
        }
    }
}
