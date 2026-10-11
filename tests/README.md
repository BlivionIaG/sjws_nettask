# sjws_nettask tests

JUnit 5 via Maven (`pom.xml` at repo root, reusing the NetBeans `src/` layout).
Tests live in `tests/` because `test/` is listed in `.gitignore`.

    mvn test

| Class | Covers |
|---|---|
| `HTTP/Http1ParserTest` | request-head parsing: smuggling vectors, limits, versions, absolute-form, Connection/Expect |
| `HTTP/HeaderTest`, `ToolsTest` | response head, dates, MIME, port parsing |
| `HTTP/FileServingTest` | file/listing/error responses without a network (containment, symlinks, dotfiles, 301/403/304, exact-length sends) |
| `ServerIntegrationTest`, `HardeningIntegrationTest` | real sockets: keep-alive, pipelining, deadlines, connection cap, leak check, process exit codes |
| `ServerConfigTest`, `BackoffTest` | config parsing/precedence, accept-loop back-off |

Tests named `BUG_*` were written against the original code and failed there.
New regressions can be added as `@Tag("bug")` tests first (`mvn test -DexcludedGroups=bug`
runs the rest), then untagged once fixed. The permission-based 403 tests are
skipped when run as root.
