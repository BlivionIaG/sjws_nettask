import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/**
 * java JdkH2Load.java url conns secs warm [bodyfile] — HTTP/2 GET or POST via JDK client.
 * HTTPS trusts only the PEM at {@code SJWS_BENCH_CA} (the bench self-signed cert from run-abba.sh).
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
    run(client, req, conns, warm, null);
    var lat = new ConcurrentLinkedQueue<Long>();
    long t0 = System.nanoTime();
    long n = run(client, req, conns, secs, lat);
    double el = (System.nanoTime() - t0) / 1e9;
    long[] v = lat.stream().mapToLong(Long::longValue).sorted().toArray();
    System.out.printf("rps=%.0f p50=%.2fms p99=%.2fms n=%d%n", n / el, pct(v, .5), pct(v, .99), n);
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
  static long run(HttpClient client, HttpRequest req, int conns, int secs, Queue<Long> lat) throws Exception {
    var count = new LongAdder();
    long end = System.nanoTime() + secs * 1_000_000_000L;
    try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
      var futs = new ArrayList<Future<?>>();
      for (int c = 0; c < conns; c++) {
        futs.add(ex.submit(() -> {
          while (System.nanoTime() < end) {
            long t0 = System.nanoTime();
            try {
              client.send(req, HttpResponse.BodyHandlers.discarding());
              if (lat != null) lat.add(System.nanoTime() - t0);
              count.increment();
            } catch (Exception e) {
              if (lat == null) break;
            }
          }
        }));
      }
      for (var f : futs) f.get();
    }
    return count.sum();
  }
}
