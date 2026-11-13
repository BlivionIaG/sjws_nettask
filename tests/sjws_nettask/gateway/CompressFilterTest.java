package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import sjws_nettask.core.StaticFileHandler;
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
    void synthesizes304OnlyForSuccessfulGetOrHead() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of()));
        var plain = ("hello gzip ".repeat(40)).getBytes(StandardCharsets.UTF_8);
        java.util.function.BiFunction<Integer, String, Response> run = (status, method) -> call(filter,
                new Request(method, "/", RequestHeaders.of(Map.of(
                        "accept-encoding", List.of("gzip"),
                        "if-none-match", List.of("*")))),
                (r) -> new Response(status, ResponseHeaders.of()
                        .add("content-type", "text/plain")
                        .add("etag", "\"abc\""), Body.Bytes.of(plain)));
        // Upstream errors keep their status and body.
        for (int status : new int[] {404, 412, 500}) {
            var response = run.apply(status, "GET");
            assertEquals(status, response.status(), "GET " + status);
            assertFalse(response.body() instanceof Body.Empty, "GET " + status);
        }
        // Unsafe-method results are never hidden behind a 304.
        for (var method : new String[] {"POST", "PUT", "DELETE", "PATCH"}) {
            var response = run.apply(200, method);
            assertEquals(200, response.status(), method);
            assertEquals("gzip", response.headers().get("content-encoding"), method);
        }
        assertEquals(304, run.apply(200, "GET").status());
        assertEquals(304, run.apply(200, "HEAD").status());
    }

    @Test
    void honorsRequestNoTransformBeforeCompressingOrSynthesizing304() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of()));
        var plain = "hello gzip ".repeat(40);
        var body = Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8));
        java.util.function.Function<Request, Response> inner = (r) -> new Response(200,
                ResponseHeaders.of().add("content-type", "text/plain").add("etag", "\"abc\""), body);
        var request = new Request("GET", "/", RequestHeaders.of(Map.of(
                "accept-encoding", List.of("gzip"),
                "cache-control", List.of("No-Transform"))));
        var response = call(filter, request, inner);
        assertNull(response.headers().get("content-encoding"));
        assertTrue(response.body() == body, "request no-transform must leave the body alone");
        assertEquals(200, response.status());
        // Mixed with other directives, and a wildcard validator must not become a gzip 304.
        var conditional = new Request("GET", "/", RequestHeaders.of(Map.of(
                "accept-encoding", List.of("gzip"),
                "cache-control", List.of("max-age=0", "no-transform, private"),
                "if-none-match", List.of("*"))));
        var notModified = call(filter, conditional, inner);
        assertEquals(200, notModified.status());
        assertNull(notModified.headers().get("content-encoding"));
        assertTrue(notModified.body() == body);
    }

    @Test
    void distinguishesGzipValidatorWhenUpstreamTagAlreadyEndsWithGzip() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of()));
        var plain = "hello gzip ".repeat(40);
        java.util.function.Function<Request, Response> inner = (r) -> new Response(200,
                ResponseHeaders.of().add("content-type", "text/plain").add("etag", "\"revision-gzip\""),
                Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8)));
        var compressed = call(filter, request("gzip"), inner);
        assertEquals("gzip", compressed.headers().get("content-encoding"));
        assertEquals("\"revision-gzip-gzip\"", compressed.headers().get("etag"));
        assertEquals("\"revision-gzip-gzip\"", CompressFilter.withCoding("\"revision-gzip\"", "gzip"));
        assertEquals("W/\"revision-gzip-gzip\"", CompressFilter.withCoding("W/\"revision-gzip\"", "gzip"));
        var hit = call(filter, new Request("GET", "/", RequestHeaders.of(Map.of(
                "accept-encoding", List.of("gzip"),
                "if-none-match", List.of("\"revision-gzip-gzip\"")))), inner);
        assertEquals(304, hit.status());
        assertEquals("\"revision-gzip-gzip\"", hit.headers().get("etag"));
        var miss = call(filter, new Request("GET", "/", RequestHeaders.of(Map.of(
                "accept-encoding", List.of("gzip"),
                "if-none-match", List.of("\"revision-gzip\"")))), inner);
        assertEquals(200, miss.status());
        assertEquals("gzip", miss.headers().get("content-encoding"));
        assertEquals("\"revision-gzip-gzip\"", miss.headers().get("etag"));
    }

    @Test
    void negotiatesAcrossRepeatedAcceptEncodingFields() throws Exception {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of()));
        var plain = "hello gzip ".repeat(40);
        var body = Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8));
        java.util.function.Function<Request, Response> inner = (r) -> new Response(200,
                ResponseHeaders.of().add("content-type", "text/plain"), body);
        // A later field's gzip;q=0 overrides the earlier wildcard.
        var refused = call(filter, new Request("GET", "/", RequestHeaders.of(Map.of(
                "accept-encoding", List.of("*", "gzip;q=0")))), inner);
        assertNull(refused.headers().get("content-encoding"));
        assertTrue(refused.body() == body);
        assertEquals("Accept-Encoding", refused.headers().get("vary"));
        // gzip offered only in a later field is still selected.
        var later = call(filter, new Request("GET", "/", RequestHeaders.of(Map.of(
                "accept-encoding", List.of("br", "gzip")))), inner);
        assertEquals("gzip", later.headers().get("content-encoding"));
        assertEquals(plain, gunzip(later));
        // identity;q=0 in a later field forces gzip even when it does not shrink the body.
        var noise = new byte[300];
        new java.util.Random(7).nextBytes(noise);
        var incompressible = call(filter, new Request("GET", "/", RequestHeaders.of(Map.of(
                "accept-encoding", List.of("gzip", "identity;q=0")))), (r) -> new Response(200,
                        ResponseHeaders.of().add("content-type", "text/plain"), Body.Bytes.of(noise)));
        assertEquals("gzip", incompressible.headers().get("content-encoding"));
        assertEquals("*,gzip;q=0", CompressFilter.acceptEncoding(RequestHeaders.of(Map.of(
                "accept-encoding", List.of("*", "gzip;q=0")))));
        assertEquals("gzip", CompressFilter.acceptEncoding(RequestHeaders.of(Map.of(
                "accept-encoding", List.of("gzip")))));
        assertNull(CompressFilter.acceptEncoding(RequestHeaders.of(Map.of())));
    }

    @Test
    void dropsDigestHeadersInvalidatedByCompression() throws Exception {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of()));
        var plain = "hello gzip ".repeat(40);
        java.util.function.Function<Request, Response> inner = (r) -> new Response(200, ResponseHeaders.of()
                .add("content-type", "text/plain")
                .add("etag", "\"abc\"")
                .add("content-digest", "sha-256=:AAAA:")
                .add("repr-digest", "sha-256=:BBBB:")
                .add("digest", "SHA-256=CCCC")
                .add("content-md5", "DDDD"),
                Body.Bytes.of(plain.getBytes(StandardCharsets.UTF_8)));
        var compressed = call(filter, request("gzip"), inner);
        assertEquals("gzip", compressed.headers().get("content-encoding"));
        assertEquals(plain, gunzip(compressed));
        for (var name : List.of("content-digest", "repr-digest", "digest", "content-md5")) {
            assertNull(compressed.headers().get(name), name + " must not describe the identity bytes");
        }
        var notModified = call(filter, new Request("GET", "/", RequestHeaders.of(Map.of(
                "accept-encoding", List.of("gzip"),
                "if-none-match", List.of("\"abc-gzip\"")))), inner);
        assertEquals(304, notModified.status());
        assertNull(notModified.headers().get("content-digest"));
        assertNull(notModified.headers().get("repr-digest"));
        // An identity response keeps its digests.
        var identity = call(filter, request(null), inner);
        assertEquals("sha-256=:AAAA:", identity.headers().get("content-digest"));
        assertEquals("sha-256=:BBBB:", identity.headers().get("repr-digest"));
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

    @Test
    void emitsGzipWhenIdentityIsRefusedEvenIfLarger() throws Exception {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("min_size", "1")));
        byte[] plain = null;
        byte[] compressed = null;
        for (int seed = 1; seed <= 50; seed++) {
            var candidate = new byte[300];
            new java.util.Random(seed).nextBytes(candidate);
            var out = CompressFilter.gzipBytes(candidate);
            if (out.length >= candidate.length) {
                plain = candidate;
                compressed = out;
                break;
            }
        }
        if (plain == null) {
            fail("need an incompressible 300-byte payload");
        }
        var inner = new Response(200, ResponseHeaders.of().add("content-type", "text/plain"), Body.Bytes.of(plain));
        var refused = call(filter, request("gzip, identity;q=0"), inner);
        assertEquals("gzip", refused.headers().get("content-encoding"));
        assertEquals(compressed.length, ((Body.Bytes) refused.body()).length());
        assertFalse(CompressFilter.acceptsIdentity("gzip, identity;q=0"));
        assertFalse(CompressFilter.acceptsIdentity("gzip, *;q=0"));
        assertTrue(CompressFilter.acceptsIdentity("gzip"));
        assertTrue(CompressFilter.acceptsIdentity("gzip, identity;q=0.1"));
        var fallback = call(filter, request("gzip"), inner);
        assertNull(fallback.headers().get("content-encoding"), "identity is still allowed when not refused");
        assertEquals(plain.length, ((Body.Bytes) fallback.body()).length());
        // *;q=0 without identity also forbids the identity fallback
        var star = call(filter, request("gzip, *;q=0"), inner);
        assertEquals("gzip", star.headers().get("content-encoding"));
    }

    @Test
    void preservesGeneratedMarkerWhenCopyingResponses() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("min_size", "1")));
        var generated = StaticFileHandler.error(404, false);
        assertTrue(generated.generated());
        var identity = call(filter, request(null), generated);
        assertTrue(identity.generated(), "adding Vary must keep the generated marker");
        assertEquals("Accept-Encoding", identity.headers().get("vary"));
        assertEquals(404, identity.status());
        var gzipped = call(filter, request("gzip"), generated);
        assertTrue(gzipped.generated(), "compressing a generated error must keep the marker");
        assertEquals("gzip", gzipped.headers().get("content-encoding"));
        assertEquals(404, gzipped.status());
        var page = new Response(200, ResponseHeaders.of().add("content-type", "text/html; charset=UTF-8")
                .add("etag", "\"err\""), Body.Bytes.of("not found".repeat(4).getBytes(StandardCharsets.UTF_8)),
                false, false, true);
        var notModified = call(filter, new Request("GET", "/", RequestHeaders.of(Map.of(
                "accept-encoding", List.of("gzip"),
                "if-none-match", List.of(CompressFilter.withCoding("\"err\"", "gzip"))))), page);
        assertEquals(304, notModified.status());
        assertTrue(notModified.generated(), "a gzip 304 must keep the generated marker");
    }

    @Test
    void problemConversionAfterCompressionDropsTheGzipMetadata() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("min_size", "1")));
        var problems = new sjws_nettask.routing.ProblemErrorHandler(
                (request) -> filter.filter(request, (r) -> StaticFileHandler.error(404, false)), "/", null, null);
        var response = problems.handle(request("gzip"));
        assertEquals(404, response.status());
        assertEquals("application/problem+json", response.headers().get("content-type"));
        assertNull(response.headers().get("content-encoding"), "the JSON body is not gzip");
        assertNull(response.headers().get("etag"));
        var data = ((Body.Bytes) response.body()).data().duplicate();
        var bytes = new byte[data.remaining()];
        data.get(bytes);
        var json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{\"type\":"), json);
        assertEquals("Accept-Encoding", response.headers().get("vary"));
    }

    @Test
    void enforcesTheDeclaredStreamLengthWhileBuffering() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("min_size", "1")));
        var shortBody = "hello".getBytes(StandardCharsets.UTF_8);
        var truncated = call(filter, request("gzip"), (r) -> new Response(200,
                ResponseHeaders.of().add("content-type", "text/plain"), new Body.Stream(32, new BytesSource(shortBody))));
        assertEquals(500, truncated.status(), "a stream that ends early must not become a complete 200");
        assertNull(truncated.headers().get("content-encoding"));
        var longBody = "hello gzip ".repeat(40).getBytes(StandardCharsets.UTF_8);
        var oversized = call(filter, request("gzip"), (r) -> new Response(200,
                ResponseHeaders.of().add("content-type", "text/plain"), new Body.Stream(5, new BytesSource(longBody))));
        assertEquals(500, oversized.status(), "a stream longer than declared must not be buffered");
        assertNull(oversized.headers().get("content-encoding"));
        var exact = call(filter, request("gzip"), (r) -> new Response(200,
                ResponseHeaders.of().add("content-type", "text/plain"),
                new Body.Stream(longBody.length, new BytesSource(longBody))));
        assertEquals(200, exact.status());
        assertEquals("gzip", exact.headers().get("content-encoding"));
    }

    @Test
    void leavesAResponseAloneWhenAnyContentEncodingFieldIsNotIdentity() {
        var filter = CompressFilter.create(GatewayTestSupport.spec("compress", Map.of("min_size", "1")));
        var body = Body.Bytes.of("hello gzip ".repeat(40).getBytes(StandardCharsets.UTF_8));
        var upstream = new Response(200, ResponseHeaders.of().add("content-type", "text/plain")
                .add("content-encoding", "identity").add("content-encoding", "gzip"), body);
        var response = call(filter, request("gzip, identity;q=0"), upstream);
        assertTrue(response == upstream, "an already encoded body must be passed through unchanged");
        assertEquals(List.of("identity", "gzip"), response.headers().entries().stream()
                .filter(e -> "content-encoding".equals(e.getKey())).map(Map.Entry::getValue).toList());
        var identityOnly = new Response(200, ResponseHeaders.of().add("content-type", "text/plain")
                .add("content-encoding", "identity").add("content-encoding", "Identity"), body);
        assertEquals("gzip", call(filter, request("gzip"), identityOnly).headers().get("content-encoding"));
    }
}
