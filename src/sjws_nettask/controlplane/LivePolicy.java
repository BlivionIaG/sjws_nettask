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
package sjws_nettask.controlplane;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import sjws_nettask.core.AccessPolicy;
import sjws_nettask.core.AccessRules;

/**
 * {@link AccessPolicy} a data-plane node installs only when the control plane manages its access
 * rules. Requests read one volatile reference; {@link #apply} compiles off the request path and
 * publishes the new rules atomically, so a request sees either the old or the new policy, never a
 * mix. Versions only move forward: an older or equal version is refused (a replayed or reordered
 * push). A rollback is the control plane re-issuing old content under a new, higher version.
 * <p>
 * The lexical {@link #check} and the later {@link #checkFile} of one request each read the
 * reference, so a request racing a swap may be judged by the old path rules and the new file
 * rules; both are complete policies, so it is never served by neither.
 * <p>
 * Nodes that are not CP-managed keep their plain {@link AccessRules}: nothing here is on their path.
 */
public final class LivePolicy implements AccessPolicy {

    /** What is active now. */
    public record Active(long version, AccessRules rules) {
    }

    private final Map<String, String> labels;
    private final AtomicReference<Active> active;

    /**
     * @param labels this node's labels, used to filter documents by {@link Scope}
     * @param initial rules served until the first push (version 0)
     */
    public LivePolicy(Map<String, String> labels, AccessRules initial) {
        this.labels = Map.copyOf(labels);
        this.active = new AtomicReference<>(new Active(0, Objects.requireNonNull(initial, "initial")));
    }

    /** For a node configured by {@code node.labels} in the config file. */
    public static LivePolicy forNode(sjws_nettask.config.NodeSettings node, AccessRules initial) {
        return new LivePolicy(node.labels(), initial);
    }

    public Active active() {
        return this.active.get();
    }

    /** Outcome of a push. */
    public enum Result { APPLIED, NOT_FOR_THIS_NODE, STALE }

    /**
     * Decodes, validates and installs a pushed document.
     *
     * @throws IllegalArgumentException if the document is invalid (the active policy is kept)
     */
    public Result apply(String encoded) {
        return apply(AccessPolicyDocument.decode(encoded));
    }

    public Result apply(AccessPolicyDocument doc) {
        if (!doc.scope().appliesToNode(this.labels)) {
            return Result.NOT_FOR_THIS_NODE;
        }
        var rules = doc.toRules(); // compile before publishing
        var next = new Active(doc.version(), rules);
        while (true) {
            var cur = this.active.get();
            if (doc.version() <= cur.version()) {
                return Result.STALE;
            }
            if (this.active.compareAndSet(cur, next)) {
                return Result.APPLIED;
            }
        }
    }

    @Override
    public int check(CharSequence path, int from) {
        return this.active.get().rules().check(path, from);
    }

    @Override
    public int checkFile(Path real) throws IOException {
        return this.active.get().rules().checkFile(real);
    }

    @Override
    public boolean checksFiles() {
        return this.active.get().rules().checksFiles();
    }
}
