// Starts the server in-process and replaces upstream "pool"'s endpoint set every N ms, alternating
// between two 3-endpoint sets that share one endpoint, while wrk runs against it.
// usage: java -cp target/sjws_nettask-0.2-SNAPSHOT.jar:target/lib/* Swap.java <config> <ms> <seconds> <port>...(6)
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.routing.discovery.Endpoint;
import sjws_nettask.routing.discovery.LbPolicy;
import sjws_nettask.routing.discovery.UpstreamRegistry;
import sjws_nettask.routing.discovery.UpstreamSet;

public class Swap {
    public static void main(String[] a) throws Exception {
        var loaded = ConfigLoader.load(new String[] {"--config=" + a[0], "--no-h3"}, System.getenv());
        if (!loaded.ok()) throw new IllegalStateException(loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            long ms = Long.parseLong(a[1]), end = System.nanoTime() + Long.parseLong(a[2]) * 1_000_000_000L;
            var source = UpstreamRegistry.lookup("pool");
            Endpoint[][] sets = {
                {ep(a[3]), ep(a[4]), ep(a[5])},
                {ep(a[5]), ep(a[6]), ep(a[7])}};
            long v = 1;
            while (System.nanoTime() < end) {
                source.update(new UpstreamSet("pool", v, sets[(int) (v & 1)], LbPolicy.WEIGHTED_RANDOM));
                v++;
                Thread.sleep(ms);
            }
            System.out.println("swaps=" + (v - 1));
        }
    }

    static Endpoint ep(String port) {
        return Endpoint.of("127.0.0.1", Integer.parseInt(port));
    }
}
