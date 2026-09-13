#!/usr/bin/env python3
"""Phase 8 static check: the workflow this phase asks the user to install.

Same rules as phase7/scripts/check-workflow.py (quoted step names, valid YAML,
triggers on THIS session's branch), pointed at phase8/workflow. Kept as a small
standalone so the Phase 7 checker stays untouched.

Usage: python3 phase8/scripts/check-workflow-p8.py [repo-root]
"""
import os
import re
import subprocess
import sys

STEP_NAME_RE = re.compile(r"^(\s*)- name:\s*(.+?)\s*$", re.MULTILINE)


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
    base = os.path.join(root, "phase8", "workflow")
    if not os.path.isdir(base):
        print("FAIL no phase8/workflow directory")
        return 1
    paths = [os.path.join(base, f) for f in sorted(os.listdir(base)) if f.endswith((".yml", ".yaml"))]
    if not paths:
        print("FAIL no workflow template under phase8/workflow")
        return 1
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
        bad |= check_branch(p, body, root, branch)
    print("check-workflow-p8: %d files, pyyaml=%s" % (len(paths), have_yaml))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
