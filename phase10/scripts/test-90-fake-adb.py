#!/usr/bin/env python3
"""A fake `adb` for test-90-real-device.sh: enough of a phone to run the driver.

This is not a mock of the driver - it is a mock of the DEVICE. The driver is the
real script, unmodified, driven through a state machine: `input tap` does a hit test
against the current screen's uiautomator dump, `uiautomator dump` serves that dump,
`screencap` serves a real photo of this app, and the fake filesystem answers `ls`/`cat`
the way an unprivileged shell answers them on a stock phone (app-specific external
storage readable, /data/data refused).

Why this exists: the driver's job is to be trusted on a phone the author cannot
plug in. Four of its bugs were found by reading it (a `grep -c || echo 0` that made
three gates fail on a perfect run, a tap() that accepted a "FOUND_NOT_TAPPABLE"
diagnostic as a coordinate, a needle split that made a wait pass on the wrong word,
and an ANR dialog recorded with a verdict token of "1"). Reading is not running, so
the driver now runs here first.

Environment: P10D_FAKE_ROOT (fixtures + state), P10D_FAKE_SCENARIO (happy|locked|blank).
"""
import os
import re
import shutil
import sys

ROOT = os.environ.get("P10D_FAKE_ROOT", "")
SCENARIO = os.environ.get("P10D_FAKE_SCENARIO", "happy")
PKG = "io.github.mcyber12.opencode"
EXT_PREFIX = "/storage/emulated/0/Android/data/%s" % PKG

DEV = os.path.join(ROOT, "dev")
FX = os.path.join(ROOT, "fx")
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def state(key, default=""):
    path = os.path.join(DEV, key)
    if not os.path.exists(path):
        return default
    with open(path, encoding="utf-8") as fh:
        return fh.read().strip()


def put(key, value):
    os.makedirs(DEV, exist_ok=True)
    with open(os.path.join(DEV, key), "w", encoding="utf-8") as fh:
        fh.write(value)


def screen():
    return state("screen", "welcome")


EMPTY_DUMP = '<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>\n<hierarchy rotation="0">\n</hierarchy>\n'


def fixture(name):
    """The dump for a screen, or an EMPTY hierarchy when the fixture is missing.

    An empty tree is what a real device gives you when there is nothing to describe,
    and it makes the driver say "found nothing" instead of crashing on a traceback -
    a fake phone that dies is a fake phone that lies about the driver.
    """
    path = os.path.join(FX, "screens", name + ".xml")
    if os.path.exists(path):
        return path
    fallback = os.path.join(FX, "screens", "chat.xml")
    if os.path.exists(fallback):
        return fallback
    sys.stderr.write("test-90-fake-adb: no fixture for screen '%s'\n" % name)
    return None


def nodes(xml_path):
    if not xml_path or not os.path.exists(xml_path):
        return []
    with open(xml_path, encoding="utf-8") as fh:
        text = fh.read()
    out = []
    for tag in re.findall(r"<node[^>]*/>", text):
        def attr(key):
            m = re.search(r'%s="([^"]*)"' % key, tag)
            return m.group(1) if m else ""
        out.append({
            "id": attr("resource-id"),
            "text": attr("text"),
            "desc": attr("content-desc"),
            "bounds": attr("bounds"),
            "clickable": attr("clickable") == "true",
            "enabled": attr("enabled") == "true",
        })
    return out


def hit(x, y, name):
    for n in nodes(fixture(name)):
        m = BOUNDS.search(n["bounds"])
        if not m:
            continue
        x1, y1, x2, y2 = (int(m.group(i)) for i in range(1, 5))
        if x1 <= x <= x2 and y1 <= y <= y2:
            if n["clickable"] and n["enabled"]:
                return n["id"]
    return ""


def ext_dir():
    return os.path.join(DEV, "ext", PKG, "files", "workspaces")


def transition(node_id):
    """Where a tap takes the app. Mirrors the real app's navigation."""
    here = screen()
    if here == "welcome" and node_id == "welcome_continue":
        put("screen", "projects")
    elif here == "projects" and node_id == "project_create":
        name = state("typed", "p10d-proj")
        os.makedirs(os.path.join(ext_dir(), name), exist_ok=True)
        put("project", name)
        put("typed", "")
        put("screen", "chat")
    elif here == "chat" and node_id == "open_files":
        put("screen", "files")
    elif here == "chat" and node_id == "open_settings":
        put("screen", "settings")
    elif here == "chat" and node_id == "composer_send":
        # The model does its work: a file the server writes into the project
        # directory. This is the file R7 then reads from outside the app.
        project = state("project", "p10d-proj")
        target = os.path.join(ext_dir(), project)
        os.makedirs(target, exist_ok=True)
        with open(os.path.join(target, "p10-visible.txt"), "w", encoding="utf-8") as fh:
            fh.write("p10-live-ok\n")
        put("screen", "answer")
    # settings/key_save, tool-card taps and everything else stay where they are


def shell(command):
    """Answer a device shell command the way a stock, non-rooted phone would."""
    cmd = command.strip()

    if cmd.startswith("getprop "):
        prop = cmd.split(None, 1)[1]
        table = {
            "ro.product.cpu.abi": "arm64-v8a",
            "ro.build.version.sdk": "34",
            "ro.build.version.release": "14",
            "ro.product.model": "Pixel 7",
            "ro.product.manufacturer": "Google",
        }
        if prop not in table:
            return (1, "")
        return (0, table[prop] + "\n")

    if cmd.startswith("uiautomator dump"):
        return (0, "UI hierchary dumped to: /sdcard/p10d-ui.xml\n")

    if cmd.startswith("rm -f /sdcard/p10d-ui.xml"):
        return (0, "")

    if cmd.startswith("input text "):
        typed = cmd[len("input text "):].replace("%s", " ")
        put("typed", state("typed", "") + typed)
        return (0, "")

    if cmd.startswith("input tap "):
        parts = cmd.split()
        if len(parts) >= 4:
            try:
                x, y = int(parts[2]), int(parts[3])
            except ValueError:
                return (0, "")
            node_id = hit(x, y, screen())
            if not node_id:
                return (1, "Error: a tap landed on no node\n")
            transition(node_id)
        return (0, "")

    if cmd.startswith("input keyevent"):
        key = cmd.split()[-1]
        if key in ("KEYCODE_BACK", "4"):
            if screen() in ("files", "settings"):
                put("screen", "chat")
        return (0, "")

    if cmd.startswith("input swipe") or cmd.startswith("svc ") or \
            cmd.startswith("settings put") or cmd.startswith("pm grant") or \
            cmd.startswith("wm dismiss-keyguard"):
        return (0, "")

    if cmd.startswith("wm size"):
        return (0, "Physical size: 1080x1920\n")
    if cmd.startswith("wm density"):
        return (0, "Physical density: 420\n")

    if cmd.startswith("dumpsys power"):
        locked = SCENARIO == "locked"
        return (0, "mWakefulness=%s\n" % ("Asleep" if locked else "Awake"))

    if cmd.startswith("dumpsys window"):
        if SCENARIO == "locked":
            return (0, "  mCurrentFocus=Window{7d0 u0 com.android.systemui/KeyguardHostView}\n"
                       "  mFocusedApp=ActivityRecord{1b2 u0 com.android.systemui/.keyguard.KeyguardService}\n"
                       "  mShowingLockscreen=true\n")
        return (0, "  mCurrentFocus=Window{1a2 u0 %s/ai.opencode.android.MainActivity}\n"
                   "  mFocusedApp=ActivityRecord{3c4 u0 %s/ai.opencode.android.MainActivity}\n"
                   "  mShowingLockscreen=false\n" % (PKG, PKG))

    if cmd.startswith("dumpsys activity"):
        return (0, "  mResumedActivity: ActivityRecord{3c4 u0 %s/ai.opencode.android.MainActivity}\n" % PKG)

    if cmd.startswith("dumpsys deviceidle"):
        return (0, "mState=ACTIVE\n")

    if cmd.startswith("dumpsys package"):
        return (0, "  versionName=1.18.23-phase10\n  versionCode=8 minSdk=29 targetSdk=34\n")

    if cmd.startswith("dumpsys meminfo"):
        return (0, "               TOTAL PSS:   123456      TOTAL RSS:  234567\n")

    if cmd.startswith("du ") or " du -sh " in cmd or cmd.startswith("du -sh"):
        return (0, "12M\t%s\n" % EXT_PREFIX)

    if "/data/data/%s" % PKG in cmd:
        # The sandbox, from an unprivileged shell. V4/V5 of the visibility script
        # assert exactly this, so the fake device must refuse it too.
        path = re.search(r"(/data/data/%s\S*)" % re.escape(PKG), cmd)
        return (0, "ls: %s: Permission denied\n" % (path.group(1) if path else "/data/data/%s" % PKG))

    if EXT_PREFIX in cmd:
        # The app-specific external directory, mapped onto the fixture filesystem and
        # executed for real, so `ls`/`cat`/`wc`/`du` behave like they do on a phone.
        real = cmd.replace(EXT_PREFIX, os.path.join(DEV, "ext", PKG))
        import subprocess
        proc = subprocess.run(["bash", "-c", real], capture_output=True, text=True)
        return (proc.returncode, proc.stdout + proc.stderr)

    return (0, "")


def main(argv):
    if not ROOT:
        sys.stderr.write("test-90-fake-adb: P10D_FAKE_ROOT is not set\n")
        return 2
    args = argv[1:]
    if not args:
        return 0
    head = args[0]

    if head == "get-state":
        sys.stdout.write("device\n")
        return 0

    if head == "shell":
        code, out = shell(" ".join(args[1:]))
        sys.stdout.write(out)
        return 0

    if head == "exec-out":
        rest = " ".join(args[1:])
        if rest.startswith("cat /sdcard/p10d-ui.xml"):
            path = fixture(screen())
            sys.stdout.write(open(path, encoding="utf-8").read() if path else EMPTY_DUMP)
            return 0
        if rest.startswith("screencap"):
            name = state("screencap", "screen")
            path = os.path.join(FX, "shots", "blank.png" if SCENARIO == "blank" else name + ".png")
            if not os.path.exists(path):
                path = os.path.join(FX, "shots", "screen.png")
            with open(path, "rb") as fh:
                sys.stdout.buffer.write(fh.read())
            return 0
        return 0

    if head in ("install", "uninstall"):
        sys.stdout.write("Performing Streamed Install\nSuccess\n" if head == "install" else "Success\n")
        return 0

    if head == "logcat":
        if "-c" in args:
            return 0
        sys.stdout.write("09-19 12:00:00.001  1234  1234 I OpenCode/runtime: agent healthy\n"
                         "09-19 12:00:01.002  1234  1234 I OpenCode/gate: P6_MODEL_AVAILABLE 1 :: fake\n")
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
