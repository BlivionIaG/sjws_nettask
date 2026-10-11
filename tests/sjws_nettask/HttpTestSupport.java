package sjws_nettask;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal, deliberately tolerant HTTP/1.x client used by the tests.
 * Tolerant on purpose (accepts bare LF line endings) so tests can make
 * separate, targeted assertions about strict protocol conformance.
 */
public final class HttpTestSupport {

    private HttpTestSupport() {
    }

    /** A parsed HTTP response. Header names are lower-cased. */
    public record Response(String statusLine, Map<String, String> headers, byte[] body, String rawHead) {

        public int statusCode() {
            String[] parts = statusLine.split(" ");
            return parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** Send a raw request and read one response (Content-Length framed). */
    public static Response rawRequest(int port, String request, int timeoutMs) throws IOException {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(timeoutMs);
            write(socket.getOutputStream(), request);
            return readResponse(socket.getInputStream());
        }
    }

    public static Response get(int port, String path, int timeoutMs) throws IOException {
        return rawRequest(port, "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nUser-Agent: sjws-tests\r\n\r\n", timeoutMs);
    }

    public static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * Reads the head up to the first blank line (CRLFCRLF or LFLF), then exactly
     * Content-Length body bytes. Returns null status line if the peer closed
     * without sending anything.
     */
    public static Response readResponse(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            String h = head.toString(StandardCharsets.ISO_8859_1);
            if (h.endsWith("\r\n\r\n") || h.endsWith("\n\n")) {
                break;
            }
        }
        String rawHead = head.toString(StandardCharsets.ISO_8859_1);
        if (rawHead.isEmpty()) {
            return new Response(null, Map.of(), new byte[0], rawHead);
        }
        String[] lines = rawHead.split("\r?\n");
        var headers = new LinkedHashMap<String, String>();
        for (int i = 1; i < lines.length; i++) {
            int idx = lines[i].indexOf(':');
            if (idx > 0) {
                headers.put(lines[i].substring(0, idx).trim().toLowerCase(Locale.ROOT), lines[i].substring(idx + 1).trim());
            }
        }
        byte[] body = new byte[0];
        if (headers.containsKey("content-length")) {
            int len = Integer.parseInt(headers.get("content-length"));
            body = in.readNBytes(len);
        }
        return new Response(lines[0], headers, body, rawHead);
    }
}
