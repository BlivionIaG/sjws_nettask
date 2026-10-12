# Configuration reference

The canonical list of options, environment variables and defaults is the option tables in the
[command-line page](command-line.md) (HTTP/1.1 options and the [HTTP/3](http3.md) table);
`java -jar sjws_nettask-<version>.jar --help` prints the same list. `DocsConsistencyTest` fails the
build if those tables and `--help` disagree. This page explains how values are read.

## Sources and precedence

    java -jar target/sjws_nettask-0.2-SNAPSHOT.jar [port] [html folder] [--option=value ...]

* `[port]` and `[html folder]` are positional (first and second non-option argument); `--options`
  may appear anywhere.
* Every option except `--help` has a `WEB_SERVER_*` environment variable. **Environment variables
  override command-line arguments.**
* The TLS key password is accepted only from `WEB_SERVER_TLS_KEY_PASSWORD` or a file
  (`--key-password-file`), never as an argument; the variable wins over the file.

## Value formats

| Kind | Accepted | Examples |
|---|---|---|
| numbers (ports, counts, seconds, bytes/s) | 1 to 9 decimal digits, within the option's range | `8080`, `30` |
| byte sizes (`--cache-bytes`) | digits with an optional binary suffix `k`/`m`/`g` (case-insensitive), at most `16g` | `0`, `65536`, `64k`, `64m`, `1g` |
| booleans (`WEB_SERVER_H3`, `WEB_SERVER_ALT_SVC`, ...) | `true/false`, `1/0`, `yes/no`, `on/off` (case-insensitive) | `on` |
| flags on the command line | `--x` / `--no-x` where the option tables list both | `--h3`, `--no-alt-svc` |
| paths | any non-blank value | `--cert=/etc/sjws/cert.pem` |

An invalid value is replaced by the default and a `WARN:` line names it; unknown options also warn
(`WARN: Unknown option '...' (see --help)`). HTTP/3 options given without `--h3` warn and have no effect.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | normal shutdown, or `--help` / `-h` |
| 1 | cannot listen on the TCP port |
| 2 | document root missing or not a directory |
| 64 | configuration error (e.g. `--cert` without `--key`, unreadable key password file) |

## Examples

    # defaults: port 8080, ./HTML
    java -jar target/sjws_nettask-0.2-SNAPSHOT.jar

    # environment instead of arguments
    WEB_SERVER_PORT=9000 WEB_SERVER_DIR=/srv/www java -jar target/sjws_nettask-0.2-SNAPSHOT.jar

    # behind a reverse proxy: every client shares one address, so raise the per-address caps
    java -jar target/sjws_nettask-0.2-SNAPSHOT.jar 8080 /srv/www --max-connections-per-ip=2048

    # HTTP/3 with a real certificate and Alt-Svc on HTTP/1.1 responses
    WEB_SERVER_TLS_KEY_PASSWORD_FILE=/etc/sjws/key.pass java -jar target/sjws_nettask-0.2-SNAPSHOT.jar \
        443 /srv/www --h3 --cert=/etc/sjws/cert.pem --key=/etc/sjws/key.pem --alt-svc

Copy `target/lib/` next to the jar: it holds Netty and the QUIC natives named in the manifest
`Class-Path`. See the [Platform notes](platform-notes.md) for
`--allow-unlistable-dirs` and the [HTTP/3](http3.md) section for reaching HTTP/3 from clients.
