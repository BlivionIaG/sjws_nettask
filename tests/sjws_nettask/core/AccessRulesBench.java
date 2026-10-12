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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Microbenchmark of the per-request access decision (not a test; run by hand):
 * <pre>
 * mvn -q test-compile
 * java -cp target/classes:target/test-classes sjws_nettask.core.AccessRulesBench
 * </pre>
 * "before" is master's lexical dotfile check (iterating {@code root.relativize(path)}); "after" is
 * {@link PathResolver#access} plus {@link PathResolver#accessResolved} (the real path is compared
 * with the URL path and only checked again when they differ, e.g. through a symlink: last row). Paths are normalized beforehand, as both run after normalization.
 * Reports ns and allocated bytes per request.
 */
public final class AccessRulesBench {

    private static final String[] URLS = {"/index.html", "/assets/css/site.css", "/blog/2026/10/post-title/index.html",
        "/img/logo.png", "/docs/api/v2/reference/handlers.html", "/downloads/sjws-0.2.tar.gz", "/a/b/c/d/e/f/g.txt",
        "/favicon.ico"};

    interface Op {
        int run(Path p);
    }

    public static void main(String[] args) throws IOException {
        var root = Files.createTempDirectory("sjws-bench");
        var plain = new PathResolver(root);
        var configured = new PathResolver(root, new AccessRules(List.of("*.bak", "*~", "/private", "*.env"),
                List.of(AccessRules.Rule.parse("allow:/.well-known/acme-challenge"),
                        AccessRules.Rule.parse("hide:/drafts"), AccessRules.Rule.parse("deny:/admin/**"),
                        AccessRules.Rule.parse("hide:/**/*.map"), AccessRules.Rule.parse("deny:/internal/*/secret")),
                false, null, false));
        var paths = new Path[URLS.length];
        for (int i = 0; i < URLS.length; i++) {
            paths[i] = plain.normalize(URLS[i]);
        }
        Op before = p -> {
            for (var part : root.relativize(p)) {
                if (part.toString().startsWith(".")) {
                    return 404;
                }
            }
            return 0;
        };
        Op afterDefault = p -> plain.access(p) | plain.accessResolved(new PathResolver.Resolved(p, p));
        Op afterRules = p -> configured.access(p) | configured.accessResolved(new PathResolver.Resolved(p, p));
        Op afterSymlinked = p -> configured.access(p) | configured.accessReal(p); // real path differs: checked twice
        System.out.printf("%-44s %10s %10s%n", "variant", "ns/req", "B/req");
        for (int round = 0; round < 2; round++) { // round 0 is warm-up
            report("before: master dotfile check", before, paths, round == 1);
            report("after: default (dotfiles), URL + real path", afterDefault, paths, round == 1);
            report("after: 4 deny + 5 rules", afterRules, paths, round == 1);
            report("after: 4 deny + 5 rules, symlinked (2 checks)", afterSymlinked, paths, round == 1);
        }
        Files.delete(root);
    }

    private static void report(String name, Op op, Path[] paths, boolean print) {
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        int iterations = 4_000_000;
        int sink = 0;
        long bytes0 = mx.getThreadAllocatedBytes(tid);
        long t0 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            sink += op.run(paths[i & 7]);
        }
        long t1 = System.nanoTime();
        long bytes1 = mx.getThreadAllocatedBytes(tid);
        if (sink != 0) {
            throw new AssertionError("unexpected denial");
        }
        if (print) {
            System.out.printf("%-44s %10.1f %10.1f%n", name, (t1 - t0) / (double) iterations,
                    (bytes1 - bytes0) / (double) iterations);
        }
    }
}
