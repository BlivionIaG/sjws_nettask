package sjws_nettask.HTTP;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Body;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.core.Validator;

class Http1WriterTest {

    @TempDir
    Path tmp;

    private static String write(Response r, boolean keepAlive, ResponseHeaders extra) throws IOException {
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, r, keepAlive, extra);
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void canonicalNames() {
        assertEquals("Content-Type", Http1Writer.canonicalName("content-type"));
        assertEquals("ETag", Http1Writer.canonicalName("etag"));
        assertEquals("WWW-Authenticate", Http1Writer.canonicalName("www-authenticate"));
        assertEquals("Alt-Svc", Http1Writer.canonicalName("alt-svc"));
    }

    @Test
    void headLayoutAndExtraHeadersHook() throws IOException {
        var r = StaticFileHandler.redirect("/x/", false);
        String s = write(r, true, ResponseHeaders.of().add("alt-svc", "h3=\":443\"; ma=86400"));
        var lines = s.substring(0, s.indexOf("\r\n\r\n")).split("\r\n");
        assertEquals("HTTP/1.1 301 Moved Permanently", lines[0]);
        assertEquals("Server: sjws_nettask web server/0.2", lines[1]);
        assertTrue(lines[2].startsWith("Date: "));
        assertEquals("Accept-Ranges: none", lines[3]);
        assertEquals("Content-Type: text/html; charset=utf-8", lines[4]);
        assertEquals("Connection: keep-alive", lines[5]);
        assertTrue(lines[6].startsWith("Content-Length: "));
        assertEquals("Location: /x/", lines[7]);
        assertEquals("Alt-Svc: h3=\":443\"; ma=86400", lines[8]); // hook output comes last
        assertEquals(9, lines.length);
    }

    @Test
    void headAnd304SendNoBody() throws IOException {
        var head = StaticFileHandler.error(404, true);
        String s = write(head, false, ResponseHeaders.of());
        assertTrue(s.endsWith("\r\n\r\n"));
        assertTrue(s.contains("Content-Length: " + head.contentLength()));
        var nm = new Response(304, ResponseHeaders.of().add("content-type", "text/plain"), Body.Empty.INSTANCE);
        String n = write(nm, true, ResponseHeaders.of());
        assertFalse(n.contains("Content-Length"));
        assertTrue(n.endsWith("\r\n\r\n"));
    }

    @Test
    void fileBodyRegionIsSentExactly() throws IOException {
        var f = tmp.resolve("f.txt");
        Files.writeString(f, "0123456789");
        var attrs = Files.readAttributes(f, java.nio.file.attribute.BasicFileAttributes.class);
        var r = new Response(200, ResponseHeaders.of(), new Body.FileBody(f.toRealPath(), 3, 4, Validator.of(attrs)));
        String s = write(r, true, ResponseHeaders.of());
        assertTrue(s.contains("Content-Length: 4\r\n"));
        assertTrue(s.endsWith("\r\n\r\n3456"), s);
    }

    @Test
    void swappedFileIsRefusedBeforeAnythingIsWritten() throws IOException {
        var f = tmp.resolve("f.txt");
        Files.writeString(f, "original");
        var r = StaticFileHandler.fileResponse(f, false, null);
        // Replace the file (new inode) after resolution, as a rename-over would.
        var other = tmp.resolve("other.txt");
        Files.writeString(other, "REPLACED");
        Files.move(other, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, r, true);
        var wire = out.toString(StandardCharsets.ISO_8859_1);
        assertTrue(wire.startsWith("HTTP/1.1 404 "), "an error instead of the swapped file: " + wire);
        assertFalse(wire.contains("REPLACED"), "nothing of the other file");
    }

    @Test
    void symlinkSwappedInIsNotFollowed() throws IOException {
        var f = tmp.resolve("f.txt");
        Files.writeString(f, "original");
        Files.writeString(tmp.resolve("secret.txt"), "TOP-SECRET");
        var r = StaticFileHandler.fileResponse(f, false, null);
        Files.delete(f);
        Files.createSymbolicLink(f, tmp.resolve("secret.txt"));
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, r, true);
        assertTrue(out.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 404 "));
        assertFalse(out.toString(StandardCharsets.ISO_8859_1).contains("TOP-SECRET"));
    }
}
