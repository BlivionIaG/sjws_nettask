# Benchmarks

Small, dependency-free harnesses (not part of the Maven build, so they never affect `mvn verify`).

```sh
mvn -B -q -DskipTests package            # builds target/classes
# HTTP/1.1 keep-alive closed loop against an in-process SjwsServer
java -cp target/classes bench/HttpBench.java 64 10 1024    # connections seconds bodyBytes
# Append-only log write-path prototype
java bench/LogBench.java /tmp/logbench none  8 200000 256
java bench/LogBench.java /tmp/logbench each  8 20000  256
java bench/LogBench.java /tmp/logbench group 8 200000 256
```

Client and server share the machine (loopback); compare runs only on the same host.
Use a real local disk for `LogBench` (fsync on tmpfs/overlay can be a no-op or unrepresentative).
