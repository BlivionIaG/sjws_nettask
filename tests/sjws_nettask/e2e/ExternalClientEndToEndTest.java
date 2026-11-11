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
package sjws_nettask.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.h2.Http2Server;

/**
 * Live listeners driven by real external clients (curl, and h2load / quiche / ngtcp2
 * when those binaries are on {@code PATH}). HTTPS on the main TCP port is out of scope:
 * HTTP/2 is exercised on the cleartext h2c port; HTTP/3 uses the QUIC listener and
 * curl's {@code --http3-only} (or a native client) with the process's self-signed cert.
 */
@Tag("e2e")
@Tag("e2e-clients")
@Timeout(20)
class ExternalClientEndToEndTest {

    private static final String BODY = "hello from the temp docroot\n";
    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(8);

    @TempDir
    Path root;

    private SjwsServer server;

    @BeforeEach
    void start() throws IOException {
        Files.writeString(root.resolve("hello.txt"), BODY, StandardCharsets.UTF_8);
        server = SjwsServer.start(ServerConfig.builder(root)
                .port(0)
                .h2Enabled(true)
                .h2Port(0)
                .h2cPort(0)
                .h3Enabled(true)
                .h3Port(0)
                .h3Host("127.0.0.1")
                .build());
        assertTrue(server.tcpPort() > 0, "ephemeral HTTP/1.1 port");
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void curlHttp11ServesFileAnd404AndRange() throws Exception {
        assertTrue(onPath("curl"), "curl is required for the HTTP/1.1 external-client smoke");
        var ok = run(List.of("curl", "-sS", "--http1.1", "-w", "%{http_code}", h1("/hello.txt")));
        assertEquals(0, ok.exit, ok.detail());
        assertTrue(ok.stdout.endsWith("200"), ok.detail());
        assertTrue(ok.stdout.startsWith(BODY), ok.detail());

        var missing = run(List.of("curl", "-sS", "--http1.1", "-o", "/dev/null", "-w", "%{http_code}",
                h1("/no-such-file.txt")));
        assertEquals(0, missing.exit, missing.detail());
        assertEquals("404", missing.stdout.trim(), missing.detail());

        var range = run(List.of("curl", "-sS", "--http1.1", "-H", "Range: bytes=0-4",
                "-D", "-", "-o", "-", h1("/hello.txt")));
        assertEquals(0, range.exit, range.detail());
        assertTrue(range.stdout.contains("HTTP/1.1 206") || range.stdout.contains(" 206 "), range.detail());
        assertEquals("hello", bodyAfterHeaders(range.stdout));
    }

    @Test
    void curlHttp2PriorKnowledgeWhenSupported() throws Exception {
        assumeTrue(onPath("curl"), "curl is missing");
        assumeTrue(curlFeature("HTTP2"), "curl was not built with HTTP/2");
        int h2c = h2cPort();
        assumeTrue(h2c > 0, "h2c listener did not bind" + h2Hint());
        var ok = run(List.of("curl", "-sS", "--http2-prior-knowledge", "-w", " %{http_code} %{http_version}",
                "http://127.0.0.1:" + h2c + "/hello.txt"));
        assertEquals(0, ok.exit, ok.detail());
        assertTrue(ok.stdout.startsWith(BODY), ok.detail());
        assertTrue(ok.stdout.contains(" 200 2"), ok.detail());
    }

    @Test
    void h2loadSmokeWhenInstalled() throws Exception {
        assumeTrue(onPath("h2load"), "h2load (nghttp2-client) is not on PATH");
        int h2c = h2cPort();
        assumeTrue(h2c > 0, "h2c listener did not bind" + h2Hint());
        var result = run(List.of("h2load", "--no-tls-proto=h2c", "-n", "10", "-c", "2",
                "http://127.0.0.1:" + h2c + "/hello.txt"), CLIENT_TIMEOUT);
        assertEquals(0, result.exit, result.detail());
        assertH2loadAllSucceeded(result, 10);
    }

    @Test
    void curlHttp3OnlyWhenSupported() throws Exception {
        assumeTrue(onPath("curl"), "curl is missing");
        assumeTrue(curlFeature("HTTP3"), "curl was not built with HTTP/3");
        assumeTrue(server.udpPort() > 0, "HTTP/3 listener did not bind" + h3Hint());
        var ok = run(List.of("curl", "-sS", "--http3-only", "-k",
                "-w", " %{http_code} %{http_version}",
                "https://127.0.0.1:" + server.udpPort() + "/hello.txt"));
        assertEquals(0, ok.exit, ok.detail());
        assertTrue(ok.stdout.startsWith(BODY), ok.detail());
        assertTrue(ok.stdout.contains(" 200 3"), ok.detail());
    }

    @Test
    void quicheOrNgtcp2WhenInstalled() throws Exception {
        assumeTrue(server.udpPort() > 0, "HTTP/3 listener did not bind" + h3Hint());
        var client = firstOnPath("quiche-client", "quiche", "ngtcp2-client", "ngtcp2_client");
        assumeTrue(client != null, "no quiche or ngtcp2 client on PATH");
        List<String> cmd = new ArrayList<>();
        cmd.add(client);
        String name = Path.of(client).getFileName().toString();
        if (name.contains("quiche")) {
            cmd.add("--no-verify");
        } else {
            cmd.add("--insecure");
        }
        cmd.add("https://127.0.0.1:" + server.udpPort() + "/hello.txt");
        var result = run(cmd);
        assertEquals(0, result.exit, result.detail());
        var text = result.stdout + result.stderr;
        assertTrue(text.contains("hello from the temp docroot") || text.contains("200"), result.detail());
    }

    private String h1(String path) {
        return "http://127.0.0.1:" + server.tcpPort() + path;
    }

    private int h2cPort() {
        if (server.h2() instanceof Http2Server http2) {
            return http2.h2cPort();
        }
        return -1;
    }

    private String h2Hint() {
        return server.h2Failure() == null ? "" : (": " + server.h2Failure());
    }

    private String h3Hint() {
        return server.h3Failure() == null ? "" : (": " + server.h3Failure());
    }

    private static String bodyAfterHeaders(String raw) {
        int split = raw.indexOf("\r\n\r\n");
        if (split < 0) {
            split = raw.indexOf("\n\n");
            return split < 0 ? raw : raw.substring(split + 2);
        }
        return raw.substring(split + 4);
    }

    private static boolean curlFeature(String feature) {
        var result = run(List.of("curl", "--version"), Duration.ofSeconds(3));
        return result.exit == 0 && result.stdout.toUpperCase(Locale.ROOT).contains(feature.toUpperCase(Locale.ROOT));
    }

    private static void assertH2loadAllSucceeded(CommandResult result, int expected) {
        var text = result.stdout + "\n" + result.stderr;
        Integer succeeded = h2loadRequestCount(text, "succeeded");
        Integer failed = h2loadRequestCount(text, "failed");
        Integer twoXx = h2loadStatusCount(text, "2xx");
        assertTrue(succeeded != null && failed != null && twoXx != null,
                "expected h2load requests/status summary:\n" + result.detail());
        assertEquals(expected, succeeded, "h2load succeeded count:\n" + result.detail());
        assertEquals(0, failed, "h2load failed count:\n" + result.detail());
        assertEquals(expected, twoXx, "h2load 2xx count:\n" + result.detail());
    }

    private static Integer h2loadRequestCount(String text, String field) {
        Matcher m = Pattern.compile("requests:[^\\n]*\\b(\\d+)\\s+" + Pattern.quote(field) + "\\b",
                Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static Integer h2loadStatusCount(String text, String category) {
        Matcher m = Pattern.compile("status codes:[^\\n]*\\b(\\d+)\\s+" + Pattern.quote(category) + "\\b",
                Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static boolean onPath(String binary) {
        var path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (var dir : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
            if (dir.isEmpty()) {
                continue;
            }
            if (Files.isExecutable(Path.of(dir, binary))) {
                return true;
            }
        }
        return false;
    }

    private static String firstOnPath(String... binaries) {
        for (var binary : binaries) {
            if (onPath(binary)) {
                return binary;
            }
        }
        return null;
    }

    private static CommandResult run(List<String> cmd) {
        return run(cmd, CLIENT_TIMEOUT);
    }

    private static CommandResult run(List<String> cmd, Duration timeout) {
        Path stdoutFile = null;
        Path stderrFile = null;
        try {
            stdoutFile = Files.createTempFile("sjws-e2e-out-", ".txt");
            stderrFile = Files.createTempFile("sjws-e2e-err-", ".txt");
            var builder = new ProcessBuilder(cmd);
            var env = builder.environment();
            env.remove("http_proxy");
            env.remove("https_proxy");
            env.remove("HTTP_PROXY");
            env.remove("HTTPS_PROXY");
            env.remove("ALL_PROXY");
            env.remove("all_proxy");
            builder.redirectOutput(stdoutFile.toFile());
            builder.redirectError(stderrFile.toFile());
            var process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                fail("timed out: " + cmd);
            }
            return new CommandResult(cmd, process.exitValue(),
                    Files.readString(stdoutFile), Files.readString(stderrFile));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            fail("failed to run " + cmd + ": " + e);
            throw new IllegalStateException(e);
        } finally {
            if (stdoutFile != null) {
                try {
                    Files.deleteIfExists(stdoutFile);
                } catch (IOException ignored) {
                }
            }
            if (stderrFile != null) {
                try {
                    Files.deleteIfExists(stderrFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private record CommandResult(List<String> cmd, int exit, String stdout, String stderr) {
        String detail() {
            return "cmd=" + cmd + " exit=" + exit + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
        }
    }
}
