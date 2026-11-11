# Windows service

`--daemon`, `--stop`, and `--restart` are built around POSIX process control:
`setsid` when a new session is available, SIGTERM to drain, SIGKILL after the
drain budget. Those signals are not available as on Linux. The supported way to
run as a Windows service is a wrapper that starts the jar in the **foreground**
and stops it with a console control event (CTRL_C / CTRL_BREAK), which runs the
JVM shutdown hook and therefore `SjwsServer.drain()`.

Do not pass `--daemon` on Windows. That parent/child split needs `setsid` and
is not the service manager's process.

`java -jar … --stop --pid-file=…` calls `ProcessHandle.destroy()`. On Windows
that is `TerminateProcess`, so the shutdown hook and the drain path do not
run. Prefer the service wrapper's stop command. `--status` still works if the
process was started with `--pid-file`.

## WinSW

`contrib/windows/sjws_nettask.xml` is a [WinSW](https://github.com/winsw/winsw)
configuration for the standalone server. Copy the jar and its `lib\` directory
together (the manifest `Class-Path` is `lib/`). Adjust the paths, document
root, and port in the XML, then:

    rem WinSW 2.x: rename WinSW.exe to sjws_nettask.exe next to the XML
    sjws_nettask.exe install
    sjws_nettask.exe start
    sjws_nettask.exe stop
    sjws_nettask.exe uninstall

`<stoptimeout>` is 45 seconds, matching `TimeoutStopSec` on the Linux units
(default drain budget 30 s plus the 5 s margin, plus headroom). Raise it if you
raise `--drain-budget`.

The `--add-opens=jdk.httpserver/sun.net.httpserver=ALL-UNNAMED` flag is in the
sample arguments so a launcher that does not use the jar manifest still gets
the admin TLS keep-alive abort. Confirm a packaged jar with
`java -jar sjws_nettask.jar --self-check-admin-tls-abort`.

Control and data roles use the same foreground shape. Point `--config` and
`--bootstrap` at the role files from `contrib/` (see
[control-plane-service.md](control-plane-service.md)).

## Alternatives

[Apache Commons Daemon procrun](https://commons.apache.org/proper/commons-daemon/procrun.html)
(`prunsrv.exe`) can also host the jar. Use a `StopMode` that signals the JVM
(for example `StdOutput` / console control), not a hard kill. `sc.exe` plus a
manual `java -jar` command does not provide a drain on stop unless something
else sends CTRL_C to that console.

This sample was not executed on Windows in the change that added it; treat path
and WinSW version details as starting points and verify install/start/stop on
the target host.
