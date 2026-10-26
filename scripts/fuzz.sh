#!/usr/bin/env bash
# Runs every Jazzer fuzz target in fuzzing mode, one after the other (each needs its own JVM).
# A crash fails the target and is saved as a test input under src/test/resources/<package>/<Class>Inputs/<method>/,
# so `./gradlew test` replays it from then on. Fix the bug, keep the file.
#
# Usage:  scripts/fuzz.sh [duration per target, default 1m] [filter: only targets containing this text]
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DURATION="${1:-1m}"
FILTER="${2:-}"
TARGETS=(
    "engine-core io.github.louch.devdesk.core.config.ConfigFuzzTest devdeskYaml"
    "engine-core io.github.louch.devdesk.core.config.ConfigFuzzTest envFile"
    "engine-core io.github.louch.devdesk.core.config.ConfigFuzzTest envEditorRoundTrip"
    "engine-core io.github.louch.devdesk.core.setup.ComposeImportFuzzTest composeFile"
    "engine-core io.github.louch.devdesk.core.http.HttpImportFuzzTest curl"
    "engine-core io.github.louch.devdesk.core.http.HttpImportFuzzTest postman"
    "engine-core io.github.louch.devdesk.core.http.HttpImportFuzzTest openApi"
    "engine-data io.github.louch.devdesk.data.RespFuzzTest reply"
    "engine-linux io.github.louch.devdesk.linux.process.ProcStatFuzzTest anyProgramName"
)

mkdir -p "$ROOT/build"
failed=0
for target in "${TARGETS[@]}"; do
    read -r module class method <<<"$target"
    [[ -n "$FILTER" && "$class.$method" != *"$FILTER"* ]] && continue
    echo "== $class.$method ($DURATION)"
    inputs="$ROOT/$module/src/test/resources/${class//.//}Inputs/$method"
    mkdir -p "$inputs"
    before=$(ls "$inputs")
    if JAZZER_FUZZ=1 JAZZER_MAX_DURATION="$DURATION" "$ROOT/gradlew" -q -p "$ROOT" ":$module:test" \
        --tests "$class.$method" >"$ROOT/build/fuzz-$method.log" 2>&1; then
        echo "   no crash"
    else
        failed=1
        # Jazzer saves the crashing input in $inputs (or, the first time, next to the module): keep it there.
        find "$ROOT/$module" -maxdepth 1 \( -name 'crash-*' -o -name 'timeout-*' -o -name 'oom-*' \) \
            -exec mv {} "$inputs/" \;
        new=$(comm -13 <(echo "$before") <(ls "$inputs"))
        echo "   CRASH: ${new:-no input saved} (in ${inputs#"$ROOT/"}); details: build/fuzz-$method.log"
    fi
    [[ -z "$(ls "$inputs")" ]] && rmdir "$inputs"
done
exit $failed
