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

Environment: P10D_FAKE_ROOT (fixtures + state), P10D_FAKE_SCENARIO
  (happy|locked|blank|tags-gone|shown-hidden|dump-unusable|reader-dead|readertmp|
   incomplete|msys-mangled|onboarding).

The `onboarding` scenario is the v4 first-run flow: welcome -> the workspace step
(one folder, one action) -> the first project appears on disk and its chat opens.
It exists because the v4 driver has its own verdicts for that step, and a verdict
that is never exercised by the self-test is a verdict nobody has run.
"""
import os
import re
import shutil
import sys

ROOT = os.environ.get("P10D_FAKE_ROOT", "")
SCENARIO = os.environ.get("P10D_FAKE_SCENARIO", "happy")
PKG = "io.github.mcyber12.opencode"
EXT_PREFIX = "/storage/emulated/0/Android/data/%s" % PKG
# Phase 10 continuation v3: the shipping default root is shared storage. The fake
# phone serves that path from the fixture filesystem exactly like the app-specific
# one, so the driver's external checks (92-workspace-visibility.sh) run for real
# against a root that is NOT under Android/data - which is the case that used to be
# impossible to test without a phone in the room.
SHARED_PREFIX = "/storage/emulated/0/Documents/OpenCode"

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
                # A node is identified by its tag when the scenario serves one, and by
                # its visible text when the dump has no tags - which is the real-device
                # case this self-test exists to reproduce (a phone where the Compose
                # tags never reached the accessibility tree and the driver had to drive
                # by content).
                return n["id"] or n["text"]
    return ""


def ext_dir():
    """The app-specific external fallback (Android/data/<pkg>/files/workspaces)."""
    return os.path.join(DEV, "ext", PKG, "files", "workspaces")


def shared_dir():
    """The shared-storage default (Documents/OpenCode): the v3 live root."""
    return os.path.join(DEV, "shared", "Documents", "OpenCode")


def live_dir():
    """Where the fake phone says the projects are, per scenario."""
    return ext_dir() if SCENARIO == "no-grant" else shared_dir()


def transition(node_id):
    """Where a tap takes the app. Mirrors the real app's navigation."""
    here = screen()
    if here == "welcome" and node_id in ("welcome_continue", "Continue"):
        # Phase 10 continuation v4: a first run asks for the workspace folder before
        # it shows a project list, but only once per install - the flag is what the
        # real app persists, so the scenario can be walked exactly once.
        if SCENARIO == "onboarding" and "onboarded" not in state("flags", ""):
            put("screen", "workspace")
        else:
            put("screen", "projects")
    elif here == "workspace" and node_id in ("onboarding_workspace_use",
                                             "Use this folder as workspace"):
        # One tap: the first project is created and its chat opens. The chat creates
        # no directory - the project folder is the only thing that appears.
        project = "1"
        os.makedirs(os.path.join(live_dir(), project), exist_ok=True)
        put("project", project)
        put("flags", "onboarded")
        put("screen", "chat")
    elif here == "projects" and node_id in ("project_create", "Create project"):
        name = state("typed", "p10d-proj")
        os.makedirs(os.path.join(live_dir(), name), exist_ok=True)
        put("project", name)
        put("typed", "")
        put("screen", "chat")
    elif here in ("chat", "answer", "chat-menu") and node_id in ("open_files", "Project files"):
        put("screen", "files")
    elif here in ("chat", "answer") and node_id in ("open_settings", "Settings and diagnostics"):
        put("screen", "settings")
    elif here in ("chat", "answer") and node_id in ("open_projects", "Projects", "Open the project list"):
        put("screen", "projects")
    # ---- v4 items 2 and 3: search, the key dialog, the star and the quick switch --
    elif here in ("settings", "chat", "answer") and node_id in ("provider_search", "Search providers"):
        # The field is where it is; typing is what changes the screen (see shell()).
        put("search", "")
    elif here == "settings" and node_id in ("provider_openrouter", "No key stored"):
        put("screen", "settings-openr")
    elif here == "settings-openr" and node_id in ("provider_connect_openrouter", "Save key"):
        put("screen", "settings-key")
    elif here == "settings-key" and node_id in ("provider_key_cancel", "Not now"):
        put("screen", "settings-openr")
    elif here in ("settings-openr", "settings") and isinstance(node_id, str) and \
            node_id.startswith("model_star_"):
        # Starring: recorded so the detail line can say it happened.
        put("starred", node_id)
    elif here in ("chat", "answer") and isinstance(node_id, str) and (
            node_id == "model_quick_switch" or node_id.startswith("Model:")):
        put("screen", "chat-menu")
    elif here == "chat-menu" and (node_id in ("model_pick_0", "gpt-4o-mini")):
        # Picking a starred model: the header now names it.
        put("model", "openrouter/gpt-4o-mini")
        put("screen", "chat")
    elif here == "chat" and node_id == "composer_send":
        # The model does its work: a file the server writes into the project
        # directory. This is the file R7 then reads from outside the app.
        project = state("project", "p10d-proj")
        target = os.path.join(live_dir(), project)
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
        if SCENARIO == "dump-unusable":
            # What a real phone says when the UI never goes idle. The driver retries
            # with --compressed and with a different output path, and the scenario
            # makes every one of those fail - so the run has to report "the screen
            # could not be seen" rather than inventing an app-side reason.
            return (1, "ERROR: could not get idle state.\n")
        return (0, "UI hierchary dumped to: /sdcard/p10d-ui.xml\n")

    if cmd.startswith("rm -f "):
        return (0, "")

    if cmd.startswith("input text "):
        typed = cmd[len("input text "):].replace("%s", " ")
        put("typed", state("typed", "") + typed)
        # v4 item 2: a search box that does not filter is the bug this stage exists to
        # catch, so the fake phone filters the way the app does - a query nothing
        # matches shows the no-match copy, and a real one narrows the list.
        if screen() in ("settings", "settings-nomatch", "settings-openr"):
            query = state("search", "") + typed
            put("search", query)
            if "zzzqq" in query:
                put("screen", "settings-nomatch")
            elif "openr" in query:
                put("screen", "settings-openr")
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
            if screen() in ("files", "settings", "settings-nomatch", "settings-openr"):
                put("screen", "chat")
            elif screen() == "settings-key":
                put("screen", "settings-openr")
            elif screen() == "chat-menu":
                put("screen", "chat")
        if key in ("KEYCODE_DEL", "67"):
            # One delete per keyevent, exactly as the platform does it.
            put("search", state("search", "")[:-1])
            query = state("search", "")
            if screen() in ("settings-nomatch", "settings-openr", "settings"):
                if "zzzqq" in query:
                    put("screen", "settings-nomatch")
                elif "openr" in query:
                    put("screen", "settings-openr")
                else:
                    put("screen", "settings")
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

    if "content query" in cmd and "externalstorage.documents" in cmd:
        # The system Documents provider. The driver treats "the provider answered" as
        # evidence a file manager can open the folder and "the provider refused" as a
        # SKIP, so the fake answers like a device whose shell may query it.
        if SCENARIO in ("happy", "tags-gone", "shown-hidden"):
            return (0, "Row: 0 _display_name=OpenCode\n")
        return (1, "Error: Unsupported\n")

    if cmd.startswith("appops ") or " appops " in cmd:
        # `appops set|get MANAGE_EXTERNAL_STORAGE`: the grant state the driver and the
        # gates script read and set.
        if SCENARIO == "no-grant":
            return (0, "MANAGE_EXTERNAL_STORAGE: deny\n")
        return (0, "MANAGE_EXTERNAL_STORAGE: allow\n")

    if SHARED_PREFIX in cmd or EXT_PREFIX in cmd:
        # Shared storage and the app-specific external directory, mapped onto the
        # fixture filesystem and executed for real, so `ls`/`cat`/`wc`/`du` behave like
        # they do on a phone. The shared root is served from its own directory: a
        # v3 run reads the project files THERE, not from Android/data.
        real = cmd
        for prefix, mapped in ((SHARED_PREFIX, shared_dir()), (EXT_PREFIX, os.path.join(DEV, "ext", PKG))):
            real = real.replace(prefix, mapped)
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
        if rest.startswith("cat /sdcard/p10d-ui.xml") or rest.startswith("cat /data/local/tmp/p10d-ui.xml"):
            if SCENARIO == "msys-mangled":
                # THE v2 FALSE FAIL, byte for byte (p10d-out/ui/ui-wait-app-window.xml):
                # the host shell rewrote the device path into a Windows path, so what
                # the driver "cat"-ed was the device's complaint about a path that does
                # not exist there. The app is fine; the harness is mangling arguments.
                # The driver must say THAT, in seconds, and never blame the app.
                sys.stdout.write(
                    "cat: C:/Program Files/Git/sdcard/p10d-ui.xml: No such file or directory\n")
                return 0
            path = fixture(screen())
            if path is None or SCENARIO == "dump-unusable":
                # Nothing to serve: an unreadable/absent dump is what the driver has
                # to survive (and report) on a real phone.
                return 1
            body = open(path, encoding="utf-8").read()
            if SCENARIO == "tags-gone":
                # The real-device failure: the app is on screen and its words are in
                # the dump, but no resource-id survived. Every tag-based wait fails
                # here, which is exactly why the driver must also match content.
                body = re.sub(r'resource-id="[^"]*"', 'resource-id=""', body)
            elif SCENARIO == "no-grant":
                # The app is running without All files access: what it shows on screen
                # is the Android/data fallback, and the visibility script must say a
                # file manager cannot open it (that is V5's whole job).
                body = body.replace(SHARED_PREFIX, EXT_PREFIX + "/files/workspaces")
            elif SCENARIO == "shown-hidden":
                # A platform that marks its whole hierarchy not-shown. The reader must
                # still find things (a driver that goes blind here reports a working
                # app as broken); `find` flags such a match with exit code 4.
                # EVERY node, root and children alike: the point of the scenario is
                # a platform that marks the whole screen not-shown, and a driver that
                # then finds nothing would report a working app as broken.
                body = body.replace("<node ", '<node shown="false" ')
            # The fixture writes a placeholder project name; the phone serves the name
            # the driver actually typed, so the path on screen is the real one and the
            # root the driver derives from it is the root the shell check then verifies.
            body = body.replace("p10d-proj", state("project", "p10d-proj"))
            # v4 item 3: the model the chat header names. It starts on one model and
            # changes when the quick switch is used, so the driver can observe the
            # change instead of assuming it.
            body = body.replace("__MODEL__", state("model", "opencode/zen-1"))
            sys.stdout.write(body)
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
        if head == "uninstall":
            sys.stdout.write("Success\n")
            return 0
        # adb.exe on the owner's Windows host is a WINDOWS binary: it receives the
        # path string literally, and a POSIX-absolute path like
        # /c/src/app-release-signed.apk is 'C:\c\src\...' to it. The 2026-09-23 run
        # showed exactly that: 'failed to stat /c/src/app-release-signed.apk:
        # No such file or directory'. The fake phone rejects precisely the paths
        # real adb.exe rejects (anything that is not C:/... after conversion), so
        # the driver's host_path conversion is load-bearing in the winhost scenario -
        # without it the same FAIL the owner saw appears here instead of silently
        # pretending the install succeeded.
        apk_arg = args[-1] if args else ""
        force_msys = os.environ.get("P10D_FORCE_HOST_SHELL") == "windows-msys"
        if force_msys and not re.match(r"^[A-Za-z]:/", apk_arg):
            sys.stdout.write("Performing Streamed Install\n")
            sys.stdout.write(
                "adb.exe: failed to stat %s: No such file or directory\n" % apk_arg)
            return 1
        sys.stdout.write("Performing Streamed Install\nSuccess\n")
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
