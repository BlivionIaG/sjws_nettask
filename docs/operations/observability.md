# Observability

Off by default, and off means the request path does not install an observe filter. A request then
does not call `System.nanoTime`, does not update a `LongAdder`, does not compare-and-set a latency
sample, and does not inspect the path for `/healthz` or `/metrics`. HTTP/1.1 calls the static file
handler directly. HTTP/2 and HTTP/3 do the same after one volatile read of a handler slot that stays
null until `metricsSnapshot()` arms it. A file at either path is served from the document root, the
same as a build without this feature.

Counters, the access log and the probes turn on only when something asks for them:

| Option | Environment | Default | |
|---|---|---|---|
| `--access-log=PATH` | `WEB_SERVER_ACCESS_LOG` | off | `-` writes to stdout; any other value appends to that file. Unset leaves logging off. Setting it also turns counters on |
| `--access-log-format=FMT` | `WEB_SERVER_ACCESS_LOG_FORMAT` | combined | `common`, `combined` or `json` |
| `--metrics=MODE` | `WEB_SERVER_METRICS` | off | `/metrics` in Prometheus text. `MODE` is `off`, `localhost` or `on`. A bare `--metrics` means `localhost`. Any mode other than `off` also turns counters on |
| `--no-metrics` | | | leave `/metrics` disabled |
| `--healthz` | `WEB_SERVER_HEALTHZ` | off | GET and HEAD `/healthz` answer `ok` (and counters turn on). Off: a document-root file at `/healthz` is served |
| `--no-healthz` | | | leave `/healthz` to the document root |

The admin TUI is the other switch. `EmbeddedServerControl.status()` calls `server.metricsSnapshot()`,
which is `armMetrics().snapshot()`. The first call replaces `NONE` with a recording observer and, when
the observe filter was not installed at startup, publishes that filter. The next request on each
transport enters it and starts sampling the clock and updating counters. That snapshot does not
enable `/healthz` or `/metrics` — those stay at the flags above — so a document-root `/healthz` file
is still served after the TUI has armed the counters. Reading `server.metrics()` without
`metricsSnapshot()` does not arm anything; it returns the empty `NONE` registry.

```java
Snapshot snap = server.metricsSnapshot(); // arms on the first call, then reads
snap.requests();
```

Request threads never write the log and never take a lock for it. Each response updates striped `LongAdder`s (status, protocol, a latency bucket, bytes). When logging is on, the thread formats one `byte[]` and enqueues it on a bounded lock-free queue; a single `access-log` thread writes and flushes. A full queue drops the line and counts it (`accessLogDropped` / `sjws_access_log_dropped_total`) instead of blocking the request.

**Access line.** Common and combined are the NCSA formats, with the duration in microseconds appended. A zero body is `-`. Combined adds the quoted Referer and User-Agent. JSON is one object per line: `time` (UTC), `remote`, `method`, `path` (the request-target), `protocol`, `status`, `bytes`, `duration_us`, `referer`, `user_agent`. Quotes and controls in a CLF field are replaced with `?`. The client address is the numeric IP captured at accept (no reverse DNS).

**Metrics API** (what an admin TUI reads; also rendered at `/metrics` when that endpoint is enabled):

```java
ServerMetrics metrics = server.metrics();
Snapshot snap = metrics.snapshot();
snap.requests();                  // responses so far
snap.status(200);                 // by status code
snap.protocol("HTTP/1.1");        // HTTP/1.0, HTTP/1.1, HTTP/2, HTTP/3, or other
snap.bytesSent();                 // response body bytes (not headers)
snap.activeConnections();         // HTTP/1.1 being served + established HTTP/3
snap.http1Connections();
snap.http3Connections();
snap.accessLogDropped();
snap.latency().count();
snap.latency().sumNanos();
snap.latency().maxNanos();
snap.latency().meanNanos();
snap.latency().upperBoundNanos(); // inclusive; the last bound is Long.MAX_VALUE
snap.latency().counts();          // one bucket, not cumulative
snap.latency().cumulative(i);     // Prometheus-style, through bucket i
metrics.prometheus();             // text exposition 0.0.4
```

Status and protocol are counted separately (not as a cross product) so the hot path stays a pair of array increments. `bytes` is the body size the handler committed to, recorded when the filter returns, before the socket write. `/healthz` and `/metrics` are counted like any other response once those probes are enabled. A request that never enters the chain (an HTTP/1 parse error or 408, an HTTP/2 refusal, an HTTP/3 refusal) is not counted. With observability off, nothing is counted.

**Latency histogram contract.** `sjws_http_request_duration_seconds` is a fixed log2 scale so a cluster can merge nodes by adding bucket `i` to bucket `i` (do not re-bin). The value is the time until the handler returns the response, which is before the transport writes the body. Bounds are inclusive and stable: 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192 and 16384 ms, then everything above. `Snapshot.latency().counts()` is not cumulative; index `i` is `(previous bound, upperBoundNanos()[i]]`. `upperBoundNanos()` is `ServerMetrics.LATENCY_BOUNDS_NANOS` (the last entry is `Long.MAX_VALUE`). `ServerMetrics.LATENCY_LE_LABELS` is the Prometheus `le` for that same index: `0.001`, `0.002`, `0.004`, `0.008`, `0.016`, `0.032`, `0.064`, `0.128`, `0.256`, `0.512`, `1.024`, `2.048`, `4.096`, `8.192`, `16.384`, `+Inf`. The `/metrics` histogram is cumulative at those labels; adding two nodes' series with the same `le` is the merged cumulative. Changing a bound or a label is a breaking change for that merge.

`/metrics` on `localhost` answers only a loopback peer (403 otherwise), so it stays safe on a public bind. `on` answers every client. Behind a proxy that connects from 127.0.0.1, `localhost` is the proxy, not the browser. The endpoint is not a document-root file: when it is off, a file named `metrics` is served normally.

The hook is `ObserveRequestFilter`, registered at order 0 through `RequestFilters.create().add(0, filter)` when `--access-log`, `--metrics` or `--healthz` is on. It is added before any caller filter, so among order 0 it is the outermost stage (a negative order still runs further out). The filter calls `intercept` only when `/healthz` or `/metrics` is enabled, then `complete` when `proceed` returns. While observability is off, that filter is not installed and the transports do not read the clock, update a counter, or check the path. `metricsSnapshot()` publishes the same filter on a server that started off. HTTP/1.1 sees it on the next request of an already-accepted connection; HTTP/2 and HTTP/3 install it through `Transport.armHandler`.

**Control heartbeat.** HA's membership beat stays the fixed 64-byte UDP datagram (generation and content hash, default 500 ms). Histograms do not fit in that packet, and this package does not open a socket. The control payload is a separate `NodeReport`, taken by `NodeReporter` on the heartbeat thread. The default cadence is 5 s (`NodeReporter.INTERVAL_NANOS`). A faster membership loop reuses the previous report; the sequence number advances only when a new snapshot is copied. There is no per-request call.

`NodeReport` is cumulative from process start: requests, 5xx (`errors`), 4xx (`clientErrors`), body bytes, latency sum, and the same non-cumulative log2 histogram (`ServerMetrics.LATENCY_BOUNDS_NANOS`). Health (`alive`, `ready`, `draining`) and the applied config generation plus content hash are suppliers the heartbeat thread reads at take time. `appliedVersion` 0 means no generation has been published. Per-route and per-API-key series are optional (`LabelSource`); the default is empty, so a node without gateway metrics sends no label tables. Names are configured ids, never secrets.

A control plane merges latency by index with `HistogramMerge.sum`. `HistogramMerge.percentileUpperNanos(counts, 99)` is the inclusive upper bound of the first bucket whose cumulative rank reaches 99%. It is not an interpolated quantile. Changing a bound breaks that merge. Rate is the receiver's job: the difference of two cumulative `requests` values divided by the difference of `wallMillis`. This node does not emit per-request events.
