package sjws_nettask.gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import sjws_nettask.core.Body;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;

final class GatewayTestSupport {

    private GatewayTestSupport() {
    }

    static MiddlewareSpec spec(String name, Map<String, String> options) {
        return MiddlewareSpec.of(name, 0, null, options);
    }

    static Request request(String method, String target, Map<String, List<String>> headers) {
        return new Request(method, target, RequestHeaders.of(headers));
    }

    static Response ok(Request request) {
        return new Response(200, ResponseHeaders.of(), Body.Bytes.of("ok".getBytes(StandardCharsets.UTF_8)),
                request.headOnly());
    }

    static Response call(RequestFilter filter, Request request) {
        return filter.filter(request, GatewayTestSupport::ok);
    }

    static String text(Response response) {
        var data = ((Body.Bytes) response.body()).data().duplicate();
        var bytes = new byte[data.remaining()];
        data.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
