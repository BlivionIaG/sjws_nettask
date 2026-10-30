import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** java JdkH2Load.java url conns secs warm [bodyfile] — HTTP/2 GET or POST via JDK client */
public class JdkH2Load {
  public static void main(String[] a) throws Exception {
    URI uri = URI.create(a[0]);
    int conns = Integer.parseInt(a[1]), secs = Integer.parseInt(a[2]), warm = Integer.parseInt(a[3]);
    byte[] body = a.length > 4 ? Files.readAllBytes(Path.of(a[4])) : null;
    HttpClient.Builder b = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(5));
    if ("https".equals(uri.getScheme())) {
      var tm = new javax.net.ssl.X509TrustManager() {
        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a2) {}
        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a2) {}
        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
      };
      var ctx = javax.net.ssl.SSLContext.getInstance("TLS");
      ctx.init(null, new javax.net.ssl.TrustManager[]{tm}, null);
      b.sslContext(ctx);
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
