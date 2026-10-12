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

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Built-in {@link AccessPolicy}: per-path rules, dotfile handling and an opt-in owner/permission
 * policy. Immutable and thread-safe; patterns are compiled once and matched without regular
 * expressions or allocation.
 * <p>
 * Decision order for a path: every {@link Action#DENY deny-list} rule first (they always win), then
 * the other rules in order (first match wins), then dotfiles (a component starting with '.') answer
 * 404 unless {@code allowDotfiles}, else the path is allowed.
 * <p>
 * Patterns: '/'-separated globs. {@code *} matches within one component, {@code ?} one character,
 * {@code **} any number of components. A pattern starting with '/' is anchored at the document
 * root, any other pattern may match at any depth ({@code *.bak} is {@code /**}{@code /*.bak}). A pattern
 * also matches everything below what it matches: {@code /private} covers {@code /private/a/b}.
 * Like shell globs, wildcards (and that implicit "everything below") never match a component
 * starting with '.' in an allow rule, unless dotfiles are allowed: {@code allow:/**} cannot expose
 * {@code /.git}, but a literal component can ({@code allow:/.well-known/acme-challenge}). Deny and
 * hide rules match dotfiles too.
 */
public final class AccessRules implements AccessPolicy {

    /** What a matching rule answers. */
    public enum Action {
        /** serve (status 0) */
        ALLOW(0),
        /** 403 Forbidden */
        DENY(403),
        /** 404 Not Found, as if the file did not exist */
        HIDE(404);

        final int status;

        Action(int status) {
            this.status = status;
        }
    }

    /** A rule in source form, e.g. {@code deny:/private}. */
    public record Rule(Action action, String pattern) {

        public Rule {
            Objects.requireNonNull(action, "action");
            Pattern.compile(pattern); // validates
        }

        /**
         * @param spec {@code allow:PATTERN}, {@code deny:PATTERN} or {@code hide:PATTERN}
         * @throws IllegalArgumentException if malformed
         */
        public static Rule parse(String spec) {
            int colon = spec.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("access rule '" + spec + "' must be allow:, deny: or hide:PATTERN");
            }
            var action = switch (spec.substring(0, colon).trim().toLowerCase(Locale.ROOT)) {
                case "allow" -> Action.ALLOW;
                case "deny" -> Action.DENY;
                case "hide" -> Action.HIDE;
                default -> throw new IllegalArgumentException("access rule '" + spec
                        + "' must start with allow:, deny: or hide:");
            };
            return new Rule(action, spec.substring(colon + 1).trim());
        }

        @Override
        public String toString() {
            return this.action.name().toLowerCase(Locale.ROOT) + ":" + this.pattern;
        }
    }

    /** Dotfiles hidden, no rules, no file policy: what the server always did. */
    public static final AccessRules DEFAULT = new AccessRules(List.of(), List.of(), false, null, false);

    private static final char SEP = File.separatorChar;

    private final List<Rule> denyList;
    private final List<Rule> rules;
    private final Pattern[] patterns;
    private final int[] statuses;
    private final boolean[] dotOk;
    private final boolean allowDotfiles;
    private final String requireOwner;
    private final int ownerUid; // -1: by name (ownerPrincipal) or none
    private final UserPrincipal ownerPrincipal;
    private final boolean requireWorldReadable;

    /**
     * @param denyList patterns answered 403 before any rule
     * @param rules evaluated in order after the deny list, first match wins
     * @param allowDotfiles serve paths with a component starting with '.' (default: 404)
     * @param requireOwner serve only regular files owned by this user name or numeric uid (null: off)
     * @param requireWorldReadable serve only files readable by "others" (mode o+r)
     * @throws IllegalArgumentException for a malformed pattern or an unknown owner
     */
    public AccessRules(List<String> denyList, List<Rule> rules, boolean allowDotfiles, String requireOwner,
            boolean requireWorldReadable) {
        var deny = new ArrayList<Rule>();
        for (var p : denyList) {
            deny.add(new Rule(Action.DENY, p));
        }
        this.denyList = List.copyOf(deny);
        this.rules = List.copyOf(rules);
        int n = this.denyList.size() + this.rules.size();
        this.patterns = new Pattern[n];
        this.statuses = new int[n];
        this.dotOk = new boolean[n];
        int i = 0;
        for (var list : List.of(this.denyList, this.rules)) {
            for (var r : list) {
                this.patterns[i] = Pattern.compile(r.pattern());
                this.statuses[i] = r.action().status;
                this.dotOk[i] = r.action() != Action.ALLOW || allowDotfiles;
                i++;
            }
        }
        this.allowDotfiles = allowDotfiles;
        this.requireWorldReadable = requireWorldReadable;
        if (requireOwner != null && requireOwner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        this.requireOwner = requireOwner;
        if (requireOwner == null) {
            this.ownerUid = -1;
            this.ownerPrincipal = null;
        } else if (requireOwner.matches("[0-9]{1,9}")) {
            this.ownerUid = Integer.parseInt(requireOwner);
            this.ownerPrincipal = null;
        } else {
            this.ownerUid = -1;
            try {
                this.ownerPrincipal = FileSystems.getDefault().getUserPrincipalLookupService()
                        .lookupPrincipalByName(requireOwner);
            } catch (UserPrincipalNotFoundException e) {
                throw new IllegalArgumentException("unknown user '" + requireOwner + "'");
            } catch (IOException | UnsupportedOperationException e) {
                throw new IllegalArgumentException("cannot look up user '" + requireOwner + "': " + e);
            }
        }
    }

    public List<Rule> denyList() {
        return this.denyList;
    }

    public List<Rule> rules() {
        return this.rules;
    }

    public boolean allowDotfiles() {
        return this.allowDotfiles;
    }

    /** @return the required owner (name or uid), or null */
    public String requireOwner() {
        return this.requireOwner;
    }

    public boolean requireWorldReadable() {
        return this.requireWorldReadable;
    }

    /** @return true if this is the default policy (nothing configured) */
    public boolean isDefault() {
        return this.patterns.length == 0 && !this.allowDotfiles && this.requireOwner == null
                && !this.requireWorldReadable;
    }

    @Override
    public int check(CharSequence path, int from) {
        int end = path.length();
        var p = this.patterns;
        for (int i = 0; i < p.length; i++) {
            if (p[i].matches(path, from, end, this.dotOk[i])) {
                return this.statuses[i];
            }
        }
        return !this.allowDotfiles && hasHidden(path, from, end) ? 404 : 0;
    }

    /** Convenience for tests and tools: {@code path} may start with '/'. */
    public int check(String path) {
        return check(path, path.startsWith("/") ? 1 : 0);
    }

    @Override
    public boolean checksFiles() {
        return this.requireOwner != null || this.requireWorldReadable;
    }

    @Override
    public int checkFile(Path real) throws IOException {
        if (!checksFiles()) {
            return 0;
        }
        try {
            if (this.ownerUid >= 0 && ((Integer) Files.getAttribute(real, "unix:uid")).intValue() != this.ownerUid) {
                return 403;
            }
            if (this.ownerPrincipal != null || this.requireWorldReadable) {
                var a = Files.readAttributes(real, PosixFileAttributes.class);
                if (this.ownerPrincipal != null && !this.ownerPrincipal.equals(a.owner())) {
                    return 403;
                }
                if (this.requireWorldReadable && !a.permissions().contains(PosixFilePermission.OTHERS_READ)) {
                    return 403;
                }
            }
            return 0;
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return 403; // no POSIX attributes here: a policy that can't be checked refuses (fail closed)
        }
    }

    @Override
    public String toString() {
        return "AccessRules[deny=" + this.denyList + ", rules=" + this.rules + ", allowDotfiles=" + this.allowDotfiles
                + ", requireOwner=" + this.requireOwner + ", requireWorldReadable=" + this.requireWorldReadable + "]";
    }

    static boolean isSep(char c) {
        return c == '/' || c == SEP;
    }

    /** @return true if a component of {@code s[from, end)} starts with '.' */
    static boolean hasHidden(CharSequence s, int from, int end) {
        if (from < end && s.charAt(from) == '.') {
            return true;
        }
        for (int i = from; i < end - 1; i++) {
            if (isSep(s.charAt(i)) && s.charAt(i + 1) == '.') {
                return true;
            }
        }
        return false;
    }

    /** A compiled glob: one entry per component. */
    static final class Pattern {

        private static final byte EXACT = 0;
        private static final byte GLOB = 1;
        private static final byte ANY = 2;
        /** One bit per component plus the final state must fit a long. */
        static final int MAX_COMPONENTS = 62;

        private final String[] segments;
        private final byte[] kinds;
        /** Longest wildcard-free run of the pattern: any matching path contains it (null: none). */
        private final String literal;

        private Pattern(String[] segments, byte[] kinds) {
            this.segments = segments;
            this.kinds = kinds;
            String best = null;
            for (int i = 0; i < segments.length; i++) {
                if (kinds[i] != ANY) {
                    for (var run : segments[i].split("[*?]")) {
                        if (best == null || run.length() > best.length()) {
                            best = run;
                        }
                    }
                }
            }
            this.literal = best == null || best.isEmpty() ? null : best;
        }

        static Pattern compile(String pattern) {
            Objects.requireNonNull(pattern, "pattern");
            if (pattern.isEmpty() || pattern.indexOf('\0') >= 0 || pattern.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("invalid access pattern '" + pattern + "'");
            }
            boolean anchored = pattern.startsWith("/");
            var body = anchored ? pattern.substring(1) : pattern;
            if (body.endsWith("/")) {
                body = body.substring(0, body.length() - 1); // "/dir/" = "/dir" (everything below)
            }
            var parts = new ArrayList<String>();
            if (!anchored) {
                parts.add("**");
            }
            if (!body.isEmpty()) {
                for (var part : body.split("/", -1)) {
                    if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                        throw new IllegalArgumentException("invalid access pattern '" + pattern
                                + "' (empty, '.' or '..' component)");
                    }
                    if (!(part.equals("**") && !parts.isEmpty() && parts.get(parts.size() - 1).equals("**"))) {
                        parts.add(part); // "**/**" = "**"
                    }
                }
            }
            if (parts.size() > MAX_COMPONENTS) {
                throw new IllegalArgumentException("access pattern '" + pattern + "' has more than " + MAX_COMPONENTS
                        + " components");
            }
            var kinds = new byte[parts.size()];
            for (int i = 0; i < kinds.length; i++) {
                var part = parts.get(i);
                kinds[i] = part.equals("**") ? ANY : part.indexOf('*') >= 0 || part.indexOf('?') >= 0 ? GLOB : EXACT;
            }
            return new Pattern(parts.toArray(String[]::new), kinds);
        }

        /**
         * @param dotOk whether wildcards (and the implicit "everything below") may match a
         *        component starting with '.'
         */
        boolean matches(CharSequence s, int from, int end, boolean dotOk) {
            // prefilter: an intrinsic substring search rejects most paths without walking the pattern
            if (this.literal != null && s instanceof String str && str.indexOf(this.literal, from) < 0) {
                return false;
            }
            return match(0, s, from, end, dotOk);
        }

        /**
         * Simulates the pattern as an NFA over path components, one bit per pattern position (bit n:
         * pattern done, the rest is "below" a match): O(components x pattern length) whatever the
         * number of {@code **}, so a deep request path can't make matching backtrack.
         */
        private boolean match(int pi, CharSequence s, int pos, int end, boolean dotOk) {
            int n = this.kinds.length;
            long done = 1L << n;
            long state = closure(1L << pi);
            while (pos < end) {
                int e = pos;
                while (e < end && !isSep(s.charAt(e))) {
                    e++;
                }
                boolean eatable = dotOk || s.charAt(pos) != '.';
                long next = 0;
                for (long bits = state; bits != 0; bits &= bits - 1) {
                    int i = Long.numberOfTrailingZeros(bits);
                    if (i == n) {
                        next |= eatable ? done : 0;
                    } else if (this.kinds[i] == ANY) {
                        next |= eatable ? 1L << i : 0;
                    } else if (this.kinds[i] == EXACT ? e - pos == this.segments[i].length()
                            && regionEquals(s, pos, this.segments[i]) : glob(this.segments[i], s, pos, e, dotOk)) {
                        next |= 1L << (i + 1);
                    }
                }
                state = closure(next);
                if (state == 0) {
                    return false;
                }
                pos = e + 1;
            }
            return (state & done) != 0;
        }

        /** Adds the positions reachable without consuming a component ({@code **} may match none). */
        private long closure(long state) {
            for (int i = 0; i < this.kinds.length; i++) {
                if ((state & (1L << i)) != 0 && this.kinds[i] == ANY) {
                    state |= 1L << (i + 1);
                }
            }
            return state;
        }

        private static boolean regionEquals(CharSequence s, int pos, String seg) {
            for (int i = 0; i < seg.length(); i++) {
                if (s.charAt(pos + i) != seg.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        /** Glob match of one component {@code s[a, b)} ('*' and '?'), linear with one backtrack point. */
        static boolean glob(String g, CharSequence s, int a, int b, boolean dotOk) {
            if (!dotOk && s.charAt(a) == '.' && g.charAt(0) != '.') {
                return false;
            }
            int gi = 0;
            int si = a;
            int star = -1;
            int mark = 0;
            while (si < b) {
                if (gi < g.length() && (g.charAt(gi) == '?' || g.charAt(gi) == s.charAt(si))) {
                    gi++;
                    si++;
                } else if (gi < g.length() && g.charAt(gi) == '*') {
                    star = gi++;
                    mark = si;
                } else if (star >= 0) {
                    gi = star + 1;
                    si = ++mark;
                } else {
                    return false;
                }
            }
            while (gi < g.length() && g.charAt(gi) == '*') {
                gi++;
            }
            return gi == g.length();
        }
    }
}
