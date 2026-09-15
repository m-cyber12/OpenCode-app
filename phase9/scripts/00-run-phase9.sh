#!/usr/bin/env bash
# 00-run-phase9.sh - single orchestrator for the Phase 9 RELEASE run.
#
# What a release run has to prove (Core Rule 4: a green build is not evidence):
#   1. static checks (seconds) ->
#   2. embedded runtime payload built from the pinned upstream commit
#      (Bun-for-Android, Bionic git + ripgrep, the OpenCode bundle; sha256
#      manifest) -> payload artifacts are release artifacts ->
#   3. debug + androidTest APKs, JVM unit tests ->
#   4. the FULL Phase 8 suite on a fresh emulator (which folds in the Phase 5
#      and Phase 7 regressions) - every gate reported by name ->
#   5. the Phase 9 gates on the same device (provider selection fixed, plugin
#      install fixed, version string, versions.lock == shipped) ->
#   6. release APK + AAB (arm64-v8a ships; x86_64 only for CI/emulator) ->
#   7. one GATES_SUMMARY.txt that names EVERY gate verdict ("G1 PASS / G2
#      FAIL: <reason>"), which the workflow prints as its last step and which
#      decides the job's exit status.
#
# Knobs (never for the verdict run):
#   P9_GRADLE_ONLY=1 / phase9/CI_GRADLE_ONLY: compile + JVM tests only.
#   P9_SKIP_P8=1: skip stage 4 (Phase 9 gates only - iteration).
#   P8_SKIP_P7REG / P8_SKIP_P5REG: passed through to the Phase 8 suite.
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
REL="$OUT/release"
mkdir -p "$OUT" "$EV" "$REL"
MAINLOG="$OUT/00-run-phase9.log"
: > "$MAINLOG"

step() { echo; echo "########## $1 ##########" | tee -a "$MAINLOG"; }
run_c() {
  local t="$1"; shift
  local logf="$OUT/step.$$.out"
  timeout -k 60 "$t" bash -c "${*}" > "$logf" 2>&1
  local rc=$?
  tail -c 200000 "$logf" 2>/dev/null
  cat "$logf" >> "$MAINLOG" 2>/dev/null
  rm -f "$logf"
  if [ "$rc" = 124 ] || [ "$rc" = 137 ]; then echo "STEP_TIMEOUT rc=$rc after ${t}s: ${*}" | tee -a "$MAINLOG"; fi
  [ "$rc" = 0 ] || { echo "STEP_FAILED rc=$rc: ${*}" | tee -a "$MAINLOG"; return 1; }
}

echo "=== PHASE 9 START $(date -u +%FT%TZ) ===" | tee -a "$MAINLOG"
SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"

# One summary that names every gate. Written incrementally so a run that dies
# mid-way still reports what it proved and what it did not reach.
FINAL="$EV/GATES_SUMMARY.txt"
: > "$FINAL"
note_gate() { echo "$*" | tee -a "$FINAL" | tee -a "$MAINLOG" >/dev/null; }

collect_evidence() {
  cp "$MAINLOG" "$EV/00-run-phase9.log" 2>/dev/null || true
  cp "$OUT/emulator.log" "$EV/" 2>/dev/null || true
  cp "$ROOT/phase4/out/engine/assets/runtime-manifest.json" "$EV/runtime-manifest.json" 2>/dev/null || true
  mkdir -p "$EV/jvm-unit-tests" "$EV/phase8"
  cp "$ROOT"/app/build/test-results/testDebugUnitTest/*.xml "$EV/jvm-unit-tests/" 2>/dev/null || true
  cp -r "$ROOT/phase8/out/evidence/." "$EV/phase8/" 2>/dev/null || true
  cp "$ROOT/phase8/out/00-run-phase8.log" "$EV/phase8/" 2>/dev/null || true
  # release artifact listing + hashes (the artifacts themselves go to the
  # workflow's upload, never to git)
  ( cd "$REL" 2>/dev/null && sha256sum * 2>/dev/null ) > "$EV/release-sha256.txt" || true
  ( cd "$REL" 2>/dev/null && ls -la ) > "$EV/release-listing.txt" 2>&1 || true
}

push_evidence() {
  [ "${P9_SKIP_PUSH:-0}" = "1" ] && return 0
  git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || return 0
  DEST="${GITHUB_REF_NAME:-$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)}"
  mkdir -p "$ROOT/docs/progress/phase9-evidence"
  cp -r "$EV/." "$ROOT/docs/progress/phase9-evidence/" 2>/dev/null || true
  # never commit binaries or the emulator's multi-MB log
  rm -f "$ROOT/docs/progress/phase9-evidence/emulator.log" 2>/dev/null || true
  find "$ROOT/docs/progress/phase9-evidence" -type f -size +2M -delete 2>/dev/null || true
  export GIT_TERMINAL_PROMPT=0
  timeout 180 git -C "$ROOT" fetch origin "$DEST" >/dev/null 2>&1 || true
  git -C "$ROOT" add -A docs/progress/phase9-evidence/ 2>/dev/null || true
  git -C "$ROOT" diff --cached --quiet && { echo "no evidence changes to commit"; return 0; }
  git -C "$ROOT" -c user.name="arena-ai-coding-agent[bot]" -c user.email="arena-ai-coding-agent[bot]@users.noreply.github.com" \
    commit -m "phase9: release gate evidence (auto)" >/dev/null 2>&1 || true
  timeout 180 git -C "$ROOT" pull --rebase origin "$DEST" >/dev/null 2>&1 || true
  timeout 240 git -C "$ROOT" push origin "HEAD:$DEST" > "$OUT/push.out" 2>&1 || echo "PUSH_FAILED dest=$DEST"
}

record_fatal() {
  echo "FATAL: $1" | tee -a "$MAINLOG"
  note_gate "P9-BUILD FAIL: $1"
  note_gate "P9_SUMMARY gates not reached (build/environment failure) - see 00-run-phase9.log"
  grep -aE '^(e: file:|FAILURE:|BUILD FAILED|> Task .*FAILED|\* What went wrong|.*: error:)' "$MAINLOG" 2>/dev/null | tail -200 > "$EV/compiler-errors.txt" || true
  collect_evidence; push_evidence
  echo "=== PHASE 9 END $(date -u +%FT%TZ) FATAL: $1 ===" | tee -a "$MAINLOG"
  exit 1
}

GRADLE_ONLY=0
[ "${P9_GRADLE_ONLY:-0}" = "1" ] && GRADLE_ONLY=1
grep -qs '^1' "$DIR/CI_GRADLE_ONLY" 2>/dev/null && GRADLE_ONLY=1

step "1/7 static checks"
run_c 600 "bash '$DIR/scripts/30-static-checks.sh'" || record_fatal "static checks failed (see p9-static-checks.log)"
cp "$OUT/static-checks.log" "$EV/p9-static-checks.log" 2>/dev/null || true
note_gate "P9-STATIC PASS"

step "2/7 compile + JVM unit tests (fast fail before the payload build)"
run_c 2400 "SKIP_PAYLOAD=1 '$ROOT/gradlew' -p '$ROOT' :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest -PskipPayload --no-daemon --stacktrace" \
  || record_fatal "kotlin compile or JVM unit tests failed (compiler-errors.txt)"
UT=$(python3 - "$ROOT/app/build/test-results/testDebugUnitTest" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
t=f=e=s=0
for p in glob.glob(sys.argv[1]+"/*.xml"):
    r=ET.parse(p).getroot(); t+=int(r.get("tests",0)); f+=int(r.get("failures",0)); e+=int(r.get("errors",0)); s+=int(r.get("skipped",0))
print(f"tests={t} failures={f} errors={e} skipped={s}")
EOF
)
note_gate "P9-UNIT PASS: JVM $UT (unit tests are NOT runtime evidence - see the device gates below)"
if [ "$GRADLE_ONLY" = "1" ]; then
  note_gate "P9 MODE gradle-only: no payload, no emulator, no device verdicts, no release artifacts in this run"
  collect_evidence; push_evidence
  echo "=== PHASE 9 END $(date -u +%FT%TZ) gradle-only ok ===" | tee -a "$MAINLOG"; exit 0
fi

step "3/7 embedded runtime payload (pinned upstream; reproducible manifest)"
if [ ! -f "$ROOT/phase4/out/engine/assets/runtime-manifest.json" ]; then
  run_c 3600 "bash '$ROOT/phase4/scripts/10-build-payload.sh'" || record_fatal "payload build failed"
else
  echo "reusing phase4/out/engine payload" | tee -a "$MAINLOG"
fi
MFV=$(python3 -c 'import json,sys;m=json.load(open(sys.argv[1]));print("payloadVersion=%s opencode=%s@%s bun=%s git=%s rg=%s sha256=%s files=%d"%(m["payloadVersion"],m["opencodeVersion"],m["opencodeCommit"][:12],m["bunVersion"],m["gitVersion"],m["rgVersion"],m["payloadSha256"][:16],len(m["files"])))' "$ROOT/phase4/out/engine/assets/runtime-manifest.json")
note_gate "P9-PAYLOAD PASS: $MFV"
# Payload artifacts ARE release artifacts (reproducible runtime): tar them with the manifest.
( cd "$ROOT/phase4/out/engine" && tar czf "$REL/runtime-payload-engine.tar.gz" assets jniLibs 2>/dev/null ) || true
cp "$ROOT/phase4/out/engine/assets/runtime-manifest.json" "$REL/" 2>/dev/null || true

step "4/7 emulator + full Phase 8 suite (folds in Phase 5 + Phase 7 regressions)"
P8RC=0
if [ "${P9_SKIP_P8:-0}" = "1" ]; then
  note_gate "P8-SUITE SKIP: P9_SKIP_P8=1 (iteration knob; a release run must not set it)"
  # still need a device + APKs for the P9 gates
  run_c 3000 "'$ROOT/gradlew' -p '$ROOT' :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon" || record_fatal "debug APKs"
else
  # The Phase 8 orchestrator boots the emulator, builds the APKs, runs everything
  # and writes phase8/out/evidence/GATES_SUMMARY.txt. Its own evidence push is
  # disabled (P8_SKIP_PUSH) - Phase 9 publishes one evidence tree.
  P8_SKIP_PUSH=1 bash "$ROOT/phase8/scripts/00-run-phase8.sh" >> "$MAINLOG" 2>&1 || P8RC=$?
  P8SUM="$ROOT/phase8/out/evidence/GATES_SUMMARY.txt"
  if [ -f "$P8SUM" ]; then
    # Every Phase 8 / Phase 7 / Phase 5 verdict, by name, into the release summary.
    { grep -ahE '^P8_[A-Z0-9_]+ (PASS|FAIL|SKIP)' "$ROOT/phase8/out/evidence/p8-lines.txt" 2>/dev/null
      grep -ahE '^P(7|6)_[A-Z0-9_]+ (PASS|FAIL|SKIP)' "$ROOT/phase7/out/evidence/"*.txt 2>/dev/null
      grep -ahE '^P5-[A-Z0-9_-]+ (PASS|FAIL|SKIP)' "$ROOT/phase5/out/evidence/GATES_SUMMARY.txt" 2>/dev/null
    } | sed 's/[[:space:]]*$//' | sort -u | cut -c1-240 >> "$FINAL"
    grep -a '^gates_pass=' "$P8SUM" | sed 's/^/P8-SUITE totals: /' >> "$FINAL"
  else
    note_gate "P8-SUITE FAIL: the Phase 8 suite produced no GATES_SUMMARY (rc=$P8RC) - see phase8/00-run-phase8.log"
  fi
fi

step "5/7 Phase 9 gates (provider selection, plugin install, version, lock)"
P9RC=0
bash "$DIR/scripts/20-gates.sh" >> "$MAINLOG" 2>&1 || P9RC=$?
grep -ahE '^P9_[A-Z0-9_]+ (PASS|FAIL|SKIP)' "$EV/p9-lines.txt" 2>/dev/null | cut -c1-300 >> "$FINAL"

step "6/7 release artifacts (APK + AAB; arm64-v8a ships, x86_64 = emulator only)"
RELRC=0
run_c 3000 "'$ROOT/gradlew' -p '$ROOT' :app:assembleRelease :app:bundleRelease --no-daemon --stacktrace" || RELRC=1
if [ "$RELRC" = 0 ]; then
  cp "$ROOT"/app/build/outputs/apk/release/*.apk "$REL/" 2>/dev/null || true
  cp "$ROOT"/app/build/outputs/bundle/release/*.aab "$REL/" 2>/dev/null || true
  cp "$ROOT"/app/build/outputs/apk/debug/*.apk "$REL/" 2>/dev/null || true
  APK_REL=$(ls "$REL"/*-release*.apk 2>/dev/null | head -1)
  AAB_REL=$(ls "$REL"/*.aab 2>/dev/null | head -1)
  if [ -n "$APK_REL" ] && [ -n "$AAB_REL" ]; then
    SIGNED="unsigned"
    if [ -n "${P9_KEYSTORE_B64:-}" ]; then SIGNED="signed"; fi
    note_gate "P9-RELEASE PASS: apk=$(basename "$APK_REL") ($(stat -c%s "$APK_REL") bytes) aab=$(basename "$AAB_REL") ($(stat -c%s "$AAB_REL") bytes) $SIGNED"
    # per-ABI size: what a user actually downloads
    if command -v unzip >/dev/null; then
      unzip -l "$APK_REL" 2>/dev/null | awk '/lib\/(arm64-v8a|x86_64)\//{s[$4 ~ /arm64/ ? "arm64-v8a" : "x86_64"]+=$1} END{for(k in s) printf "P9-RELEASE note: %s native libs %.1f MB (uncompressed)\n", k, s[k]/1048576}' >> "$FINAL"
    fi
  else
    note_gate "P9-RELEASE FAIL: gradle succeeded but apk/aab missing under app/build/outputs"
    RELRC=1
  fi
else
  note_gate "P9-RELEASE FAIL: assembleRelease/bundleRelease failed (see 00-run-phase9.log)"
fi

step "7/7 summary"
collect_evidence
P8F=$(sed -n 's/^P8-SUITE totals: gates_pass=[0-9]* gates_fail=\([0-9]*\).*/\1/p' "$FINAL" | head -1)
P9F=$(grep -acE '^P9_[A-Z0-9_]+ FAIL' "$FINAL")
{
  echo "P9_SUMMARY $(date -u +%FT%TZ)"
  echo "p8_suite_fails=${P8F:-n/a} p9_gate_fails=$P9F release_rc=$RELRC"
  echo "NOTE: PASS lines above are real gate executions on the emulator; unit/compile lines are not runtime evidence."
} >> "$FINAL"
cat "$FINAL" | tee -a "$MAINLOG"
FINAL_RC=0
[ "${P8F:-0}" = "0" ] || FINAL_RC=1
[ "$P9F" = "0" ] || FINAL_RC=1
[ "$RELRC" = "0" ] || FINAL_RC=1
echo "=== PHASE 9 END $(date -u +%FT%TZ) rc=$FINAL_RC ===" | tee -a "$MAINLOG"
push_evidence
exit "$FINAL_RC"
