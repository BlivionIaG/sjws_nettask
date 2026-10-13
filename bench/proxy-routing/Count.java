import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/** Bench upstream on ports base..base+n-1: 1 KiB 200s, counts hits per port. GET /__counts lists, /__reset clears. */
public class Count {
    public static void main(String[] args) throws Exception {
        int base = Integer.parseInt(args[0]);
        int n = Integer.parseInt(args[1]);
        var hits = new LongAdder[n];
        byte[] ok = new byte[1024];
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < n; i++) {
            hits[i] = new LongAdder();
            int me = i;
            var s = HttpServer.create(new InetSocketAddress("127.0.0.1", base + i), 1024);
            s.setExecutor(pool);
            s.createContext("/", ex -> {
                var path = ex.getRequestURI().getPath();
                byte[] b = ok;
                if (path.equals("/__counts")) {
                    var sb = new StringBuilder();
                    for (var h : hits) {
                        sb.append(h.sum()).append(' ');
                    }
                    b = sb.toString().trim().getBytes();
                } else if (path.equals("/__reset")) {
                    for (var h : hits) {
                        h.reset();
                    }
                    b = new byte[0];
                } else {
                    hits[me].increment();
                }
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
        }
    }
}
