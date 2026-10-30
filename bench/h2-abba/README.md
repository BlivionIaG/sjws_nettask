# h2 ABBA helper

```
./bench/h2-abba/run-abba.sh <before-dir> <after-dir> <www>
```

Each dir must contain `sjws_nettask-*.jar` and its `lib/` (Maven assembly / jar-with-deps layout).
The script `flock`s `/tmp/sjws-bench.lock` (Architecture N2), waits for load1 &lt; 4, and records it.
Runs h1 wrk ABBA then h2c/h2 GET via `JdkH2Load.java`.

TLS: `run-abba.sh` writes a one-day self-signed `bench-cert.pem` / `bench-key.pem`, passes them to
`SjwsServer` via `--cert`/`--key`, and sets `SJWS_BENCH_CA` so `JdkH2Load` trusts only that cert
(no trust-all TrustManager).

