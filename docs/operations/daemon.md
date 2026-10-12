# Running in the background

`--daemon` starts a second JVM and exits once that process is listening. The child runs in a new
session when `setsid` is available (`/usr/bin/setsid`), ignores `SIGHUP`, writes the pid file and
appends stdout and stderr to the log file (default `sjws_nettask.log` in the working directory).
JVM flags placed before `-jar` or the main class, such as `-Xmx`, are kept. Paths in `--pid-file`
and `--log-file` are relative to the working directory; a service should use absolute paths.

```
java -jar target/sjws_nettask-0.2-SNAPSHOT.jar --daemon --pid-file=/run/sjws_nettask.pid \
    --log-file=/var/log/sjws_nettask.log 8080 /var/www/html
java -jar target/sjws_nettask-0.2-SNAPSHOT.jar --status --pid-file=/run/sjws_nettask.pid
java -jar target/sjws_nettask-0.2-SNAPSHOT.jar --stop --pid-file=/run/sjws_nettask.pid
java -jar target/sjws_nettask-0.2-SNAPSHOT.jar --restart --pid-file=/run/sjws_nettask.pid \
    --log-file=/var/log/sjws_nettask.log 8080 /var/www/html
```

`--stop` sends SIGTERM and waits for `--shutdown-timeout` plus 5 seconds, then SIGKILL.
`--status` prints `running pid=<n>`, `stopped`, or `stopped stale pid=<n>`. A pid file whose
process is gone, or whose pid was reused (the start time no longer matches), is stale: `--stop`
removes it and does not signal the new process. A file a live server has locked is left in place,
so a restart cannot delete the pid file the new process just claimed. `--restart` is `--stop`
followed by `--daemon`.

SIGTERM and SIGINT (the shutdown hook) stop accepting on every listener, drop idle and
half-read HTTP/1 connections immediately, and let responses already in progress finish until
`--shutdown-timeout` (default 10 seconds). Whatever is still open is then closed. HTTP/3 sends
GOAWAY and drains for up to 2 seconds in parallel with that wait. A foreground process with
`--pid-file` writes the same file, so `--stop` works for it too; without `--log-file` it keeps
logging to the console.

`contrib/sjws` is a small start/stop/status/restart wrapper (`SJWS_JAR`, `SJWS_PID_FILE`,
`SJWS_LOG_FILE`, `JAVA`). `contrib/sjws_nettask.service` is a systemd `Type=forking` example
(`PIDFile` plus `--daemon`). Copy `target/lib/` next to the jar: `java -jar` loads Netty from the
manifest `Class-Path`. On Windows, `--stop` ends the process without a POSIX SIGTERM, so the
drain hook does not run; use a service manager that can signal the JVM there.
The daemon child ignores `SIGHUP`. `ReloadHook.run()` is the reload entry a handler can call.
