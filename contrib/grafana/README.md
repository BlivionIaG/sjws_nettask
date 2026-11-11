# Grafana dashboard for sjws

`sjws.json` is a Grafana dashboard for one process, plus the cluster and gateway series when those sources are attached, and the messaging and swarm-stream series when those exporters are attached. `prometheus.yml` is a sample scrape config. Prometheus stores the text exposition from `GET /metrics` (`text/plain; version=0.0.4; charset=utf-8`).

```
prometheus --config.file=contrib/grafana/prometheus.yml
```

In Grafana: Dashboards, Import, upload `sjws.json`, and choose the Prometheus datasource. `--metrics=localhost` answers only a loopback client, so the scraper has to run on the same host. `--metrics=on` answers every client.

The request path does not change when these files are added. Counters stay off until `/metrics`, the access log, `/healthz`, or an admin snapshot turns them on.

## Panels

| Panel | PromQL |
|---|---|
| Request rate | `sum(rate(sjws_http_requests_total[1m]))` |
| Latency p50 | `histogram_quantile(0.50, sum by (le) (rate(sjws_http_request_duration_seconds_bucket[5m])))` |
| Latency p99 | `histogram_quantile(0.99, sum by (le) (rate(sjws_http_request_duration_seconds_bucket[5m])))` |
| Errors by status | `sum by (status) (rate(sjws_http_requests_total{status=~"[45].."}[1m]))` |
| Response bytes | `sum(rate(sjws_http_response_bytes_total[1m]))` |
| Connections | `sum by (protocol) (sjws_connections_active)` |
| Access-log drops | `sum(rate(sjws_access_log_dropped_total[5m]))` |
| Cluster request rate | `sum(sjws_cluster_requests_per_second)` |
| Cluster p99 | `max(sjws_cluster_request_p99_seconds)` |
| Config drift | `sum(sjws_cluster_config_drifted_nodes)` |
| Per-node request rate | `sum by (node) (sjws_cluster_node_requests_per_second)` |
| Route request rate | `sum by (route) (rate(sjws_gateway_route_requests_total[1m]))` |
| Route p99 | `sum by (route) (sjws_gateway_route_request_p99_seconds)` |
| API-key errors | `sum by (api_key) (rate(sjws_gateway_api_key_errors_total[1m]))` |
| Cluster route errors | `sum by (route) (sjws_cluster_route_errors)` |
| Cluster API-key requests | `sum by (api_key) (sjws_cluster_api_key_requests)` |
| Messaging lag | `sum by (topic) (sjws_messaging_lag_messages)` and `sum by (topic) (sjws_messaging_lag_offsets)` |
| Messaging in-flight | `sum by (topic) (sjws_messaging_inflight)` |
| Messaging DLQ depth | `sum by (topic) (sjws_messaging_dlq_depth)` |
| Messaging oldest pending age | `max by (topic) (sjws_messaging_oldest_pending_age_ms)` |
| Messaging produce latency | `histogram_quantile(0.50, sum by (le) (rate(sjws_messaging_produce_duration_seconds_bucket[5m])))` and the same with `0.99` |
| Messaging fetch latency | `histogram_quantile(0.50, sum by (le) (rate(sjws_messaging_fetch_duration_seconds_bucket[5m])))` and the same with `0.99` |
| Messaging topics folded | `sum(rate(sjws_messaging_topics_folded_total[5m]))` |
| Swarm stream memory | `sjws_swarm_stream_budget_bytes`, `sjws_swarm_stream_charged_bytes`, `sjws_swarm_stream_peak_charged_bytes` |
| Swarm stream tickets | `sjws_swarm_stream_open_sessions`, `sjws_swarm_stream_gap_tickets_outstanding`, `sjws_swarm_stream_last_tickets_outstanding` |
| Swarm stream refusals | `sum(rate(sjws_swarm_stream_*_refusals_total[1m]))` and `sjws_swarm_stream_credit_unknown_total` |
| Swarm stream trims and drops | `sum(rate(sjws_swarm_stream_pressure_trim_*_total[1m]))` and `sjws_swarm_stream_sink_drops_total` |

p50 and p99 use `histogram_quantile` on `sjws_http_request_duration_seconds` and, for messaging produce/fetch, on `sjws_messaging_*_duration_seconds`. Those histograms use the same fixed log2 `le` labels (`ServerMetrics.LATENCY_LE_LABELS`): 1 ms doubling through 16.384 s and then `+Inf`, plus `_sum` and `_count`. Route and API-key p99 panels are gauges: the series store bucket counts without a latency sum, so they are not histograms. The gauge is the log2 bucket upper bound, the same number the admin TUI shows, or 0 when the series has no samples.

Cluster, route, and API-key panels stay empty until a `ClusterBoard` or a `LabelSource` is attached. Messaging lag, in-flight, DLQ, age, produce/fetch histograms and the topics-folded counter stay empty until messaging HTTP is on and the exporter is attached (`Sjws_nettask.attachMessagingMetricsExporter`; automatic when the messaging module starts). Swarm-stream panels stay empty until swarm token-streaming is on (`-Dsjws.swarm.stream.enabled=true` / `SJWS_SWARM_STREAM_ENABLED=true`, itself only when swarm is on) and stream credit is on (`-Dsjws.swarm.stream.credit.enabled=true`); credit-off scrapes omit the `sjws_swarm_stream_*` families. Attaching these sources does not install the observe filter. `/metrics` still needs `--metrics=localhost` or `--metrics=on` (or an admin snapshot that arms counters). Names are documented in `docs/operations/observability.md`.

Check the scrape and these expressions with Prometheus's own tools:

```
promtool check config contrib/grafana/prometheus.yml
promtool check metrics < metrics.txt
promtool check rules recording.yml
```

`observe/PrometheusScrapeTest` parses `sjws.json`, runs `promtool check config` on this directory's `prometheus.yml`, and runs `promtool check rules` on every panel expression when `promtool` is available.
