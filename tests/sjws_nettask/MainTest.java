package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command-line entry point: output, warnings, errors and exit codes, in-process. */
class MainTest {

    @TempDir
    Path root;

    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    static final Transport.Factory NO_H3 = (c, h) -> {
        throw new AssertionError("HTTP/3 must not be created");
    };

    int run(Map<String, String> env, String... args) {
        return Sjws_nettask.run(args, env, out, err, NO_H3, SjwsServer::close);
    }

    @Test
    void helpPrintsUsageAndExitsZero() {
        assertEquals(Sjws_nettask.EXIT_OK, run(Map.of(), "--help"));
        assertEquals(Sjws_nettask.usage(), out());
        assertEquals("", err());
        assertEquals(Sjws_nettask.EXIT_OK, run(Map.of(), "-h"));
    }

    @Test
    void usageErrorsExit64() {
        assertEquals(Sjws_nettask.EXIT_USAGE, run(Map.of(), "--cert=/c.pem", root.toString()));
        assertTrue(err().contains("ERROR: --cert and --key must be given together"), err());
        assertTrue(err().endsWith("See --help." + System.lineSeparator()), err());
        assertEquals("", out());
    }

    @Test
    void missingDocumentRootExits2() {
        var missing = root.resolve("nope");
        assertEquals(Sjws_nettask.EXIT_BAD_ROOT, run(Map.of("WEB_SERVER_DIR", missing.toString())));
        assertTrue(err().contains("Document root " + missing.toAbsolutePath() + " does not exist or is not a directory."),
                err());
    }

    @Test
    void aFileAsDocumentRootExits2() throws Exception {
        var file = Files.writeString(root.resolve("f"), "x");
        assertEquals(Sjws_nettask.EXIT_BAD_ROOT, Sjws_nettask.serve(ServerConfig.builder(file).port(0).build(),
                NO_H3, out, err, SjwsServer::close));
    }

    @Test
    void busyPortExits1() throws Exception {
        try (var taken = new ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))) {
            int port = taken.getLocalPort();
            assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, run(Map.of(), String.valueOf(port), root.toString()));
            assertTrue(err().contains("Cannot listen on port " + port + ": "), err());
            assertTrue(err().contains("(already in use or not permitted)."), err());
        }
    }

    @Test
    void warningsArePrintedAndTheServerStillRuns() {
        // started callback closes the server at once: a normal, clean termination
        int code = Sjws_nettask.run(new String[] {"--bogus", "--allow-unlistable-dirs", root.toString()},
                Map.of("WEB_SERVER_PORT", String.valueOf(freePort())), out, err, NO_H3, SjwsServer::close);
        assertEquals(Sjws_nettask.EXIT_OK, code);
        assertTrue(err().contains("WARN: Unknown option '--bogus' (see --help)"), err());
        assertTrue(err().contains("WARN: --allow-unlistable-dirs: "), err());
        assertTrue(out().startsWith("Server started: HTTP/1.1 on tcp/"), out());
        assertTrue(out().contains("Server Terminated properly !"), out());
    }

    @Test
    void servesUntilClosedAndReportsTheBoundPorts() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var fake = new SjwsServerTest.FakeTransport(4433, null);
        var running = new CompletableFuture<SjwsServer>();
        var exit = CompletableFuture.supplyAsync(() -> Sjws_nettask.serve(
                ServerConfig.builder(root).port(0).h3Enabled(true).build(), (c, h) -> fake, out, err, running::complete));
        var server = running.get(10, TimeUnit.SECONDS);
        var r = HttpTestSupport.get(server.tcpPort(), "/hello.txt", 5000);
        assertEquals(200, r.statusCode());
        assertEquals("hello", r.bodyText());
        assertTrue(out().contains("Server started: HTTP/1.1 on tcp/" + server.tcpPort()), out());
        assertTrue(out().contains("4433"), "HTTP/3 port in the startup line: " + out());
        server.close(); // what the shutdown hook does on SIGTERM
        assertEquals(Sjws_nettask.EXIT_OK, exit.get(10, TimeUnit.SECONDS));
        assertTrue(out().endsWith("Server Terminated properly !" + System.lineSeparator()), out());
        assertEquals(1, fake.closes.get());
    }

    @Test
    void interruptedWhileServingExitsZeroAndCloses() throws Exception {
        var running = new CompletableFuture<SjwsServer>();
        var code = new int[] {-1};
        var t = Thread.ofVirtual().start(() -> code[0] = Sjws_nettask.serve(ServerConfig.builder(root).port(0).build(),
                NO_H3, out, err, running::complete));
        var server = running.get(10, TimeUnit.SECONDS);
        t.interrupt();
        t.join(10_000);
        assertEquals(Sjws_nettask.EXIT_OK, code[0]);
        assertEquals(-1, server.tcpPort(), "closed on the way out");
        assertFalse(out().contains("Terminated properly"));
    }

    @Test
    void signalHookWaitsForTheModulesToCloseOnceTheMainThreadReported() throws Exception {
        var root = Files.createDirectory(this.root.resolve("modules"));
        try (var server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), NO_H3)) {
            var task = new Sjws_nettask.Shutdown(server, new AtomicReference<>(), out, err);
            task.markServing();
            task.awaitModulesOnSignal(Duration.ofSeconds(10));
            var hook = new Thread(task, "shutdown");
            task.bind(hook);
            hook.start();
            task.reportTerminated();
            hook.join(300);
            assertTrue(hook.isAlive(), "the hook returned (and the JVM would halt) while the modules were closing");
            task.modulesClosed();
            hook.join(5_000);
            assertFalse(hook.isAlive());
        }

        outBytes.reset();
        try (var server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), NO_H3)) {
            var task = new Sjws_nettask.Shutdown(server, new AtomicReference<>(), out, err);
            task.markServing();
            task.awaitModulesOnSignal(Duration.ofMillis(50));
            var hook = new Thread(task, "shutdown");
            task.bind(hook);
            hook.start();
            task.reportTerminated();
            hook.join(5_000);
            assertFalse(hook.isAlive(), "bounded by the close wait");
            assertTrue(err().contains("modules still closing"), err());
        }
    }

    @Test
    void signalHookWaitsForTheTerminationLine() throws Exception {
        var root = Files.createDirectory(this.root.resolve("hook"));
        try (var server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), NO_H3)) {
            var task = new Sjws_nettask.Shutdown(server, new AtomicReference<>(), out, err);
            task.markServing();
            var hook = new Thread(task, "shutdown");
            task.bind(hook);
            hook.start();
            task.reportTerminated();
            hook.join(5_000);
            assertFalse(hook.isAlive());
            assertEquals(1, out().split("Server Terminated properly !", -1).length - 1, out());
        }

        outBytes.reset();
        try (var server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), NO_H3)) {
            var task = new Sjws_nettask.Shutdown(server, new AtomicReference<>(), out, err);
            task.markServing();
            task.reportWait(Duration.ZERO);
            var hook = new Thread(task, "shutdown");
            task.bind(hook);
            hook.start();
            hook.join(5_000);
            assertFalse(hook.isAlive());
            assertTrue(out().contains("Server Terminated properly !"), out());
        }

        outBytes.reset();
        try (var server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), NO_H3)) {
            var task = new Sjws_nettask.Shutdown(server, new AtomicReference<>(), out, err);
            var hook = new Thread(task, "shutdown");
            task.bind(hook);
            hook.start();
            hook.join(5_000);
            assertFalse(hook.isAlive());
            assertFalse(out().contains("Terminated properly"), out());
            task.run();
            assertFalse(out().contains("Terminated properly"), out());
            task.reportTerminated();
            assertFalse(out().contains("Terminated properly"), out());
            task.markServing();
            task.reportTerminated();
            task.reportTerminated();
            assertEquals(1, out().split("Server Terminated properly !", -1).length - 1, out());
        }

        outBytes.reset();
        try (var server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), NO_H3)) {
            var task = new Sjws_nettask.Shutdown(server, new AtomicReference<>(), out, err);
            task.markServing();
            task.reportWait(Duration.ofSeconds(30));
            var hook = new Thread(task, "shutdown");
            task.bind(hook);
            hook.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (hook.isAlive() && hook.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            hook.interrupt();
            hook.join(5_000);
            assertFalse(hook.isAlive());
            assertTrue(out().contains("Server Terminated properly !"), out());
        }
    }

    @Test
    void checkValidPort() {
        assertTrue(Sjws_nettask.checkValidPort("1"));
        assertTrue(Sjws_nettask.checkValidPort("65535"));
        assertFalse(Sjws_nettask.checkValidPort("0"));
        assertFalse(Sjws_nettask.checkValidPort("65536"));
        assertFalse(Sjws_nettask.checkValidPort("123456"));
        assertFalse(Sjws_nettask.checkValidPort("8o"));
        assertFalse(Sjws_nettask.checkValidPort(null));
    }

    @Test
    void pidFileOfALiveProcessBlocksAndAnAbsentFileStillStartsHttp2() throws Exception {
        var pid = root.resolve("sjws.pid");
        var me = ProcessHandle.current();
        long started = me.info().startInstant().orElseThrow().toEpochMilli();
        Files.writeString(pid, PidFile.text(me.pid(), started));
        assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, Sjws_nettask.serve(
                ServerConfig.builder(root).port(0).build(), NO_H3, out, err, SjwsServer::close,
                Duration.ofSeconds(1), pid));
        assertTrue(err().contains("Already running with pid " + me.pid()), err());

        outBytes.reset();
        errBytes.reset();
        Files.delete(pid);
        assertEquals(Sjws_nettask.EXIT_OK, Sjws_nettask.serve(
                ServerConfig.builder(root).port(0).h2Enabled(true).h2cPort(0).build(),
                NO_H3, out, err, SjwsServer::close, Duration.ofSeconds(1), pid), err());
        assertTrue(out().contains("HTTP/2 on tcp/"), out());
        assertTrue(out().contains("h2c tcp/"), out());
    }

    static int freePort() {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
