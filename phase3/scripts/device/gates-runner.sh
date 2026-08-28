#!/system/bin/sh
# gates-runner.sh — the Phase 3 gate suite, executed ON the Android emulator.
# Runs as root (adb root) on a google_apis image. Each gate writes
# $GATES_DIR/out/gate-NN-*.log and prints GATE_NN: PASS|FAIL|BLOCKED lines;
# the summary lands in $GATES_DIR/out/GATES_SUMMARY.txt.
#
# Real components only: Android native ELF exec (bionic), /system/bin/sh (mksh),
# Bun-for-Android 1.3.14, static musl ripgrep 15.1.0, static musl git v2.48.1,
# the real OpenCode server bundle @ 05ea5073, the real @modelcontextprotocol/sdk.

export GATES_DIR=/data/local/tmp/gates
export PATH="$GATES_DIR/bin:/system/bin:/system/xbin"
export HOME="$GATES_DIR/home"
export XDG_DATA_HOME="$GATES_DIR/data"
export XDG_CONFIG_HOME="$GATES_DIR/config"
export XDG_STATE_HOME="$GATES_DIR/state"
export XDG_CACHE_HOME="$GATES_DIR/cache"
export TMPDIR="$GATES_DIR/tmp"
export SHELL=/system/bin/sh
export OPENCODE_SERVER_USERNAME=opencode
export OPENCODE_SERVER_PASSWORD=gates-password
export OPENCODE_CLIENT=android-gates
export OPENCODE_SERVER_PORT=4111

OUT="$GATES_DIR/out"
mkdir -p "$OUT" "$HOME" "$XDG_DATA_HOME" "$XDG_CONFIG_HOME" "$XDG_STATE_HOME" "$XDG_CACHE_HOME" "$TMPDIR" \
         "$GATES_DIR/project" "$GATES_DIR/logs"
: > "$OUT/gates-runner.log"
exec > "$OUT/gates-runner.log" 2>&1   # full transcript also lives here

log() { echo "[$(date -u +%FT%TZ)] $*"; }

# ---------------- model availability (key file pushed by the host driver) ----------------
if [ -s "$GATES_DIR/.api-key" ]; then
  MODEL_AVAILABLE=1
  export OPENROUTER_API_KEY="$(cat "$GATES_DIR/.api-key")"
  log "MODEL_AVAILABLE=1 (key present, $(echo -n "$OPENROUTER_API_KEY" | wc -c) chars)"
else
  MODEL_AVAILABLE=0
  log "MODEL_AVAILABLE=0 (no key — model gates will be BLOCKED)"
fi
unset OPENROUTER_API_KEY   # never echo the key; server reads the file itself

log "=== GATES RUNNER START $(date -u +%FT%TZ) ==="
log "uname: $(uname -a)"
log "release=$(getprop ro.build.version.release) sdk=$(getprop ro.build.version.sdk) abi=$(getprop ro.product.cpu.abi)"

GATE_PASS=0; GATE_FAIL=0; GATE_BLOCKED=0; GATE_TOTAL=0
record() {  # record <nn> <name> <PASS|FAIL|BLOCKED> [detail...]
  GATE_TOTAL=$((GATE_TOTAL+1))
  case "$3" in PASS) GATE_PASS=$((GATE_PASS+1));; FAIL) GATE_FAIL=$((GATE_FAIL+1));; BLOCKED) GATE_BLOCKED=$((GATE_BLOCKED+1));; esac
  echo "GATE_$1: $3 $2 ${4:-}" | tee -a "$OUT/GATES_SUMMARY.txt"
}

# =====================================================================
# G1 — Android native host can launch the execution layer.
# =====================================================================
gate_01() {
  G="gate-01-execution-layer.log"; : > "$OUT/$G"
  {
    log "=== G1: Android native host launches the execution layer ==="
    echo "-- android facts:"; id; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abi
    echo "-- ELF interp of the bundled runtime (bionic expected):"
    readelf -l "$GATES_DIR/bin/bun" 2>/dev/null | grep -A1 INTERP || echo "(readelf not on device)"
    echo "-- execute the runtime from the Android filesystem:"
    "$GATES_DIR/bin/bun" --version
    "$GATES_DIR/bin/bun" -e 'console.log("bun platform=" + process.platform + " arch=" + process.arch)'
    "$GATES_DIR/bin/bun" -e 'console.log("bun pid=" + process.pid + " ppid=" + process.ppid)'
  } >> "$OUT/$G" 2>&1
  if grep -q "^1.3.14" "$OUT/$G" && grep -q 'platform=android' "$OUT/$G" && grep -q 'arch=x64' "$OUT/$G"; then
    record 01 "execution-layer" PASS
  else
    record 01 "execution-layer" FAIL
  fi
}

# =====================================================================
# G2 — Execution layer can boot the minimal userspace.
# =====================================================================
gate_02() {
  G="gate-02-userspace.log"; : > "$OUT/$G"
  {
    log "=== G2: minimal userspace boots ==="
    echo "-- env:"; env | sort | head -20
    echo "-- dirs:"; ls -ld "$HOME" "$XDG_DATA_HOME" "$XDG_CONFIG_HOME" "$XDG_STATE_HOME" "$XDG_CACHE_HOME" "$TMPDIR"
    echo "-- /proc:"; head -1 /proc/version; head -1 /proc/loadavg
    echo "-- tmp writable:"; echo probe > "$TMPDIR/probe.txt" && cat "$TMPDIR/probe.txt"
    echo "-- bundled tools:"; "$GATES_DIR/bin/rg" --version | head -1; "$GATES_DIR/bin/git" --version
    echo "-- sqlite via bun:"; "$GATES_DIR/bin/bun" -e '
      const { Database } = require("bun:sqlite");
      const db = new Database(process.env.TMPDIR + "/userspace.db");
      db.run("CREATE TABLE IF NOT EXISTS t (k TEXT, v TEXT)");
      db.run("INSERT OR REPLACE INTO t VALUES (?, ?)", ["g2", "userspace-ok"]);
      const row = db.query("SELECT v FROM t WHERE k=?").get("g2");
      console.log("SQLITE_ROW=" + JSON.stringify(row));
      db.close();
    '
  } >> "$OUT/$G" 2>&1
  if grep -q "SQLITE_ROW=.*userspace-ok" "$OUT/$G" && grep -q "^ripgrep 15.1.0" "$OUT/$G" && grep -q "^git version" "$OUT/$G"; then
    record 02 "userspace" PASS
  else
    record 02 "userspace" FAIL
  fi
}

# =====================================================================
# G3 — Real shell executes commands.
# =====================================================================
gate_03() {
  G="gate-03-shell.log"; : > "$OUT/$G"
  {
    log "=== G3: real shell executes commands ==="
    echo "shell=$(readlink -f /system/bin/sh)"
    /system/bin/sh -c 'echo "G3_SHELL name=$(basename $0) ver=$KSH_VERSION"'
    /system/bin/sh -c 'x=G3_VAR; echo "var=$x" | tr a-z A-Z; (cd /data/local/tmp && pwd); echo "pipe_rc=$?"; false; echo "rc=$?"; for i in 1 2 3; do printf "%s" "$i"; done; echo'
    /system/bin/sh -c 'printf "G3_FILE_OK\n" > /data/local/tmp/g3-redirect.txt && cat /data/local/tmp/g3-redirect.txt && rm -f /data/local/tmp/g3-redirect.txt'
  } >> "$OUT/$G" 2>&1
  if grep -q "G3_SHELL name=sh" "$OUT/$G" && grep -q "G3_VAR" "$OUT/$G" && grep -q "G3_FILE_OK" "$OUT/$G" && grep -q "123" "$OUT/$G"; then
    record 03 "real-shell" PASS
  else
    record 03 "real-shell" FAIL
  fi
}

# =====================================================================
# G4 — Real Bun/runtime executes successfully (required: Phase 1 chose
#     Bun-for-Android as the server runtime; G2/G5 depend on it).
# =====================================================================
gate_04() {
  G="gate-04-runtime.log"; : > "$OUT/$G"
  {
    log "=== G4: real Bun runtime ==="
    "$GATES_DIR/bin/bun" --version
    "$GATES_DIR/bin/bun" -e '
      const fs = require("fs");
      fs.writeFileSync(process.env.TMPDIR + "/bun-fs.txt", "BUN_FS_OK\n");
      console.log("fs=" + fs.readFileSync(process.env.TMPDIR + "/bun-fs.txt", "utf8").trim());
      const { spawnSync } = require("child_process");
      const r = spawnSync("/system/bin/sh", ["-c", "echo BUN_SPAWN_OK"], { encoding: "utf8" });
      console.log("spawn=" + r.stdout.trim() + " rc=" + r.status);
      console.log("http-check=" + (typeof fetch === "function" ? "fetch-available" : "NO-FETCH"));
      const { Database } = require("bun:sqlite");
      const db = new Database(":memory:");
      db.run("CREATE TABLE t (v TEXT)"); db.run("INSERT INTO t VALUES (?)", ["BUN_SQLITE_OK"]);
      console.log("sqlite=" + db.query("SELECT v FROM t").get().v);
    '
  } >> "$OUT/$G" 2>&1
  if grep -q "BUN_FS_OK" "$OUT/$G" && grep -q "BUN_SPAWN_OK" "$OUT/$G" && grep -q "BUN_SQLITE_OK" "$OUT/$G"; then
    record 04 "runtime" PASS
  else
    record 04 "runtime" FAIL
  fi
}

# =====================================================================
# project fixture (used by G5–G15): a small real git repo
# =====================================================================
make_project() {
  P="$GATES_DIR/project"
  rm -rf "$P" && mkdir -p "$P/src"
  cat > "$P/README.md" <<'EOF'
# Phase-3 gates project — G15-E2E-MARKER

This repository is the fixture project for the Phase 3 runtime gates (G1–G15):
it exists to be inspected by the real OpenCode agent running on the Android
emulator. It is an Android app project whose agent backend is the real
OpenCode server, running on-device under Bun-for-Android with a real shell,
real Git, real ripgrep and real MCP support. No mocks, no fake components,
no remote fallback.
EOF
  cat > "$P/src/app.js" <<'EOF'
// A tiny fixture module for the gates project.
export function describeProject() {
  return "Phase-3 gates project: real OpenCode agent on Android";
}
EOF
  cat > "$P/notes.txt" <<'EOF'
Phase 3 gate fixture notes
- G9 exercises real git in this repo (init/status/add/commit/diff/branches)
- G15 asks the agent to inspect this project and explain what it does
EOF
  cd "$P" || return 1
  export GIT_AUTHOR_NAME=gates GIT_AUTHOR_EMAIL=gates@localhost
  export GIT_COMMITTER_NAME=gates GIT_COMMITTER_EMAIL=gates@localhost
  export GIT_EDITOR=true
  git init -q -b main
  git config user.name gates
  git config user.email gates@localhost
  git add README.md src/app.js notes.txt
  git commit -q -m "gates fixture: initial import"
  git checkout -q -b feature/g9
  echo "branch work" >> notes.txt
  git add notes.txt
  git commit -q -m "gates fixture: feature branch commit"
  git checkout -q main
  echo "G9_REPO_READY branch=$(git branch --show-current) commits=$(git rev-list --count HEAD)"
}

# =====================================================================
# opencode.jsonc — written before the server starts (real config the server
# reads: shell for the bash tool, model for the agent, local MCP server).
# =====================================================================
write_config() {
  MODEL="$(cat "$GATES_DIR/.model" 2>/dev/null || echo "nvidia/nemotron-3-ultra-550b-a55b:free")"
  mkdir -p "$XDG_CONFIG_HOME/opencode" "$XDG_DATA_HOME/opencode"
  cat > "$XDG_CONFIG_HOME/opencode/opencode.jsonc" <<EOF
{
  "\$schema": "https://opencode.ai/config.json",
  "shell": "/system/bin/sh",
  "model": "openrouter/$MODEL",
  "mcp": {
    "gates-mcp": {
      "type": "local",
      "command": ["/data/local/tmp/gates/bin/bun", "/data/local/tmp/gates/mcp/mcp-server.js"],
      "enabled": true
    }
  },
  "permission": {
    "bash": "ask",
    "edit": "ask",
    "webfetch": "ask"
  }
}
EOF
  echo "config written: $(head -c 200 "$XDG_CONFIG_HOME/opencode/opencode.jsonc" | tr '\n' ' ')"
}

# =====================================================================
# G5 — Real OpenCode starts locally.
# =====================================================================
start_server() {
  cd "$GATES_DIR"
  setsid "$GATES_DIR/bin/bun" "$GATES_DIR/device/launch-server.js" \
    > "$GATES_DIR/server.log" 2>&1 < /dev/null &
  SERVER_PID=$!
  echo "$SERVER_PID" > "$OUT/server.pid"
  log "server pid=$SERVER_PID; waiting for boot..."
  i=0
  while [ $i -lt 30 ]; do
    if grep -q "SERVER_READY" "$GATES_DIR/server.log" 2>/dev/null; then
      log "server ready after ~$((i*2))s"; return 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then log "SERVER_DIED"; return 1; fi
    i=$((i+1)); sleep 2
  done
  log "SERVER_BOOT_TIMEOUT"; return 1
}

gate_05() {
  G="gate-05-server-start.log"; : > "$OUT/$G"
  {
    log "=== G5: real OpenCode starts locally ==="
    if start_server; then
      echo "SERVER_READY_MARKER=yes pid=$(cat "$OUT/server.pid")"
      echo "-- server.log:"; head -30 "$GATES_DIR/server.log"
    else
      echo "SERVER_START_FAILED"
      tail -40 "$GATES_DIR/server.log"
    fi
  } >> "$OUT/$G" 2>&1
  if grep -q "SERVER_READY_MARKER=yes" "$OUT/$G"; then
    record 05 "opencode-start" PASS
  else
    record 05 "opencode-start" FAIL
  fi
}

# =====================================================================
# G6 — OpenCode server health endpoint responds successfully.
# =====================================================================
gate_06() {
  G="gate-06-health.log"; : > "$OUT/$G"
  {
    log "=== G6: health endpoint ==="
    "$GATES_DIR/bin/bun" "$GATES_DIR/device/gate-06-health.js" >> "$OUT/$G" 2>&1
  } >> "$OUT/$G" 2>&1
  if grep -q '"healthy":true' "$OUT/$G" && grep -q "HTTP 200 /global/health" "$OUT/$G"; then
    record 06 "health-endpoint" PASS
  else
    record 06 "health-endpoint" FAIL
  fi
}

# =====================================================================
# G7 — OpenCode can execute a shell command (real /session/:id/shell path,
#      which runs the real shell via ChildProcess with the configured shell).
# =====================================================================
gate_07() {
  G="gate-07-shell-exec.log"; : > "$OUT/$G"
  {
    log "=== G7: OpenCode executes a shell command ==="
    "$GATES_DIR/bin/bun" "$GATES_DIR/device/gate-07-shell.js" >> "$OUT/$G" 2>&1
  } >> "$OUT/$G" 2>&1
  if grep -q "G7_SHELL_OK" "$OUT/$G" && grep -q "G7_PASS" "$OUT/$G"; then
    record 07 "opencode-shell-exec" PASS
  else
    record 07 "opencode-shell-exec" FAIL
  fi
}

# =====================================================================
# G8 — OpenCode can read/write project files.
# =====================================================================
gate_08() {
  G="gate-08-files.log"; : > "$OUT/$G"
  {
    log "=== G8: read/write project files ==="
    "$GATES_DIR/bin/bun" "$GATES_DIR/device/gate-08-files.js" "$MODEL_AVAILABLE" >> "$OUT/$G" 2>&1
  } >> "$OUT/$G" 2>&1
  if grep -q "G8_READ_OK" "$OUT/$G" && grep -q "G8_WRITE_OK" "$OUT/$G"; then
    record 08 "file-read-write" PASS
  else
    record 08 "file-read-write" FAIL
  fi
}

# =====================================================================
# G9 — Real Git works (init, status, add, commit, diff, branches).
# =====================================================================
gate_09() {
  G="gate-09-git.log"; : > "$OUT/$G"
  {
    log "=== G9: real git ==="
    export GIT_AUTHOR_NAME=gates GIT_AUTHOR_EMAIL=gates@localhost
    export GIT_COMMITTER_NAME=gates GIT_COMMITTER_EMAIL=gates@localhost
    export GIT_EDITOR=true
    R="$GATES_DIR/project"
    cd "$R" || { echo "CD_FAILED $R"; record 09 "real-git" FAIL "cd-failed"; return 0; }
    echo "-- version:"
    git --version
    echo "-- init:"
    git init -q -b main . && echo "INIT_OK"
    echo "-- status:"
    git status --short
    echo "-- add:"
    echo "g9 change" >> notes.txt
    git add notes.txt && echo "ADD_OK"
    echo "-- diff (staged):"
    git diff --cached --stat
    echo "-- commit:"
    git commit -q -m "g9: commit from the Android runtime" && echo "COMMIT_OK"
    echo "-- log:"
    git log --oneline | head -5
    echo "-- branches:"
    git branch -a
    git checkout -q feature/g9 && echo "BRANCH_CHECKOUT_OK"
    git checkout -q main
    echo "-- status after:"
    git status --short
    echo "-- git through OpenCode (server-side shell endpoint, same git binary):"
    "$GATES_DIR/bin/bun" "$GATES_DIR/device/gate-09-opencode-git.js" >> "$OUT/$G" 2>&1
  } >> "$OUT/$G" 2>&1
  if grep -q "git version 2.4" "$OUT/$G" && grep -q "INIT_OK" "$OUT/$G" && grep -q "ADD_OK" "$OUT/$G" \
     && grep -q "COMMIT_OK" "$OUT/$G" && grep -q "feature/g9" "$OUT/$G" && grep -q "BRANCH_CHECKOUT_OK" "$OUT/$G" \
     && grep -q "G9_OPENCODE_GIT_OK" "$OUT/$G"; then
    record 09 "real-git" PASS
  else
    record 09 "real-git" FAIL
  fi
}

# =====================================================================
# G10 — MCP stdio child process works.
# =====================================================================
gate_10() {
  G="gate-10-mcp.log"; : > "$OUT/$G"
  {
    log "=== G10: MCP stdio ==="
    "$GATES_DIR/bin/bun" "$GATES_DIR/device/gate-10-mcp.js" "$MODEL_AVAILABLE" >> "$OUT/$G" 2>&1
  } >> "$OUT/$G" 2>&1
  if grep -q "G10_MCP_ROUNDTRIP_OK" "$OUT/$G" && grep -q "G10_PASS" "$OUT/$G"; then
    record 10 "mcp-stdio" PASS
  else
    record 10 "mcp-stdio" FAIL
  fi
}

# =====================================================================
# G11 — OpenCode streaming/SSE/event flow works.
# =====================================================================
gate_11() {
  G="gate-11-streaming.log"; : > "$OUT/$G"
  {
    log "=== G11: streaming/SSE ==="
    "$GATES_DIR/bin/bun" "$GATES_DIR/device/gate-11-stream.js" "$MODEL_AVAILABLE" >> "$OUT/$G" 2>&1
  } >> "$OUT/$G" 2>&1
  if grep -q "G11_PASS" "$OUT/$G"; then
    record 11 "streaming-sse" PASS
  else
    record 11 "streaming-sse" FAIL
  fi
}

# =====================================================================
# G12 — Permissions/tool approval work.
# =====================================================================
gate_12() {
  G="gate-12-permission.log"; : > "$OUT/$G"
  {
    log "=== G12: permissions/tool approval ==="
    if [ "$MODEL_AVAILABLE" = 1 ]; then
      "$GATES_DIR/bin/bun" "$GATES_DIR/device/gate-12-permission.js" >> "$OUT/$G" 2>&1
      if grep -q "G12_PASS" "$OUT/$G"; then
        record 12 "permissions" PASS
      else
        record 12 "permissions" FAIL
      fi
    else
      echo "G12_BLOCKED_NO_MODEL: permission flow requires the real agent loop (a model); no OPENROUTER_API_KEY was provided"
      record 12 "permissions" BLOCKED "no-model-credentials"
    fi
  } >> "$OUT/$G" 2>&1
}

# =====================================================================
# G13 — OpenCode process can be stopped and restarted.
# =====================================================================
gate_13() {
  G="gate-13-restart.log"; : > "$OUT/$G"
  {
    log "=== G13: stop and restart the OpenCode process ==="
    PID="$(cat "$OUT/server.pid" 2>/dev/null)"
    echo "server pid=$PID"
    kill -TERM "$PID" 2>/dev/null
    i=0
    while kill -0 "$PID" 2>/dev/null && [ $i -lt 20 ]; do i=$((i+1)); sleep 1; done
    if kill -0 "$PID" 2>/dev/null; then
      echo "SERVER_STILL_ALIVE_AFTER_TERM"
      kill -KILL "$PID" 2>/dev/null
      sleep 1
    else
      echo "SERVER_STOPPED_AFTER_TERM"
    fi
    echo "-- port check (expect connection refused):"
    "$GATES_DIR/bin/bun" -e '
      const ok = await fetch("http://127.0.0.1:4111/global/health").then(() => "PORT_STILL_UP").catch(() => "PORT_DOWN");
      console.log(ok);
    '
    echo "-- restart with the same data dirs:"
    if start_server; then
      echo "RESTART_READY"
      "$GATES_DIR/bin/bun" "$GATES_DIR/device/gate-06-health.js" | head -3
    else
      echo "RESTART_FAILED"
    fi
  } >> "$OUT/$G" 2>&1
  if grep -q "SERVER_STOPPED_AFTER_TERM" "$OUT/$G" && grep -q "PORT_DOWN" "$OUT/$G" && grep -q "RESTART_READY" "$OUT/$G"; then
    record 13 "stop-restart" PASS
  else
    record 13 "stop-restart" FAIL
  fi
}

# =====================================================================
# G14 — App restart/reconnect: a fresh client reconnects to the recovered
#       session (session + messages survive the server restart).
# =====================================================================
gate_14() {
  G="gate-14-reconnect.log"; : > "$OUT/$G"
  {
    log "=== G14: reconnect to the recovered session ==="
    "$GATES_DIR/bin/bun" "$GATES_DIR/device/gate-14-reconnect.js" "$MODEL_AVAILABLE" >> "$OUT/$G" 2>&1
  } >> "$OUT/$G" 2>&1
  if grep -q "G14_PASS" "$OUT/$G"; then
    record 14 "reconnect" PASS
  else
    record 14 "reconnect" FAIL
  fi
}

# =====================================================================
# G15 — End-to-end: "Inspect this project and explain what it does"
# =====================================================================
gate_15() {
  G="gate-15-e2e.log"; : > "$OUT/$G"
  {
    log "=== G15: end-to-end project inspection ==="
    if [ "$MODEL_AVAILABLE" = 1 ]; then
      "$GATES_DIR/bin/bun" "$GATES_DIR/device/gate-15-e2e.js" >> "$OUT/$G" 2>&1
      if grep -q "G15_PASS" "$OUT/$G"; then
        record 15 "end-to-end" PASS
      else
        record 15 "end-to-end" FAIL
      fi
    else
      echo "G15_BLOCKED_NO_MODEL: end-to-end inspection requires the real agent loop (a model); no OPENROUTER_API_KEY was provided"
      record 15 "end-to-end" BLOCKED "no-model-credentials"
    fi
  } >> "$OUT/$G" 2>&1
}

# ---------------------------------------------------------------------
log "--- preparing project fixture ---"
make_project >> "$OUT/gate-09-git.log" 2>&1 || log "PROJECT_FIXTURE_FAILED"

write_config

gate_01; gate_02; gate_03; gate_04
gate_05; gate_06
gate_07; gate_08; gate_09; gate_10; gate_11; gate_12
gate_13; gate_14; gate_15

log "=== GATES RUNNER END ==="
{
  echo "GATES_SUMMARY $(date -u +%FT%TZ)"
  echo "total=$GATE_TOTAL pass=$GATE_PASS fail=$GATE_FAIL blocked=$GATE_BLOCKED"
  echo "model_available=$MODEL_AVAILABLE"
} >> "$OUT/GATES_SUMMARY.txt"
echo "GATES_RUNNER_DONE"
