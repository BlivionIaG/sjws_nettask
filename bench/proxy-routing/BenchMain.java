import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.routing.RoutingConfig;
import sjws_nettask.routing.RoutingConfig.RouteSpec;
import sjws_nettask.routing.RoutingConfig.Upstream;

/** args: port root mode [n|upstreamUrl]; mode = off | routes | proxy */
public class BenchMain {
    public static void main(String[] a) throws Exception {
        var config = ServerConfig.builder(Path.of(a[1])).port(Integer.parseInt(a[0])).h3Enabled(false).build();
        RoutingConfig rc = RoutingConfig.DISABLED;
        if (a[2].equals("routes")) {
            int n = Integer.parseInt(a[3]);
            var routes = new ArrayList<RouteSpec>();
            for (int i = 0; i < n - 1; i++) routes.add(new RouteSpec("h" + i + ".test", "/r" + i, null, null, null, true, null));
            routes.add(new RouteSpec(null, "/", null, null, null, true, null)); // last route matches
            rc = new RoutingConfig(true, null, List.of(), Map.of(), routes);
        } else if (a[2].equals("proxy")) {
            rc = new RoutingConfig(true, null, List.of(), Map.of("up", new Upstream(URI.create(a[3]))),
                    List.of(new RouteSpec(null, "/", null, "up", null, false, null)));
        }
        var s = SjwsServer.start(config, null, null, rc.terminal());
        System.out.println("READY " + s.tcpPort());
        Thread.currentThread().join();
    }
}
