#!/bin/sh
# The java PIT starts for each test JVM ("minion"), in its own session and process group (setsid). A mutant that
# breaks DevDesk's own setsid makes the service share the test's group; DevDesk's "signal the whole group" then hits
# that group. Isolated like this, it kills only this minion, which PIT counts as a caught mutant, instead of PIT and
# the Gradle daemon too. (setsid execs without forking here: PIT's child isn't a group leader, so PIT keeps the PID.)
exec setsid "$PIT_REAL_JAVA" "$@"
