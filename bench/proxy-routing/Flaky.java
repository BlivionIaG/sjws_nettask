import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/** Bench upstream: answers 1 KiB with 200, or 503 with probability {@code args[1]} percent. */
public class Flaky {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int pct = Integer.parseInt(args[1]);
        byte[] ok = new byte[1024];
        byte[] down = "down".getBytes();
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 1024);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext("/", ex -> {
            boolean fail = ThreadLocalRandom.current().nextInt(100) < pct;
            byte[] b = fail ? down : ok;
            ex.sendResponseHeaders(fail ? 503 : 200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
    }
}
