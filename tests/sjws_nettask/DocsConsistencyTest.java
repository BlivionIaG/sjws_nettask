package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** S6: the documented option tables, {@code --help} and the coverage gates can't drift apart silently. */
class DocsConsistencyTest {

    private static final Pattern FLAG = Pattern.compile("--[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern ENV = Pattern.compile("WEB_SERVER_[A-Z0-9_]+");

    private static TreeSet<String> all(Pattern p, String text) {
        return p.matcher(text).results().map(m -> m.group()).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The docs pages that hold the option tables (moved out of the README into docs/). */
    static final List<Path> OPTION_DOCS = List.of(Path.of("docs/user/command-line.md"),
            Path.of("docs/user/http2.md"), Path.of("docs/user/http3.md"),
            Path.of("docs/operations/observability.md"));

    /** The option tables: table rows whose first cell names an option or an argument. */
    private static String readmeOptionRows() throws IOException {
        var rows = new java.util.ArrayList<String>();
        for (var doc : OPTION_DOCS) {
            Files.readAllLines(doc).stream()
                    .filter(l -> l.startsWith("| `-") || l.startsWith("| `[") || l.startsWith("| |"))
                    .forEach(rows::add);
        }
        return String.join("\n", rows);
    }

    @Test
    void everyOptionAndVariableIsDocumentedInBothPlaces() throws IOException {
        var usage = Sjws_nettask.usage();
        var readme = readmeOptionRows();
        assertEquals(all(FLAG, usage), all(FLAG, readme), "--flags: --help vs docs option tables");
        assertEquals(all(ENV, usage), all(ENV, readme), "environment variables: --help vs docs option tables");
        assertTrue(all(FLAG, usage).size() >= 20, "sanity: " + all(FLAG, usage));
    }

    @Test
    void readmeDefaultsMatchTheCode() throws IOException {
        var d = ServerConfig.defaults(".");
        var readme = readmeOptionRows();
        assertTrue(readme.contains("`--max-connections=N` | `WEB_SERVER_MAX_CONNECTIONS` | " + d.maxConnections() + " |"));
        assertTrue(readme.contains("`WEB_SERVER_MAX_CONNECTIONS_PER_IP` | " + d.maxConnectionsPerIp() + " |"));
        assertTrue(readme.contains("`WEB_SERVER_BACKLOG` | " + d.backlog() + " |"));
        assertTrue(readme.contains("`WEB_SERVER_IDLE_TIMEOUT` | " + d.idleTimeout().toSeconds() + " |"));
        assertTrue(readme.contains("`WEB_SERVER_HEADER_TIMEOUT` | " + d.headerTimeout().toSeconds() + " |"));
        assertTrue(readme.contains("`WEB_SERVER_WRITE_TIMEOUT` | " + d.writeTimeout().toSeconds() + " |"));
        assertTrue(readme.contains("`WEB_SERVER_CACHE_BYTES` | " + (d.cacheBytes() >> 20) + "m |"));
        assertTrue(readme.contains("`WEB_SERVER_H3_MAX_CONNECTIONS` | " + d.h3MaxConnections() + " |"));
        assertTrue(readme.contains("`WEB_SERVER_H3_MAX_CONNECTIONS_PER_IP` | " + d.h3MaxConnectionsPerIp() + " |"));
        assertTrue(readme.contains("`WEB_SERVER_SHUTDOWN_TIMEOUT` | " + Daemon.DEFAULT_SHUTDOWN_TIMEOUT.toSeconds() + " |"));
    }

    @Test
    void everySourcePackageHasACoverageGate() throws IOException {
        var packages = new TreeSet<String>();
        try (var walk = Files.walk(Path.of("src"))) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> packages.add(Path.of("src").relativize(p.getParent()).toString().replace('/', '.')));
        }
        var pom = Files.readString(Path.of("pom.xml"));
        var gated = new TreeSet<String>();
        var m = Pattern.compile("<element>PACKAGE</element>\\s*<includes><include>([\\w.]+)</include></includes>")
                .matcher(pom);
        while (m.find()) {
            gated.add(m.group(1));
        }
        assertEquals(packages, gated, "every package under src/ needs its own JaCoCo PACKAGE rule (and no stale ones)");
    }

    /**
     * #383: after #296 / #302, HTTP/2 (h2/h2c streams) fills {@code fields()} when the live
     * generation has {@code attachBodies} on. Docs must not claim HTTP/2 always leaves the map empty.
     */
    @Test
    void h2FieldCaptureDocsMatchAttachBodies() throws IOException {
        var gateway = Files.readString(Path.of("docs/development/gateway.md"));
        var filters = Files.readString(Path.of("docs/development/filters.md"));
        var stale = List.of(
                "HTTP/2 leaves it empty",
                "always leave `fields()` empty");
        try (var walk = Files.walk(Path.of("docs"))) {
            walk.filter(p -> p.toString().endsWith(".md")).forEach(doc -> {
                String body;
                try {
                    body = Files.readString(doc);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                for (var phrase : stale) {
                    assertFalse(body.contains(phrase),
                            () -> doc + " must not say HTTP/2 never has a field map (#383): found " + phrase);
                }
            });
        }
        assertTrue(gateway.contains("attachBodies"),
                "gateway.md must describe attachBodies-gated field capture on HTTP/2 (#383)");
        assertTrue(filters.contains("attachBodies"),
                "filters.md must describe attachBodies-gated field capture on HTTP/2 (#383)");
    }
}
