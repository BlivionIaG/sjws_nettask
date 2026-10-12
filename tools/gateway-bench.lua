-- wrk done() hook: req/s, error count, and p50/p90/p99/p99.9 in microseconds.
done = function(summary, latency, requests)
  local secs = summary.duration / 1e6
  local err = summary.errors.connect + summary.errors.read + summary.errors.write
      + summary.errors.status + summary.errors.timeout
  io.write(string.format(
    "wrk req/s %.2f errors %d p50_us %.3f p90_us %.3f p99_us %.3f p999_us %.3f\n",
    summary.requests / secs, err,
    latency:percentile(50),
    latency:percentile(90),
    latency:percentile(99),
    latency:percentile(99.9)))
end
