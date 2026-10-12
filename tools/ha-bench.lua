-- wrk done() hook: req/s plus p50/p90/p99/p99.9 in microseconds.
done = function(summary, latency, requests)
  local secs = summary.duration / 1e6
  io.write(string.format("wrk req/s %.2f p50_us %.3f p90_us %.3f p99_us %.3f p999_us %.3f\n",
    summary.requests / secs,
    latency:percentile(50),
    latency:percentile(90),
    latency:percentile(99),
    latency:percentile(99.9)))
end
