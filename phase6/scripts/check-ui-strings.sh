#!/usr/bin/env bash
# check-ui-strings.sh - the Phase 6 copy/resource gate named in the plan of record
# (docs/progress/phase6-ui-polish-plan.md, item P6-07): fail on hardcoded
# user-visible strings in the UI layer, and on any non-loopback URL literal
# compiled into the app (the source-side half of Phase 5's P5-G19).
#
# Runs locally (no JDK needed) and in CI, so a screen that ships untranslated
# copy fails before it ever reaches an emulator.
set -uo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
cd "$ROOT" || exit 1

RC=0
echo "--- check-ui-strings (hardcoded copy + URL literals)"
python3 "$DIR/scripts/check-ui-strings.py" "app/src/main/java" || RC=1

echo "--- strings.xml sanity: every referenced R.string.* must exist"
python3 - "$ROOT" <<'PY' || RC=1
import os, re, sys
root = sys.argv[1]
res = os.path.join(root, "app", "src", "main", "res", "values", "strings.xml")
if not os.path.isfile(res):
    print("FAIL missing %s" % res); sys.exit(1)
xml = open(res, encoding="utf-8").read()
defined = set(re.findall(r'<string name="([^"]+)"', xml))
used = set()
for dp, dn, fn in os.walk(os.path.join(root, "app", "src")):
    dn[:] = [d for d in dn if d not in {"build", "out", ".git"}]
    for f in fn:
        if not f.endswith((".kt", ".xml")):
            continue
        p = os.path.join(dp, f)
        if p == res:
            continue
        body = open(p, encoding="utf-8", errors="replace").read()
        used |= set(re.findall(r"R\.string\.(\w+)", body))
        used |= set(re.findall(r"@string/(\w+)", body))
# app_name / notification_* are referenced from the manifest and the service.
missing = sorted(u for u in used if u not in defined)
unused = sorted(d for d in defined if d not in used)
for m in missing:
    print("FAIL R.string.%s is referenced but not defined in strings.xml" % m)
print("strings.xml: %d defined, %d referenced, %d missing, %d unused (%s)"
      % (len(defined), len(used), len(missing), len(unused), ", ".join(unused[:8])))
sys.exit(1 if missing else 0)
PY

echo "check-ui-strings.sh rc=$RC"
exit "$RC"
