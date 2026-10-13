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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Neutral topic and consumer-group schema carried in the config snapshot. The control plane
 * names this type and does not name a broker class. Placement lists are stored here after the
 * control plane computes them; an operator proposal that already contains {@code assignment}
 * is rejected by the store.
 */
public record MessagingSettings(boolean enabled, int defaultPartitions, int defaultReplication,
        String defaultDurability, List<String> defaultDurabilityOptIn, int defaultMaxAttempts,
        Duration defaultBackoffBase, Duration defaultBackoffMax, Duration defaultVisibility,
        Duration defaultRetentionAge, long defaultRetentionBytes, boolean defaultKeepResolved,
        double defaultCompactRatio, long defaultSegmentBytes, Map<String, String> brokerSelector, String spreadBy,
        Map<String, TopicSpec> topics, Map<String, List<String>> assignment) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final Pattern START = Pattern.compile("earliest|latest|offset:[0-9]+");
    private static final List<String> DURABILITY = List.of("async", "group", "replicated", "strict");
    private static final List<String> OPT_IN = List.of("async", "group", "replicated", "strict", "none", "each");

    private static final MessagingSettings DISABLED = new MessagingSettings(false, 3, 3, "replicated",
            List.of("strict"), 5, Duration.ofMillis(100), Duration.ofSeconds(30), Duration.ofSeconds(30),
            Duration.ofDays(7), 10L * 1024 * 1024 * 1024, false, 0.5, 1024L * 1024 * 1024, Map.of(), "", Map.of(),
            Map.of());

    public MessagingSettings {
        defaultDurabilityOptIn = List.copyOf(defaultDurabilityOptIn);
        brokerSelector = Map.copyOf(brokerSelector);
        topics = Map.copyOf(topics);
        var copied = new LinkedHashMap<String, List<String>>();
        for (var entry : assignment.entrySet()) {
            copied.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        assignment = Map.copyOf(copied);
        if (spreadBy == null) {
            spreadBy = "";
        }
    }

    public static MessagingSettings disabled() {
        return DISABLED;
    }

    /** A disabled section with no topics and no assignment is left out of the canonical YAML. */
    public boolean omittedFromCanonical() {
        return this.equals(DISABLED);
    }

    public List<PartitionAssignment> assignments() {
        var out = new ArrayList<PartitionAssignment>();
        for (var key : new TreeMap<>(assignment).keySet()) {
            out.add(new PartitionAssignment(key, assignment.get(key)));
        }
        return List.copyOf(out);
    }

    /** Document-local checks. The eligible-broker count needs the registration table and is not here. */
    public List<String> shapeErrors() {
        var errors = new ArrayList<String>();
        checkOptIn(errors, "messaging.defaults.durability_opt_in", defaultDurabilityOptIn);
        if (!DURABILITY.contains(defaultDurability)) {
            errors.add("messaging.defaults.durability: unsupported value");
        }
        for (var entry : topics.entrySet()) {
            var name = entry.getKey();
            var topic = entry.getValue();
            var path = "messaging.topics." + name;
            if (!NAME.matcher(name).matches() || name.endsWith(".dlq")) {
                errors.add(path + ": invalid topic name");
            }
            if (topic.partitions() < 1) {
                errors.add(path + ".partitions: must be at least 1");
            }
            if (topic.replication() < 1) {
                errors.add(path + ".replication: must be at least 1");
            }
            if (!DURABILITY.contains(topic.durability())) {
                errors.add(path + ".durability: unsupported value");
            }
            if (("async".equals(topic.durability()) || "group".equals(topic.durability()))
                    && topic.replication() != 1) {
                errors.add(path + ".durability: async and group require replication 1");
            }
            checkOptIn(errors, path + ".durability_opt_in", topic.durabilityOptIn());
            if (topic.maxAttempts() < 1) {
                errors.add(path + ".retry.max_attempts: must be at least 1");
            }
            if (topic.backoffMax().compareTo(topic.backoffBase()) < 0) {
                errors.add(path + ".retry: backoff_max is below backoff_base");
            }
            for (var group : topic.groups().entrySet()) {
                var gpath = path + ".groups." + group.getKey();
                if (!NAME.matcher(group.getKey()).matches() || group.getKey().endsWith(".dlq")) {
                    errors.add(gpath + ": invalid group name");
                }
                if (!START.matcher(group.getValue().start()).matches()) {
                    errors.add(gpath + ".start: expected earliest, latest, or offset:n");
                }
            }
        }
        for (var key : assignment.keySet()) {
            int slash = key.lastIndexOf('/');
            if (slash <= 0 || slash == key.length() - 1) {
                errors.add("messaging.assignment." + key + ": expected topic/partition");
            }
        }
        return List.copyOf(errors);
    }

    /**
     * Guards that need the previous document. A partition-count decrease is rejected at schema 1
     * even when {@code allow_destructive} is set. Topic deletion, group deletion, and a
     * replication-factor change require that flag.
     */
    public static List<String> destructiveErrors(MessagingSettings before, MessagingSettings after) {
        var errors = new ArrayList<String>();
        for (var entry : before.topics.entrySet()) {
            var name = entry.getKey();
            var previous = entry.getValue();
            var next = after.topics.get(name);
            if (next == null) {
                if (!previous.allowDestructive()) {
                    errors.add("messaging.topics." + name + ": topic deletion requires allow_destructive");
                }
                continue;
            }
            if (next.partitions() < previous.partitions()) {
                errors.add("messaging.topics." + name
                        + ": partition decrease is rejected at schema 1");
            }
            if (next.replication() != previous.replication() && !next.allowDestructive()) {
                errors.add("messaging.topics." + name + ": replication change requires allow_destructive");
            }
            for (var group : previous.groups().keySet()) {
                if (!next.groups().containsKey(group) && !next.allowDestructive()) {
                    errors.add("messaging.topics." + name + ".groups." + group
                            + ": group deletion requires allow_destructive");
                }
            }
        }
        return List.copyOf(errors);
    }

    static MessagingSettings parse(Map<String, Object> root) {
        if (!(root.get("messaging") instanceof Object raw)) {
            return disabled();
        }
        var map = YamlValues.mapping(raw, "messaging");
        YamlValues.knownKeys(map, List.of("enabled", "defaults", "placement", "topics", "assignment"), "messaging");
        boolean enabled = !map.containsKey("enabled") || bool(map.get("enabled"), "messaging.enabled");
        var defaults = defaults(map.get("defaults"));
        var placement = placement(map.get("placement"), "messaging.placement");
        var topics = topics(map.get("topics"), defaults, placement);
        var assignment = assignment(map.get("assignment"));
        return new MessagingSettings(enabled, defaults.partitions, defaults.replication, defaults.durability,
                defaults.optIn, defaults.maxAttempts, defaults.backoffBase, defaults.backoffMax, defaults.visibility,
                defaults.retentionAge, defaults.retentionBytes, defaults.keepResolved, defaults.compactRatio,
                defaults.segmentBytes, placement.selector, placement.spreadBy, topics, assignment);
    }

    void appendCanonical(StringBuilder out) {
        out.append("messaging:\n");
        out.append("  enabled: ").append(enabled).append('\n');
        out.append("  defaults:\n");
        out.append("    partitions: ").append(defaultPartitions).append('\n');
        out.append("    replication: ").append(defaultReplication).append('\n');
        out.append("    durability: ").append(defaultDurability).append('\n');
        out.append("    durability_opt_in: ");
        flow(out, defaultDurabilityOptIn);
        out.append('\n');
        out.append("    retry:\n");
        out.append("      max_attempts: ").append(defaultMaxAttempts).append('\n');
        out.append("      backoff_base: ").append(ConfigCodec.duration(defaultBackoffBase)).append('\n');
        out.append("      backoff_max: ").append(ConfigCodec.duration(defaultBackoffMax)).append('\n');
        out.append("    visibility_timeout: ").append(ConfigCodec.duration(defaultVisibility)).append('\n');
        out.append("    retention:\n");
        out.append("      max_age: ").append(ConfigCodec.duration(defaultRetentionAge)).append('\n');
        out.append("      max_bytes: ").append(defaultRetentionBytes).append('\n');
        out.append("      keep_resolved: ").append(defaultKeepResolved).append('\n');
        out.append("      compact_live_ratio: ").append(defaultCompactRatio).append('\n');
        out.append("    segment_bytes: ").append(defaultSegmentBytes).append('\n');
        out.append("  placement:\n");
        selector(out, "    ", brokerSelector, spreadBy);
        out.append("  topics:\n");
        if (topics.isEmpty()) {
            out.append("    {}\n");
        }
        for (var name : sorted(topics)) {
            var topic = topics.get(name);
            out.append("    ").append(name).append(":\n");
            out.append("      partitions: ").append(topic.partitions()).append('\n');
            out.append("      replication: ").append(topic.replication()).append('\n');
            out.append("      durability: ").append(topic.durability()).append('\n');
            out.append("      durability_opt_in: ");
            flow(out, topic.durabilityOptIn());
            out.append('\n');
            out.append("      retry:\n");
            out.append("        max_attempts: ").append(topic.maxAttempts()).append('\n');
            out.append("        backoff_base: ").append(ConfigCodec.duration(topic.backoffBase())).append('\n');
            out.append("        backoff_max: ").append(ConfigCodec.duration(topic.backoffMax())).append('\n');
            out.append("      dlq:\n");
            out.append("        enabled: ").append(topic.dlqEnabled()).append('\n');
            out.append("        suffix: ").append(ConfigCodec.quote(topic.dlqSuffix())).append('\n');
            out.append("        retention: ").append(ConfigCodec.duration(topic.dlqRetention())).append('\n');
            out.append("      retention:\n");
            out.append("        keep_resolved: ").append(topic.keepResolved()).append('\n');
            out.append("        compact_live_ratio: ").append(topic.compactLiveRatio()).append('\n');
            out.append("        max_age: ").append(ConfigCodec.duration(topic.retentionMaxAge())).append('\n');
            out.append("        max_bytes: ").append(topic.retentionMaxBytes()).append('\n');
            out.append("      segment_bytes: ").append(topic.segmentBytes()).append('\n');
            out.append("      visibility_timeout: ").append(ConfigCodec.duration(topic.visibilityTimeout())).append('\n');
            out.append("      placement:\n");
            selector(out, "        ", topic.brokerSelector(), topic.spreadBy());
            out.append("      groups:\n");
            if (topic.groups().isEmpty()) {
                out.append("        {}\n");
            }
            for (var group : sorted(topic.groups())) {
                var spec = topic.groups().get(group);
                out.append("        ").append(group).append(":\n");
                out.append("          start: ").append(ConfigCodec.quote(spec.start())).append('\n');
                out.append("          visibility_timeout: ").append(ConfigCodec.duration(spec.visibilityTimeout()))
                        .append('\n');
                out.append("          retry:\n");
                out.append("            max_attempts: ").append(spec.maxAttempts()).append('\n');
                out.append("            backoff_base: ").append(ConfigCodec.duration(spec.backoffBase())).append('\n');
                out.append("            backoff_max: ").append(ConfigCodec.duration(spec.backoffMax())).append('\n');
            }
            out.append("      allow_destructive: ").append(topic.allowDestructive()).append('\n');
        }
        out.append("  assignment:\n");
        if (assignment.isEmpty()) {
            out.append("    {}\n");
            return;
        }
        for (var key : sorted(assignment)) {
            out.append("    ").append(ConfigCodec.quote(key)).append(": ");
            flow(out, assignment.get(key));
            out.append('\n');
        }
    }

    private static void checkOptIn(List<String> errors, String path, List<String> values) {
        for (var value : values) {
            if (!OPT_IN.contains(value)) {
                errors.add(path + ": unsupported value " + value);
            }
        }
    }

    private record Defaults(int partitions, int replication, String durability, List<String> optIn, int maxAttempts,
            Duration backoffBase, Duration backoffMax, Duration visibility, Duration retentionAge, long retentionBytes,
            boolean keepResolved, double compactRatio, long segmentBytes) {
    }

    private record Placement(Map<String, String> selector, String spreadBy) {
    }

    private static Defaults defaults(Object raw) {
        int partitions = 3;
        int replication = 3;
        String durability = "replicated";
        var optIn = List.of("strict");
        int attempts = 5;
        var base = Duration.ofMillis(100);
        var max = Duration.ofSeconds(30);
        var visibility = Duration.ofSeconds(30);
        var age = Duration.ofDays(7);
        long bytes = 10L * 1024 * 1024 * 1024;
        boolean keep = false;
        double ratio = 0.5;
        long segment = 1024L * 1024 * 1024;
        if (raw == null) {
            return new Defaults(partitions, replication, durability, optIn, attempts, base, max, visibility, age, bytes,
                    keep, ratio, segment);
        }
        var map = YamlValues.mapping(raw, "messaging.defaults");
        YamlValues.knownKeys(map, List.of("partitions", "replication", "durability", "durability_opt_in", "retry",
                "visibility_timeout", "retention", "segment_bytes"), "messaging.defaults");
        if (map.containsKey("partitions")) {
            partitions = integer(map.get("partitions"), "messaging.defaults.partitions");
        }
        if (map.containsKey("replication")) {
            replication = integer(map.get("replication"), "messaging.defaults.replication");
        }
        if (map.containsKey("durability")) {
            durability = text(map.get("durability"), "messaging.defaults.durability");
        }
        if (map.containsKey("durability_opt_in")) {
            optIn = strings(map.get("durability_opt_in"), "messaging.defaults.durability_opt_in");
        }
        if (map.containsKey("retry")) {
            var retry = YamlValues.mapping(map.get("retry"), "messaging.defaults.retry");
            YamlValues.knownKeys(retry, List.of("max_attempts", "backoff_base", "backoff_max"),
                    "messaging.defaults.retry");
            if (retry.containsKey("max_attempts")) {
                attempts = integer(retry.get("max_attempts"), "messaging.defaults.retry.max_attempts");
            }
            if (retry.containsKey("backoff_base")) {
                base = duration(retry.get("backoff_base"), "messaging.defaults.retry.backoff_base");
            }
            if (retry.containsKey("backoff_max")) {
                max = duration(retry.get("backoff_max"), "messaging.defaults.retry.backoff_max");
            }
        }
        if (map.containsKey("visibility_timeout")) {
            visibility = duration(map.get("visibility_timeout"), "messaging.defaults.visibility_timeout");
        }
        if (map.containsKey("retention")) {
            var retention = YamlValues.mapping(map.get("retention"), "messaging.defaults.retention");
            YamlValues.knownKeys(retention, List.of("max_age", "max_bytes", "keep_resolved", "compact_live_ratio"),
                    "messaging.defaults.retention");
            if (retention.containsKey("max_age")) {
                age = duration(retention.get("max_age"), "messaging.defaults.retention.max_age");
            }
            if (retention.containsKey("max_bytes")) {
                bytes = size(retention.get("max_bytes"), "messaging.defaults.retention.max_bytes");
            }
            if (retention.containsKey("keep_resolved")) {
                keep = bool(retention.get("keep_resolved"), "messaging.defaults.retention.keep_resolved");
            }
            if (retention.containsKey("compact_live_ratio")) {
                ratio = ratio(retention.get("compact_live_ratio"), "messaging.defaults.retention.compact_live_ratio");
            }
        }
        if (map.containsKey("segment_bytes")) {
            segment = size(map.get("segment_bytes"), "messaging.defaults.segment_bytes");
        }
        return new Defaults(partitions, replication, durability, optIn, attempts, base, max, visibility, age, bytes,
                keep, ratio, segment);
    }

    private static Placement placement(Object raw, String path) {
        if (raw == null) {
            return new Placement(Map.of(), "");
        }
        var map = YamlValues.mapping(raw, path);
        YamlValues.knownKeys(map, List.of("broker_selector", "spread_by"), path);
        var selector = Map.<String, String>of();
        if (map.containsKey("broker_selector")) {
            var body = YamlValues.mapping(map.get("broker_selector"), path + ".broker_selector");
            var built = new LinkedHashMap<String, String>();
            for (var entry : body.entrySet()) {
                built.put(entry.getKey(), text(entry.getValue(), path + ".broker_selector." + entry.getKey()));
            }
            selector = Map.copyOf(built);
        }
        var spread = map.containsKey("spread_by") ? text(map.get("spread_by"), path + ".spread_by") : "";
        return new Placement(selector, spread);
    }

    private static Map<String, TopicSpec> topics(Object raw, Defaults defaults, Placement inherited) {
        if (raw == null) {
            return Map.of();
        }
        var map = YamlValues.mapping(raw, "messaging.topics");
        var topics = new LinkedHashMap<String, TopicSpec>();
        for (var entry : map.entrySet()) {
            var path = "messaging.topics." + entry.getKey();
            var body = YamlValues.mapping(entry.getValue(), path);
            YamlValues.knownKeys(body, List.of("partitions", "replication", "durability", "durability_opt_in", "retry",
                    "dlq", "retention", "segment_bytes", "visibility_timeout", "placement", "groups",
                    "allow_destructive"), path);
            int partitions = body.containsKey("partitions")
                    ? integer(body.get("partitions"), path + ".partitions") : defaults.partitions;
            int replication = body.containsKey("replication")
                    ? integer(body.get("replication"), path + ".replication") : defaults.replication;
            String durability = body.containsKey("durability")
                    ? text(body.get("durability"), path + ".durability") : defaults.durability;
            var optIn = body.containsKey("durability_opt_in")
                    ? strings(body.get("durability_opt_in"), path + ".durability_opt_in") : defaults.optIn;
            int attempts = defaults.maxAttempts;
            var backoffBase = defaults.backoffBase;
            var backoffMax = defaults.backoffMax;
            if (body.containsKey("retry")) {
                var retry = YamlValues.mapping(body.get("retry"), path + ".retry");
                YamlValues.knownKeys(retry, List.of("max_attempts", "backoff_base", "backoff_max"), path + ".retry");
                if (retry.containsKey("max_attempts")) {
                    attempts = integer(retry.get("max_attempts"), path + ".retry.max_attempts");
                }
                if (retry.containsKey("backoff_base")) {
                    backoffBase = duration(retry.get("backoff_base"), path + ".retry.backoff_base");
                }
                if (retry.containsKey("backoff_max")) {
                    backoffMax = duration(retry.get("backoff_max"), path + ".retry.backoff_max");
                }
            }
            boolean dlq = true;
            String suffix = ".dlq";
            var dlqAge = Duration.ofDays(30);
            if (body.containsKey("dlq")) {
                var dlqMap = YamlValues.mapping(body.get("dlq"), path + ".dlq");
                YamlValues.knownKeys(dlqMap, List.of("enabled", "suffix", "retention"), path + ".dlq");
                if (dlqMap.containsKey("enabled")) {
                    dlq = bool(dlqMap.get("enabled"), path + ".dlq.enabled");
                }
                if (dlqMap.containsKey("suffix")) {
                    suffix = text(dlqMap.get("suffix"), path + ".dlq.suffix");
                }
                if (dlqMap.containsKey("retention")) {
                    var retention = dlqMap.get("retention");
                    if (retention instanceof Map<?, ?>) {
                        var nested = YamlValues.mapping(retention, path + ".dlq.retention");
                        YamlValues.knownKeys(nested, List.of("max_age"), path + ".dlq.retention");
                        if (nested.containsKey("max_age")) {
                            dlqAge = duration(nested.get("max_age"), path + ".dlq.retention.max_age");
                        }
                    } else {
                        dlqAge = duration(retention, path + ".dlq.retention");
                    }
                }
            }
            boolean keep = defaults.keepResolved;
            double ratio = defaults.compactRatio;
            var age = defaults.retentionAge;
            long bytes = defaults.retentionBytes;
            if (body.containsKey("retention")) {
                var retention = YamlValues.mapping(body.get("retention"), path + ".retention");
                YamlValues.knownKeys(retention, List.of("max_age", "max_bytes", "keep_resolved", "compact_live_ratio"),
                        path + ".retention");
                if (retention.containsKey("keep_resolved")) {
                    keep = bool(retention.get("keep_resolved"), path + ".retention.keep_resolved");
                }
                if (retention.containsKey("compact_live_ratio")) {
                    ratio = ratio(retention.get("compact_live_ratio"), path + ".retention.compact_live_ratio");
                }
                if (retention.containsKey("max_age")) {
                    age = duration(retention.get("max_age"), path + ".retention.max_age");
                }
                if (retention.containsKey("max_bytes")) {
                    bytes = size(retention.get("max_bytes"), path + ".retention.max_bytes");
                }
            }
            long segment = body.containsKey("segment_bytes")
                    ? size(body.get("segment_bytes"), path + ".segment_bytes")
                    : defaults.segmentBytes;
            var visibility = body.containsKey("visibility_timeout")
                    ? duration(body.get("visibility_timeout"), path + ".visibility_timeout")
                    : defaults.visibility;
            var place = body.containsKey("placement") ? placement(body.get("placement"), path + ".placement") : inherited;
            var groups = groups(body.get("groups"), path, visibility, attempts, backoffBase, backoffMax);
            boolean destructive = body.containsKey("allow_destructive")
                    && bool(body.get("allow_destructive"), path + ".allow_destructive");
            topics.put(entry.getKey(), new TopicSpec(partitions, replication, durability, optIn, attempts, backoffBase,
                    backoffMax, dlq, suffix, dlqAge, keep, ratio, age, bytes, segment, visibility, place.selector,
                    place.spreadBy, groups, destructive));
        }
        return Map.copyOf(topics);
    }

    private static Map<String, GroupSpec> groups(Object raw, String topicPath, Duration visibility, int attempts,
            Duration backoffBase, Duration backoffMax) {
        if (raw == null) {
            return Map.of();
        }
        var map = YamlValues.mapping(raw, topicPath + ".groups");
        var groups = new LinkedHashMap<String, GroupSpec>();
        for (var entry : map.entrySet()) {
            var path = topicPath + ".groups." + entry.getKey();
            var body = YamlValues.mapping(entry.getValue(), path);
            YamlValues.knownKeys(body, List.of("start", "visibility_timeout", "retry"), path);
            String start = body.containsKey("start") ? text(body.get("start"), path + ".start") : "latest";
            var seen = body.containsKey("visibility_timeout")
                    ? duration(body.get("visibility_timeout"), path + ".visibility_timeout")
                    : visibility;
            int groupAttempts = attempts;
            var groupBase = backoffBase;
            var groupMax = backoffMax;
            if (body.containsKey("retry")) {
                var retry = YamlValues.mapping(body.get("retry"), path + ".retry");
                YamlValues.knownKeys(retry, List.of("max_attempts", "backoff_base", "backoff_max"), path + ".retry");
                if (retry.containsKey("max_attempts")) {
                    groupAttempts = integer(retry.get("max_attempts"), path + ".retry.max_attempts");
                }
                if (retry.containsKey("backoff_base")) {
                    groupBase = duration(retry.get("backoff_base"), path + ".retry.backoff_base");
                }
                if (retry.containsKey("backoff_max")) {
                    groupMax = duration(retry.get("backoff_max"), path + ".retry.backoff_max");
                }
            }
            groups.put(entry.getKey(), new GroupSpec(start, seen, groupAttempts, groupBase, groupMax));
        }
        return Map.copyOf(groups);
    }

    private static Map<String, List<String>> assignment(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        var map = YamlValues.mapping(raw, "messaging.assignment");
        var assignment = new LinkedHashMap<String, List<String>>();
        for (var entry : map.entrySet()) {
            assignment.put(entry.getKey(), strings(entry.getValue(), "messaging.assignment." + entry.getKey()));
        }
        return Map.copyOf(assignment);
    }

    private static void selector(StringBuilder out, String indent, Map<String, String> selector, String spreadBy) {
        out.append(indent).append("broker_selector:\n");
        if (selector.isEmpty()) {
            out.append(indent).append("  {}\n");
        } else {
            for (var key : sorted(selector)) {
                out.append(indent).append("  ").append(key).append(": ").append(ConfigCodec.quote(selector.get(key)))
                        .append('\n');
            }
        }
        if (!spreadBy.isBlank()) {
            out.append(indent).append("spread_by: ").append(ConfigCodec.quote(spreadBy)).append('\n');
        }
    }

    private static void flow(StringBuilder out, List<String> values) {
        out.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(values.get(i));
        }
        out.append(']');
    }

    private static List<String> sorted(Map<String, ?> map) {
        var keys = new ArrayList<>(map.keySet());
        keys.sort(String::compareTo);
        return keys;
    }

    private static Duration duration(Object value, String path) {
        if (value instanceof Number number) {
            return Durations.parse(Long.toString(number.longValue()), path);
        }
        return Durations.parse(text(value, path), path);
    }

    private static long size(Object value, String path) {
        if (value instanceof Number number) {
            if (number.longValue() < 0) {
                throw new ConfigException(path + ": expected a size");
            }
            return number.longValue();
        }
        return Sizes.parse(text(value, path), Long.MAX_VALUE, path);
    }

    private static int integer(Object value, String path) {
        if (!(value instanceof Number number) || number.longValue() > Integer.MAX_VALUE || number.longValue() < 0
                || number.doubleValue() != number.longValue()) {
            throw new ConfigException(path + ": expected a non-negative integer");
        }
        return number.intValue();
    }

    private static boolean bool(Object value, String path) {
        if (!(value instanceof Boolean flag)) {
            throw new ConfigException(path + ": expected true or false");
        }
        return flag;
    }

    private static String text(Object value, String path) {
        if (!(value instanceof String string) || string.isBlank()) {
            throw new ConfigException(path + ": expected a string");
        }
        return string.trim();
    }

    private static double ratio(Object value, String path) {
        if (!(value instanceof Number number)) {
            throw new ConfigException(path + ": expected a number");
        }
        double ratio = number.doubleValue();
        if (ratio < 0 || ratio > 1) {
            throw new ConfigException(path + ": expected a ratio from 0 to 1");
        }
        return ratio;
    }

    private static List<String> strings(Object value, String path) {
        var list = YamlValues.sequence(value, path);
        var out = new ArrayList<String>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(text(list.get(i), path + "[" + i + "]"));
        }
        return List.copyOf(out);
    }
}
