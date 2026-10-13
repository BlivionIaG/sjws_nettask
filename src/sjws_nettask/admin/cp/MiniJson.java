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
package sjws_nettask.admin.cp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the control-plane admin API responses (objects, arrays, strings,
 * integers, booleans, null). Numbers are returned as {@code Long}; fractions are rejected.
 */
final class MiniJson {

    private final String text;
    private int at;

    private MiniJson(String text) {
        this.text = text;
    }

    /** @throws IllegalArgumentException on malformed input */
    static Object parse(String text) {
        var p = new MiniJson(text == null ? "" : text);
        var value = p.value();
        p.space();
        if (p.at != p.text.length()) {
            throw p.error("trailing data");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        if (parse(text) instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("JSON object expected");
    }

    private Object value() {
        space();
        if (this.at >= this.text.length()) {
            throw error("unexpected end");
        }
        char c = this.text.charAt(this.at);
        if (c == '{') {
            return obj();
        }
        if (c == '[') {
            return arr();
        }
        if (c == '"') {
            return str();
        }
        if (c == '-' || Character.isDigit(c)) {
            int start = this.at++;
            while (this.at < this.text.length() && Character.isDigit(this.text.charAt(this.at))) {
                this.at++;
            }
            try {
                return Long.parseLong(this.text.substring(start, this.at));
            } catch (NumberFormatException e) {
                throw error("bad number");
            }
        }
        for (var word : new String[] {"true", "false", "null"}) {
            if (this.text.startsWith(word, this.at)) {
                this.at += word.length();
                return word.equals("null") ? null : Boolean.valueOf(word.equals("true"));
            }
        }
        throw error("unexpected '" + c + "'");
    }

    private Map<String, Object> obj() {
        var out = new LinkedHashMap<String, Object>();
        this.at++;
        space();
        if (peek('}')) {
            return out;
        }
        do {
            space();
            if (!peekChar('"')) {
                throw error("key expected");
            }
            var key = str();
            space();
            expect(':');
            out.put(key, value());
            space();
        } while (peek(','));
        expect('}');
        return out;
    }

    private List<Object> arr() {
        var out = new ArrayList<Object>();
        this.at++;
        space();
        if (peek(']')) {
            return out;
        }
        do {
            out.add(value());
            space();
        } while (peek(','));
        expect(']');
        return out;
    }

    private String str() {
        var sb = new StringBuilder();
        this.at++;
        while (this.at < this.text.length()) {
            char c = this.text.charAt(this.at++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (this.at >= this.text.length()) {
                break;
            }
            char e = this.text.charAt(this.at++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (this.at + 4 > this.text.length()) {
                        throw error("bad escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(this.text.substring(this.at, this.at + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad escape");
                    }
                    this.at += 4;
                }
                default -> sb.append(e); // \" \\ \/
            }
        }
        throw error("unterminated string");
    }

    private void space() {
        while (this.at < this.text.length() && Character.isWhitespace(this.text.charAt(this.at))) {
            this.at++;
        }
    }

    private boolean peekChar(char c) {
        return this.at < this.text.length() && this.text.charAt(this.at) == c;
    }

    private boolean peek(char c) {
        if (peekChar(c)) {
            this.at++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("'" + c + "' expected");
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at " + this.at);
    }
}
