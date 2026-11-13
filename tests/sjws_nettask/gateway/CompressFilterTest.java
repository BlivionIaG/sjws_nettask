package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.Validator;

class CompressFilterTest {

    @TempDir
    java.nio.file.Path root;

    @Test
    void gzipsALargeTextBodyAndRewritesHeaders() throws Exception {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of()));
        var plain = "hello gzip ".repeat(40);
        var response = call(filter, request("gzip"), (req) -> {
            var headers = ResponseHeaders.of()
                    .add("content-type", "text/plain; charset=utf-8")
                    .add("etag", "\"abc\"")
                    .add("accept-ranges", "bytes")
                    .add("content-length", String.valueOf(plain.length()));
            return new Response(200, headers, Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8)));
        });
        assertEquals(200, response.status());
        assertEquals("gzip", response.headers().get("content-encoding"));
        assertEquals("Accept-Encoding", response.headers().get("vary"));
        assertEquals("\"abc-gzip\"", response.headers().get("etag"));
        assertNull(response.headers().get("accept-ranges"));
        assertNull(response.headers().get("content-length"));
        assertEquals(plain, gunzip(response));
        assertTrue(response.body().length() < plain.length());
    }

    @Test
    void skipsWhenTheClientDoesNotOfferGzipOrTheBodyShouldNotChange() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of()));
        var plain = "hello gzip ".repeat(40);
        assertNull(call(filter, request(null), text(plain, "text/plain")).headers().get("content-encoding"));
        assertNull(call(filter, request("identity"), text(plain, "text/plain")).headers().get("content-encoding"));
        assertNull(call(filter, request("gzip;q=0, *;q=0"), text(plain, "text/plain")).headers().get("content-encoding"));
        assertNull(call(filter, request("gzip"), text("short", "text/plain")).headers().get("content-encoding"));
        assertNull(call(filter, request("gzip"), text(plain, "image/png")).headers().get("content-encoding"));
        var already = new Response(200, ResponseHeaders.of().add("content-type", "text/plain")
                .add("content-encoding", "br"), Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8)));
        assertEquals("br", call(filter, request("gzip"), (r) -> already).headers().get("content-encoding"));
        var partial = new Response(206, ResponseHeaders.of().add("content-type", "text/plain")
                .add("content-range", "bytes 0-10/100"), Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8)));
        assertNull(call(filter, request("gzip"), (r) -> partial).headers().get("content-encoding"));
    }

    @Test
    void typeListsAndEncodingsConfig() {
        var onlyJson = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("types", "application/json")));
        var plain = "hello gzip ".repeat(40);
        assertNull(call(onlyJson, request("gzip"), text(plain, "text/plain")).headers().get("content-encoding"));
        assertEquals("gzip", call(onlyJson, request("gzip"), text(plain, "application/json"))
                .headers().get("content-encoding"));
        var except = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("except", "text/csv")));
        assertNull(call(except, request("gzip"), text(plain, "text/csv")).headers().get("content-encoding"));
        assertThrows(IllegalArgumentException.class,
                () -> CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("encodings", "br,gzip"))));
        assertThrows(IllegalArgumentException.class,
                () -> CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("min_size", "-1"))));
    }

    @Test
    void acceptsStarAndXGzipAndMergesVary() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of()));
        var plain = "hello gzip ".repeat(40);
        assertEquals("gzip", call(filter, request("*"), text(plain, "text/html")).headers().get("content-encoding"));
        assertEquals("gzip", call(filter, request("x-gzip"), text(plain, "text/html")).headers().get("content-encoding"));
        var merged = call(filter, request("gzip"), (r) -> {
            var headers = ResponseHeaders.of().add("content-type", "text/html").add("vary", "Accept, Origin");
            return new Response(200, headers, Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8)));
        });
        assertEquals("Accept, Origin, Accept-Encoding", merged.headers().get("vary"));
        var multi = call(filter, request("gzip"), (r) -> {
            var headers = ResponseHeaders.of().add("content-type", "text/html")
                    .add("vary", "Accept-Language").add("vary", "Origin");
            return new Response(200, headers, Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8)));
        });
        assertEquals("Accept-Language, Origin, Accept-Encoding", multi.headers().get("vary"));
        int varyFields = 0;
        for (var entry : multi.headers().entries()) {
            if ("vary".equals(entry.getKey())) {
                varyFields++;
            }
        }
        assertEquals(1, varyFields);
        var star = call(filter, request("gzip"), (r) -> {
            var headers = ResponseHeaders.of().add("content-type", "text/html")
                    .add("vary", "*").add("vary", "Origin");
            return new Response(200, headers, Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8)));
        });
        assertEquals("*", star.headers().get("vary"));
    }

    @Test
    void doesNotBufferBytesLargerThanMaxBuffer() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress",
                Map.of("min_size", "8", "max_buffer", "64")));
        var plain = "hello gzip ".repeat(40); // well above 64 bytes, would compress
        var body = Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8));
        var response = call(filter, request("gzip"), (r) -> {
            var headers = ResponseHeaders.of().add("content-type", "text/plain");
            return new Response(200, headers, body);
        });
        assertNull(response.headers().get("content-encoding"));
        assertTrue(response.body() == body, "bytes above max_buffer must be left alone");
    }

    @Test
    void leavesMetadataOnlyHeadStreamsAlone() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("min_size", "8")));
        var length = "hello gzip ".repeat(40).getBytes(StandardCharsets.UTF_8).length;
        var source = new Body.Stream.Source() {
            @Override
            public java.nio.ByteBuffer next() {
                throw new AssertionError("HEAD metadata stream must not be consumed");
            }

            @Override
            public void close() {
            }
        };
        var stream = new Body.Stream(length, source);
        var response = call(filter, new Request("HEAD", "/",
                sjws_nettask.core.RequestHeaders.of(java.util.Map.of("accept-encoding", java.util.List.of("gzip")))),
                (r) -> {
                    var headers = ResponseHeaders.of().add("content-type", "text/plain");
                    return new Response(200, headers, stream, true);
                });
        assertNull(response.headers().get("content-encoding"));
        assertTrue(response.body() == stream);
        assertEquals(length, response.body().length());
        assertTrue(response.headOnly());
    }

    @Test
    void buffersAKnownLengthStreamAndLeavesUnknownStreamsAlone() throws Exception {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("min_size", "8")));
        var plain = "hello gzip ".repeat(40).getBytes(StandardCharsets.UTF_8);
        var boxed = call(filter, request("gzip"), (r) -> {
            var headers = ResponseHeaders.of().add("content-type", "text/plain");
            return new Response(200, headers, new Body.Stream(plain.length, new BytesSource(plain)));
        });
        assertEquals("gzip", boxed.headers().get("content-encoding"));
        assertInstanceOf(Body.Bytes.class, boxed.body());
        assertEquals(new String(plain, StandardCharsets.UTF_8), gunzip(boxed));

        var unknown = new Body.Stream(-1, new BytesSource(plain));
        var left = call(filter, request("gzip"), (r) -> {
            var headers = ResponseHeaders.of().add("content-type", "text/plain");
            return new Response(200, headers, unknown);
        });
        assertNull(left.headers().get("content-encoding"));
        assertTrue(left.body() == unknown);
    }

    @Test
    void anIncompressibleStreamIsReturnedAsBytesWithoutReusingTheSource() throws Exception {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("min_size", "8", "types", "text/plain")));
        var plain = new byte[512];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) ((i * 47) ^ (i * 13));
        }
        var response = call(filter, request("gzip"), (r) -> {
            var headers = ResponseHeaders.of().add("content-type", "text/plain");
            return new Response(200, headers, new Body.Stream(plain.length, new BytesSource(plain)));
        });
        assertInstanceOf(Body.Bytes.class, response.body(), "consumed stream must not be returned");
        if (response.headers().get("content-encoding") == null) {
            var data = ((Body.Bytes) response.body()).data().duplicate();
            var got = new byte[data.remaining()];
            data.get(got);
            org.junit.jupiter.api.Assertions.assertArrayEquals(plain, got);
        } else {
            assertEquals("gzip", response.headers().get("content-encoding"));
            var data = ((Body.Bytes) response.body()).data().duplicate();
            var gz = new byte[data.remaining()];
            data.get(gz);
            try (var in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                org.junit.jupiter.api.Assertions.assertArrayEquals(plain, in.readAllBytes());
            }
        }
    }

    @Test
    void streamsAFileLargerThanTheBufferCap() throws Exception {
        var file = this.root.resolve("big.txt");
        var plain = "hello gzip file body\n".repeat(200);
        Files.writeString(file, plain);
        var attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
        var body = new Body.FileBody(file.toRealPath(), 0, attrs.size(), Validator.of(attrs), this.root.toRealPath());
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("min_size", "8", "max_buffer", "64")));
        var response = call(filter, request("gzip"), (r) -> {
            var headers = ResponseHeaders.of().add("content-type", "text/plain");
            return new Response(200, headers, body);
        });
        assertEquals("gzip", response.headers().get("content-encoding"));
        assertInstanceOf(Body.Stream.class, response.body());
        assertEquals(-1, response.body().length());
        assertEquals(plain, gunzipStream((Body.Stream) response.body()));
    }

    @Test
    void addsVaryToEligibleIdentityResponses() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of()));
        var plain = "hello gzip ".repeat(40);
        var identity = call(filter, request(null), text(plain, "text/plain"));
        assertNull(identity.headers().get("content-encoding"));
        assertEquals("Accept-Encoding", identity.headers().get("vary"));
        var refused = call(filter, request("gzip;q=0"), text(plain, "text/plain"));
        assertEquals("Accept-Encoding", refused.headers().get("vary"));
        var small = call(filter, request(null), text("short", "text/plain"));
        assertNull(small.headers().get("vary"), "ineligible responses must not advertise negotiation");
        var merged = call(filter, request("identity"), (r) -> {
            var headers = ResponseHeaders.of().add("content-type", "text/plain").add("vary", "Origin");
            return new Response(200, headers, Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8)));
        });
        assertEquals("Origin, Accept-Encoding", merged.headers().get("vary"));
        int varyFields = 0;
        for (var entry : merged.headers().entries()) {
            if ("vary".equals(entry.getKey())) {
                varyFields++;
            }
        }
        assertEquals(1, varyFields);
    }

    @Test
    void leavesNoTransformResponsesUncompressed() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of()));
        var plain = "hello gzip ".repeat(40).getBytes(StandardCharsets.UTF_8);
        var body = Body.Bytes.of(plain);
        var response = call(filter, request("gzip"), (r) -> {
            var headers = ResponseHeaders.of()
                    .add("content-type", "text/plain")
                    .add("cache-control", "public, max-age=60")
                    .add("cache-control", "No-Transform");
            return new Response(200, headers, body);
        });
        assertNull(response.headers().get("content-encoding"));
        assertTrue(response.body() == body, "no-transform must leave the body alone");
        // Same directive on a single field, mixed with others.
        var single = call(filter, request("gzip"), (r) -> {
            var headers = ResponseHeaders.of()
                    .add("content-type", "text/plain")
                    .add("cache-control", "max-age=10, no-transform, public");
            return new Response(200, headers, body);
        });
        assertNull(single.headers().get("content-encoding"));
        assertTrue(single.body() == body);
    }

    @Test
    void compressedEtagOnIfNoneMatchReturns304() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of()));
        var plain = "hello gzip ".repeat(40);
        // Inner handler compares If-None-Match only to the unsuffixed ETag, like FileMeta.notModified().
        java.util.function.Function<Request, Response> inner = (r) -> {
            var etag = "\"abc\"";
            if (r.headers().ifNoneMatch() != null
                    && r.headers().ifNoneMatch().contains(etag)
                    && !r.headers().ifNoneMatch().contains("-gzip")) {
                return new Response(304, ResponseHeaders.of().add("etag", etag), Body.Empty.INSTANCE);
            }
            var headers = ResponseHeaders.of()
                    .add("content-type", "text/plain")
                    .add("etag", etag)
                    .add("last-modified", "Wed, 11 Nov 2026 17:00:00 GMT");
            return new Response(200, headers, Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8)));
        };
        var conditional = new Request("GET", "/", RequestHeaders.of(Map.of(
                "accept-encoding", List.of("gzip"),
                "if-none-match", List.of("\"abc-gzip\""))));
        var response = call(filter, conditional, inner);
        assertEquals(304, response.status());
        assertEquals("\"abc-gzip\"", response.headers().get("etag"));
        assertEquals("gzip", response.headers().get("content-encoding"));
        assertEquals("Accept-Encoding", response.headers().get("vary"));
        assertTrue(response.body() instanceof Body.Empty);
        // A body we would not compress must not be turned into 304 just because the client
        // sent a gzip-suffixed validator.
        var small = call(filter, new Request("GET", "/", RequestHeaders.of(Map.of(
                "accept-encoding", List.of("gzip"),
                "if-none-match", List.of("\"x-gzip\"")))),
                (r) -> new Response(200, ResponseHeaders.of().add("content-type", "text/plain").add("etag", "\"x\""),
                        Body.Bytes.of("short".getBytes(StandardCharsets.UTF_8))));
        assertEquals(200, small.status());
        assertNull(small.headers().get("content-encoding"));
        assertEquals("\"x\"", small.headers().get("etag"));
    }

    @Test
    void factoryIsInstalled() throws Exception {
        var filters = GatewayFilters.install(RequestFilters.create())
                .add(GatewayTestSupport.spec("compress", Map.of()));
        var plain = "hello gzip ".repeat(40);
        var response = filters.wrapping((r) -> {
            var headers = ResponseHeaders.of().add("content-type", "text/plain");
            return new Response(200, headers, Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8)));
        }).handle(request("gzip"));
        assertEquals("gzip", response.headers().get("content-encoding"));
        assertEquals(plain, gunzip(response));
    }

    private static Response call(sjws_nettask.core.RequestFilter filter, Request request,
            java.util.function.Function<Request, Response> inner) {
        return filter.filter(request, inner::apply);
    }

    private static Response call(sjws_nettask.core.RequestFilter filter, Request request, Response inner) {
        return call(filter, request, (r) -> inner);
    }

    private static Request request(String acceptEncoding) {
        if (acceptEncoding == null) {
            return new Request("GET", "/");
        }
        return new Request("GET", "/", RequestHeaders.of(Map.of("accept-encoding", List.of(acceptEncoding))));
    }

    private static Response text(String plain, String type) {
        return new Response(200, ResponseHeaders.of().add("content-type", type),
                Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static String gunzip(Response response) throws IOException {
        var data = ((Body.Bytes) response.body()).data().duplicate();
        var bytes = new byte[data.remaining()];
        data.get(bytes);
        try (var in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String gunzipStream(Body.Stream stream) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        try (var source = stream.source()) {
            for (var data = source.next(); data != null; data = source.next()) {
                var bytes = new byte[data.remaining()];
                data.get(bytes);
                out.write(bytes);
            }
        }
        try (var in = new GZIPInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class BytesSource implements Body.Stream.Source {
        private final byte[] data;
        private boolean done;

        BytesSource(byte[] data) {
            this.data = data;
        }

        @Override
        public ByteBuffer next() {
            if (this.done) {
                return null;
            }
            this.done = true;
            return ByteBuffer.wrap(this.data);
        }

        @Override
        public void close() {
        }
    }
}
