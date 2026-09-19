#!/usr/bin/env python3
"""Self-test for p10d-ui.py: the dump reader the real-device driver depends on.

The driver's verdicts ("the composer appeared", "the app is in front", "that
control is tappable") all come through this reader, so it must be tested with
dumps whose answers are known - including the two cases that made the v1 run
undiagnosable:

  * a dump where a node exists but is DISABLED (the v1 driver's `grep` would have
    "found" it and then tapped a dead control), and
  * a dump that belongs to a system dialog rather than the app (so "the app owns
    the window" must be answerable).

Usage: python3 phase10/scripts/test-p10d-ui.py
"""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
READER = os.path.join(HERE, "p10d-ui.py")

APP_XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="io.github.mcyber12.opencode" content-desc="" clickable="false" enabled="true" bounds="[0,0][1080,1920]">
 <node index="0" text="OpenCode" resource-id="" class="android.view.View" package="io.github.mcyber12.opencode" content-desc="" clickable="false" enabled="true" bounds="[400,100][680,150]" />
 <node index="1" text="Continue" resource-id="welcome_continue" class="android.view.View" package="io.github.mcyber12.opencode" content-desc="" clickable="true" enabled="false" bounds="[20,600][1060,680]" />
 <node index="2" text="Send" resource-id="composer_send" class="android.view.View" package="io.github.mcyber12.opencode" content-desc="" clickable="true" enabled="true" bounds="[980,1700][1060,1780]" />
 <node index="3" text="" resource-id="files_location_path" class="android.view.View" package="io.github.mcyber12.opencode" content-desc="Folder" clickable="false" enabled="true" bounds="[10,200][1070,260]" />
</node>
</hierarchy>
"""

DIALOG_XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.android.systemui" content-desc="" clickable="false" enabled="true" bounds="[0,0][1080,1920]">
 <node index="0" text="OpenCode isn't responding" resource-id="" class="android.widget.TextView" package="android" content-desc="" clickable="false" enabled="true" bounds="[80,800][1000,860]" />
 <node index="1" text="Wait" resource-id="android:id/aerr_wait" class="android.widget.Button" package="android" content-desc="" clickable="true" enabled="true" bounds="[700,900][1000,980]" />
</node>
</hierarchy>
"""

BLANK_XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.android.systemui" content-desc="" clickable="false" enabled="true" bounds="[0,0][1080,1920]" />
</hierarchy>
"""


def run(path, *args):
    proc = subprocess.run([sys.executable, READER, path] + list(args),
                          capture_output=True, text=True)
    return proc.returncode, proc.stdout.strip()


def main():
    checks = []
    with tempfile.TemporaryDirectory() as tmp:
        app = os.path.join(tmp, "app.xml")
        dlg = os.path.join(tmp, "dialog.xml")
        blank = os.path.join(tmp, "blank.xml")
        for path, text in ((app, APP_XML), (dlg, DIALOG_XML), (blank, BLANK_XML)):
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(text)

        rc, out = run(app, "find", "composer_send")
        checks.append(("an enabled clickable control yields tap coordinates",
                       rc == 0 and out == "1020 1740", "rc=%s out=%r" % (rc, out)))

        rc, out = run(app, "find", "welcome_continue")
        checks.append(("a DISABLED control is refused, and says why",
                       rc == 1 and "NOT_TAPPABLE" in out and "enabled=false" in out,
                       "rc=%s out=%r" % (rc, out)))

        rc, out = run(app, "has", "welcome_screen")
        checks.append(("a missing tag is reported as missing", rc == 1, "rc=%s" % rc))

        rc, out = run(app, "has", "welcome_continue")
        checks.append(("an existing tag (even disabled) is found by has", rc == 0, "rc=%s" % rc))

        rc, out = run(app, "has", "FOLDER")
        checks.append(("matching is case-insensitive and covers content-desc", rc == 0, "rc=%s" % rc))

        rc, out = run(app, "package", "io.github.mcyber12.opencode")
        checks.append(("the app owning the window is detectable", rc == 0, "rc=%s" % rc))
        rc, out = run(dlg, "package", "io.github.mcyber12.opencode")
        checks.append(("a system dialog does NOT look like the app", rc == 1, "rc=%s" % rc))

        rc, out = run(dlg, "has", "isn't responding")
        checks.append(("an ANR dialog is detectable", rc == 0, "rc=%s" % rc))

        rc, out = run(blank, "is-blank")
        checks.append(("a dump with no text is reported blank", rc == 0, "rc=%s" % rc))
        rc, out = run(app, "is-blank")
        checks.append(("a real screen is not reported blank", rc == 1, "rc=%s" % rc))

        rc, out = run(app, "texts", "3")
        checks.append(("texts lists what is on screen, in order",
                       out.splitlines()[:3] == ["OpenCode", "Continue", "Send"],
                       "out=%r" % out))

        rc, out = run(app, "nodes")
        checks.append(("nodes summarises the dump",
                       rc == 0 and "nodes=5" in out and "clickable=2" in out, "out=%r" % out))

        rc, out = run(app, "texts-matching", "android")
        checks.append(("texts-matching lists only what matched",
                       out == "" or all("android" in l.lower() for l in out.splitlines()),
                       "out=%r" % out))

        broken = os.path.join(tmp, "broken.xml")
        with open(broken, "w", encoding="utf-8") as fh:
            fh.write("<hierarchy><node")
        rc, out = run(broken, "has", "anything")
        checks.append(("an unreadable dump is reported, not matched",
                       rc == 3 and "UNREADABLE" in out, "rc=%s out=%r" % (rc, out)))

    failed = [c for c in checks if not c[1]]
    for name, ok, detail in checks:
        print("%s %s (%s)" % ("PASS" if ok else "FAIL", name, detail))
    print("p10d-ui selftest: %d checks, %d failed" % (len(checks), len(failed)))
    print("SELFTEST %s" % ("PASS" if not failed else "FAIL"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
