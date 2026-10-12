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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sjws_nettask.http1.Http1Writer;

/** Status-line reason phrases, error page titles and MIME/charset rendering. */
class RenderingTest {

    @ParameterizedTest
    @CsvSource({"100,Continue", "101,Switching Protocols", "200,OK", "201,Created", "202,Accepted", "203,Non-Authoritative Information", "204,No Content", "205,Reset Content", "206,Partial Content", "300,Multiple Choices", "301,Moved Permanently", "302,Found", "303,See Other", "304,Not Modified", "305,Use Proxy", "307,Temporary Redirect", "308,Permanent Redirect", "400,Bad Request", "401,Unauthorized", "402,Payment Required", "403,Forbidden", "404,Not Found", "405,Method Not Allowed", "406,Not Acceptable", "407,Proxy Authentication Required", "408,Request Timeout", "409,Conflict", "410,Gone", "411,Length Required", "412,Precondition Failed", "413,Content Too Large", "414,URI Too Long", "415,Unsupported Media Type", "416,Range Not Satisfiable", "417,Expectation Failed", "421,Misdirected Request", "422,Unprocessable Content", "426,Upgrade Required", "428,Precondition Required", "429,Too Many Requests", "431,Request Header Fields Too Large", "500,Internal Server Error", "501,Not Implemented", "502,Bad Gateway", "503,Service Unavailable", "504,Gateway Timeout", "505,HTTP Version Not Supported"})
    void registeredCodesHaveTheirReasonPhrase(int code, String phrase) {
        assertEquals(phrase, StaticFileHandler.reasonPhrase(code));
        var head = Http1Writer.head(code, ResponseHeaders.of(), false, 0, true, ResponseHeaders.of(), Instant.EPOCH);
        assertTrue(head.startsWith("HTTP/1.1 " + code + " " + phrase + "\r\n"), head);
    }

    @Test
    void unregisteredCodeHasEmptyReasonNotUnknown() {
        assertEquals("", StaticFileHandler.reasonPhrase(599));
        var head = Http1Writer.head(599, ResponseHeaders.of(), true, 0, false, ResponseHeaders.of(), Instant.EPOCH);
        assertTrue(head.startsWith("HTTP/1.1 599 \r\n"), head);
    }

    @Test
    void errorPageTitles() {
        assertTrue(body(StaticFileHandler.error(503, false)).contains("<title>503 Service Unavailable</title>"));
        var unknown = body(StaticFileHandler.error(599, false));
        assertTrue(unknown.contains("<title>599</title>") && unknown.contains("<h1>599</h1>"), unknown);
    }

    @Test
    void headErrorHasLengthButNoBody() {
        var r = StaticFileHandler.error(404, true);
        assertFalse(r.sendsBody());
        assertTrue(r.contentLength() > 0);
    }

    @ParameterizedTest
    @CsvSource({"a.md,text/markdown; charset=utf-8", "a.CSV,text/csv; charset=utf-8",
        "feed.xml,application/xml; charset=utf-8", "app.js.map,application/json; charset=utf-8",
        "site.webmanifest,application/manifest+json; charset=utf-8", "p.avif,image/avif", "p.bmp,image/bmp",
        "f.ttf,font/ttf", "f.otf,font/otf", "s.wav,audio/wav", "x.tar.gz,application/gzip", "x.tar,application/x-tar"})
    void mimeAndCharset(String file, String contentType) {
        assertEquals(contentType, Mime.contentType(Mime.of(file)));
    }

    private static String body(Response r) {
        var b = (Body.Bytes) r.body();
        var bytes = new byte[(int) b.length()];
        b.data().duplicate().get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
