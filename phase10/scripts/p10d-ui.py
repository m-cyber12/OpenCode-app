#!/usr/bin/env python3
"""Read uiautomator dumps for the real-device driver (90-real-device-signed.sh).

Why a helper instead of grepping the XML: the Phase 10 signed-build run died with
"no working screen after 941s" and nothing in the bundle said WHAT was on screen.
The appliance of every step here is therefore diagnostic as well as operational -
every subcommand can print what it saw, not just whether it matched.

The app exposes Compose test tags as resource ids (`testTagsAsResourceId`, set in
MainActivity for exactly this reason), so a node can be addressed as
`resource-id="composer_send"` instead of "the button whose text happens to be
Send today". Text and content-description still match, as a fallback for the
system dialogs that have no tags.

Subcommands (first argument is always the dump file):
  texts [LIMIT]                 one text/desc per line, in document order
  nodes                         counts by tag/class, plus clickable/enabled totals
  has NEEDLE                    exit 0 when any node's text/desc/id contains NEEDLE
  state NEEDLE                  prints "enabled=.. clickable=.. bounds=.." (first match)
  find NEEDLE                   prints "x y" centre of the first enabled, clickable match
  find-any NEEDLE               like find, but accepts non-clickable matches too
                                (find/find-any exit 4 when the only match is marked
                                 shown="false", 0 otherwise; coordinates either way)
  package PKG                   exit 0 when a node belongs to PKG (the app is on screen)
  texts-matching NEEDLE         every text/desc containing NEEDLE (diagnostics)
  is-blank                      exit 0 when the dump has no visible text at all
  attrs                         every attribute name the dump uses, with counts
NEEDLE matching is case-insensitive.

Both halves of a real-device failure mode live here (Phase 10 continuation v3):
the dump may expose the app's test tags or only its words, and it may mark a node
as not actually on screen (`shown="false"` on the Android builds that emit that
attribute). Matching therefore runs over tags AND text, and a node the platform
marks as not shown is preferred-against: it is only chosen when nothing else
matches, and the choice is reported, so a driver can see it happened.
"""
import re
import sys
import xml.etree.ElementTree as ET

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def load(path):
    try:
        tree = ET.parse(path)
    except Exception:
        return None
    return tree.getroot()


def val(node, key):
    return node.get(key, "") or ""


def haystack(node):
    return (val(node, "text") + " " + val(node, "content-desc") + " " + val(node, "resource-id")).lower()


def not_shown(node):
    """True when the platform explicitly marks this node as not on screen.

    Android builds that emit a `shown` attribute use `shown="false"` for nodes that
    exist in the hierarchy but are not being displayed (a scrolled-away item, a
    collapsed panel). Such a node can still be *found* by text or tag - and tapping
    its bounds taps whatever is really at those coordinates, which is how a driver
    ends up pressing the wrong thing. The dump is authoritative here; the attribute
    is simply absent on builds that do not emit it, in which case every node counts
    as shown (i.e. the old behaviour, not a new guess).
    """
    return val(node, "shown").lower() == "false"


def in_preferred_order(nodes):
    """Visible nodes first. Stable within each group, so document order holds."""
    return [n for n in nodes if not not_shown(n)] + [n for n in nodes if not_shown(n)]


def visible_text(node):
    text = val(node, "text")
    desc = val(node, "content-desc")
    return text or desc


def centre(node):
    m = BOUNDS_RE.search(val(node, "bounds"))
    if not m:
        return None
    x1, y1, x2, y2 = (int(m.group(i)) for i in range(1, 5))
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    path, cmd = sys.argv[1], sys.argv[2]
    needle = sys.argv[3].lower() if len(sys.argv) > 3 else ""
    root = load(path)
    if root is None:
        print("UI_DUMP_UNREADABLE %s" % path)
        return 3
    nodes = [n for n in root.iter("node")]

    if cmd == "texts":
        limit = int(sys.argv[3]) if len(sys.argv) > 3 else 40
        shown = 0
        for n in in_preferred_order(nodes):
            t = visible_text(n)
            if t:
                print(t)
                shown += 1
                if shown >= limit:
                    break
        return 0

    if cmd == "nodes":
        by_class = {}
        clickable = enabled = 0
        for n in nodes:
            cls = val(n, "class").split(".")[-1]
            by_class[cls] = by_class.get(cls, 0) + 1
            if val(n, "clickable") == "true":
                clickable += 1
            if val(n, "enabled") == "true":
                enabled += 1
        top = sorted(by_class.items(), key=lambda kv: -kv[1])[:6]
        print("nodes=%d clickable=%d enabled=%d classes=%s" % (
            len(nodes), clickable, enabled, ",".join("%s:%d" % kv for kv in top)))
        return 0

    if cmd == "has":
        for n in in_preferred_order(nodes):
            if needle in haystack(n):
                return 0
        return 1

    if cmd == "attrs":
        counts = {}
        for n in nodes:
            for key in n.keys():
                counts[key] = counts.get(key, 0) + 1
        hidden = sum(1 for n in nodes if not_shown(n))
        shown_attr = "no node carries a shown attribute" if "shown" not in counts else "%d node(s) marked shown=false" % hidden
        print("nodes=%d %s" % (len(nodes), shown_attr))
        print(" ".join("%s:%d" % kv for kv in sorted(counts.items())))
        return 0

    if cmd == "is-blank":
        for n in nodes:
            if visible_text(n):
                return 1
        return 0

    if cmd in ("state", "find", "find-any"):
        want_clickable = cmd == "find"
        for n in in_preferred_order(nodes):
            if needle in haystack(n):
                c = centre(n)
                if not c:
                    continue
                if want_clickable:
                    if val(n, "clickable") != "true" or val(n, "enabled") != "true":
                        # keep looking: a disabled duplicate is not tappable, and a
                        # parent container usually carries the click itself
                        continue
                print("%d %d" % c)
                # Exit 4 = "coordinates, but this match is not on screen": the
                # caller still gets usable coordinates (a device that marks its
                # whole hierarchy that way must not become undrivable), and can say
                # so in its log instead of pretending the tap was unambiguous.
                return 4 if not_shown(n) else 0
        # second pass: report the state of the first match so the driver can say
        # "found but disabled" instead of "not found"
        for n in nodes:
            if needle in haystack(n):
                print("FOUND_NOT_TAPPABLE enabled=%s clickable=%s bounds=%s shown=%s" % (
                    val(n, "enabled"), val(n, "clickable"), val(n, "bounds"),
                    val(n, "shown") or "n/a"))
                return 1
        return 1

    if cmd == "texts-matching":
        for n in nodes:
            if needle in haystack(n):
                t = visible_text(n) or val(n, "resource-id")
                if t:
                    print(t)
        return 0

    if cmd == "package":
        pkg = sys.argv[3] if len(sys.argv) > 3 else ""
        for n in nodes:
            if val(n, "package") == pkg:
                return 0
        return 1

    print("unknown subcommand %s" % cmd)
    return 2


if __name__ == "__main__":
    sys.exit(main())
