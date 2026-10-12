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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Access rules: pattern matching, decision order, dotfiles, owner/permission policy, and the handler wiring. */
class AccessRulesTest {

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @TempDir
    Path tmp;
    Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tmp.resolve("www"));
        Files.writeString(root.resolve("hello.txt"), "hello");
        Files.writeString(root.resolve("old.bak"), "old");
        Files.createDirectories(root.resolve("private/deep"));
        Files.writeString(root.resolve("private/deep/x.txt"), "x");
        Files.createDirectories(root.resolve("pub"));
        Files.writeString(root.resolve("pub/a.txt"), "a");
        Files.writeString(root.resolve("pub/.env"), "SECRET=1");
        Files.createDirectories(root.resolve(".well-known/acme-challenge"));
        Files.writeString(root.resolve(".well-known/acme-challenge/tok"), "token");
        Files.writeString(root.resolve(".well-known/other"), "other");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "[core]");
    }

    private static AccessRules rules(String... specs) {
        return new AccessRules(List.of(), java.util.Arrays.stream(specs).map(AccessRules.Rule::parse).toList(), false,
                null, false);
    }

    @ParameterizedTest(name = "{0} vs {1} -> {2}")
    @CsvSource({
        // pattern,          path,                 matches (deny rule)
        "/private,           /private,             true",
        "/private,           /private/deep/x.txt,  true",
        "/private,           /privateer,           false",
        "/private,           /pub/private,         false",
        "/private/,          /private/a,           true",
        "private,            /pub/private/a,       true",
        "*.bak,              /old.bak,             true",
        "*.bak,              /a/b/c/old.bak,       true",
        "*.bak,              /old.bak.txt,         false",
        "/*.bak,             /a/old.bak,           false",
        "/a/*/c,             /a/b/c,               true",
        "/a/*/c,             /a/b/x/c,             false",
        "/a/**/c,            /a/c,                 true",
        "/a/**/c,            /a/b/x/c/d,           true",
        "/a/**/**/c,         /a/b/c,               true",
        "/a/**,              /a,                   true",
        "/a/**,              /ab,                  false",
        "/**,                /,                    true",
        "/,                  /anything/at/all,     true",
        "/f?o,               /foo,                 true",
        "/f?o,               /fo,                  false",
        "/*a*b*,             /xaybz,               true",
        "/*a*b*,             /xbya,                false",
        "/a*,                /a,                   true",
        "/**/x,              /x,                   true",
        "/a/b,               /a,                   false",
        "*.bak,              /.hidden.bak,         true",
    })
    void denyPatterns(String pattern, String path, boolean matches) {
        var r = new AccessRules(List.of(pattern), List.of(), true, null, false);
        assertEquals(matches ? 403 : 0, r.check(path));
    }

    @Test
    void allowWildcardsSkipDotfilesButLiteralsDont() {
        var r = rules("allow:/**", "deny:/");
        assertEquals(0, r.check("/pub/a.txt"));
        assertEquals(403, r.check("/pub/.env"), "** in allow must not match .env; falls to deny:/");
        assertEquals(403, r.check("/.git/config"));
        var acme = rules("allow:/.well-known/acme-challenge");
        assertEquals(0, acme.check("/.well-known/acme-challenge/tok"));
        assertEquals(404, acme.check("/.well-known/acme-challenge/.tok"), "implicit 'below' skips dotfiles");
        assertEquals(404, acme.check("/.well-known/other"));
        assertEquals(404, rules("allow:/pub/*").check("/pub/.env"));
        assertEquals(404, rules("allow:/pub/?env").check("/pub/.env"));
        assertEquals(0, rules("allow:/pub/.e*").check("/pub/.env"), "a glob starting with '.' may match");
        var dots = new AccessRules(List.of(), List.of(AccessRules.Rule.parse("allow:/**")), true, null, false);
        assertEquals(0, dots.check("/pub/.env"));
    }

    @Test
    void decisionOrder() {
        var r = new AccessRules(List.of("*.bak"),
                List.of(AccessRules.Rule.parse("allow:/x.bak"), AccessRules.Rule.parse("hide:/drafts"),
                        AccessRules.Rule.parse("deny:/drafts")), false, null, false);
        assertEquals(403, r.check("/x.bak"), "deny list beats an earlier-looking allow");
        assertEquals(404, r.check("/drafts/a"), "first matching rule wins");
        assertEquals(0, r.check("/hello"));
        assertEquals(404, r.check("/.git"), "dotfiles hidden by default");
        assertEquals(0, r.check(""), "root");
        assertEquals(0, r.check("a.b/c.d"), "dot inside a name is not hidden");
        assertEquals(404, r.check("a/.b"));
        assertEquals(0, AccessRules.DEFAULT.check("/a/b.c/d"));
        assertTrue(AccessRules.DEFAULT.isDefault());
        assertFalse(r.isDefault());
        assertFalse(new AccessRules(List.of(), List.of(), true, null, false).isDefault());
        assertFalse(new AccessRules(List.of(), List.of(), false, "0", false).isDefault());
        assertFalse(new AccessRules(List.of(), List.of(), false, null, true).isDefault());
        assertEquals(1, r.denyList().size());
        assertEquals(3, r.rules().size());
        assertEquals("hide:/drafts", r.rules().get(1).toString());
        assertTrue(r.toString().contains("deny:*.bak"));
    }

    @Test
    void manyGlobstarsOnADeepPathStayLinear() {
        var r = new AccessRules(List.of("/**/a/**/b/**/c/**/d/**/e/**/zzz"), List.of(), false, null, false);
        var deep = "/" + "a/b/c/d/e/".repeat(800) + "x";
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            assertEquals(0, r.check(deep));
        }
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "matching must not backtrack");
        assertEquals(403, r.check(deep.substring(0, deep.length() - 1) + "zzz"));
        assertThrows(IllegalArgumentException.class, () -> new AccessRules(List.of("/a".repeat(63)), List.of(), false,
                null, false));
        new AccessRules(List.of("/a".repeat(62)), List.of(), false, null, false);
    }

    @Test
    void malformedRulesAreRejected() {
        for (var bad : new String[] {"", "/a//b", "/a/../b", "/./a", "a\\b", "/a\0"}) {
            assertThrows(IllegalArgumentException.class, () -> new AccessRules(List.of(bad), List.of(), false, null,
                    false), bad);
        }
        for (var bad : new String[] {"/x", "permit:/x", "allow:"}) {
            assertThrows(IllegalArgumentException.class, () -> AccessRules.Rule.parse(bad), bad);
        }
        assertEquals(AccessRules.Action.HIDE, AccessRules.Rule.parse(" HIDE : /x ").action());
        assertThrows(IllegalArgumentException.class, () -> new AccessRules(List.of(), List.of(), false, " ", false));
        assertThrows(IllegalArgumentException.class,
                () -> new AccessRules(List.of(), List.of(), false, "no-such-user-sjws-x", false));
    }

    private Response get(StaticFileHandler h, String target) {
        return h.handle(new Request("GET", target));
    }

    private StaticFileHandler handler(AccessRules rules, boolean cache) {
        return new StaticFileHandler(root, false, cache ? new FileCache(1 << 20) : FileCache.disabled(), rules);
    }

    @Test
    void handlerAppliesRulesWithAndWithoutCache() throws IOException {
        var rules = new AccessRules(List.of("*.bak"), List.of(AccessRules.Rule.parse("hide:/private"),
                AccessRules.Rule.parse("allow:/.well-known/acme-challenge")), false, null, false);
        for (boolean cache : new boolean[] {false, true}) {
            var h = handler(rules, cache);
            for (int i = 0; i < 2; i++) { // second round: cache hits
                assertEquals(200, get(h, "/hello.txt").status());
                assertEquals(403, get(h, "/old.bak").status());
                assertEquals(403, get(h, "/pub/../old.bak").status(), "normalized before matching");
                assertEquals(403, get(h, "/%6fld.bak").status(), "decoded before matching");
                assertEquals(404, get(h, "/private/deep/x.txt").status());
                assertEquals(404, get(h, "/private/").status());
                assertEquals(404, get(h, "/pub/.env").status());
                assertEquals(200, get(h, "/.well-known/acme-challenge/tok").status());
                assertEquals(404, get(h, "/.well-known/other").status());
                assertEquals(404, get(h, "/.git/config").status());
            }
            var listing = StaticFileHandlerTest.text(get(h, "/"));
            assertTrue(listing.contains("hello.txt"));
            assertFalse(listing.contains("old.bak"), listing);
            assertFalse(listing.contains("private"), listing);
            assertFalse(listing.contains(".git"), listing);
            assertFalse(StaticFileHandlerTest.text(get(h, "/pub/")).contains(".env"));
        }
    }

    @Test
    void symlinksCannotReachRefusedPaths() throws IOException {
        Path link;
        try {
            link = Files.createSymbolicLink(root.resolve("pub/innocent.txt"), root.resolve("private/deep/x.txt"));
            Files.createSymbolicLink(root.resolve("gitdir"), root.resolve(".git"));
            Files.createSymbolicLink(root.resolve("pub/envlink"), root.resolve("pub/.env"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no symlinks: " + e);
            return;
        }
        var h = handler(new AccessRules(List.of("/private"), List.of(), false, null, false), false);
        assertEquals(403, get(h, "/pub/innocent.txt").status());
        assertEquals(404, get(h, "/gitdir/config").status());
        assertEquals(404, get(h, "/pub/envlink").status());
        assertFalse(StaticFileHandlerTest.text(get(h, "/pub/")).contains("innocent"));
        assertFalse(StaticFileHandlerTest.text(get(h, "/")).contains("gitdir"));
        assertTrue(Files.exists(link));
    }

    @Test
    void allowDotfilesServesThem() {
        var h = handler(new AccessRules(List.of("/.git"), List.of(), true, null, false), false);
        assertEquals(200, get(h, "/pub/.env").status());
        assertEquals(403, get(h, "/.git/config").status());
        assertTrue(new String(get(h, "/pub/").body() instanceof Body.Bytes b ? toBytes(b) : new byte[0],
                StandardCharsets.UTF_8).contains(".env"));
    }

    private static byte[] toBytes(Body.Bytes b) {
        var d = b.data().duplicate();
        var out = new byte[d.remaining()];
        d.get(out);
        return out;
    }

    @Test
    void deniedIndexFallsBackToListing() throws IOException {
        Files.writeString(root.resolve("pub/index.html"), "<p>idx</p>");
        var h = handler(new AccessRules(List.of("index.html"), List.of(), false, null, false), false);
        var r = get(h, "/pub/");
        assertEquals(200, r.status());
        assertFalse(StaticFileHandlerTest.text(r).contains("idx"));
        assertEquals(403, get(h, "/pub/index.html").status());
    }

    @Test
    void worldReadablePolicy() throws IOException {
        assumeTrue(POSIX, "POSIX only");
        var f = root.resolve("pub/a.txt");
        var h = handler(new AccessRules(List.of(), List.of(), false, null, true), true);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals(200, get(h, "/pub/a.txt").status());
        assertEquals(200, get(h, "/pub/a.txt").status()); // cache hit
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r-----"));
        assertEquals(403, get(h, "/pub/a.txt").status(), "re-checked on a cache hit, even within one ctime tick");
        Files.delete(f);
        assertEquals(404, get(h, "/pub/a.txt").status(), "a hit on a deleted file takes the full path");
        assertEquals(0, AccessRules.DEFAULT.checkFile(f));
    }

    @Test
    void ownerPolicy() throws IOException {
        assumeTrue(POSIX, "POSIX only");
        var f = root.resolve("hello.txt");
        int uid = (Integer) Files.getAttribute(f, "unix:uid");
        var me = Files.getOwner(f).getName();
        assertEquals(200, get(handler(new AccessRules(List.of(), List.of(), false, Integer.toString(uid), false),
                false), "/hello.txt").status());
        assertEquals(200, get(handler(new AccessRules(List.of(), List.of(), false, me, false), false), "/hello.txt")
                .status());
        assertEquals(403, get(handler(new AccessRules(List.of(), List.of(), false, Integer.toString(uid + 4242),
                false), false), "/hello.txt").status());
        if (uid != 0) {
            assertEquals(403, get(handler(new AccessRules(List.of(), List.of(), false, "root", false), false),
                    "/hello.txt").status());
        }
        var both = new AccessRules(List.of(), List.of(), false, me, true);
        assertEquals(me, both.requireOwner());
        assertTrue(both.requireWorldReadable());
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        assertEquals(403, both.checkFile(f));
    }

    @Test
    void filePolicyFailsClosedWithoutPosixAttributes() throws IOException {
        var zip = tmp.resolve("z.zip");
        try (var fs = FileSystems.newFileSystem(zip, java.util.Map.of("create", "true"))) {
            var f = Files.writeString(fs.getPath("/a.txt"), "a");
            assertEquals(403, new AccessRules(List.of(), List.of(), false, "0", false).checkFile(f));
        }
    }

    @Test
    void customPolicyPlugsIntoTheSeam() {
        AccessPolicy onlyTxt = (path, from) -> path.toString().endsWith(".txt") || path.length() == from
                || path.toString().endsWith(".env") || path.toString().endsWith("pub") ? 0 : 403;
        var h = new StaticFileHandler(root, false, FileCache.disabled(), onlyTxt);
        assertEquals(200, get(h, "/hello.txt").status());
        assertEquals(403, get(h, "/old.bak").status());
        assertEquals(200, get(h, "/pub/.env").status(), "the seam replaces the dotfile default too");
        assertEquals(onlyTxt, h.resolver().policy());
        assertFalse(onlyTxt.checksFiles());
    }
}
