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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** SnakeYAML with {@link SafeConstructor} only: no arbitrary types, no duplicate keys. */
final class YamlValues {

    private YamlValues() {
    }

    static Map<String, Object> parse(String text, String name) {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setCodePointLimit(1_000_000);
        options.setNestingDepthLimit(50);
        var yaml = new Yaml(new SafeConstructor(options));
        Object loaded;
        try {
            loaded = yaml.load(text);
        } catch (DuplicateKeyException e) {
            throw new ConfigException(name + ": " + leaf(e));
        } catch (YAMLException e) {
            throw new ConfigException(name + ": " + leaf(e));
        }
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new ConfigException(name + ": the document must be a mapping");
        }
        return copyMap(map, name);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapping(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ConfigException(path + ": expected a mapping");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> sequence(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw new ConfigException(path + ": expected a list");
        }
        return (List<Object>) list;
    }

    static void knownKeys(Map<String, Object> map, List<String> known, String path) {
        var unknown = new ArrayList<String>();
        for (var key : map.keySet()) {
            if (!known.contains(key)) {
                unknown.add(path + "." + key);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ConfigException("Unknown key " + String.join(", ", unknown));
        }
    }

    private static Map<String, Object> copyMap(Map<?, ?> map, String path) {
        var out = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ConfigException(path + ": keys must be strings");
            }
            out.put(key, copyValue(entry.getValue(), path + "." + key));
        }
        return out;
    }

    private static Object copyValue(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Double || value instanceof Float) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return copyMap(map, path);
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<Object>(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add(copyValue(list.get(i), path + "[" + i + "]"));
            }
            return List.copyOf(out);
        }
        throw new ConfigException(path + ": unsupported value (" + value.getClass().getSimpleName() + ")");
    }

    private static String leaf(Throwable error) {
        var message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
