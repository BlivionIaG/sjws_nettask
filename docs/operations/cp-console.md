# Control-plane console (mockup)

Status: **mockup**. The console runs against an in-memory control plane
(`FakeCpClient`) until HA ships the operator API it proposes (`sjws_nettask.admin.cp.CpClient`).

    java -cp "target/sjws_nettask-0.2-SNAPSHOT.jar:target/lib/*" sjws_nettask.admin.cp.CpConsoleMain --demo [--endpoints=cp-1:9443,cp-2:9443] [--no-color]

It is a separate entry point: the server never loads `sjws_nettask.admin.cp` (a test enforces
this), so the console costs nothing when it is not used.

## What it does
- **Nodes** (`n`): leader, term, head version; per node role, health, applied version, lag, labels.
- **Edit** a local draft (`e <section>`, `+ <section> <entry>`, `- <section> <n>`, `^ <section> <n>`
  to move up). Sections: routes, filters (ordered), access, certs, topics.
- **Diff** (`d`) against the base version; **validate** (`v`) on the control plane (dry run).
- **Apply** (`a <selector>`): validate, stage against the base version (CONFLICT if someone
  else published meanwhile), roll out to nodes matching a label selector (`canary=true`,
  `zone=a,tier!=edge`, `*`). **Promote** (`p <selector>`) widens the rollout of the head version.
- **Rollback** (`b <version>`): a new version with the old content, rolled out everywhere.
- **Audit** (`l`): who did what, plus the endpoint switches of the failover client.
- **Failover**: `FailoverCpClient` sticks to one endpoint, moves on when it is unreachable, follows
  `NOT_LEADER` hints, and retries writes safely because every write carries an idempotency key.
