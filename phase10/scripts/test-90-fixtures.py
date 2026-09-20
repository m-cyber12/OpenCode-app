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


def node(nid, text="", desc="", bounds="[0,0][1,1]", cls="android.widget.Button",
         clickable="true", enabled="true", pkg=PKG):
    return ('  <node index="0" text="%s" resource-id="%s" class="%s" package="%s" '
            'content-desc="%s" checkable="false" checked="false" clickable="%s" '
            'enabled="%s" focusable="true" focused="false" scrollable="false" '
            'long-clickable="false" password="false" selected="false" '
            'bounds="%s" />\n') % (text, nid, cls, pkg, desc, clickable, enabled, bounds)


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
    # The screen carries the v3 storage panel: the location, the mode label and the
    # two ways the user can change it. A driver run must find the path here and say
    # which mode the app claims - and, on a device without the grant, must NOT find a
    # "Documents/OpenCode" label next to an Android/data path.
    screens_files = screen("files", [
        text_node("Where these files are", "[40,150][1040,200]"),
        node("files_location_path", text=SHARED_WORKSPACE, bounds="[40,260][1040,300]", clickable="false"),
        node("files_storage_mode", text="Documents/OpenCode", bounds="[40,300][1040,340]", clickable="false"),
        node("files_list", text="", bounds="[0,380][1080,1700]", clickable="false"),
        node("files_up", text="Up", bounds="[40,400][300,480]"),
        node("files_dir", text="src", bounds="[40,520][1040,600]"),
        node("files_file", text="p10-visible.txt", bounds="[40,620][1040,700]"),
        node("files_publish", text="Export a copy...", bounds="[40,720][540,800]"),
    ])

    # ---- settings (provider keys) ---------------------------------------------
    screens_settings = screen("settings", [
        text_node("Provider keys", "[40,200][1040,280]"),
        node("key_provider", text="openrouter", desc="Provider", bounds="[40,320][1040,420]",
             cls="android.widget.EditText"),
        node("key_value", text="", desc="API key", bounds="[40,460][1040,560]",
             cls="android.widget.EditText"),
        node("key_save", text="Save key", bounds="[40,600][540,700]"),
    ])

    for name, body in (("welcome", screens_welcome), ("projects", screens_projects),
                       ("chat", screens_chat), ("answer", screens_answer),
                       ("files", screens_files), ("settings", screens_settings)):
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
    for name in ("screen", "answer", "files", "projects", "welcome", "settings"):
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
