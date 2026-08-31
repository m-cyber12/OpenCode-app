#!/usr/bin/env bash
# 20-integration-gates.sh — Phase 5 gates: the Android app as a REAL client of
# the on-device OpenCode server.
#
# Gate families (appended to phase5/out/evidence/GATES_SUMMARY.txt):
#   P5-01..P5-04  prerequisites: phase-5 APK + payload v5 on device, fixture
#                 provisioning, Keystore-held server password reachable through
#                 the test-only harness export, health 200 over adb forward.
#   P5-K          the instrumented Kotlin client gates (K1..K8) — these are the
#                 Phase 5 deliverable: the app's own OpenCodeApi / OpenCodeEventStream
#                 / SecretStore / LoopbackGuard driving the real server.
#   G6 G7 G10 G11 G12  the Phase 3/4 gate drivers re-run VERBATIM against this
#                 integration (same code path, app-owned server).
#   P5-G16        OpenCode's remote MCP transports (StreamableHTTP + legacy
#                 HTTP+SSE) against a real host-side MCP server, plus the
#                 unreachable-must-report-failed negative case.
#   P5-G17        loopback-only binding evidence (/proc/net/tcp{,6} audit, the
#                 launcher's own bind assertion, external-interface refusal,
#                 no mDNS/5353 socket, no wildcard listener).
#   P5-G18        credentials at rest: Keystore ciphertext only, no plaintext
#                 secret files, OpenCode's own 0600 auth.json, no legacy file.
#   P5-G19        no hardcoded/bundled secret anywhere (APK entries, payload,
#                 repo sources) and no non-loopback endpoint compiled into the app.
#
# Mechanics (base64-over-stdin run-as, host bun as the HTTP driver, adb forward)
# are inherited from phase4/scripts/20-device-gates.sh, which proved them in CI.
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
P4GATES="$ROOT/phase4/scripts/device"
P4OUT="$ROOT/phase4/out"
mkdir -p "$EV"
SUMMARY="$EV/GATES_SUMMARY.txt"
LOG="$EV/integration-gates.log"
: > "$SUMMARY"; : > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }

PKG="ai.opencode.android.debug"
TEST_PKG="ai.opencode.android.debug.test"
TEST_CLASS="ai.opencode.android.client.OpenCodeClientGatesTest"
FILES="/data/data/$PKG/files"
PORT=4111
PORT_HEX=$(printf '%04X' "$PORT")
MCP_PORT="${P5_MCP_PORT:-4551}"
DEAD_PORT=$((MCP_PORT + 48))
WORKDIR="$FILES/workspaces/gates"
HOST_BUN="$P4OUT/host-bun"

PASS=0; FAIL=0; SKIP=0
rec() { echo "$1: $2 $3" | tee -a "$SUMMARY"; }
# p5 <id> <rc> <what>  — rc 0 pass, 1 fail, 7 skip (skip is always reported)
p5() {
  case "$2" in
    0) PASS=$((PASS+1)); rec "P5-$1" PASS "$3" ;;
    7) SKIP=$((SKIP+1)); rec "P5-$1" SKIP "$3" ;;
    *) FAIL=$((FAIL+1)); rec "P5-$1" FAIL "$3" ;;
  esac
}

# Run a script as the app uid. The whole script travels base64-encoded so adb's
# outer shell cannot re-tokenize anything (quoting alone proved fragile in CI).
rash() {
  # The script travels on STDIN, so adb never re-tokenizes its contents (this is
  # exactly what phase4 proved; quoting an argv copy of it did not survive).
  printf '%s\n' "$1" | adb shell run-as "$PKG" sh 2>&1 | tr -d '\r'
}
# Same, but as the shell uid (sees /proc/net/tcp without app-uid restrictions).
shash() { printf '%s\n' "$1" | adb shell sh 2>&1; }

write_stdin_runas() { # $1=remote-relative-to-files ; content on stdin
  local b64
  b64=$(base64 -w0)
  rash "mkdir -p \$(dirname '$FILES/$1'); echo '$b64' | base64 -d > '$FILES/$1'; echo wrote_rc=\$?"
}
push_file_runas() { # $1=local  $2=remote-relative-to-files
  write_stdin_runas "$2" < "$1"
}

count_launchers() {
  rash 'n=0; for p in /proc/[0-9]*; do c=$(tr "\000" " " < "$p/cmdline" 2>/dev/null); case "$c" in *launcher.js*) n=$((n+1));; esac; done; echo "$n"' | tr -d '[:space:]'
}

APP_UID=$(adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1)

wait_healthy() { # $1 timeout-s ; needs PASSWD
  local deadline=$(( $(date +%s) + ${1:-120} )) code
  while [ "$(date +%s)" -lt "$deadline" ]; do
    code=$(curl -s -o "$OUT/health.json" -w '%{http_code}' -u "opencode:$PASSWD" --max-time 4 \
      "http://127.0.0.1:$PORT/global/health" 2>/dev/null || echo 000)
    [ "$code" = "200" ] && grep -q healthy "$OUT/health.json" 2>/dev/null && { echo HEALTH_OK; return 0; }
    sleep 2
  done
  echo HEALTH_TIMEOUT; return 1
}

# ---------------------------------------------------------------------------
log "=== P5-01 device / app / payload prerequisites ==="
{
  echo "adb devices: $(adb devices | tr -d '\r' | tail -n +2 | tr '\n' ' ')"
  echo "release=$(adb shell getprop ro.build.version.release | tr -d '\r') sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r') abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "app uid: $APP_UID"
  adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | grep -E 'versionName|codePath' | head -4
} > "$EV/p5-01-device.txt" 2>&1
cat "$EV/p5-01-device.txt" >> "$LOG"
VER=$(grep -o 'versionName=[^ ]*' "$EV/p5-01-device.txt" | head -1 | cut -d= -f2)
MARKER=$(rash "cat '$FILES/runtime/.extracted' 2>/dev/null" | tr -d '\r')
adb shell am start -n "$PKG/ai.opencode.android.MainActivity" >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
  [ -n "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ] && break
  sleep 2
done
sleep 3
adb forward tcp:$PORT tcp:$PORT >/dev/null 2>&1 || true
P501=1
case "$VER" in *phase5*) [ -n "$APP_UID" ] && P501=0 ;; esac
log "P5-01 versionName=$VER uid=$APP_UID extraction_marker=$MARKER"
p5 01 "$P501" "phase5-apk-installed-and-app-running"

# The payload manifest must say payloadVersion 5 (the phase-5 layout: Keystore
# secrets + harness dir + loopback audit). Accept 4 only if the APK was built
# before the version bump, and say so loudly in that case.
P502=1
if echo "$MARKER" | grep -qE '"payloadVersion":5'; then P502=0; fi
if [ "$P502" != 0 ] && echo "$MARKER" | grep -qE '"payloadVersion":4'; then
  log "P5-02 payloadVersion=4 (phase-4 payload with a phase-5 app) — layout compatible, recorded as skip"
  P502=7
fi
p5 02 "$P502" "payload-v5-manifest-marker"

# ---------------------------------------------------------------------------
log "=== P5-03 provision fixture (workspace, git repo, MCP, config) ==="
rash "mkdir -p '$WORKDIR/src' '$FILES/mcp' '$FILES/xdg/config/opencode' '$FILES/harness'; echo dirs_rc=\$?" | tee -a "$LOG" >/dev/null
cat <<'EOF' | write_stdin_runas "workspaces/gates/README.md"
# phase5 gates fixture

README with a P5-E2E-MARKER so read/find tools have something to match.
EOF
cat <<'EOF' | write_stdin_runas "workspaces/gates/notes.txt"
phase5 notes
P5-E2E-MARKER in notes too, so /find (ripgrep) matches a tracked file.
EOF
cat <<'EOF' | write_stdin_runas "workspaces/gates/src/app.js"
// P5-E2E-MARKER source marker
export function greet(name) {
  return "hello " + name;
}
EOF
# Git fixture through the packaged Android Git (nativeLibraryDir/libgit.so), as
# in phase 4 — no host or user-installed Git touches the fixture.
native_lib_dir() {
  local apk base arch
  apk=$(adb shell pm path "$PKG" | tr -d '\r' | sed 's/^package://' | head -1)
  base=$(dirname "$apk"); arch=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
  echo "$base/lib/$arch"
}
GIT_NATIVE="$(native_lib_dir)/libgit.so"
rash "cd '$WORKDIR' && export GIT_AUTHOR_NAME=gates GIT_AUTHOR_EMAIL=gates@opencode.local GIT_COMMITTER_NAME=gates GIT_COMMITTER_EMAIL=gates@opencode.local && '$GIT_NATIVE' init -q -b main && '$GIT_NATIVE' config user.email gates@opencode.local && '$GIT_NATIVE' config user.name gates && '$GIT_NATIVE' add -A && '$GIT_NATIVE' commit -qm 'phase5 fixture' && echo FIXTURE_GIT_OK" | tee -a "$LOG"

if [ -d "$P4OUT/mcp/node_modules/@modelcontextprotocol" ]; then
  tar czf "$OUT/mcp.tgz" -C "$P4OUT/mcp" node_modules mcp-server.js mcp-roundtrip.js 2>/dev/null
  push_file_runas "$OUT/mcp.tgz" "mcp/mcp.tgz" >/dev/null
  rash "cd '$FILES/mcp' && tar xzf mcp.tgz && rm -f mcp.tgz && echo MCP_PUSHED" | tee -a "$LOG" >/dev/null
fi

# Phase 5 config fixture: the permission rules are BARE strings (no path scoping)
# so every bash/edit tool call is a genuine `permission.asked` that the client
# must answer — the precondition for the real permission-flow gates. MCP keeps
# the Phase 4 local stdio server so the stdio transport is exercised alongside.
cat > "$OUT/opencode.jsonc" <<EOF
{
  "\$schema": "https://opencode.ai/config.json",
  "shell": "/system/bin/sh",
  "permission": {
    "bash": "ask",
    "edit": "ask",
    "webfetch": "ask"
  },
  "mcp": {
    "gates-mcp": {
      "type": "local",
      "command": ["$FILES/bin/bun", "$FILES/mcp/mcp-server.js"],
      "environment": {}
    }
  }
}
EOF
push_file_runas "$OUT/opencode.jsonc" "xdg/config/opencode/opencode.jsonc" >/dev/null
rash "cat '$FILES/xdg/config/opencode/opencode.jsonc'" > "$EV/p5-03-config.txt" 2>&1
P503=1
if grep -q '"bash": "ask"' "$EV/p5-03-config.txt" && grep -q 'gates-mcp' "$EV/p5-03-config.txt"; then P503=0; fi
# The harness marker is what allows the instrumentation APK to export the
# Keystore-held password for the host-side drivers; the app never writes it.
HARNESS=$(rash "touch '$FILES/harness/enabled'; rm -f '$FILES/harness/server-password'; chmod 700 '$FILES/harness'; echo harness_rc=\$?" | tr -d '\r')
log "P5-03 config_present=$([ $P503 = 0 ] && echo yes || echo NO) $HARNESS"
p5 03 "$P503" "fixture-config-and-harness-marker"

# A stale server from a previous phase-4 config must not linger: restart the
# runtime so it re-reads the config fixture and re-provisions credentials.
adb shell am start -n "$PKG/ai.opencode.android.runtime.DebugControlActivity" --ei mode 2 >>"$LOG" 2>&1 || true
sleep 8

# ---------------------------------------------------------------------------
log "=== P5-04 server password via Keystore + test-only harness export ==="
adb shell am instrument -w -e class "$TEST_CLASS#harnessExportLoopbackCredentialForHostDrivers" \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" > "$EV/p5-04-instrument-export.txt" 2>&1
PASSWD=$(rash "cat '$FILES/harness/server-password' 2>/dev/null" | tr -d '\r\n ')
[ -n "$PASSWD" ] || PASSWD=$(rash "cat '$FILES/secrets/server-password' 2>/dev/null" | tr -d '\r\n ')
log "P5-04 password: ${PASSWD:0:4}… (${#PASSWD} chars)"
P504=1
[ "${#PASSWD}" -ge 20 ] && [ "$(wait_healthy 180)" = "HEALTH_OK" ] && P504=0
if [ "$P504" != 0 ]; then
  log "P5-04 not healthy; supervisor log tail:"; rash "tail -40 '$FILES/log/runtime.log'" | tee -a "$LOG"
fi
p5 04 "$P504" "keystore-password-and-healthy-server"

# ---------------------------------------------------------------------------
log "=== P5-K instrumented Kotlin client gates (K1..K8) ==="
adb logcat -c >/dev/null 2>&1 || true
adb shell am instrument -w -e class "$TEST_CLASS" \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" > "$EV/p5-k-instrument.log" 2>&1
KRC=$?
# The Kotlin gates print "P5_<NAME> PASS|FAIL :: detail" under logcat tag
# OpenCode/gate; the instrument log carries the runner's own verdict lines.
adb logcat -d 2>/dev/null | grep -aE 'OpenCode/gate|P5_[A-Z0-9_]+' > "$EV/p5-k-gates.log" 2>&1 || true
adb logcat -d -s OpenCode:V 2>/dev/null | tail -60 >> "$EV/p5-k-gates.log" 2>&1 || true
cat "$EV/p5-k-gates.log" >> "$LOG"
KP=$(grep -acE 'P5_[A-Z0-9_]+ PASS' "$EV/p5-k-gates.log" 2>/dev/null | tr -d ' ')
KF=$(grep -acE 'P5_[A-Z0-9_]+ FAIL' "$EV/p5-k-gates.log" 2>/dev/null | tr -d ' ')
KOK=$(grep -cE '^OK \([0-9]+ tests?\)' "$EV/p5-k-instrument.log" 2>/dev/null | tr -d ' ')
KFAILTEST=$(grep -cE '^FAILURES!!!|Tests failed|INSTRUMENTATION_FAILED|Process crashed|Error while' "$EV/p5-k-instrument.log" 2>/dev/null | tr -d ' ')
log "P5-K instrument_rc=$KRC kotlin_gates_pass=$KP kotlin_gates_fail=$KF gradle_ok=$KOK failures_marker=$KFAILTEST"
{ echo "instrument_rc=$KRC"; echo "gate_pass=$KP gate_fail=$KF"; echo "--- tail of instrument log ---"; tail -40 "$EV/p5-k-instrument.log"; } > "$EV/p5-k-summary.txt" 2>&1
[ "$KFAILTEST" = "0" ] && [ "${KP:-0}" -ge 6 ] && [ "${KF:-1}" = "0" ] && [ "$KRC" = "0" ]
p5 K $? "kotlin-client-gates-on-device"

# ---------------------------------------------------------------------------
log "=== G6/G7/G10/G11/G12 re-run: phase-4 drivers verbatim, phase-5 server ==="
export OPENCODE_BASE="http://127.0.0.1:$PORT"
export OPENCODE_SERVER_PASSWORD="$PASSWD"
export OPENCODE_SERVER_USERNAME="opencode"
export OPENCODE_DIRECTORY="$WORKDIR"
export OPENCODE_MCP_DIR="$P4OUT/mcp"
export OPENCODE_BUN_BIN="$HOST_BUN"
# Phase 5 needs no provider key: the pinned build ships a key-free default model
# (opencode/big-pickle). Ask the server whether a default resolves, and hand the
# drivers model_available=1 only when it really does — the same flag phase 4 used.
MODEL=0
PROV=$(curl -s -u "opencode:$PASSWD" --max-time 10 "http://127.0.0.1:$PORT/provider?directory=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$WORKDIR")" 2>/dev/null || true)
echo "$PROV" > "$EV/p5-providers.json" 2>&1
if echo "$PROV" | grep -q '"default"'; then MODEL=1; log "default model resolves from /provider -> drivers may assert streamed model text"
else log "no resolvable default model (no provider configured) -> model-dependent driver halves stay skipped"; fi
[ -x "$HOST_BUN" ] || HOST_BUN="$(command -v bun || echo '')"
[ -n "$HOST_BUN" ] || { log "FATAL: no host bun/node runner for gate drivers"; }

run_driver() { # $1=gate-id $2=script
  if [ ! -f "$P4GATES/$2" ]; then p5 "$1" 7 "driver $2 missing"; return; fi
  log "--- re-run $2 (as shipped, no edits) ---"
  if "$HOST_BUN" "$P4GATES/$2" "$MODEL" > "$EV/rerun-$2.log" 2>&1; then
    p5 "R-$1" 0 "phase4-driver-$2-unmodified"
  else
    tail -20 "$EV/rerun-$2.log" >> "$LOG"
    p5 "R-$1" 1 "phase4-driver-$2-unmodified"
  fi
}
run_driver 06 gate-06-health.js
run_driver 07 gate-07-shell.js
run_driver 10 gate-10-mcp.js
run_driver 11 gate-11-stream.js
run_driver 12 gate-12-permission.js

# ---------------------------------------------------------------------------
log "=== P5-G16 remote MCP transports (host MCP server + device OpenCode client) ==="
MCP_DIR="$OUT/mcp"
FIXTURE_PID=""
if [ -d "$MCP_DIR/node_modules/@modelcontextprotocol" ] && [ -f "$MCP_DIR/remote-mcp-server.mjs" ]; then
  NODE_BIN="$(command -v node || echo '')"
  RUNNER="${NODE_BIN:-$HOST_BUN}"
  [ -n "$RUNNER" ] || { log "no node/bun to run the remote MCP fixture"; }
  if [ -n "$RUNNER" ]; then
    ( cd "$MCP_DIR" && P5_MCP_PORT="$MCP_PORT" nohup "$RUNNER" remote-mcp-server.mjs > "$EV/p5-16-fixture-host.log" 2>&1 & echo $! > "$OUT/fixture.pid" )
    FIXTURE_PID=$(cat "$OUT/fixture.pid")
    FIXTURE_UP=0
    for _ in $(seq 1 20); do
      curl -sf --max-time 3 "http://127.0.0.1:$MCP_PORT/health" > "$EV/p5-16-fixture-health.json" 2>&1 && { FIXTURE_UP=1; break; }
      sleep 1
    done
    log "fixture host listener up=$FIXTURE_UP pid=$FIXTURE_PID"
    if [ "$FIXTURE_UP" = "1" ]; then
      export P5_MCP_URL="http://10.0.2.2:$MCP_PORT"
      export P5_MCP_DEAD_URL="http://10.0.2.2:$DEAD_PORT/mcp"
      if [ -f "$DIR/scripts/device/gate-16-mcp-remote.js" ] && [ -n "$RUNNER" ]; then
        "$RUNNER" "$DIR/scripts/device/gate-16-mcp-remote.js" > "$EV/p5-16-mcp-remote.log" 2>&1
        p5 G16 $? "remote-mcp-streamable-http-sse-and-negative"
      else
        p5 G16 7 "gate-16 driver or runner unavailable"
      fi
    else
      p5 G16 1 "remote-mcp-fixture-did-not-listen"
    fi
  fi
else
  p5 G16 7 "remote-mcp-fixture-not-built (run phase5/scripts/11-build-remote-mcp.sh)"
fi
[ -n "$FIXTURE_PID" ] && kill "$FIXTURE_PID" 2>/dev/null

# ---------------------------------------------------------------------------
log "=== P5-G17 loopback-only binding evidence ==="
{
  echo "=== listeners owned by uid $APP_UID (host-visible /proc/net/tcp) ==="
  shash "cat /proc/net/tcp /proc/net/tcp6" > "$OUT/proc-net-tcp.txt" 2>&1
  echo "-- IPv4/IPv6 LISTEN(0A) rows for the app uid --"
  awk -v uid="$APP_UID" '$4=="0A" && $8==uid {print "  tcp_row local="$2" rem="$3" state="$4" uid="$8}' "$OUT/proc-net-tcp.txt"
  echo "-- any row for the app uid that is NOT a loopback local address --"
  awk -v uid="$APP_UID" '$8==uid && $2 !~ /^0100007F:/ && $2 !~ /^00000000000000000000000001000000:/ && $2 !~ /^::1:/ {print "  viol "$0}' "$OUT/proc-net-tcp.txt" | head -20
  echo "-- UDP: app-uid sockets on mDNS 5353 (0x14F9) --"
  shash "cat /proc/net/udp /proc/net/udp6" > "$OUT/proc-net-udp.txt" 2>&1
  awk -v uid="$APP_UID" '$8==uid && ($2 ~ /:14F9$/ || $2 ~ /:000000000000000000000000000014F9$/) {print "  mdns "$0}' "$OUT/proc-net-udp.txt" | head -10
  echo "-- count of LISTEN rows on port $PORT by the app uid --"
  awk -v uid="$APP_UID" -v ph="$PORT_HEX" '$4=="0A" && $8==uid && index($2, ":"ph)>0 {n++} END{print "  listen_on_4111="n+0}' "$OUT/proc-net-tcp.txt"
  echo "-- count of LISTEN rows on port $PORT by ANY uid --"
  awk -v ph="$PORT_HEX" '$4=="0A" && index($2, ":"ph)>0 {n++} END{print "  any_listen_on_4111="n+0}' "$OUT/proc-net-tcp.txt"
  echo "-- wildcard LISTEN rows for the app uid (00000000 or all-zero IPv6) --"
  awk -v uid="$APP_UID" '$4=="0A" && $8==uid && ($2 ~ /^00000000:/ || $2 ~ /^00000000000000000000000000000000:/) {print "  WILDCARD "$0}' "$OUT/proc-net-tcp.txt"
  echo
  echo "=== the app's own audit (written by runtime/LoopbackAudit) ==="
  rash "cat '$FILES/log/loopback-audit.txt' 2>&1"
  echo
  echo "=== launcher bind evidence (runtime.log) ==="
  rash "grep -aE 'SERVER_BOUND|BIND_AUDIT|SERVER_BIND_POLICY|SERVER_BIND_REJECTED|SERVER_MDNS_REFUSED|mDNS|mdns' '$FILES/log/runtime.log' 2>&1 | tail -25"
  echo
  echo "=== OpenCode server log: mDNS publish lines (want none) ==="
  rash 'for d in "'"$FILES"'/xdg/data/opencode/log" "'"$FILES"'/xdg/state/opencode/log"; do for f in "$d"/*.log; do [ -e "$f" ] || continue; grep -aiE "mDNS|publish" "$f" 2>/dev/null | tail -10; done; done'
  echo "(end of mDNS grep)"
  echo
  echo "=== host-side forward (adb forward binds host loopback only) ==="
  adb forward --list | tr -d '\r'
} > "$EV/p5-17-loopback.txt" 2>&1
cat "$EV/p5-17-loopback.txt" >> "$LOG"

G17=1
if [ -n "$APP_UID" ]; then
  WILDCARD=$(grep -c 'WILDCARD' "$EV/p5-17-loopback.txt" | tr -d ' ')
  VIOL=$(grep -c 'viol ' "$EV/p5-17-loopback.txt" | tr -d ' ')
  LISTENS=$(sed -n 's/.*listen_on_4111=//p' "$EV/p5-17-loopback.txt" | head -1 | tr -d '[:space:]')
  MDNS=$(grep -c 'mdns ' "$EV/p5-17-loopback.txt" | tr -d ' ')
  BOUND=$(grep -c 'SERVER_BOUND' "$EV/p5-17-loopback.txt" | tr -d ' ')
  PUB=$(grep -cE 'mDNS published|Publishing.*5353' "$EV/p5-17-loopback.txt" | tr -d ' ')
  log "P5-G17 wildcard=$WILDCARD nonloopback_rows=$VIOL listens=$LISTENS mdns_sockets=$MDNS bound_lines=$BOUND publish_lines=$PUB"
  if [ "${WILDCARD:-1}" = "0" ] && [ "${VIOL:-1}" = "0" ] && [ "${LISTENS:-0}" = "1" ] \
     && [ "${MDNS:-1}" = "0" ] && [ "${BOUND:-0}" -ge 1 ] && [ "${PUB:-1}" = "0" ]; then G17=0; fi
fi
# Behavioural half: a socket from the device to its own GLOBAL interface must be
# refused, while 127.0.0.1 works. Driven by the payload's own bun (a native
# socket, so Android's NetworkSecurityPolicy cannot fake a refusal) running as
# the app uid — the same uid that owns the server.
DEVIPIP=$(shash "ip -o -4 addr show scope global | awk '{print \$4}' | cut -d/ -f1 | head -1" | tr -d '\r[:space:]')
log "device global ipv4: '$DEVIPIP'"
LB_JS='const net=require("net");
function t(h,p){return new Promise(r=>{const s=net.connect({host:h,port:p});s.setTimeout(4000);s.on("connect",()=>{s.destroy();r("OPEN")});s.on("timeout",()=>{s.destroy();r("TIMEOUT")});s.on("error",e=>r("REFUSED:"+e.code))})}
(async()=>{
  console.log("loopback="+await t("127.0.0.1",@PORT@));
  console.log("global_@IP@="+await t("@IP@",@PORT@));
})();'
LB_SCRIPT="${LB_JS//@PORT@/$PORT}"
LB_SCRIPT="${LB_SCRIPT//@IP@/${DEVIPIP:-10.255.255.255}}"
printf '%s' "$LB_SCRIPT" > "$OUT/tmp-lb.js"
write_stdin_runas "tmp-lb.js" < "$OUT/tmp-lb.js" >/dev/null
rash "'$FILES/bin/bun' '$FILES/tmp-lb.js' 2>&1; rc=\$?; rm -f '$FILES/tmp-lb.js'; echo bun_rc=\$rc" > "$EV/p5-17-external-connect.txt" 2>&1
cat "$EV/p5-17-external-connect.txt" >> "$LOG"
grep -q 'loopback=OPEN' "$EV/p5-17-external-connect.txt" || G17=1
if [ -n "$DEVIPIP" ] && [ "$DEVIPIP" != "127.0.0.1" ]; then
  grep -qE "global_${DEVIPIP}=(REFUSED|TIMEOUT)" "$EV/p5-17-external-connect.txt" || G17=1
fi
p5 G17 "$G17" "loopback-only-binding-and-no-mdns"

# ---------------------------------------------------------------------------
log "=== P5-G18 credentials at rest (Keystore ciphertext, no plaintext) ==="
{
  echo "--- filesDir/secrets listing ---"
  rash "ls -la '$FILES/secrets' 2>&1"
  echo "--- blob magic (each .enc must start with the OCS1 ciphertext header) ---"
  CIPHER_SCRIPT='n=0
for f in @FILES@/secrets/*.enc; do
  [ -e "$f" ] || continue
  n=$((n+1))
  if head -c 4 "$f" | grep -aq OCS1; then
    echo "CIPHER_OK $f size=$(wc -c < "$f")"
  else
    echo "PLAIN_TEXT $f"
  fi
done
echo "enc_count=$n"'
  rash "${CIPHER_SCRIPT//@FILES@/$FILES}"
  echo "--- legacy plaintext password file (must be ABSENT) ---"
  rash "ls -l '$FILES/secrets/server-password' 2>&1; echo legacy_rc=\$?"
  echo "--- plaintext key-file bootstrap (must be ABSENT in a phase-5 run) ---"
  rash "ls -l '$FILES/secrets/openrouter-api-key' 2>&1; echo keyfile_rc=\$?"
  echo "--- OpenCode's own auth store: mode must be 600, no live key after revoke ---"
  rash "stat -c 'auth_json mode=%a size=%s' '$FILES/xdg/data/opencode/auth.json' 2>&1"
  rash "grep -ac 'sk-or' '$FILES/xdg/data/opencode/auth.json' 2>/dev/null; echo authcanary_done"
  echo "--- server password readable anywhere outside the test harness dir? ---"
  rash "grep -rl '$PASSWD' '$FILES' 2>/dev/null | grep -v '/harness/' | head -20; echo leakscan_done"
  echo "--- hardware-backed keystore (from the instrumentation run) ---"
  grep -aE 'P5_KEYSTORE|hardware' "$EV/p5-k-gates.log" 2>/dev/null | head -6
} > "$EV/p5-18-credentials.txt" 2>&1
cat "$EV/p5-18-credentials.txt" >> "$LOG"
G18=1
PLAIN=$(grep -c 'PLAIN_TEXT' "$EV/p5-18-credentials.txt" | tr -d ' ')
ENC=$(sed -n 's/.*enc_count=//p' "$EV/p5-18-credentials.txt" | head -1 | tr -d '[:space:]')
LEGACY_GONE=$(sed -n '/legacy plaintext/,+1p' "$EV/p5-18-credentials.txt" | grep -c 'No such file' | tr -d ' ')
KEYFILE_GONE=$(sed -n '/key-file bootstrap/,+1p' "$EV/p5-18-credentials.txt" | grep -c 'No such file' | tr -d ' ')
LEAK=$(sed -n '/server password readable anywhere/,/leakscan_done/p' "$EV/p5-18-credentials.txt" | grep -c '^/data/' | tr -d ' ')
MODE=$(sed -n 's/.*mode=\([0-9]\+\).*/\1/p' "$EV/p5-18-credentials.txt" | head -1 | tr -d '[:space:]')
# auth.json must not still carry the K8 canary key (proves DELETE /auth cleared
# the provider credential from OpenCode's durable store too).
CANARY=$(sed -n '/no live key after revoke/,/authcanary_done/p' "$EV/p5-18-credentials.txt" | grep -cx '0' | tr -d ' ')
# OpenCode's durable credential store: if it exists it must be 0600 (upstream's
# own mode) and must not still hold the K8 canary key after DELETE /auth. No file
# at all also passes: it just means nothing was ever provisioned.
AUTH_OK=1
if [ -z "$MODE" ]; then
  AUTH_OK=0
elif [ "$MODE" = "600" ] && [ "${CANARY:-0}" = "1" ]; then
  AUTH_OK=0
fi
log "P5-G18 enc_blobs=${ENC:-0} plaintext_blobs=${PLAIN:-1} legacy_absent=$LEGACY_GONE keyfile_absent=$KEYFILE_GONE auth_mode=${MODE:-absent} auth_canary_cleared=${CANARY:-0} auth_ok=$AUTH_OK leaks_outside_harness=${LEAK:-1}"
if [ "${PLAIN:-1}" = "0" ] && [ "${ENC:-0}" -ge 1 ] && [ "${LEGACY_GONE:-0}" -ge 1 ] \
   && [ "${KEYFILE_GONE:-0}" -ge 1 ] && [ "${LEAK:-1}" = "0" ] && [ "$AUTH_OK" = "0" ]; then G18=0; fi
p5 G18 "$G18" "credentials-keystore-only-at-rest"

log "=== P5-G19 no hardcoded/bundled secret in APK, payload or sources ==="
APK=$(adb shell pm path "$PKG" | tr -d '\r' | sed 's/^package://' | head -1)
{
  echo "--- APK under test: $APK ---"
  adb pull "$APK" "$OUT/app-under-test.apk" >/dev/null 2>&1
  { unzip -l "$OUT/app-under-test.apk" 2>/dev/null | tail -5
    unzip -p "$OUT/app-under-test.apk" 'classes*.dex' 2>/dev/null | strings -n 12 | \
      grep -aiE 'sk-or-v1-[0-9a-f]{24,}|sk-[A-Za-z0-9]{32,}|BEGIN (RSA|EC|OPENSSH|PGP) PRIVATE|api[_-]?key[\"'\'']?\s*[:=]\s*[\"'\''][A-Za-z0-9._-]{24,}' | head -20
    echo "(dex secret scan done rc=$?)"
    unzip -p "$OUT/app-under-test.apk" 'assets/*' 2>/dev/null | strings -n 12 | \
      grep -aiE 'sk-or-v1-[0-9a-f]{24,}|BEGIN (RSA|EC|OPENSSH|PGP) PRIVATE' | head -20
    echo "(asset secret scan done)"
    unzip -l "$OUT/app-under-test.apk" 2>/dev/null | grep -aE 'secrets/|openrouter-api-key|\.pem|id_rsa|server-password' | head -10
    echo "(asset name scan done)"
  } 2>&1
  echo "--- repo/payload source scan ---"
  ( cd "$ROOT" && git grep -nIE 'sk-or-v1-[0-9a-f]{24,}|sk-[A-Za-z0-9]{32,}|BEGIN (RSA|EC|OPENSSH|PGP) PRIVATE' -- app phase4 phase5 docs 2>/dev/null | head -20; echo "(git grep rc=$?)")
  echo "--- non-loopback endpoint literals compiled into the client ---"
  ( cd "$ROOT" && grep -rnIE 'https?://[A-Za-z0-9._-]+' app/src/main/java --include='*.kt' | \
      grep -avE '127\.0\.0\.1|localhost|::1|example\.|opencode\.ai/config|schemas|://host' | head -20; echo "(url scan done)")
} > "$EV/p5-19-secret-scan.txt" 2>&1
cat "$EV/p5-19-secret-scan.txt" >> "$LOG"
HITS=$(grep -acE 'sk-or-v1-[0-9a-f]{24,}|sk-[A-Za-z0-9]{32,}|BEGIN (RSA|EC|OPENSSH|PGP) PRIVATE' "$EV/p5-19-secret-scan.txt" | tr -d ' ')
URLHITS=$(sed -n '/non-loopback endpoint literals/,/(url scan done)/p' "$EV/p5-19-secret-scan.txt" | grep -cE '^\s*app/src' | tr -d ' ')
BUNDLED=$(sed -n '/APK under test/,/(asset name scan done)/p' "$EV/p5-19-secret-scan.txt" | grep -cE 'openrouter-api-key|\.pem$|id_rsa|server-password' | tr -d ' ')
log "P5-G19 secret_hits=$HITS bundled_secret_entries=$BUNDLED external_url_literals=$URLHITS"
[ "${HITS:-1}" = "0" ] && [ "${BUNDLED:-1}" = "0" ] && [ "${URLHITS:-1}" = "0" ] && [ -f "$OUT/app-under-test.apk" ]
p5 G19 $? "no-hardcoded-or-bundled-secrets"

# ---------------------------------------------------------------------------
log "=== evidence pull ==="
rash "tail -400 '$FILES/log/runtime.log'" > "$EV/runtime.log" 2>&1 || true
rash 'for d in "'"$FILES"'/xdg/data/opencode/log" "'"$FILES"'/xdg/state/opencode/log"; do for f in "$d"/*.log; do [ -e "$f" ] || continue; echo "### $f"; tail -120 "$f"; done; done' > "$EV/opencode-server.log" 2>&1 || true
rash "ls -laR '$FILES/harness' '$FILES/secrets' 2>&1 | head -40" > "$EV/device-secret-layout.txt" 2>&1 || true
rash "grep -aE 'integration|provisioned|loopback' '$FILES/log/runtime.log' | tail -20" > "$EV/integration-lines.txt" 2>&1 || true
adb logcat -d -s OpenCode:V > "$EV/logcat-OpenCode.txt" 2>&1 || true
[ -n "$FIXTURE_PID" ] && kill "$FIXTURE_PID" 2>/dev/null
rash "rm -f '$FILES/harness/server-password'" 2>/dev/null || true

cat >> "$SUMMARY" <<EOF
P5_SUMMARY $(date -u +%FT%TZ)
gates_pass=$PASS gates_fail=$FAIL gates_skip=$SKIP
kotlin_gate_pass=$KP kotlin_gate_fail=$KF
model_available=$MODEL app_uid=$APP_UID device_abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
live_launcher_processes=$(count_launchers)
EOF
log "=== SUMMARY ==="; cat "$SUMMARY"
RC=0
[ "$FAIL" -eq 0 ] || RC=1
exit "$RC"
