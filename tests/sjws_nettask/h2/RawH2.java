package sjws_nettask.h2;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Byte-level HTTP/2 client over cleartext (h2c prior knowledge) for the hardening tests: it can do
 * what a well-behaved client library refuses to (exceed the peer's stream limit, reset in a loop,
 * flood CONTINUATION frames, withhold WINDOW_UPDATE). HPACK: literal fields without indexing or
 * Huffman only, which every decoder must accept.
 */
final class RawH2 implements AutoCloseable {

    static final int DATA = 0, HEADERS = 1, RST_STREAM = 3, SETTINGS = 4, PING = 6, GOAWAY = 7, WINDOW_UPDATE = 8,
            CONTINUATION = 9;
    static final int END_STREAM = 1, END_HEADERS = 4, ACK = 1;

    record Frame(int type, int flags, int stream, byte[] payload) {
        int errorCode() { // RST_STREAM: payload[0..3]; GOAWAY: payload[4..7]
            int o = this.type == GOAWAY ? 4 : 0;
            return ((this.payload[o] & 0xff) << 24) | ((this.payload[o + 1] & 0xff) << 16)
                    | ((this.payload[o + 2] & 0xff) << 8) | (this.payload[o + 3] & 0xff);
        }
    }

    final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;

    /** Connects, sends the preface and SETTINGS (initial stream window {@code initialWindow}). */
    RawH2(int port, int initialWindow) throws IOException {
        this(port, initialWindow, 0);
    }

    /** @param receiveBuffer SO_RCVBUF to request before connecting, or 0 for the default */
    RawH2(int port, int initialWindow, int receiveBuffer) throws IOException {
        this.socket = new Socket();
        if (receiveBuffer > 0) {
            this.socket.setReceiveBufferSize(receiveBuffer);
        }
        this.socket.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        this.socket.setSoTimeout(10_000);
        this.in = new DataInputStream(this.socket.getInputStream());
        this.out = this.socket.getOutputStream();
        this.out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        frame(SETTINGS, 0, 0, new byte[] {0, 4, (byte) (initialWindow >>> 24), (byte) (initialWindow >>> 16),
            (byte) (initialWindow >>> 8), (byte) initialWindow});
    }

    void frame(int type, int flags, int stream, byte[] payload) throws IOException {
        var b = new byte[9 + payload.length];
        b[0] = (byte) (payload.length >>> 16);
        b[1] = (byte) (payload.length >>> 8);
        b[2] = (byte) payload.length;
        b[3] = (byte) type;
        b[4] = (byte) flags;
        b[5] = (byte) (stream >>> 24);
        b[6] = (byte) (stream >>> 16);
        b[7] = (byte) (stream >>> 8);
        b[8] = (byte) stream;
        System.arraycopy(payload, 0, b, 9, payload.length);
        this.out.write(b);
    }

    /** HPACK block for a GET (or {@code method}) of {@code path}, plus extra literal fields. */
    static byte[] block(String method, String path, String... extra) {
        var b = new ByteArrayOutputStream();
        literal(b, ":method", method);
        literal(b, ":scheme", "http");
        literal(b, ":path", path);
        literal(b, ":authority", "localhost");
        for (int i = 0; i < extra.length; i += 2) {
            literal(b, extra[i], extra[i + 1]);
        }
        return b.toByteArray();
    }

    private static void literal(ByteArrayOutputStream b, String name, String value) {
        b.write(0); // literal without indexing, new name
        string(b, name.getBytes(StandardCharsets.ISO_8859_1));
        string(b, value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void string(ByteArrayOutputStream b, byte[] s) { // 7-bit prefix integer, no Huffman
        int n = s.length;
        if (n < 127) {
            b.write(n);
        } else {
            b.write(127);
            n -= 127;
            while (n >= 128) {
                b.write((n & 0x7f) | 0x80);
                n >>>= 7;
            }
            b.write(n);
        }
        b.writeBytes(s);
    }

    void get(int stream, String path) throws IOException {
        frame(HEADERS, END_HEADERS | END_STREAM, stream, block("GET", path));
    }

    void windowUpdate(int stream, int increment) throws IOException {
        frame(WINDOW_UPDATE, 0, stream, new byte[] {(byte) (increment >>> 24), (byte) (increment >>> 16),
            (byte) (increment >>> 8), (byte) increment});
    }

    void rst(int stream, int code) throws IOException {
        frame(RST_STREAM, 0, stream, new byte[] {(byte) (code >>> 24), (byte) (code >>> 16), (byte) (code >>> 8),
            (byte) code});
    }

    /** @return the next frame (SETTINGS from the server are acknowledged), or null at EOF/timeout */
    Frame read() throws IOException {
        try {
            var h = new byte[9];
            this.in.readFully(h);
            int len = ((h[0] & 0xff) << 16) | ((h[1] & 0xff) << 8) | (h[2] & 0xff);
            var payload = new byte[len];
            this.in.readFully(payload);
            var f = new Frame(h[3], h[4] & 0xff, ((h[5] & 0x7f) << 24) | ((h[6] & 0xff) << 16) | ((h[7] & 0xff) << 8)
                    | (h[8] & 0xff), payload);
            if (f.type() == SETTINGS && (f.flags() & ACK) == 0) {
                frame(SETTINGS, ACK, 0, new byte[0]);
            }
            return f;
        } catch (EOFException | SocketTimeoutException | java.net.SocketException e) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        this.socket.close();
    }
}
