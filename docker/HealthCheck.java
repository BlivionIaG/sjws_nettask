import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Container HEALTHCHECK: exit 0 when GET of the URL (default local /healthz) answers 200. */
public final class HealthCheck {
    public static void main(String[] args) {
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
