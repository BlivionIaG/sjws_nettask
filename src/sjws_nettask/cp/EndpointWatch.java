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
package sjws_nettask.cp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sjws_nettask.routing.discovery.Endpoint;
import sjws_nettask.routing.discovery.LbPolicy;
import sjws_nettask.routing.discovery.UpstreamRegistry;
import sjws_nettask.routing.discovery.UpstreamSet;
import sjws_nettask.routing.discovery.UpstreamSource;

/**
 * Control-plane to node endpoint watch. The node registers the services it routes. The watch
 * thread offers full snapshots and, after about a second of quiet, calls
 * {@link UpstreamSource#update}. The request path does not call this class. A stale version is
 * ignored. {@code SUSPECT} stays at weight 0 and {@code DEAD} is dropped by the source. An empty
 * snapshot keeps the last non-empty set, which is what gets persisted beside the config.
 */
public final class EndpointWatch {

    /** Quiet period before a burst of frames is published. */
    public static final long DEBOUNCE_NANOS = 1_000_000_000L;

    private final Registration registration;
    private final Path file;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Long> offered = new LinkedHashMap<>();

    public EndpointWatch(Registration registration, Path stateDir) {
        this.registration = registration;
        this.file = stateDir == null ? null : stateDir.resolve("config").resolve("endpoints-last-good.json");
    }

    /** Services this node asked the control plane to watch. */
    public record Registration(List<String> services) {
        public Registration {
            services = List.copyOf(services);
        }

        public boolean contains(String service) {
            return this.services.contains(service);
        }
    }

    /** One member of a full snapshot. {@code health} is {@code READY}, {@code SUSPECT}, or {@code DEAD}. */
    public record Member(String id, String address, int port, int weight, String health, String zone) {
    }

    /** A full snapshot for one service. Versions start at 1; config sets use 0. */
    public record Frame(String service, long version, String policy, List<Member> members) {
        public Frame {
            members = members == null ? List.of() : List.copyOf(members);
        }

        public UpstreamSet toSet() {
            var endpoints = new Endpoint[this.members.size()];
            for (int i = 0; i < this.members.size(); i++) {
                var member = this.members.get(i);
                var health = member.health() == null ? Endpoint.Health.READY : Endpoint.Health.valueOf(member.health());
                endpoints[i] = new Endpoint(member.id(), member.address(), member.port(), member.weight(), Map.of(),
                        health, member.zone());
            }
            return new UpstreamSet(this.service, this.version, endpoints, LbPolicy.parseOrDefault(this.policy));
        }
    }

    /**
     * Watch thread. Remembers the newest frame for a registered service. A version that is not
     * greater than one already offered for that service is ignored.
     *
     * @return true when the frame replaced the pending snapshot
     */
    public boolean offer(Frame frame, long nowNanos) {
        if (frame == null || !this.registration.contains(frame.service())) {
            return false;
        }
        var seen = this.offered.getOrDefault(frame.service(), Long.MIN_VALUE);
        if (frame.version() <= seen) {
            return false;
        }
        this.offered.put(frame.service(), frame.version());
        this.pending.put(frame.service(), new Pending(frame, nowNanos));
        return true;
    }

    /**
     * Watch thread. Publishes every pending frame that has been quiet for {@link #DEBOUNCE_NANOS}.
     *
     * @return how many sources accepted the snapshot
     */
    public int flush(long nowNanos) throws IOException {
        int applied = 0;
        var due = new ArrayList<Pending>();
        for (var item : this.pending.values()) {
            if (nowNanos - item.changedAt >= DEBOUNCE_NANOS) {
                due.add(item);
            }
        }
        for (var item : due) {
            var source = UpstreamRegistry.lookup(item.frame.service());
            if (source == null) {
                continue; // the compiler has not registered this service yet; keep the frame
            }
            this.pending.remove(item.frame.service());
            if (source.update(item.frame.toSet())) {
                applied++;
            }
            persist(source);
        }
        return applied;
    }

    /** Watch thread, after the routing compiler has registered sources. Feeds persisted sets. */
    public int restore() throws IOException {
        if (this.file == null || !Files.isRegularFile(this.file)) {
            return 0;
        }
        int applied = 0;
        for (var set : read(Files.readString(this.file))) {
            if (!this.registration.contains(set.service())) {
                continue;
            }
            this.offered.put(set.service(), Math.max(this.offered.getOrDefault(set.service(), Long.MIN_VALUE), set.version()));
            var source = UpstreamRegistry.lookup(set.service());
            if (source != null && source.update(set)) {
                applied++;
            }
        }
        return applied;
    }

    private void persist(UpstreamSource source) throws IOException {
        if (this.file == null) {
            return;
        }
        var current = source.current();
        if (current == null) {
            return;
        }
        var sets = new LinkedHashMap<String, UpstreamSet>();
        if (Files.isRegularFile(this.file)) {
            for (var existing : read(Files.readString(this.file))) {
                sets.put(existing.service(), existing);
            }
        }
        sets.put(current.service(), current);
        var json = new StringBuilder();
        json.append("{\"services\":[");
        var first = true;
        for (var set : sets.values()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            writeSet(json, set);
        }
        json.append("]}");
        var parent = this.file.getParent();
        Files.createDirectories(parent);
        var tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        var bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a posix volume
        }
        try {
            Files.move(tmp, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeSet(StringBuilder json, UpstreamSet set) {
        json.append("{\"service\":").append(quote(set.service())).append(",\"version\":").append(set.version())
                .append(",\"policy\":").append(quote(set.policy().wireName())).append(",\"endpoints\":[");
        for (int i = 0; i < set.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            var endpoint = set.endpointAt(i);
            json.append("{\"id\":").append(quote(endpoint.id())).append(",\"address\":").append(quote(endpoint.address()))
                    .append(",\"port\":").append(endpoint.port()).append(",\"weight\":").append(endpoint.weight())
                    .append(",\"health\":").append(quote(endpoint.health().name())).append(",\"zone\":")
                    .append(quote(endpoint.zone() == null ? "" : endpoint.zone())).append('}');
        }
        json.append("]}");
    }

    static List<UpstreamSet> read(String json) {
        var sets = new ArrayList<UpstreamSet>();
        int cursor = 0;
        while (true) {
            int serviceAt = json.indexOf("\"service\":\"", cursor);
            if (serviceAt < 0) {
                return sets;
            }
            var service = unquote(json, serviceAt + "\"service\":".length());
            var version = number(json, "\"version\":", serviceAt);
            var policy = unquote(json, json.indexOf("\"policy\":", serviceAt) + "\"policy\":".length());
            var endpoints = new ArrayList<Endpoint>();
            int end = json.indexOf("]}", serviceAt);
            int member = json.indexOf("\"address\":\"", serviceAt);
            while (member >= 0 && member < end) {
                var address = unquote(json, member + "\"address\":".length());
                var idAt = json.lastIndexOf("\"id\":\"", member);
                var id = idAt < serviceAt ? null : unquote(json, idAt + "\"id\":".length());
                int port = (int) number(json, "\"port\":", member);
                int weight = (int) number(json, "\"weight\":", member);
                var health = Endpoint.Health.valueOf(unquote(json, json.indexOf("\"health\":", member) + "\"health\":".length()));
                var zone = unquote(json, json.indexOf("\"zone\":", member) + "\"zone\":".length());
                endpoints.add(new Endpoint(id, address, port, weight, Map.of(), health, zone.isEmpty() ? null : zone));
                member = json.indexOf("\"address\":\"", member + 1);
            }
            sets.add(new UpstreamSet(service, version, endpoints.toArray(Endpoint[]::new), LbPolicy.parseOrDefault(policy)));
            cursor = end + 2;
        }
    }

    private static long number(String json, String key, int from) {
        int at = json.indexOf(key, from) + key.length();
        int end = at;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(at, end));
    }

    private static String unquote(String json, int quote) {
        var raw = new StringBuilder();
        for (int i = quote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                raw.append(json.charAt(++i));
                continue;
            }
            if (c == '"') {
                return raw.toString();
            }
            raw.append(c);
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record Pending(Frame frame, long changedAt) {
    }
}
