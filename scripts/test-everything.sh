#!/usr/bin/env bash
# Tests all of DevDesk in one go:
#   1. prerequisites      java, docker, python3, node, curl
#   2. build + unit tests ./gradlew build (compile, tests, style checks)
#   3. UI build           npm install + ng build
#   4. live engine        starts the real engine on examples/full-stack and checks every Workbench feature through its API
#   5. UI click-through   a headless browser clicks through the real app (skip with --no-ui)
#
# Usage:  scripts/test-everything.sh [--skip-build] [--no-ui] [--keep]
#   --skip-build  skip steps 2 and 3 (reuse what is already built)
#   --no-ui       skip step 5
#   --keep        keep the temporary folder (screenshots, engine logs) instead of deleting it
#
# Nothing touches your real DevDesk data: the engine gets a throwaway state folder (XDG_STATE_HOME).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE="$ROOT/examples/full-stack"
SKIP_BUILD=0 UI=1 KEEP=0
for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        --no-ui) UI=0 ;;
        --keep) KEEP=1 ;;
        -h | --help) sed -n '2,15p' "$0"; exit 0 ;;
        *) echo "unknown option: $arg (try --help)"; exit 2 ;;
    esac
done

WORK="$(mktemp -d -t devdesk-test-XXXXXX)"
PASSED=0 FAILED=0 FAILURES=()
ENGINE_PID="" UI_PID=""

green() { printf '\033[32m%s\033[0m\n' "$*"; }
red() { printf '\033[31m%s\033[0m\n' "$*"; }
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
pass() { PASSED=$((PASSED + 1)); green "  ok    $1"; }
fail() { FAILED=$((FAILED + 1)); FAILURES+=("$1"); red "  FAIL  $1${2:+  ($2)}"; }

cleanup() {
    if [ -n "$ENGINE_PID" ] && kill -0 "$ENGINE_PID" 2>/dev/null; then
        api POST /project/down >/dev/null 2>&1
        # Closing the engine's stdin is the app's "I'm gone" signal: the engine stops every service, then exits.
        exec 3>&- 2>/dev/null
        for _ in $(seq 60); do kill -0 "$ENGINE_PID" 2>/dev/null || break; sleep 1; done
        kill -TERM "$ENGINE_PID" 2>/dev/null
    fi
    [ -n "$UI_PID" ] && kill "$UI_PID" 2>/dev/null
    rm -rf "$EXAMPLE/__pycache__"
    if [ "$KEEP" = 1 ]; then echo "kept: $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# api METHOD /path [json-body]: calls the engine, prints the body; the HTTP status goes to $WORK/status.
api() {
    local body=${3:-}
    curl -s -o "$WORK/body" -w '%{http_code}' -X "$1" \
        -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        ${body:+--data "$body"} "http://127.0.0.1:$PORT/api/v1$2" >"$WORK/status"
    cat "$WORK/body"
}

# check "name" METHOD /path body 'python expression on d (the JSON answer)'
check() {
    local name=$1 out code
    out="$(api "$2" "$3" "$4")"
    code="$(cat "$WORK/status")"
    if [ "${code:0:1}" != 2 ]; then
        fail "$name" "HTTP $code: ${out:0:200}"
    elif python3 -c "import json,sys; d=json.loads(sys.argv[1] or 'null'); sys.exit(0 if ($5) else 1)" "$out" 2>/dev/null; then
        pass "$name"
    else
        fail "$name" "unexpected answer: ${out:0:200}"
    fi
}

# check_error "name" METHOD /path body 'text the error must mention': the engine must refuse AND explain why.
check_error() {
    local out code
    out="$(api "$2" "$3" "$4")"
    code="$(cat "$WORK/status")"
    if [ "${code:0:1}" = 4 ] && grep -qi "$5" <<<"$out"; then pass "$1"; else fail "$1" "HTTP $code: ${out:0:200}"; fi
}

port_free() { ! ss -ltn 2>/dev/null | grep -q ":$1 "; }

# ---------------------------------------------------------------------------------------------------------------------
step "1. Prerequisites"
for tool in java docker python3 node npm curl ss; do
    if command -v "$tool" >/dev/null; then pass "$tool found"; else fail "$tool found" "install it first"; fi
done
if docker info >/dev/null 2>&1; then pass "docker daemon reachable"; else fail "docker daemon reachable" "start Docker"; fi
# The full-stack example and the UI server need these ports; something else on them makes the checks fail.
for p in 4200 18000 18080 18081 18300 18301 55440 56380 58026 51026; do
    port_free "$p" || fail "port $p is free" "$(ss -ltnp 2>/dev/null | grep ":$p " | head -1)"
done
[ "$FAILED" = 0 ] || { red "Fix the prerequisites above first."; exit 1; }

# ---------------------------------------------------------------------------------------------------------------------
if [ "$SKIP_BUILD" = 0 ]; then
    step "2. Build and unit tests (./gradlew build)"
    if (cd "$ROOT" && ./gradlew build --console=plain -q >"$WORK/gradle.log" 2>&1); then pass "gradle build"; else
        fail "gradle build" "see the output below"; grep -v WARNING "$WORK/gradle.log" | tail -30
    fi
    python3 - "$ROOT" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
t = f = s = 0
for p in glob.glob(sys.argv[1] + '/*/build/test-results/test/*.xml'):
    r = ET.parse(p).getroot()
    t += int(r.get('tests')); f += int(r.get('failures')) + int(r.get('errors')); s += int(r.get('skipped'))
    for c in r.iter('testcase'):
        if c.find('failure') is not None or c.find('error') is not None:
            print('        failed:', r.get('name').split('.')[-1], c.get('name'))
print(f'        {t} tests, {f} failed, {s} skipped')
EOF

    step "3. UI build"
    if (cd "$ROOT/ui" && npm install --no-audit --no-fund >"$WORK/npm.log" 2>&1 && npm run build >>"$WORK/npm.log" 2>&1); then
        pass "npm install + ng build"
    else
        fail "npm install + ng build" "see the output below"; tail -30 "$WORK/npm.log"
    fi
fi

# ---------------------------------------------------------------------------------------------------------------------
step "4. Live engine on examples/full-stack"
(cd "$ROOT" && ./gradlew :daemon:installDist --console=plain -q >"$WORK/install.log" 2>&1) || {
    fail "daemon installDist"; tail -20 "$WORK/install.log"; exit 1
}
mkdir -p "$WORK/state"
mkfifo "$WORK/stdin"
# The engine's stdin is a pipe we hold open (like the desktop app does). Closing it later stops everything cleanly.
XDG_STATE_HOME="$WORK/state" "$ROOT/daemon/build/install/devdeskd/bin/devdeskd" \
    <"$WORK/stdin" >"$WORK/engine.out" 2>"$WORK/engine.err" &
ENGINE_PID=$!
exec 3>"$WORK/stdin"
for _ in $(seq 60); do [ -s "$WORK/engine.out" ] && break; sleep 0.5; done
read -r PORT TOKEN < <(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d["port"], d["token"])' "$WORK/engine.out" 2>/dev/null)
if [ -z "${PORT:-}" ]; then fail "engine started" "$(tail -5 "$WORK/engine.err")"; exit 1; fi
pass "engine started on port $PORT"

check "open the project" POST /project/open "{\"path\": \"$EXAMPLE\"}" 'd["name"] == "full-stack"'
check "trust the project" POST /project/trust '' 'True'
check "start all services" POST /project/up '' 'd.get("started")'

echo "        waiting for the 11 services (first run pulls Docker images, this can take minutes)..."
ok=0
for _ in $(seq 120); do
    ok="$(api GET /project | python3 -c 'import json,sys; d=json.load(sys.stdin); print(sum(s["state"]["label"] in ("healthy","running","completed") for s in d["services"]))' 2>/dev/null)"
    [ "$ok" = 11 ] && break
    sleep 3
done
if [ "$ok" = 11 ]; then pass "11/11 services up"; else
    fail "11/11 services up" "only $ok"
    api GET /project | python3 -c 'import json,sys; [print("        ", s["name"], s["state"]) for s in json.load(sys.stdin)["services"]]'
fi

echo "  -- run and fix pages"
check "ports owned by the project" GET /ports '' '"18080" in json.dumps(d) or 18080 in str(d)'
check "why (diagnosis)" GET /project/why '' 'd is not None'
check "timeline history" GET /project/history?hours=1 '' 'd is not None'
check "machine connections" GET /project/machine '' '"connections" in d'
check "service drawer hides env values" GET /services/api '' '"18080" not in json.dumps(d.get("env", ""))'

echo "  -- Data: Postgres"
check "databases listed" GET /data '' '"postgres" in json.dumps(d) and "redis" in json.dumps(d)'
check "postgres tables" GET /data/postgres/tables '' 'all(t in json.dumps(d) for t in ("orders", "products", "users"))'
check "table structure" GET '/data/postgres/structure?schema=public&table=orders' '' '"user_id" in json.dumps(d)'
check "browse rows" POST /data/postgres/rows '{"schema":"public","table":"products","limit":5}' 'len(d["rows"]) == 5'
check "filtered rows" POST /data/postgres/rows \
    '{"schema":"public","table":"orders","filters":[{"column":"status","op":"equals","value":"paid"}],"limit":3}' \
    'all("paid" in json.dumps(r) for r in d["rows"])'
check "run SQL" POST /data/postgres/query '{"sql":"select count(*) as n from users"}' '"n" in json.dumps(d)'
check_error "SQL error is explained" POST /data/postgres/query '{"sql":"select * from no_such_table"}' 'no_such_table'
check_error "unknown service is explained" GET /data/nope/tables '' 'nope\|no such\|not' 
check "query plan" POST /data/postgres/plan '{"sql":"select * from orders where status = '"'"'paid'"'"'"}' '"orders" in json.dumps(d)'
check "table diagram" GET /data/postgres/diagram '' '"orders" in json.dumps(d)'

echo "  -- Data: Redis"
check "redis keys" GET '/data/redis/keys?pattern=*' '' '"jobs" in json.dumps(d) or "orders" in json.dumps(d)'
check "redis key value" GET '/data/redis/key?name=orders' '' 'd is not None'
check "redis command line" POST /data/redis/command '{"line":"PING"}' '"PONG" in json.dumps(d)'
check "redis memory" GET /data/redis/memory '' 'd is not None'

echo "  -- API client and mock server"
check "send {{api.url}}/health" POST /http/send '{"method":"GET","url":"{{api.url}}/health"}' '"200" in json.dumps(d) or d.get("status") == 200'
check "import a curl command" POST /http/import/curl '{"command":"curl -X POST http://localhost:18080/orders -d x=1"}' '"POST" in json.dumps(d)'
MOCK_PORT=18977
check "save a mock route" POST /http/mock/routes '{"method":"GET","path":"/hello/:name","status":200,"body":"{\"hi\":\"{{name}}\"}"}' 'True'
check "start the mock server" POST /http/mock/start "{\"port\": $MOCK_PORT}" 'True'
if curl -s "http://127.0.0.1:$MOCK_PORT/hello/louay" | grep -q louay; then pass "mock server answers"; else fail "mock server answers"; fi
check "stop the mock server" POST /http/mock/stop '' 'True'

echo "  -- Mail"
check "mail catcher found" GET /mail '' '"mailpit" in json.dumps(d)'
# The worker sends a mail every few jobs; give it time.
got=0
for _ in $(seq 30); do
    api GET '/mail/mailpit/messages?limit=5' | grep -q 'shipped' && { got=1; break; }
    sleep 3
done
if [ "$got" = 1 ]; then pass "a mail from the worker arrived"; else fail "a mail from the worker arrived" "none after 90 s"; fi
MAIL_ID="$(api GET '/mail/mailpit/messages?limit=1' | python3 -c 'import json,sys,re; print(re.search(r"\"id\": ?\"([^\"]+)\"", json.dumps(json.load(sys.stdin))).group(1))' 2>/dev/null)"
[ -n "$MAIL_ID" ] && check "read the mail" GET "/mail/mailpit/messages/$MAIL_ID" '' '"shipped" in json.dumps(d)'

# ---------------------------------------------------------------------------------------------------------------------
if [ "$UI" = 1 ]; then
    step "5. UI click-through (headless browser)"
    # The edit test renames product 4 and back; an interrupted earlier run may have left the temporary name.
    docker exec devdesk-full-stack-postgres psql -q -U postgres shop -c "update products set title = 'Cap #4' where id = 4" >/dev/null
    DIST="$ROOT/ui/dist/devdesk-ui/browser"
    [ -d "$DIST" ] || DIST="$ROOT/ui/dist/devdesk-ui"
    # Port 4200 on purpose: the engine only accepts pages from the app's own addresses (see ApiServer.ALLOWED_ORIGINS).
    (cd "$DIST" && exec python3 -m http.server 4200 --bind 127.0.0.1 >/dev/null 2>&1) &
    UI_PID=$!
    echo "        installing playwright-core into the temp folder (not into the project)..."
    if (cd "$WORK" && npm init -y >/dev/null && PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm i playwright-core --no-audit --no-fund >/dev/null 2>&1); then
        mkdir -p "$WORK/shots"
        NODE_PATH="$WORK/node_modules" node "$ROOT/scripts/ui-click-through.js" "$PORT" "$TOKEN" "$WORK/shots" | tee "$WORK/ui.log"
        PASSED=$((PASSED + $(grep -c '^  ok' "$WORK/ui.log")))
        while IFS= read -r line; do FAILED=$((FAILED + 1)); FAILURES+=("ui: ${line#  FAIL  }"); done < <(grep '^  FAIL' "$WORK/ui.log")
        KEEP_SHOTS=1
    else
        fail "install playwright-core" "needs network access to npm"
    fi
fi

# ---------------------------------------------------------------------------------------------------------------------
step "Result"
echo "  $PASSED passed, $FAILED failed"
for f in "${FAILURES[@]}"; do red "  - $f"; done
if [ "${KEEP_SHOTS:-0}" = 1 ] && [ "$KEEP" = 0 ]; then echo "  (run with --keep to keep the screenshots and engine logs)"; fi
[ "$FAILED" = 0 ]
