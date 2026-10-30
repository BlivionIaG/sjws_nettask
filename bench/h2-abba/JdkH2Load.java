import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/**
 * java JdkH2Load.java url conns secs warm [bodyfile] — HTTP/2 GET or POST via JDK client.
 * HTTPS trusts only the PEM at {@code SJWS_BENCH_CA} (the bench self-signed cert from run-abba.sh).
 * Counts transport failures and non-2xx responses as errors. Any error in the measured window
 * invalidates the block (exit 2), so a 404/5xx workload cannot look like a clean sample.
 */
public class JdkH2Load {
  public static void main(String[] a) throws Exception {
    URI uri = URI.create(a[0]);
    int conns = Integer.parseInt(a[1]), secs = Integer.parseInt(a[2]), warm = Integer.parseInt(a[3]);
    byte[] body = a.length > 4 ? Files.readAllBytes(Path.of(a[4])) : null;
    HttpClient.Builder b = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(5));
    if ("https".equals(uri.getScheme())) {
      String ca = System.getenv("SJWS_BENCH_CA");
      if (ca == null || ca.isBlank()) {
        throw new IllegalStateException("SJWS_BENCH_CA must point at the bench PEM (no trust-all)");
      }
      b.sslContext(sslForPem(Path.of(ca)));
    }
    HttpClient client = b.build();
    HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
    if (body == null) rb.GET();
    else rb.POST(HttpRequest.BodyPublishers.ofByteArray(body));
    HttpRequest req = rb.build();
    run(client, req, conns, warm, null, null);
    var lat = new ConcurrentLinkedQueue<Long>();
    var errors = new LongAdder();
    long t0 = System.nanoTime();
    long n = run(client, req, conns, secs, lat, errors);
    double el = (System.nanoTime() - t0) / 1e9;
    long err = errors.sum();
    long[] v = lat.stream().mapToLong(Long::longValue).sorted().toArray();
    // Any error (transport or non-2xx) invalidates the block (Codex P2 / inceptor).
    if (n == 0 || err > 0) {
      System.err.printf(Locale.ROOT,
          "FAIL rps=%.0f p50=%.2fms p99=%.2fms n=%d errors=%d (listener down, non-2xx, or transport failures)%n",
          el > 0 ? n / el : 0, pct(v, .5), pct(v, .99), n, err);
      System.exit(2);
    }
    System.out.printf(Locale.ROOT, "rps=%.0f p50=%.2fms p99=%.2fms n=%d errors=%d%n",
        n / el, pct(v, .5), pct(v, .99), n, err);
  }
  static javax.net.ssl.SSLContext sslForPem(Path pem) throws Exception {
    byte[] der = Files.readAllBytes(pem);
    var certs = CertificateFactory.getInstance("X.509")
        .generateCertificates(new ByteArrayInputStream(der));
    if (certs.isEmpty()) {
      throw new IllegalStateException("no certificates in " + pem);
    }
    var ks = KeyStore.getInstance(KeyStore.getDefaultType());
    ks.load(null, null);
    int i = 0;
    for (var c : certs) {
      ks.setCertificateEntry("bench-" + (i++), c);
    }
    var tmf = javax.net.ssl.TrustManagerFactory.getInstance(
        javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(ks);
    var ctx = javax.net.ssl.SSLContext.getInstance("TLS");
    ctx.init(null, tmf.getTrustManagers(), null);
    return ctx;
  }
  static double pct(long[] v, double q) {
    return v.length == 0 ? 0 : v[Math.min(v.length - 1, (int) (q * v.length))] / 1e6;
  }
  static long run(HttpClient client, HttpRequest req, int conns, int secs, Queue<Long> lat,
      LongAdder errors) throws Exception {
    var count = new LongAdder();
    long end = System.nanoTime() + secs * 1_000_000_000L;
    try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
      var futs = new ArrayList<Future<?>>();
      for (int c = 0; c < conns; c++) {
        futs.add(ex.submit(() -> {
          while (System.nanoTime() < end) {
            long t0 = System.nanoTime();
            try {
              HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
              int code = resp.statusCode();
              if (code < 200 || code >= 300) {
                if (errors != null) {
                  errors.increment();
                } else {
                  break; // warm-up: stop this worker on first non-2xx
                }
                continue;
              }
              if (lat != null) lat.add(System.nanoTime() - t0);
              count.increment();
            } catch (Exception e) {
              if (errors != null) {
                errors.increment();
              } else {
                break; // warm-up: stop this worker on first failure
              }
            }
          }
        }));
      }
      for (var f : futs) f.get();
    }
    return count.sum();
  }
}
