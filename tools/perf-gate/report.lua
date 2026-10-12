-- wrk script: print one JSON line with throughput and latency percentiles (microseconds).
done = function(summary, latency, requests)
  local secs = summary.duration / 1e6
  io.write(string.format(
    'PERF_JSON {"rps":%.2f,"p50_us":%d,"p99_us":%d,"errors":%d,"requests":%d}\n',
    summary.requests / secs, latency:percentile(50), latency:percentile(99),
    summary.errors.connect + summary.errors.read + summary.errors.write
      + summary.errors.status + summary.errors.timeout,
    summary.requests))
end
