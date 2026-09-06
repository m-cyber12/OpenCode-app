#!/usr/bin/env python3
"""Phase 6 static check: the workflow this phase asks the user to install.

Two failure modes this project has already paid for:

  * a step `name:` containing ": " that is not quoted, which makes the YAML a
    nested mapping and the workflow is rejected before it runs (Phase 3, run
    33215077537: "mapping values are not allowed here");
  * a workflow pinned to a *previous* session's branch, so it is registered but
    never triggers - which is exactly the state phase3/phase4/phase5-integration
    are in now (their branches were merged and deleted).

So: parse the YAML when pyyaml is available, always check step names, and check
that phase6/workflow/*.yml triggers on the branch this session is working on.

Usage: python3 phase6/scripts/check-workflow-yaml.py [repo-root]
"""
import os
import re
import subprocess
import sys

STEP_NAME_RE = re.compile(r"^(\s*)-\s*name:\s*(.+?)\s*$", re.MULTILINE)


def current_branch(root):
    try:
        out = subprocess.run(["git", "-C", root, "rev-parse", "--abbrev-ref", "HEAD"],
                             capture_output=True, text=True, timeout=30)
        return out.stdout.strip()
    except Exception:
        return ""


def check_names(path, body, root):
    bad = 0
    for m in STEP_NAME_RE.finditer(body):
        val = m.group(2)
        if val.startswith(('"', "'")) or val.startswith("|") or val.startswith(">"):
            continue
        if ": " in val or val.endswith(":"):
            print("FAIL %s:%d unquoted step name containing a colon: %s"
                  % (os.path.relpath(path, root), body.count("\n", 0, m.start()) + 1, val))
            bad = 1
    return bad


def check_branch(path, body, root, branch):
    if not branch:
        print("note: current git branch unknown; skipping the trigger-branch check for %s"
              % os.path.relpath(path, root))
        return 0
    m = re.search(r"^\s*branches:\s*\[(.*?)\]", body, re.MULTILINE)
    listed = []
    if m:
        listed = [x.strip().strip("\"'") for x in m.group(1).split(",")]
    else:
        m2 = re.search(r"^\s*branches:\s*\n((?:\s*-\s*.+\n?)+)", body, re.MULTILINE)
        if m2:
            listed = [x.strip().lstrip("-").strip().strip("\"'") for x in m2.group(1).strip().split("\n")]
    if not listed:
        print("FAIL %s: no on.push.branches list found" % os.path.relpath(path, root))
        return 1
    if branch not in listed:
        print("FAIL %s: triggers on %s but this session works on %s (the workflow would never run)"
              % (os.path.relpath(path, root), listed, branch))
        return 1
    print("OK   %s triggers on the current branch %s" % (os.path.relpath(path, root), branch))
    return 0


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    branch = current_branch(root)
    paths = []
    for base in ("phase6/workflow", ".github/workflows"):
        d = os.path.join(root, base)
        if os.path.isdir(d):
            paths += [os.path.join(d, f) for f in sorted(os.listdir(d)) if f.endswith((".yml", ".yaml"))]
    try:
        import yaml
        have_yaml = True
    except Exception:
        have_yaml = False
    bad = 0
    for p in paths:
        body = open(p, encoding="utf-8").read()
        if have_yaml:
            try:
                doc = yaml.safe_load(body)
                # `on:` parses as the boolean True key in YAML 1.1 - accept both.
                triggers = doc.get("on", doc.get(True)) if isinstance(doc, dict) else None
                if not triggers:
                    print("FAIL %s: no `on:` triggers" % os.path.relpath(p, root))
                    bad = 1
                print("OK   yaml parse %s" % os.path.relpath(p, root))
            except Exception as e:
                print("FAIL yaml parse %s: %s" % (os.path.relpath(p, root), e))
                bad = 1
        else:
            print("note: pyyaml unavailable; structural checks only for %s" % os.path.relpath(p, root))
        bad |= check_names(p, body, root)
        if "phase6/workflow" in p.replace(os.sep, "/"):
            bad |= check_branch(p, body, root, branch)
    print("check-workflow-yaml: %d files, pyyaml=%s" % (len(paths), have_yaml))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
