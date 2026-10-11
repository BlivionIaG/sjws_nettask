# sjws_nettask tests

JUnit 5 via Maven (`pom.xml` at repo root, reusing the NetBeans `src/` layout).
Tests live in `tests/` because `test/` is listed in `.gitignore`.

    mvn test

Tests named `BUG_*` were written against the original code and failed there;
each one guards a fix from the `quick-wins` branch. New regressions should be
added as `@Tag("bug")` tests first (run the green set with
`mvn test -DexcludedGroups=bug`), then untagged once fixed.
