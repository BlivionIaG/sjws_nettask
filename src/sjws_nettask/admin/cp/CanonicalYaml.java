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
import java.util.EnumMap;
import java.util.List;
import sjws_nettask.admin.cp.CpClient.ConfigBundle;
import sjws_nettask.admin.cp.CpClient.Section;

/**
 * Maps the control plane's canonical YAML ({@code ConfigCodec.text}) to the console's sections and
 * back. Only {@code access.rules} is editable: the admin API takes a whole document, and the other
 * sections have no structured edit endpoint yet, so they are shown read-only (top-level block
 * lines, or middleware names) and any change to them is refused before a request is sent.
 */
final class CanonicalYaml {

    static final Section EDITABLE = Section.ACCESS;

    private CanonicalYaml() {
    }

    static ConfigBundle sections(long version, String yaml) {
        var map = new EnumMap<Section, List<String>>(Section.class);
        map.put(Section.ROUTES, block(yaml, "routing"));
        var names = new ArrayList<String>();
        for (var line : blockLines(yaml, "middleware")) {
            if (line.startsWith("  - name: ")) {
                names.add(unquote(line.substring("  - name: ".length())));
            }
        }
        map.put(Section.FILTERS, names);
        map.put(Section.ACCESS, rules(yaml));
        var certs = new ArrayList<>(block(yaml, "tls"));
        certs.addAll(block(yaml, "acme"));
        map.put(Section.CERTS, certs);
        map.put(Section.TOPICS, block(yaml, "messaging"));
        return new ConfigBundle(version, map);
    }

    /** Lines of a top-level block, without the key line, trimmed. */
    static List<String> block(String yaml, String key) {
        return blockLines(yaml, key).stream().map(String::trim).filter(l -> !l.isEmpty()).toList();
    }

    private static List<String> blockLines(String yaml, String key) {
        var out = new ArrayList<String>();
        boolean in = false;
        for (var line : yaml.split("\n", -1)) {
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                in = line.equals(key + ":") || line.startsWith(key + ": ");
                continue;
            }
            if (in) {
                out.add(line);
            }
        }
        return out;
    }

    static List<String> rules(String yaml) {
        var out = new ArrayList<String>();
        boolean inRules = false;
        for (var line : blockLines(yaml, "access")) {
            if (line.equals("  rules:")) {
                inRules = true;
            } else if (inRules && line.startsWith("    - ")) {
                out.add(unquote(line.substring(6)));
            } else {
                inRules = false;
            }
        }
        return out;
    }

    /**
     * The document with {@code access.rules} replaced. The control plane re-canonicalises it on
     * propose, so this only has to be valid YAML in the codec's layout.
     */
    static String withRules(String yaml, List<String> rules) {
        var out = new ArrayList<String>();
        boolean sawAccess = false;
        boolean inAccess = false;
        boolean skipping = false;
        for (var line : yaml.split("\n", -1)) {
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                inAccess = line.equals("access:");
                skipping = false;
                out.add(line);
                if (inAccess) {
                    sawAccess = true;
                    appendRules(out, rules);
                }
                continue;
            }
            if (inAccess && line.equals("  rules:")) {
                skipping = true;
                continue;
            }
            if (skipping && line.startsWith("    - ")) {
                continue;
            }
            skipping = false;
            out.add(line);
        }
        if (!sawAccess && !rules.isEmpty()) {
            if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
                out.remove(out.size() - 1);
            }
            out.add("access:");
            appendRules(out, rules);
            out.add("");
        }
        return String.join("\n", out);
    }

    private static void appendRules(List<String> out, List<String> rules) {
        if (!rules.isEmpty()) {
            out.add("  rules:");
            rules.forEach(r -> out.add("    - " + quote(r)));
        }
    }

    static String quote(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    static String unquote(String v) {
        var t = v.trim();
        if (t.length() < 2 || t.charAt(0) != '"' || t.charAt(t.length() - 1) != '"') {
            return t;
        }
        var sb = new StringBuilder();
        for (int i = 1; i < t.length() - 1; i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() - 1) {
                char e = t.charAt(++i);
                sb.append(e == 'n' ? '\n' : e == 'r' ? '\r' : e);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
