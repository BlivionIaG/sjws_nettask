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

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What the control-plane console needs from a control-plane (CP) node. This is a proposal for
 * the HA workstream's operator API (see the review in the PR); {@link FakeCpClient} implements it
 * in memory for the mockup and tests, {@link FailoverCpClient} spreads it over several endpoints.
 * <p>
 * Every write takes an idempotency key so a write retried on another endpoint after a failover is
 * applied at most once. Versions are monotonic; a rollback creates a new version.
 */
public interface CpClient {

    /** Typed sections of a config bundle; entries are one-line specs in the config-file syntax. */
    enum Section { ROUTES, FILTERS, ACCESS, CERTS, TOPICS }

    /** @param lag head version minus the version this node has applied */
    record NodeView(String id, String role, Map<String, String> labels, String health, long appliedVersion,
            long lag) {
        public NodeView {
            labels = Map.copyOf(labels);
        }
    }

    record ClusterView(String leader, long term, long headVersion, List<NodeView> nodes) {
        public ClusterView {
            nodes = List.copyOf(nodes);
        }
    }

    /** Immutable bundle; {@code version} is 0 for an unsaved draft. */
    record ConfigBundle(long version, Map<Section, List<String>> sections) {
        public ConfigBundle {
            var copy = new EnumMap<Section, List<String>>(Section.class);
            for (var s : Section.values()) {
                copy.put(s, List.copyOf(sections.getOrDefault(s, List.of())));
            }
            sections = java.util.Collections.unmodifiableMap(copy);
        }

        public List<String> entries(Section section) {
            return this.sections.get(section);
        }

        public ConfigBundle with(Section section, List<String> entries) {
            var copy = new EnumMap<>(this.sections);
            copy.put(section, entries);
            return new ConfigBundle(this.version, copy);
        }

        public ConfigBundle withVersion(long g) {
            return new ConfigBundle(g, this.sections);
        }
    }

    record Validation(List<String> errors, List<String> warnings) {
        public Validation {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public boolean ok() {
            return this.errors.isEmpty();
        }
    }

    record Rollout(String id, long version, String selector, State state, List<String> targets,
            List<String> applied) {
        public enum State { RUNNING, DONE }

        public Rollout {
            targets = List.copyOf(targets);
            applied = List.copyOf(applied);
        }
    }

    record AuditEntry(long seq, Instant time, String actor, String action, String detail) {
    }

    /** Label of the endpoint currently used (for display). */
    default String endpoint() {
        return "-";
    }

    ClusterView cluster() throws CpException;

    long head() throws CpException;

    ConfigBundle config(long version) throws CpException;

    /** Dry run: no side effects. */
    Validation validate(ConfigBundle draft) throws CpException;

    /** @return the new version; CONFLICT if the head is no longer {@code baseVersion} */
    long stage(ConfigBundle draft, long baseVersion, String idempotencyKey) throws CpException;

    /** Activates {@code version} on the nodes matching {@code selector} (see {@link LabelSelector}). */
    Rollout rollout(long version, String selector, String idempotencyKey) throws CpException;

    Rollout rolloutStatus(String id) throws CpException;

    /** @return the new version holding the content of {@code toVersion}, rolled out everywhere */
    long rollback(long toVersion, String idempotencyKey) throws CpException;

    List<AuditEntry> audit(long afterSeq, int limit) throws CpException;
}
