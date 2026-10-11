# sjws_nettask tests

JUnit 5 via Maven (`pom.xml` at repo root, reusing the NetBeans `src/` layout).
Tests live in `tests/` because `test/` is listed in `.gitignore`.

    mvn test                          # everything (bug-tagged tests currently FAIL on purpose)
    mvn test -DexcludedGroups=bug     # only the green characterization suite

Tests tagged `@Tag("bug")` / named `BUG_*` assert the *correct* behaviour and
currently fail because they expose real defects. Make them pass, then drop the tag.
