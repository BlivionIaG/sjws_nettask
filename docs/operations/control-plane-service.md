# Control-plane and data-node service

How a `control` or `data` process is started, how it learns its control plane, and how
`--stop` drains it. The design this follows is
[control plane](https://github.com/BlivionIaG/sjws_nettask/blob/cursor/control-plane-design-8cea/docs/design/control-plane.md)
(PR #38) and the HA drain in [high availability](../ha/design.md). Role `standalone` does
not read this file and does not load `sjws_nettask.service`.

## Units

| Unit | Role |
|---|---|
| `contrib/sjws_nettask.service` | standalone, unchanged |
| `contrib/sjws_nettask-control@.service` | `systemctl enable --now sjws_nettask-control@1` |
| `contrib/sjws_nettask-data@.service` | `systemctl enable --now sjws_nettask-data@edge1` |

Both role units are `Type=forking`. `--daemon` writes the pid file and the parent exits.
`User=sjws` must exist (`useradd --system --home /var/lib/sjws_nettask --shell /usr/sbin/nologin sjws`).
`UMask=0077`, `-XX:-HeapDumpOnOutOfMemoryError`, and `LimitCORE=0` keep a join token out of
files the process creates and out of crash dumps. Those JVM flags are not on the standalone unit.

`TimeoutStopSec=90` is longer than the default HA drain (5 s delay + 15 s drain) plus
`--shutdown-timeout` (10 s) plus the close slack and the 5 s `--stop` margin.

Data nodes do not `Requires=` a control unit. A same-host ordering drop-in that still lets
the data node start when control is down is `contrib/sjws_nettask-data-same-host.conf`.
There is no `ExecReload`. The daemon child ignores `SIGHUP`, and a full `--restart` would
drain the node and read the join token again.

## Bootstrap

`--bootstrap=PATH` (`WEB_SERVER_BOOTSTRAP`) is a mode `0600` or `0640` file. `--role=control`
or `--role=data` (`WEB_SERVER_ROLE`) must match the file. The secret is not a flag and not an
environment variable. Paths inside the file are absolute. The pin and the join token sit
outside `state-dir`.

```
role=data
data-role=gateway
node-id=gw-1
control-plane=10.0.0.2:9443
pin=/etc/sjws_nettask/data-edge1/root.pub
join-token=/etc/sjws_nettask/data-edge1/join.token
state-dir=/var/lib/sjws_nettask/data-edge1
label.track=canary
capabilities=http1,proxy
```

A control file uses `role=control`, omits `data-role` and `control-plane`, and may set
`peers=10.0.0.2:7000,10.0.0.3:7000` and `listen=10.0.0.1:9443`. `listen` is stored and not
bound. An empty `peers` list is the one-voter lab.

The join-token file is mode `0600` or `0400`, not a symlink:

```
token-id=edge1-2026
secret=<16 to 512 printable ASCII characters>
```

On first start the token file is required and the pinned root public key must exist. A
missing pin refuses to start. The process holds the secret in a `byte[]`. It does not open
a socket and does not log the secret. `--status` then shows `connection=pending` and
`config-version=0`.

`NodeEnrollment.tokenConsumed()` is the call the control plane makes after `ConsumeToken`
commits. It writes `state-dir/node.id` (mode `0600`) and then deletes the token file. A
later start may omit the token file. Until that call, a restart reads the same file again.
`NodeEnrollment.attach(ControlPlaneBinding)` updates connection state and config version.
`NodeEnrollment.current()` is null when this process is standalone.

`sjws_nettask.cp` is not on this tree. The watch, Raft, and admin listener are not started.
`SjwsServer.drain()` remains the only drain.

## Status

```
running pid=123 role=data data-role=gateway connection=pending config-version=0
```

The sidecar is `<pid-file>.status`. It is trusted only when `pid` and `start` match the pid
file, so a recycled pid does not keep the previous node's version. Connection values outside
`pending`, `connecting`, `connected`, `disconnected`, `leader`, and `follower` are stored as
`disconnected` and are not echoed. Standalone `--status` stays `running pid=<n>`.

## Lifecycle notes

These are the places the control-plane design's start, join, drain, restart, and
control-plane failure assumptions do not match what the daemon can do on this tree.

1. **One drain.** The shutdown hook calls `SjwsServer.drain()`. With HA attached that is
   `Lifecycle.drain()` (not-ready, deregistration delay, `Connection: close`, then the
   existing close). With HA absent it is `close()`, and the `--stop` wait stays
   shutdown-timeout plus 5 seconds. There is no second drain API on `ControlPlaneBinding`.

2. **`--stop` would SIGKILL a real HA drain.** The historical wait is about 15 seconds.
   Default `drainDelay` 5 s plus `drainTimeout` 15 s is already longer. When HA is
   attached the process records `drain-ms` and `--stop` waits for it, capped at 10
   minutes. The role units set `TimeoutStopSec=90` because systemd's timer does not read
   that file. The command-line server still does not attach HA; nothing in `ConfigLoader`
   builds `HaSettings`. A later flag that does must go through this budget or `--stop`
   will cut the drain.

3. **systemd active is not `leaderReady()`.** The pid file is written when the content
   port accepts. `ControlLeadership` and the election `Noop` are not in this tree. A
   control unit is active before it can sign or issue certificates. `--status` stays
   `connection=pending` until something calls `attach`. Do not treat "service started"
   as "config applied" or "safe to join data nodes".

4. **Data nodes start when the control plane is down.** The design's fail-static rule is
   that a data node boots from last-good, or binds and answers 503, when the control
   plane is unreachable. `Requires=` on a control unit would do the opposite, so the data
   unit does not have it. `After=` in the optional drop-in only orders a same-host start.
   There is no watch thread here, so "control plane down" cannot block a request. It also
   cannot move `connection` from `pending` to `disconnected`: nothing has connected.
   Max-staleness drain, when it exists, has to call `SjwsServer.drain()`, not a new method.

5. **SIGHUP cannot do what the design says.** Section 5.6 allows a data node to apply
   local-only fields on SIGHUP and requires it to reject control-plane-owned sections.
   The detached child ignores SIGHUP so closing a terminal does not kill it. The role
   units therefore have no `ExecReload`. The standalone unit's reload is a full
   `--restart`, which would drop connections and re-read the join token; the role units
   do not copy that. `ReloadHook.run()` is still the in-process entry. Nothing maps a
   signal onto it for the detached child.

6. **The token is not presented.** The design's first flight is mTLS, pin check, then
   `Register` / `ConsumeToken`. This process checks the pin file and the token file's
   mode and holds the bytes. `copySecret()` returns a clone the caller must
   `Arrays.fill`. `tokenConsumed()` deletes the file only after the caller says the
   consume committed. A `Restart=on-failure` before that reads the same token again.
   The control plane has to treat that as the same in-flight join, and reject it only
   after commit. This slice cannot check the log.

7. **Last-good, the floor, and 503 are not implemented.** The design reads the pin, then
   `floor.cp` and `trust.cp`, then `last-good.cp`, and answers 503 until a snapshot
   passes. Those files are not created here; their bytes belong to `sjws_nettask.cp`.
   A data process still serves the local document root, the same as standalone. The boot
   check this slice does enforce is the missing pin. `state-dir/node.id` is written only
   by `tokenConsumed()` and is not a config snapshot.

8. **A control process still binds the content port.** There is no admin or watch
   listener. The unit's port is the content port. Firewall it, or point it at an empty
   root, until the control-plane listener exists. `listen=` in the bootstrap is not bound.

9. **Restart is drain, then a new process.** `--restart` is `--stop` followed by
   `--daemon`. It does not transfer Raft leadership (there is no voter in this process)
   and it does not mint a new token. If `tokenConsumed()` has run, the new process starts
   without the token file. If it has not, the token file is required again.

10. **Standalone stays off this path.** No bootstrap means this package is not loaded, no
    `state-dir` is created for it, `--status` text is unchanged, and the accept loop does
    not read a role. The signal hook's `drain()` is `close()` when `ha()` is null.
