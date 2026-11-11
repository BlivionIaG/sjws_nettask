# Admin console

A small terminal console (plain ANSI, no extra dependency) runs the server in-process and lets an
admin see its status and configuration, tail logs, and start, stop or reload it:

    java -cp "target/sjws_nettask-0.2-SNAPSHOT.jar:target/lib/*" sjws_nettask.admin.AdminMain [server arguments]

It takes the server's option flags and `WEB_SERVER_*` variables (not a `--config` file, and not the daemon, role and bootstrap options), plus its own: `--log-file=PATH` (a file
shown in the logs view, e.g. redirected output), `--no-color`, `--no-autostart`. Commands (letter +
Enter): `s` start, `x` stop, `n` drain, `r` reload (re-reads arguments and environment; an invalid config
leaves the server running, a config that fails to bind restores the previous one), `v`/`c`/`l`/`g`
status/config/logs/metrics views, Enter refresh, `h` help, `q` quit (stops the server). The console talks to
the server only through `sjws_nettask.admin.ServerControl`, so another implementation (e.g. one
controlling a daemon) can be plugged in. `clusterStatus()` is null until a control plane attaches
a `ClusterBoard`; the status view then shows cluster requests per second, p99, errors, drifted
nodes, and per-route totals.

## Drain

`n` explains what a drain does. Typing `drain confirm` starts one (#381): the server stops
accepting, in-flight work finishes up to the HA drain timeout, and then the server closes. With HA
detached, a drain is a graceful close. The drain runs in the background, so the console stays
responsive. The status view shows the operation id (`drain-N`) as `DRAINING`, `DRAINED`, or
`FAILED` with the reason. A second `drain confirm` while one runs returns the same operation.
`s` and `r` are refused until it ends, and `x` still stops the server at once. A drain cannot be
cancelled, because HA's drain lifecycle has no cancel. Failover is not available from this
console: it stays behind HA's fencing path.

## Metrics view

`g` (or `5`) opens the Metrics view (#366). It reads Observability's metrics snapshots, which are
copies, so the view takes no hot-path locks. It shows one block per node: requests, bytes,
connections, responses by status class and by protocol, and latency p50, p99 and max. With more
than one node there is also an aggregate block. Counters and connection gauges are summed, latency
histograms are merged bucket by bucket on Observability's fixed log2 layout, and p50 and p99 come
from the merged buckets, never from averaging per-node percentiles. `Nodes reporting n/m` shows how
many known nodes answered, and missing nodes are not in the totals. `EmbeddedServerControl` reports
the local node. Fetching remote node snapshots is not wired yet.

`r` restarts the server with the re-evaluated configuration and rolls back if that cannot start. The
new server fails to start when, for example, its port cannot be bound:

```mermaid
flowchart TD
    r["r: reload"] --> ok{"New configuration valid?"}
    ok -->|no| keep["Error shown, the running<br/>server is left alone"]
    ok -->|yes| st["Stop the running<br/>server, gracefully,<br/>then start one with the<br/>new configuration"]
    st --> f{"Did it start?"}
    f -->|yes| done["Running with the<br/>new configuration<br/>log: reloaded"]
    f -->|no| rb["If a server was running,<br/>start its previous<br/>configuration again<br/>log: reload failed,<br/>restoring previous<br/>configuration. The error<br/>is shown. If that start<br/>fails too, the server<br/>stays down"]
```
