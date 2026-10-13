import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Container HEALTHCHECK: exit 0 when GET of the URL (default local /healthz) answers 200.
 * {@code --quic} instead exits 0 when Netty's QUIC natives load (needs lib/ on the class path).
 */
public final class HealthCheck {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--quic")) {
            try {
                Class<?> quic = Class.forName("io.netty.handler.codec.quic.Quic");
                boolean ok = (Boolean) quic.getMethod("isAvailable").invoke(null);
                if (!ok) {
                    System.err.println("quic: " + quic.getMethod("unavailabilityCause").invoke(null));
                }
                System.out.println("quic natives available: " + ok);
                System.exit(ok ? 0 : 1);
            } catch (ReflectiveOperationException e) {
                System.err.println("quic: " + e);
                System.exit(1);
            }
        }
        String url = args.length > 0 ? args[0] : "http://127.0.0.1:8080/healthz";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<Void> r = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            System.exit(r.statusCode() == 200 ? 0 : 1);
        } catch (Exception e) {
            System.err.println("healthcheck: " + e);
            System.exit(1);
        }
    }
}
