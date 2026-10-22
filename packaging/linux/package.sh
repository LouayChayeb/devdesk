#!/usr/bin/env bash
# Builds DevDesk's Linux installers: a .deb (app + the `devdesk` command) and an AppImage (app only).
# Neither needs Java, Gradle or npm on the machine that installs it: the engine comes with its own small Java runtime.
#
#   packaging/linux/package.sh          → build/package/out/DevDesk_<version>_amd64.deb and DevDesk_<version>_amd64.AppImage
#
# Needs here (the build machine): JDK 25 (jlink), Node + npm, Rust (cargo), and the Tauri Linux libraries.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STAGE="$ROOT/build/package"
ENGINE="$STAGE/engine"
OUT="$STAGE/out"

# Java modules the engine and the CLI use (from: jdeps --print-module-deps on their jars). jdk.naming.dns lets the
# MongoDB driver resolve mongodb+srv:// names; jdk.crypto.cryptoki and jdk.localedata are left out on purpose (unused).
MODULES="java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.rmi,\
java.security.jgss,java.security.sasl,java.sql,jdk.httpserver,jdk.net,jdk.unsupported,jdk.naming.dns"

echo "1/4 building the engine and the CLI"
(cd "$ROOT" && ./gradlew --quiet :daemon:installDist :cli:installDist)

echo "2/4 making a small Java runtime (jlink)"
# jlink makes some runtime files read-only; make them writable so the next build can replace them (here and in the
# copies tauri keeps next to the app and in its bundle folders).
if [ -d "$STAGE" ]; then chmod -R u+w "$STAGE"; fi
rm -rf "$STAGE"
for old in "$ROOT/ui/src-tauri/target/release/engine" "$ROOT/ui/src-tauri/target/release/bundle"; do
    if [ -d "$old" ]; then chmod -R u+w "$old" && rm -rf "$old"; fi
done
mkdir -p "$ENGINE/lib" "$OUT"
# --strip-debug and no man pages/headers: about 60 MB instead of a 300 MB JDK.
jlink --add-modules "$MODULES" --output "$ENGINE/runtime" \
    --strip-debug --no-header-files --no-man-pages --compress zip-6

# The engine and the CLI share one lib folder: they're built from the same project, so the same jar versions.
cp "$ROOT"/daemon/build/install/devdeskd/lib/*.jar "$ENGINE/lib/"
cp --update=none "$ROOT"/cli/build/install/devdesk/lib/*.jar "$ENGINE/lib/" # the shared jars are already there

# Launchers that use the bundled runtime directly. They don't set JAVA_HOME: the user's own JAVA_HOME must reach their
# services untouched (a Spring app started by DevDesk must use the user's JDK, not DevDesk's).
cat > "$ENGINE/devdeskd" <<'LAUNCH'
#!/bin/sh
# DevDesk's engine, started by the desktop app.
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/runtime/bin/java" --enable-native-access=ALL-UNNAMED -Djava.net.preferIPv4Stack=true \
    -cp "$HERE/lib/*" io.github.louch.devdesk.daemon.Main "$@"
LAUNCH
cat > "$ENGINE/devdesk" <<'LAUNCH'
#!/bin/sh
# The devdesk command (also installed as /usr/bin/devdesk by the .deb).
HERE="$(dirname "$(readlink -f "$0")")"
# UTF-8 output even without LANG set (cron, minimal shells): otherwise ✓ and ✗ come out as "?".
exec "$HERE/runtime/bin/java" --enable-native-access=ALL-UNNAMED -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
    -cp "$HERE/lib/*" io.github.louch.devdesk.cli.Main "$@"
LAUNCH
chmod +x "$ENGINE/devdeskd" "$ENGINE/devdesk"
chmod -R u+w "$ENGINE/runtime"
"$ENGINE/devdesk" --version >/dev/null || { echo "the bundled CLI does not start" >&2; exit 1; }

echo "3/4 building the app and the installers (tauri build)"
# The bundle is switched on only here, so `npm run app` (development) stays fast. The engine goes in as a resource;
# the .deb also gets /usr/bin/devdesk, a small script calling the bundled CLI. Docker isn't a package dependency:
# Docker Desktop (common on WSL) isn't a .deb, and DevDesk itself explains when Docker is missing.
CONFIG=$(cat <<JSON
{
  "bundle": {
    "active": true,
    "targets": ["deb", "appimage"],
    "shortDescription": "Run, inspect and fix local multi-service projects",
    "longDescription": "DevDesk starts a project's services in order, waits until they are healthy, shows their logs, databases, Redis, mails and HTTP calls, and explains in plain words why something is broken.",
    "resources": { "$ENGINE/": "engine/" },
    "linux": {
      "deb": {
        "files": { "/usr/bin/devdesk": "$STAGE/devdesk-command" }
      }
    }
  }
}
JSON
)
cat > "$STAGE/devdesk-command" <<'LAUNCH'
#!/bin/sh
# Installed by the DevDesk .deb: runs the CLI with DevDesk's own Java runtime.
exec /usr/lib/DevDesk/engine/devdesk "$@"
LAUNCH
chmod +x "$STAGE/devdesk-command"
# linuxdeploy (which makes the AppImage) is itself an AppImage; WSL and containers often lack FUSE to mount it, so it
# unpacks itself instead. NO_STRIP: its bundled strip is too old for the libraries of current distributions.
# LINUXDEPLOY_EXCLUDED_LIBRARIES: Java's own libraries load libjvm.so from the bundled runtime, not from the system;
# without this, linuxdeploy looks for it in the system folders and gives up.
(cd "$ROOT/ui" && npm install --no-audit --no-fund --silent \
    && APPIMAGE_EXTRACT_AND_RUN=1 NO_STRIP=1 LINUXDEPLOY_EXCLUDED_LIBRARIES="libjvm.so" \
        npx tauri build --config "$CONFIG")

echo "4/4 collecting the installers"
find "$ROOT/ui/src-tauri/target/release/bundle" \( -name '*.deb' -o -name '*.AppImage' \) -exec cp {} "$OUT/" \;
ls -lh "$OUT"
