# macOS launchd

Linux service units live in `contrib/` and are documented in
[control-plane-service.md](control-plane-service.md) and [daemon.md](daemon.md).
On macOS, use launchd with the sample plist in `contrib/launchd/sjws_nettask.plist`.

launchd must supervise the Java process itself. Start the server in the
**foreground**: do not pass `--daemon`. `--daemon` starts a second JVM, calls
`setsid` when it can, writes the pid file from the child, and exits the parent.
launchd treats that parent exit as a crash and may start another copy.

The sample uses the same jar flags as the systemd unit (`--add-opens` for admin
TLS keep-alive abort, `--config`, `--pid-file`, `--shutdown-timeout`, port and
document root) without `--daemon` or `--log-file`. Standard output and error go
to `StandardOutPath` / `StandardErrorPath`. A foreground process with
`--pid-file` still writes the pid file, so `java -jar … --status --pid-file=…`
works the same as on Linux. `--stop` can also be used (it sends SIGTERM), but
`launchctl bootout` or `launchctl kickstart -k` is the usual stop/restart path.

`ExitTimeOut` is 45 seconds, matching `TimeoutStopSec` on the systemd units
(default drain budget 30 s plus the 5 s SIGKILL margin, plus headroom). If you
raise `--drain-budget`, raise `ExitTimeOut` by the same amount.

A normal foreground process still dies on `SIGHUP` (terminal hangup). The
sample plist sets `SJWS_RELOAD_SIGHUP=1` so the supervised process installs
the same reload hook as the daemon child. Reload is then SIGHUP, the same
hook as `ExecReload` on Linux:

    sudo launchctl kill HUP system/sjws_nettask

Install, start, and stop (system-wide; adjust paths in the plist first, and
place the jar next to its `lib/` directory):

    sudo install -d -m 0755 /usr/local/sjws_nettask /usr/local/var/www /usr/local/var/log /usr/local/var/run /usr/local/etc/sjws_nettask
    sudo install -m 0644 contrib/launchd/sjws_nettask.plist /Library/LaunchDaemons/sjws_nettask.plist
    sudo launchctl bootstrap system /Library/LaunchDaemons/sjws_nettask.plist
    sudo launchctl enable system/sjws_nettask
    sudo launchctl kickstart -k system/sjws_nettask
    sudo launchctl bootout system /Library/LaunchDaemons/sjws_nettask.plist

A per-user agent under `~/Library/LaunchAgents/` works the same way with
`gui/$UID` instead of `system`, if the listen port and paths are writable by
that user.

Control and data roles use the same foreground shape. Point `--config` and
`--bootstrap` at the role files from `contrib/` (see
[control-plane-service.md](control-plane-service.md)); do not add `--daemon`.
launchd `active` (the process is running) is not readiness: use `/readyz` and
`--status`, as on Linux.
