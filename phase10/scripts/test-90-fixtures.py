#!/usr/bin/env python3
"""Build the fake-device fixtures for test-90-real-device.sh.

Everything here exists so that the real-device driver can be RUN (not just parsed)
before a human spends an evening plugging in a phone. The fixtures are the screens
the driver will meet, written as uiautomator dumps with the same shape the platform
produces: `<node class=... resource-id=... text=... bounds="[x1,y1][x2,y2]"
clickable=... enabled=... package=...>`.

Coordinates are deliberately not all at the same place, and every control the driver
taps is a real node with `clickable="true" enabled="true"` - so the run exercises the
driver's own `ui find` -> `input tap` path instead of a shortcut.
"""
import os
import struct
import sys
import zlib

PKG = "io.github.mcyber12.opencode"

HEAD = ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
        "<hierarchy rotation=\"0\">\n")
TAIL = "</hierarchy>\n"


def esc(value):
    """Escape a string the way the platform escapes it inside a dump attribute.

    Not decoration: the app's own copy contains double quotes ("No provider matches
    \"zzzqq\"."), and a raw quote inside a double-quoted XML attribute makes the whole
    dump unparsable. The reader then reports UI_DUMP_UNREADABLE and every gate that
    reads that screen sees nothing - which is how this went unnoticed until the
    self-check learned to read the fixtures through the same reader the driver uses.
    """
    return (value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                 .replace('"', "&quot;"))


def node(nid, text="", desc="", bounds="[0,0][1,1]", cls="android.widget.Button",
         clickable="true", enabled="true", pkg=PKG):
    return ('  <node index="0" text="%s" resource-id="%s" class="%s" package="%s" '
            'content-desc="%s" checkable="false" checked="false" clickable="%s" '
            'enabled="%s" focusable="true" focused="false" scrollable="false" '
            'long-clickable="false" password="false" selected="false" '
            'bounds="%s" />\n') % (esc(text), esc(nid), esc(cls), esc(pkg), esc(desc),
                                    clickable, enabled, bounds)


def text_node(text, bounds, pkg=PKG, desc=""):
    return node("", text=text, desc=desc, bounds=bounds, cls="android.widget.TextView",
                clickable="false", pkg=pkg)


# The v3 live root, as the app shows it on screen. The real project name is
# substituted by the fake phone (see test-90-fake-adb.py), so this is the path the
# driver reads off the files screen and then hands to 92-workspace-visibility.sh as
# `--root` - i.e. the self-test walks the same "app reports the location, the shell
# verifies it" chain a phone run does.
SHARED_WORKSPACE = "/storage/emulated/0/Documents/OpenCode/p10d-proj"
LEGACY_WORKSPACE = "/storage/emulated/0/Android/data/%s/files/workspaces" % PKG


def screen(name, nodes):
    return HEAD + "".join(nodes) + TAIL


def build(root):
    screens = os.path.join(root, "screens")
    os.makedirs(screens, exist_ok=True)

    # ---- welcome: the state the app shows while the runtime comes up -----------
    screens_welcome = screen("welcome", [
        node("welcome_screen", text="OpenCode", bounds="[0,0][1080,1920]", clickable="false"),
        text_node("OpenCode", "[40,600][1040,700]"),
        text_node("Setting up", "[40,720][1040,780]"),
        node("welcome_continue", text="Continue", bounds="[40,900][1040,1000]"),
    ])

    # ---- projects -------------------------------------------------------------
    screens_projects = screen("projects", [
        text_node("Projects", "[40,200][1040,280]"),
        node("project_name_input", text="", desc="Project name", bounds="[40,320][1040,420]",
             cls="android.widget.EditText"),
        node("project_create", text="Create project", bounds="[40,460][1040,560]"),
    ])

    # ---- conversation ---------------------------------------------------------
    def chat(extra=None):
        parts = [
            node("chat_screen", text="", bounds="[0,0][1080,1920]", clickable="false"),
            # v4 item 3: the quick switch at the top of the chat. The label carries a
            # token the fake phone substitutes, so the self-test can observe the model
            # actually changing after a pick (see test-90-fake-adb.py).
            node("model_quick_switch", text="Model: __MODEL__", desc="Model: __MODEL__",
                 bounds="[40,240][1040,300]"),
            # The top bar's project affordance (the app opens the project list from it).
            node("open_projects", text="Projects", desc="Open the project list",
                 bounds="[40,120][400,220]"),
            node("open_files", text="Project files", bounds="[820,120][940,220]"),
            # Deliberately its own rectangle: `hit()` matches the FIRST node whose
            # bounds contain the tap, so two controls must never overlap or the self
            # test would drive a different control than the driver aimed at.
            node("open_settings", text="Settings and diagnostics", bounds="[960,120][1060,220]"),
            text_node("Start a conversation", "[40,300][1040,380]"),
            node("composer_attach", text="Attach a file", bounds="[40,1700][300,1780]"),
            node("composer_input", text="Message the agent", bounds="[320,1690][820,1790]",
                 cls="android.widget.EditText"),
            node("composer_send", text="", desc="Send", bounds="[900,1690][1040,1790]"),
        ]
        parts.extend(extra or [])
        return screen("chat", parts)

    screens_chat = chat()
    screens_answer = chat([
        text_node("You asked me to write the file with the bash tool.", "[40,420][1040,500]"),
        text_node("Shell command \u00b7 bash", "[40,560][1040,640]"),
        node("tool_card_prt_test", text="Shell command", bounds="[40,540][1040,700]",
             clickable="true"),
        text_node("p10-visible.txt", "[40,720][1040,790]"),
        text_node("p10-live-ok", "[40,800][1040,870]"),
    ])

    # ---- the app's own file browser -------------------------------------------
    # The screen carries the v4 storage panel: the location, the mode label and-
    # when a file manager cannot open the folder - the one repair (the grant). The
    # copy-path and export controls are gone (v4 item 4): the fixture no longer
    # serves them, so a build that reintroduced one fails FILES_SIMPLIFIED in the
    # self-test instead of passing on a stale fixture.
    screens_files = screen("files", [
        text_node("Where these files are", "[40,150][1040,200]"),
        node("files_location_path", text=SHARED_WORKSPACE, bounds="[40,260][1040,300]", clickable="false"),
        node("files_storage_mode", text="Documents/OpenCode", bounds="[40,300][1040,340]", clickable="false"),
        node("files_list", text="", bounds="[0,380][1080,1700]", clickable="false"),
        node("files_up", text="Up", bounds="[40,400][300,480]"),
        node("files_dir", text="src", bounds="[40,520][1040,600]"),
        node("files_file", text="p10-visible.txt", bounds="[40,620][1040,700]"),
    ])

    # ---- settings: workspace switch, provider search, stars, provider keys -----
    # One screen carrying every section the driver reads in R5b and R6. Bounds never
    # overlap: the fake phone's hit test matches the first node containing the tap, so
    # an overlap would drive a different control than the driver aimed at.
    screens_settings = screen("settings", [
        text_node("Agent runtime", "[40,120][1040,180]"),
        node("settings_screen", text="", bounds="[0,0][1080,3000]", clickable="false"),
        # v4 item 1/4: the workspace switch, with the honest note about switching.
        text_node("Workspace folder", "[40,200][1040,260]"),
        text_node(SHARED_WORKSPACE, "[40,280][1040,330]"),
        node("settings_workspace_pick", text="Choose another folder", bounds="[40,340][540,440]"),
        text_node("Switching the workspace hides the projects in the old folder, the way cd "
                  "changes what a terminal shows. Nothing is deleted; switch back to see them again.",
                  "[40,460][1040,540]"),
        # v4 item 2: the search field over the catalog.
        node("provider_search", text="Search providers", desc="Search providers",
             bounds="[40,580][1040,680]", cls="android.widget.EditText"),
        # v4 item 2: one-step activation - the provider row and its key action.
        node("provider_openrouter", text="No key stored", bounds="[40,700][680,800]"),
        node("provider_connect_openrouter", text="Save key", bounds="[700,700][1040,800]"),
        # v3: the manual provider-key path (R6 enters a key here when one is given).
        text_node("Provider keys", "[40,900][1040,980]"),
        node("key_provider", text="openrouter", desc="Provider", bounds="[40,1000][1040,1100]",
             cls="android.widget.EditText"),
        node("key_value", text="", desc="API key", bounds="[40,1120][1040,1220]",
             cls="android.widget.EditText"),
        node("key_save", text="Save key", bounds="[40,1240][540,1340]"),
    ])

    # ---- settings, after a search that matches nothing -------------------------
    screens_settings_nomatch = screen("settings-nomatch", [
        node("settings_screen", text="", bounds="[0,0][1080,3000]", clickable="false"),
        node("provider_search", text="zzzqq", desc="Search providers", bounds="[40,580][1040,680]",
             cls="android.widget.EditText"),
        text_node("No provider matches \"zzzqq\". Use the fields below for an endpoint that is "
                  "not in the catalog.", "[40,700][1040,780]"),
        text_node("Provider keys", "[40,900][1040,980]"),
        node("key_provider", text="openrouter", desc="Provider", bounds="[40,1000][1040,1100]",
             cls="android.widget.EditText"),
        node("key_value", text="", desc="API key", bounds="[40,1120][1040,1220]",
             cls="android.widget.EditText"),
        node("key_save", text="Save key", bounds="[40,1240][540,1340]"),
    ])

    # ---- settings, after a search that matches OpenRouter ----------------------
    # The provider row is expanded here, which is where the star checkboxes live.
    screens_settings_openr = screen("settings-openr", [
        node("settings_screen", text="", bounds="[0,0][1080,3000]", clickable="false"),
        node("provider_search", text="openr", desc="Search providers", bounds="[40,580][1040,680]",
             cls="android.widget.EditText"),
        text_node("1 of 500 providers shown", "[40,700][1040,760]"),
        node("provider_openrouter", text="No key stored", bounds="[40,780][600,880]"),
        text_node("OpenRouter", "[40,780][400,880]"),
        node("model_star_openrouter_gpt-4o-mini", text="",
             desc="Show gpt-4o-mini in the chat quick switch", bounds="[620,780][860,880]"),
        node("provider_connect_openrouter", text="Save key", bounds="[880,780][1040,880]"),
        text_node("Provider keys", "[40,1100][1040,1180]"),
        node("key_provider", text="openrouter", desc="Provider", bounds="[40,1200][1040,1300]",
             cls="android.widget.EditText"),
        node("key_value", text="", desc="API key", bounds="[40,1320][1040,1420]",
             cls="android.widget.EditText"),
        node("key_save", text="Save key", bounds="[40,1440][540,1540]"),
    ])

    # ---- the one-step activation dialog (item 2): the key and nothing else -----
    screens_settings_key = screen("settings-key", [
        text_node("API key for OpenRouter", "[40,600][1040,680]"),
        text_node("Only the key is asked for here. The base URL and the model list come from "
                  "the agent provider catalog.", "[40,700][1040,780]"),
        text_node("API key", "[40,820][400,880]"),
        node("provider_key_value", text="", desc="API key", bounds="[40,900][1040,1000]",
             cls="android.widget.EditText"),
        node("provider_key_save", text="Save key", bounds="[700,1040][1040,1140]"),
        node("provider_key_cancel", text="Not now", bounds="[400,1040][680,1140]"),
    ])

    # ---- the quick-switch menu (item 3): only the starred models ---------------
    screens_chat_menu = screen("chat-menu", [
        text_node("Starred models", "[40,400][1040,460]"),
        node("model_pick_0", text="gpt-4o-mini", bounds="[40,480][1040,560]"),
        node("model_quick_switch_settings", text="Choose models in Settings", bounds="[40,580][1040,660]"),
    ])

    # ---- the v4 first-run workspace step (item 4) ------------------------------
    # Two controls and nothing else. The removed controls are NOT here: a build that
    # put one of them back would show up in R4a's own dump check.
    screens_workspace = screen("workspace", [
        text_node("Where your files will live", "[40,300][1040,380]"),
        node("onboarding_workspace", text="", bounds="[0,0][1080,1920]", clickable="false"),
        text_node("Workspace folder", "[40,420][1040,480]"),
        node("onboarding_workspace_path", text=SHARED_WORKSPACE.rsplit("/", 1)[0],
             bounds="[40,500][1040,560]", clickable="false"),
        node("onboarding_workspace_pick", text="Choose folder", bounds="[40,700][540,800]"),
        node("onboarding_workspace_use", text="Use this folder as workspace", bounds="[560,700][1040,800]"),
        text_node("You can change the folder later in Settings.", "[40,820][1040,880]"),
    ])

    for name, body in (("welcome", screens_welcome), ("projects", screens_projects),
                       ("chat", screens_chat), ("answer", screens_answer),
                       ("files", screens_files), ("settings", screens_settings),
                       ("settings-nomatch", screens_settings_nomatch),
                       ("settings-openr", screens_settings_openr),
                       ("settings-key", screens_settings_key),
                       ("chat-menu", screens_chat_menu),
                       ("workspace", screens_workspace)):
        with open(os.path.join(screens, name + ".xml"), "w", encoding="utf-8") as fh:
            fh.write(body)

    # ---- the frames a fake `screencap` will return ---------------------------
    # Generated here rather than borrowed, so this self-test needs nothing but the
    # repository: a light screen with content on it reads as a real screen to
    # p10d-png.py, and a black frame reads as blank - the two cases the driver has to
    # tell apart. (test-90-real-device.sh replaces these with the Phase 6 evidence
    # photos when they are present, which is a nicer picture of the real app but not
    # required for the test to mean something.)
    shots_dir = os.path.join(root, "shots")
    os.makedirs(shots_dir, exist_ok=True)
    write_screen_png(os.path.join(shots_dir, "blank.png"), blank=True)
    for name in ("screen", "answer", "files", "projects", "welcome", "settings",
                 "settings-nomatch", "settings-openr", "settings-key", "chat-menu", "workspace"):
        write_screen_png(os.path.join(shots_dir, name + ".png"))
    return screens, shots_dir


def write_screen_png(path, width=1080, height=1920, blank=False):
    """A PNG p10d-png.py classifies the way its name says it should be classified."""
    rows = []
    for y in range(height):
        if blank:
            rows.append(b"\x00" + b"\x00" * (width * 3))
            continue
        if y < 90:
            pixel = (0x33, 0x44, 0x55)          # a status bar
        elif y > height - 260:
            pixel = (0xE7, 0xEE, 0xF6)          # the composer area
        elif (y // 26) % 2 == 0 and 160 < y < height - 300:
            pixel = (0x1B, 0x1B, 0x1F)          # lines of text
        else:
            pixel = (0xFA, 0xFA, 0xFC)          # the page
        rows.append(b"\x00" + bytes(pixel) * width)
    raw = b"".join(rows)

    def chunk(tag, data):
        body = tag + data
        return (struct.pack(">I", len(data)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 6))
           + chunk(b"IEND", b""))
    with open(path, "wb") as fh:
        fh.write(png)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: test-90-fixtures.py OUTDIR")
        sys.exit(2)
    build(sys.argv[1])
