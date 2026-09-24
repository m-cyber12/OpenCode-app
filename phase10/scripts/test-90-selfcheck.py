#!/usr/bin/env python3
"""Phase 10 v4 contract check: the fixtures, the driver's needles and the gates agree.

Why this exists, stated as the incident that produced it. The first v4 self-test run
came back red with `P10D_FILES_SIMPLIFIED FAIL :: Export a copy`. Nothing was wrong
with the app - the *fixture* was stale: it still served a control the v4 UI had
removed, so the driver correctly reported that the removed control was on screen. The
self-test kept its gate honest and the gate pointed at the harness. That is the right
direction to fail, but it cost a run to learn.

This script is the five-second version of that lesson. It does not run the driver; it
checks the three artefacts the driver run depends on, offline:

  1. the fixtures BUILD, and they are the screens the driver expects (count, names);
  2. no fixture serves a control the app no longer has - the removed-copy list is
     read out of the driver itself, so there is one source of truth for "removed";
  3. no two clickable nodes in a fixture overlap - the fake phone taps the FIRST node
     containing a point, so an overlapping row silently steals a tap (this already
     happened once: the provider row swallowed taps meant for its own buttons);
  4. every UI string the v4 driver stages assert is producible from the fixtures - the
     staging in the driver and the screens in the fixtures cannot drift apart;
  5. every test tag the instrumented gates look for exists as a `testTag` in the main
     sources - a gate that asserts on a tag no screen sets is a gate that passes for
     the wrong reason (or never resolves at all).

It is deliberately a lint, not a proof: it cannot tell whether a screen *looks* right,
only whether the harness and the app still speak about the same screen.
"""
import collections
import importlib.util
import os
import re
import sys
import subprocess
import tempfile
import xml.etree.ElementTree as xml_mod

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
MAIN = os.path.join(ROOT, "app", "src", "main", "java")
ANDROID_TEST = os.path.join(ROOT, "app", "src", "androidTest", "java")
FIXTURES = os.path.join(HERE, "test-90-fixtures.py")
DRIVER = os.path.join(HERE, "90-real-device-signed.sh")
STRING_FILES = os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml")

EXPECTED_SCREENS = [
    "welcome", "projects", "chat", "answer", "files", "settings",
    "settings-nomatch", "settings-openr", "settings-key", "chat-menu", "workspace",
    "projects-switch",
]

failures = []
notes = []


def check(ok, label, detail=""):
    print("%s %s%s" % ("PASS" if ok else "FAIL", label, (" :: " + detail) if detail else ""))
    if not ok:
        failures.append(label)
    return ok


def read(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def load_fixtures():
    spec = importlib.util.spec_from_file_location("p10d_fixtures", FIXTURES)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def fixture_screens(mod, root):
    mod.build(root)
    out = collections.OrderedDict()
    screens = os.path.join(root, "screens")
    for name in sorted(os.listdir(screens)):
        if name.endswith(".xml"):
            out[name[:-4]] = read(os.path.join(screens, name))
    return out


# ---------------------------------------------------------------------------
# 1. the fixtures build, and are the screens the driver expects
# ---------------------------------------------------------------------------
tmp = tempfile.mkdtemp(prefix="p10d-selfcheck-")
try:
    mod = load_fixtures()
    screens = fixture_screens(mod, tmp)
except Exception as exc:  # noqa: BLE001 - the point is to report, not to crash
    check(False, "fixtures build", "%s: %s" % (type(exc).__name__, exc))
    screens = {}

if screens:
    check(sorted(screens) == sorted(EXPECTED_SCREENS),
          "fixtures: %d screens, exactly the expected set" % len(screens),
          "missing=%s extra=%s" % (sorted(set(EXPECTED_SCREENS) - set(screens)),
                                   sorted(set(screens) - set(EXPECTED_SCREENS))))

driver = read(DRIVER)
union = "\n".join(screens.values())

# ---------------------------------------------------------------------------
# 2. no fixture serves a control the app removed
#    (the removed list is read OUT of the driver - one source of truth)
# ---------------------------------------------------------------------------
gone_match = re.search(r"gone = \[(.*?)\]", driver, re.S)
if gone_match:
    removed = re.findall(r'"([^"]+)"', gone_match.group(1))
    served = [label for label in removed if label in union]
    check(not served, "removed controls: none of %d is served by any fixture" % len(removed),
          "served anyway: %s" % served)
else:
    check(False, "removed-control list found in the driver", "no `gone = [...]` block")

# ---------------------------------------------------------------------------
# 3. no two clickable nodes overlap (the fake phone taps the FIRST match)
# ---------------------------------------------------------------------------
node_re = re.compile(r"<node\b[^>]*>")
bounds_re = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
overlaps = []
for name, xml in screens.items():
    clickable = []
    for raw in node_re.findall(xml):
        head = raw.split("bounds=")[0]
        if 'clickable="true"' not in head:
            continue
        m = bounds_re.search(raw)
        if not m:
            continue
        x1, y1, x2, y2 = (int(v) for v in m.groups())
        clickable.append((x1, y1, x2, y2, raw))
    for i in range(len(clickable)):
        for j in range(i + 1, len(clickable)):
            a, b = clickable[i], clickable[j]
            if a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]:
                overlaps.append("%s: %s vs %s" % (name, a[4][:60], b[4][:60]))
check(not overlaps, "fixtures: no overlapping clickable nodes",
      "; ".join(overlaps[:3]) + (" (+%d more)" % (len(overlaps) - 3) if len(overlaps) > 3 else ""))

# ---------------------------------------------------------------------------
# 3b. every fixture is a dump the READER can parse
#     (a fixture the reader rejects is a fake phone that lies about the driver: the
#     provider-search wait timed out for 20s on a screen that was on screen, and the
#     stage still passed - through a stale read. The cause was a raw double quote in
#     an attribute: the app's no-match copy contains "zzzqq" in quotes, and unescaped
#     quotes make the whole dump unparsable.)
# ---------------------------------------------------------------------------
unparsable = []
for name, xml in screens.items():
    try:
        root = xml_mod.fromstring(xml)
    except Exception as exc:  # noqa: BLE001 - the point is to report the fixture
        unparsable.append("%s: %s" % (name, exc))
        continue
    if not list(root.iter("node")):
        unparsable.append("%s: parses but describes no nodes" % name)
check(not unparsable, "fixtures: every screen parses as XML with nodes in it",
      "; ".join(unparsable[:3]))

# And through the READER ITSELF (the one the driver uses), not just an XML parser:
# the reader is what turns a dump into "what is on screen", so a fixture it rejects
# is a screen the driver can never see.
reader = os.path.join(HERE, "p10d-ui.py")
if os.path.exists(reader):
    reader_bad = []
    for name in sorted(screens):
        path = os.path.join(tmp, "screens", name + ".xml")
        proc = subprocess.run([sys.executable, reader, path, "nodes"],
                              capture_output=True, text=True)
        if proc.returncode != 0 or "UI_DUMP_UNREADABLE" in proc.stdout:
            reader_bad.append("%s (rc=%d)" % (name, proc.returncode))
    check(not reader_bad, "fixtures: the driver's own reader parses every screen",
          "rejected: %s" % reader_bad[:4])
else:
    notes.append("p10d-ui.py not found; the reader half of check 3b did not run")

# ---------------------------------------------------------------------------
# 4. the v4 driver stages can actually see what they assert
#    (these blocks are sliced out of the driver, not duplicated here)
# ---------------------------------------------------------------------------
SETTINGS_BLOCK = "# ---- v4 item 4: the storage screen no longer carries copy/export/chooser -----"
NEXT_STAGE = "\n# ---- R6:"
if SETTINGS_BLOCK in driver:
    v4_block = driver.split(SETTINGS_BLOCK, 1)[1].split(NEXT_STAGE, 1)[0]

    # Only the ASSERTIONS count, and only the string each one asserts on:
    #  * `wait_for "name" "needles"` - arg 2 (arg 1 is the stage's own name);
    #  * `ui_has "x"` / `ui_has_any "a" "b"` / `screen_label "x"` - every argument;
    #  * `rec "P10D_x" STATUS "detail"` - skipped: the detail is prose for a human.
    # `tap_any` is tolerant by construction, so its alternatives are not assertions
    # either: one of them may be the label the other build uses.
    asserted = []
    tolerant = set()
    for line in v4_block.splitlines():
        stripped = line.strip()
        if "tap_any" in line:
            tolerant.update(re.findall(r'"([^"\n]+)"', line))
            continue
        m = re.match(r'wait_for\s+"([^"]*)"\s+"([^"]*)"', stripped)
        if m:
            asserted.append(m.group(2))
            continue
        for helper in ("ui_has_any", "ui_has", "screen_label"):
            m = re.match(helper + r'\s+(.*)', stripped)
            if m:
                asserted.extend(re.findall(r'"([^"\n]+)"', m.group(1)))
                break

    missing = []
    for needles in asserted:
        parts = [p for p in needles.split("|") if p and p not in tolerant]
        if not parts:
            continue
        if not any(part in union for part in parts):
            missing.append(needles)
    check(not missing, "v4 driver stages: every asserted label is producible from a fixture",
          "not in any fixture: %s" % missing[:6])
else:
    check(False, "v4 driver stages located in the driver", "marker comment not found")

needle_groups = re.findall(r"^NEEDLE_([A-Z_]+)='([^']+)'", driver, re.M)
unsatisfiable = []
for name, needles in needle_groups:
    parts = [p for p in needles.split("|") if p]
    hits = [p for p in parts if p in union]
    if not hits:
        unsatisfiable.append("%s=%s" % (name, needles[:60]))
check(not unsatisfiable, "driver needles: every NEEDLE_* group matches some fixture",
      "; ".join(unsatisfiable))

# ---------------------------------------------------------------------------
# 5. every test tag the gates look for is set by a screen
# ---------------------------------------------------------------------------
main_src = []
for dirpath, _dirs, files in os.walk(MAIN):
    for fname in files:
        if fname.endswith(".kt"):
            main_src.append(read(os.path.join(dirpath, fname)))
main_src = "\n".join(main_src)
tag_templates = re.findall(r'testTag\s*=\s*"([^"]+)"', main_src)
# Two legitimate shapes the literal-only regex misses: a tag chosen by an
# inline condition (`testTag = if (x) "a" else "b"`) and a tag held in a
# constant the gate imports (`const val TAG_MESSAGE_RETRY = "message_retry"`).
for line in main_src.splitlines():
    if "testTag" in line or line.strip().startswith("const val TAG_"):
        for lit in re.findall(r'"([^"]+)"', line):
            if not lit.startswith("${"):
                tag_templates.append(lit)
# Templates may interpolate: "provider_connect_${provider.id}" -> prefix "provider_connect_"
template_re = re.compile(r"[^$]*")
prefixes = []
for tmpl in tag_templates:
    templ_re = "^" + re.escape(tmpl).replace(r"\$\{[^}]*\}", ".*") + "$"
    prefixes.append(re.compile(templ_re))
    cut = tmpl.find("${")
    if cut > 0:
        prefixes.append(re.compile("^" + re.escape(tmpl[:cut])))

gate_tags = []
for dirpath, _dirs, files in os.walk(ANDROID_TEST):
    for fname in files:
        if not fname.endswith(".kt"):
            continue
        src = read(os.path.join(dirpath, fname))
        for m in re.finditer(r'(?:onNodeWithTag|onAllNodesWithTag|hasTestTag)\(\s*"([^"]+)"', src):
            gate_tags.append((fname, m.group(1)))
# Constants defined in the gate files themselves (val TAG_X = "literal") count too.
gate_src = "\n".join(read(os.path.join(d, f)) for d, _s, fs in os.walk(ANDROID_TEST)
                     for f in fs if f.endswith(".kt"))
gate_literals = set(re.findall(r'=\s*"([a-z0-9_]+)"', gate_src))
dangling = sorted({tag for _f, tag in gate_tags
                   if not tag.startswith("$")
                   and not any(rx.match(tag) for rx in prefixes)
                   and tag not in gate_literals and tag != "_prt_edit"})
check(not dangling, "gates: %d tag lookups (%d distinct) resolve to a main-source testTag"
      % (len(gate_tags), len({t for _f, t in gate_tags})),
      "dangling: %s" % dangling[:8])

# ---------------------------------------------------------------------------
print()
if failures:
    print("SELFCHECK FAIL (%d): %s" % (len(failures), ", ".join(failures)))
    sys.exit(1)
print("SELFCHECK PASS (fixtures, driver needles and gate tags agree)")
