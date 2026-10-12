package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Accept-loop failure paths, driven by fake channels around a real loopback listener. */
class ClientManagerFaultTest {

    @TempDir
    Path root;

    /** A listener whose accept() is scripted; delegates to a real one where needed. */
    static class ScriptedListener extends ServerSocketChannel {
        final ServerSocketChannel real;
        final Supplier<SocketChannel> next;
        volatile IOException closeFailure;

        ScriptedListener(ServerSocketChannel real, Supplier<SocketChannel> next) {
            super(SelectorProvider.provider());
            this.real = real;
            this.next = next;
        }

        @Override
        public SocketChannel accept() throws IOException {
            if (!isOpen()) {
                throw new java.nio.channels.ClosedChannelException();
            }
            try {
                return this.next.get();
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        }

        @Override
        protected void implCloseSelectableChannel() throws IOException {
            this.real.close();
            if (this.closeFailure != null) {
                throw this.closeFailure;
            }
        }

        @Override public ServerSocketChannel bind(SocketAddress local, int backlog) { throw new UnsupportedOperationException(); }
        @Override public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) { throw new UnsupportedOperationException(); }
        @Override public <T> T getOption(SocketOption<T> name) { throw new UnsupportedOperationException(); }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
        @Override public ServerSocket socket() { throw new UnsupportedOperationException(); }
        @Override public SocketAddress getLocalAddress() throws IOException { return this.real.getLocalAddress(); }
        @Override protected void implConfigureBlocking(boolean block) { }
    }

    /** A connected channel whose setOption fails (everything else delegates). */
    static final class OptionFailingChannel extends SocketChannel {
        final SocketChannel real;

        OptionFailingChannel(SocketChannel real) {
            super(SelectorProvider.provider());
            this.real = real;
        }

        @Override public <T> SocketChannel setOption(SocketOption<T> name, T value) throws IOException { throw new IOException("setsockopt failed"); }
        @Override public SocketAddress getRemoteAddress() throws IOException { return this.real.getRemoteAddress(); }
        @Override protected void implCloseSelectableChannel() throws IOException { this.real.close(); }
        @Override public SocketChannel bind(SocketAddress local) { throw new UnsupportedOperationException(); }
        @Override public <T> T getOption(SocketOption<T> name) { throw new UnsupportedOperationException(); }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
        @Override public SocketChannel shutdownInput() { throw new UnsupportedOperationException(); }
        @Override public SocketChannel shutdownOutput() { throw new UnsupportedOperationException(); }
        @Override public Socket socket() { throw new UnsupportedOperationException(); }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean connect(SocketAddress remote) { throw new UnsupportedOperationException(); }
        @Override public boolean finishConnect() { throw new UnsupportedOperationException(); }
        @Override public int read(ByteBuffer dst) { throw new UnsupportedOperationException(); }
        @Override public long read(ByteBuffer[] dsts, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer src) { throw new UnsupportedOperationException(); }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public SocketAddress getLocalAddress() throws IOException { return this.real.getLocalAddress(); }
        @Override protected void implConfigureBlocking(boolean block) { }
    }

    ServerSocketChannel loopback() throws IOException {
        return ServerSocketChannel.open().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    static Socket connect(ServerSocketChannel real) throws IOException {
        var s = new Socket();
        s.connect(real.getLocalAddress(), 5000);
        s.setSoTimeout(5000);
        return s;
    }

    String runLoop(ClientManager manager, Runnable whileRunning) throws Throwable {
        return SjwsServerTest.stderrOf(() -> {
            var t = new Thread(manager, "accept-loop");
            t.start();
            try {
                whileRunning.run();
            } finally {
                manager.close();
                t.join(5000);
                assertFalse(t.isAlive(), "accept loop ended");
            }
        });
    }

    ServerConfig config() {
        return ServerConfig.defaults(root);
    }

    @Test
    void acceptFailuresBackOffAndAreLoggedOncePerStreak() throws Throwable {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var real = loopback();
        var calls = new AtomicInteger();
        var listener = new ScriptedListener(real, () -> {
            try {
                if (calls.incrementAndGet() <= 3) {
                    throw new IOException("Too many open files");
                }
                return real.accept();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        var manager = new ClientManager(listener, config());
        var err = runLoop(manager, () -> {
            try (var s = connect(real)) { // served once accept() recovers
                s.getOutputStream().write("GET /hello.txt HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n".getBytes());
                assertEquals(200, HttpTestSupport.readResponse(s.getInputStream()).statusCode());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        assertEquals(1, err.split("accept\\(\\) failed, backing off: java.io.IOException: Too many open files", -1).length - 1,
                err);
    }

    @Test
    void aConnectionResetBeforeItIsRegisteredIsDropped() throws Throwable {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var real = loopback();
        var calls = new AtomicInteger();
        var listener = new ScriptedListener(real, () -> {
            try {
                var c = real.accept();
                if (calls.incrementAndGet() == 1) {
                    c.close(); // getRemoteAddress() now fails, as after a reset
                }
                return c;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        var manager = new ClientManager(listener, config());
        runLoop(manager, () -> {
            try (var first = connect(real); var second = connect(real)) {
                assertEquals(-1, first.getInputStream().read(), "dropped");
                second.getOutputStream().write("GET /hello.txt HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n".getBytes());
                assertEquals(200, HttpTestSupport.readResponse(second.getInputStream()).statusCode(), "slot was released");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        assertEquals(0, manager.activeConnections());
    }

    @Test
    void aClientThatCannotBeStartedIsClosedAndItsSlotReleased() throws Throwable {
        var real = loopback();
        var listener = new ScriptedListener(real, () -> {
            try {
                return new OptionFailingChannel(real.accept());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        var manager = new ClientManager(listener, config().toBuilder().maxConnections(1).build());
        var err = runLoop(manager, () -> {
            for (int i = 0; i < 2; i++) { // the second one also gets a slot: the first was released
                try (var s = connect(real)) {
                    assertEquals(-1, s.getInputStream().read());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        });
        assertEquals(2, err.split("Could not start client: java.io.IOException: setsockopt failed", -1).length - 1, err);
        assertEquals(0, manager.activeConnections());
    }

    @Test
    void interruptingTheAcceptLoopClosesTheListener() throws Exception {
        var real = loopback();
        var manager = new ClientManager(real, config().toBuilder().maxConnections(1).build());
        var t = new Thread(manager, "accept-loop");
        t.start();
        try (var held = connect(real)) { // takes the only slot: the loop waits for a permit
            HttpTestSupport.write(held.getOutputStream(), "GET / HTTP/1.1\r\nHost: h\r\n\r\n");
            HttpTestSupport.readResponse(held.getInputStream());
            t.interrupt();
            t.join(5000);
            assertFalse(t.isAlive());
            assertFalse(real.isOpen(), "listener closed on the way out");
        }
    }

    @Test
    void anErrorClosingTheListenerIsLogged() throws Throwable {
        var real = loopback();
        var listener = new ScriptedListener(real, () -> {
            try {
                return real.accept();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        listener.closeFailure = new IOException("close failed");
        var manager = new ClientManager(listener, config());
        var err = runLoop(manager, () -> {
        });
        assertTrue(err.contains("java.io.IOException: close failed"), err);
    }

    @Test
    void legacyConstructorsServeTheGivenRoot() throws Exception {
        try (var real = loopback()) {
            assertEquals(0, new ClientManager(real, root.toString()).activeConnections());
            assertEquals(0, new ClientManager(real, config(), sjws_nettask.core.ResponseHeaders::of).refusedPerIp());
        }
    }
}
