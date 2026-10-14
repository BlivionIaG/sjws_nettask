package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * Static guards from the design review: no carrier pinning (M12) and no Netty in core/HTTP-1.
 */
class SourceGuardTest {

    private static String all(Path dir) throws IOException {
        var sb = new StringBuilder();
        try (var walk = Files.walk(dir)) {
            for (var p : (Iterable<Path>) walk::iterator) {
                if (p.toString().endsWith(".java")) {
                    sb.append("//FILE ").append(p).append('\n').append(Files.readString(p)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    @Test
    void noSynchronizedAnywhere() throws IOException {
        // Virtual threads pin their carrier inside synchronized on JDK 21; use ReentrantLock if needed.
        var offenders = new ArrayList<String>();
        try (var walk = Files.walk(Path.of("src"))) {
            for (var p : (Iterable<Path>) walk::iterator) {
                if (p.toString().endsWith(".java") && Files.readString(p).matches("(?s).*\\bsynchronized\\b.*")) {
                    offenders.add(p.toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "synchronized found in " + offenders);
    }

    @Test
    void computeIfAbsentOnlyWithoutIo() throws IOException {
        var src = all(Path.of("src"));
        int count = src.split("computeIfAbsent\\(", -1).length - 1;
        // The only use: header list creation in Http1Parser (pure, no I/O).
        assertEquals(1, count, "review new computeIfAbsent uses: no file I/O inside (design review M12)");
        assertTrue(Files.readString(Path.of("src/sjws_nettask/http1/Http1Parser.java"))
                .contains("computeIfAbsent(line.substring(0, colon).toLowerCase(Locale.ROOT), k -> new ArrayList<>())"));
    }

    @Test
    void noNettyInCoreOrHttp1() throws IOException {
        assertFalse(all(Path.of("src/sjws_nettask/core")).contains("io.netty"));
        assertFalse(all(Path.of("src/sjws_nettask/http1")).contains("io.netty"));
    }

    @Test
    void coreDoesNotDependOnTransports() throws IOException {
        var core = all(Path.of("src/sjws_nettask/core"));
        assertFalse(core.contains("import sjws_nettask.http1"), "core must not import the HTTP/1 package");
        assertFalse(all(Path.of("src/sjws_nettask/h3")).contains("sjws_nettask.http1"),
                "h3 must not depend on the HTTP/1 package (shared rules live in core)");
        assertFalse(core.contains("java.net.Socket"), "core must not touch sockets");
        var cluster = all(Path.of("src/sjws_nettask/cluster"));
        assertFalse(cluster.contains("sjws_nettask.ha"), "cluster must not import the HTTP HA package");
        assertFalse(cluster.contains("sjws_nettask.http1"), "cluster must not import HTTP/1");
        assertFalse(cluster.contains("io.netty"), "cluster must not import Netty");
    }

    /** docs/design/agent-swarm.md §3.3, item 1: the swarm is a leaf; only the entry point names it, as a string. */
    @Test
    void nothingOutsideTheSwarmDependsOnIt() throws IOException {
        var offenders = new ArrayList<String>();
        try (var walk = Files.walk(Path.of("src"))) {
            for (var p : (Iterable<Path>) walk::iterator) {
                var name = p.toString().replace('\\', '/');
                if (!name.endsWith(".java") || name.startsWith("src/sjws_nettask/swarm/")) {
                    continue;
                }
                var text = Files.readString(p);
                if (name.equals("src/sjws_nettask/OptionalModules.java")) {
                    if (text.contains("import sjws_nettask.swarm") || text.split("sjws_nettask\\.swarm", -1).length - 1 != 2) {
                        offenders.add(name + " (must name the swarm only in the SWARM_CLASS constant and its javadoc)");
                    }
                } else if (text.contains("sjws_nettask.swarm")) {
                    offenders.add(name);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "outside the swarm package, only OptionalModules may name it: " + offenders);
        var wire = Path.of("src/sjws_nettask/swarm/wire");
        if (Files.isDirectory(wire)) {
            var imports = java.util.regex.Pattern.compile("import (static )?sjws_nettask\\.([\\w]+)").matcher(all(wire));
            while (imports.find()) {
                var pkg = imports.group(2);
                assertTrue(pkg.equals("core"), "swarm.wire may import only core: " + imports.group());
            }
        }
    }

    /**
     * control-plane-hosting.md §5: outside the cp package nothing names the CONTROL-role types, so a data or
     * standalone process cannot load them; ServiceLaunch reaches the role through the CpLaunch name only.
     */
    @Test
    void controlRoleTypesStayInsideCp() throws IOException {
        var heavy = java.util.regex.Pattern.compile(
                "sjws_nettask\\.cp\\.(CpStore|KeyRing|WatchServer|AdminServer|CpHost|CpWitness|CpLaunch|CpConfig)\\b");
        var offenders = new ArrayList<String>();
        try (var walk = Files.walk(Path.of("src"))) {
            for (var p : (Iterable<Path>) walk::iterator) {
                var name = p.toString().replace('\\', '/');
                if (!name.endsWith(".java") || name.startsWith("src/sjws_nettask/cp/")) {
                    continue;
                }
                var m = heavy.matcher(Files.readString(p));
                while (m.find()) {
                    if (!(name.equals("src/sjws_nettask/service/ServiceLaunch.java")
                            && m.group().equals("sjws_nettask.cp.CpLaunch"))) {
                        offenders.add(name + ": " + m.group());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "CONTROL-role types named outside cp: " + offenders);
        var launch = Files.readString(Path.of("src/sjws_nettask/service/ServiceLaunch.java"));
        assertEquals(1, launch.split("\"sjws_nettask\\.cp\\.CpLaunch\"", -1).length - 1,
                "ServiceLaunch names CpLaunch once, as a string");
    }

    /** Security review of #157: the {@code SecureKeyFile.beforeOpen} race hook is test-only. */
    @Test
    void secureKeyFileBeforeOpenIsAssignedOnlyInTests() throws IOException {
        var assign = java.util.regex.Pattern.compile("\\bbeforeOpen\\s*=[^=]");
        var offenders = new ArrayList<String>();
        try (var walk = Files.walk(Path.of("src"))) {
            for (var p : (Iterable<Path>) walk::iterator) {
                if (p.toString().endsWith(".java") && assign.matcher(Files.readString(p)).find()) {
                    offenders.add(p.toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "beforeOpen assigned in production code: " + offenders);
        var tests = all(Path.of("tests/sjws_nettask/config"));
        assertTrue(assign.matcher(tests).find(), "the guard pattern still matches the test assignments");
    }
}
