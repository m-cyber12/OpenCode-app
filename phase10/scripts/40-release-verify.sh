#!/usr/bin/env bash
# 40-release-verify.sh - build the release APK + AAB the way CI must (UNSIGNED)
# and inspect the actual bytes before they are published as artifacts.
#
# This stage answers, from the artifact itself rather than from the build log:
#   * is the applicationId the pinned one (and NOT the debug suffix)?
#   * do versionCode/versionName match versions.lock?
#   * is debuggable=false in the release manifest (Play rejects release artifacts
#     that are debuggable)?
#   * is the artifact UNSIGNED? (In CI it must be: docs/RELEASE.md s8. If a
#     keystore ever appears in CI this gate fails loudly instead of quietly
#     signing with something.)
#   * does it carry the app icon, the embedded runtime payload, both ABIs, and
#     only the permissions the app declares?
#
# Usage: bash phase10/scripts/40-release-verify.sh     (needs the payload staged)
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
OUT="$DIR/out"
EV="$OUT/evidence"
REL="$OUT/release"
mkdir -p "$EV" "$REL"
LOG="$EV/release-verify.log"
: > "$LOG"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$LOG"; }

: > "$EV/p10-lines.txt.tmp"
# The rec() names MUST carry the P10_ prefix: the orchestrator folds and counts
# verdict lines with `grep '^P10_[A-Z0-9_]+ (PASS|FAIL|SKIP)'`, and unprefixed
# names made every verdict of this stage invisible to it - run #7 shipped
# RELEASE_AAB FAIL next to "phase10_gate_fails=0" and a GREEN job, which is the
# worst failure mode this project knows (a gate that cannot fail the build).
rec() { echo "P10_$1 $2${3:+ :: $3}"; echo "P10_$1 $2${3:+ :: $3}" >> "$EV/p10-release-lines.txt"; log "$1 $2${3:+ :: $3}"; }
PASS=0; FAIL=0
p10() { case "$2" in 0) PASS=$((PASS+1)); rec "$1" PASS "$3" ;; *) FAIL=$((FAIL+1)); rec "$1" FAIL "$3" ;; esac; }

# Extract the EXPECTED identity from build.gradle.kts, anchored at the start of
# the line: the file carries a prose comment containing `// versionName = "<pinned
# OpenCode version>-phase10"`, and an unanchored grep found the comment first, so
# every --expect-version-name in CI compared against that placeholder (run #6).
# Anchored + head -1 sees only real assignments, which are never indented-away
# inside a comment.
VNAME=$(grep -E '^[[:space:]]*versionName = "' "$ROOT/app/build.gradle.kts" | head -1 | cut -d'"' -f2)
VCODE=$(grep -E '^[[:space:]]*versionCode = [0-9]+' "$ROOT/app/build.gradle.kts" | head -1 | grep -oE 'versionCode = [0-9]+' | grep -oE '[0-9]+')
APPID=$(grep -E '^[[:space:]]*applicationId = "' "$ROOT/app/build.gradle.kts" | head -1 | cut -d'"' -f2)
[ -n "$VNAME" ] && [ -n "$VCODE" ] && [ -n "$APPID" ] \
  || { echo "FATAL: could not extract versionName/versionCode/applicationId from app/build.gradle.kts" >&2; exit 2; }
DEBUG_APPID="$APPID.debug"
log "expected identity: applicationId=$APPID versionName=$VNAME versionCode=$VCODE"

log "=== gradle :app:assembleRelease :app:bundleRelease ==="
if ! ( cd "$ROOT" && timeout -k 30 3000 ./gradlew -p "$ROOT" \
        :app:assembleRelease :app:bundleRelease --no-daemon --stacktrace ) >> "$LOG" 2>&1; then
  p10 RELEASE 1 "assembleRelease/bundleRelease failed (see release-verify.log)"
  cp "$LOG" "$EV/p10-release-verify.log" 2>/dev/null || true
  exit 1
fi
# The Gradle block prints one signing line per build; keep it in the evidence.
grep -a "RELEASE SIGNING:" "$LOG" | tail -2 >> "$EV/release-listing.txt" 2>/dev/null || true

APK_SRC="$(ls "$ROOT/app/build/outputs/apk/release/"*.apk 2>/dev/null | head -1)"
AAB_SRC="$(ls "$ROOT/app/build/outputs/bundle/release/"*.aab 2>/dev/null | head -1)"
[ -n "$APK_SRC" ] || { p10 RELEASE 1 "no release APK produced"; exit 1; }
[ -n "$AAB_SRC" ] || { p10 RELEASE 1 "no AAB produced"; exit 1; }
APK="$REL/$(basename "$APK_SRC")"
AAB="$REL/$(basename "$AAB_SRC")"
cp "$APK_SRC" "$APK"; cp "$AAB_SRC" "$AAB"
log "apk=$(basename "$APK") ($(stat -c%s "$APK") bytes)"
log "aab=$(basename "$AAB") ($(stat -c%s "$AAB") bytes)"

# ---- APK: identity, payload, permissions, signature state --------------------
APK_REPORT="$EV/p10-release-apk-report.txt"
APK_ARGS=(--expect-package "$APPID" --expect-version-name "$VNAME" --expect-version-code "$VCODE"
          --expect-not-debuggable --expect-icon --expect-payload
          --expect-min-sdk 29 --expect-target-sdk 34
          --expect-native-abi arm64-v8a --expect-native-abi x86_64
          --expect-permission android.permission.INTERNET
          --expect-permission android.permission.FOREGROUND_SERVICE
          --expect-permission android.permission.FOREGROUND_SERVICE_SPECIAL_USE
          --expect-permission android.permission.POST_NOTIFICATIONS
          --expect-no-permission android.permission.READ_EXTERNAL_STORAGE
          --expect-no-permission android.permission.ACCESS_FINE_LOCATION
          --json "$EV/p10-release-apk.json")
if python3 "$DIR/scripts/check-apk.py" "$APK" "${APK_ARGS[@]}" > "$APK_REPORT" 2>&1; then
  p10 RELEASE_APK 0 "$(grep -a '^MANIFEST ' "$APK_REPORT" | head -1 | cut -c1-220)"
else
  p10 RELEASE_APK 1 "check-apk findings: $(grep -a '^FINDING' "$APK_REPORT" | head -4 | tr '\n' '; ')"
fi
p10 RELEASE_APK_DETAIL 0 "$(grep -a '^CONTENTS ' "$APK_REPORT" | head -1 | tr -s ' ' | cut -c1-240)"

# Signature state, asserted explicitly in BOTH directions: CI must never sign,
# and a release artifact must never be published unsigned *by the human step*.
# In CI the expected state is unsigned, so that is what is asserted here; the
# human's signed artifact is verified by sign-release-local.sh + check-apk.py
# --expect-signed (docs/RELEASE.md s4/s5).
# Written to its OWN report file (run #6 appended this second checker run into
# the first report with ">>", so the file held two concatenated reports, the
# "UNSIGNED" verdict's PASS/FAIL was decided by findings that belonged to the
# identity run, and the evidence was unreadable without diffing it).
UNSIGNED_REPORT="$EV/p10-release-apk-unsigned-report.txt"
if python3 "$DIR/scripts/check-apk.py" "$APK" --expect-unsigned > "$UNSIGNED_REPORT" 2>&1; then
  p10 UNSIGNED 0 "CI artifact is UNSIGNED, as required: signing is the human step on a machine that holds the key (docs/RELEASE.md); $(grep -a '^SIGNATURE ' "$UNSIGNED_REPORT" | head -1)"
else
  p10 UNSIGNED 1 "the CI release APK is SIGNED - CI must never receive signing material. Investigate before publishing anything; $(grep -a '^FINDING' "$UNSIGNED_REPORT" | head -2 | tr '\n' ';' | cut -c1-200)"
fi

# ---- AAB: identity + payload (protobuf manifest; see check-apk.py) -----------
AAB_REPORT="$EV/p10-release-aab-report.txt"
if python3 "$DIR/scripts/check-apk.py" "$AAB" \
      --expect-package "$APPID" --expect-version-name "$VNAME" \
      --expect-native-abi arm64-v8a --expect-payload \
      --json "$EV/p10-release-aab.json" > "$AAB_REPORT" 2>&1; then
  p10 RELEASE_AAB 0 "$(grep -a '^CONTENTS ' "$AAB_REPORT" | head -1 | tr -s ' ' | cut -c1-240)"
else
  p10 RELEASE_AAB 1 "check-apk findings: $(grep -a '^FINDING' "$AAB_REPORT" | head -4 | tr '\n' '; ')"
fi

# ---- hashes: what the human will sign ----------------------------------------
( cd "$REL" && sha256sum ./*.apk ./*.aab 2>/dev/null ) > "$EV/release-sha256.txt" || true
( cd "$REL" && ls -la ) > "$EV/release-listing.txt" 2>&1 || true
log "sha256:"; cat "$EV/release-sha256.txt" | tee -a "$LOG"

echo
echo "P10_RELEASE_SUMMARY pass=$PASS fail=$FAIL"
exit $([ "$FAIL" = 0 ] && echo 0 || echo 1)
