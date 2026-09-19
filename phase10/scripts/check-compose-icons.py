#!/usr/bin/env python3
"""Advisory check: only icons that ship in `material-icons-core` may be referenced.

Why this exists: the Phase 10 continuation added a file browser and used
`Icons.Filled.Folder` / `Icons.Filled.Description` - which are NOT in
`material-icons-core`, the only icons artifact this project depends on (the
`-extended` artifact is deliberately avoided: with `isMinifyEnabled = false` its
thousands of unused vectors would ship inside the APK). The failure mode is a
compile error, i.e. a whole CI run to learn one missing icon name.

Rule: an icon reference outside the core set is reported as a NOTE with the fix
(`Icons.AutoMirrored.Filled.*` for the deprecated directional ones, or an explicit
decision to add material-icons-extended), and never as a hard failure - the
compiler is the authority, and a wrong allow-list here must not be able to break a
verdict run.

Usage: python3 phase10/scripts/check-compose-icons.py [app/src/main/java]
"""
import os
import re
import sys

# Icons in androidx.compose.material:material-icons-core (the Filled set; Outlined,
# Rounded, Sharp and TwoTone mirror it). Kept as data so a future reviewer can see
# exactly what the app is allowed to draw without another dependency.
CORE_FILLED = {
    "AccountBox", "AccountCircle", "Add", "AddCircle", "ArrowBack", "ArrowDropDown",
    "ArrowForward", "Build", "Call", "Check", "CheckCircle", "Clear", "Close",
    "Create", "DateRange", "Delete", "Done", "Edit", "Email", "ExitToApp", "Face",
    "Favorite", "FavoriteBorder", "Home", "Info", "KeyboardArrowDown",
    "KeyboardArrowLeft", "KeyboardArrowRight", "KeyboardArrowUp", "List",
    "LocationOn", "Lock", "MailOutline", "Menu", "MoreVert", "Notifications",
    "Person", "Phone", "Place", "PlayArrow", "Refresh", "Search", "Send",
    "Settings", "Share", "ShoppingCart", "Star", "ThumbUp", "Warning",
}
# Deprecated in Compose 1.6 in favour of Icons.AutoMirrored.Filled.*: still present,
# so this is a note about style, not about availability.
DEPRECATED_DIRECTIONAL = {"ArrowBack", "ArrowForward", "ExitToApp", "KeyboardArrowLeft",
                          "KeyboardArrowRight", "List", "Send"}

ICON_RE = re.compile(r"Icons\.(?:AutoMirrored\.)?(Filled|Outlined|Rounded|Sharp|TwoTone)\.([A-Za-z0-9_]+)")


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        "app", "src", "main", "java", "ai", "opencode", "android")
    unknown, deprecated = [], []
    files = 0
    for dirpath, _dirs, names in os.walk(root):
        for name in names:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            files += 1
            with open(path, encoding="utf-8", errors="replace") as fh:
                src = fh.read()
            for m in ICON_RE.finditer(src):
                kind, icon = m.group(1), m.group(2)
                if kind != "Filled":
                    continue  # the other themes are generated from the same core set
                if icon not in CORE_FILLED:
                    line = src.count("\n", 0, m.start()) + 1
                    unknown.append("%s:%d Icons.%s.%s" % (path, line, kind, icon))
                elif icon in DEPRECATED_DIRECTIONAL and "AutoMirrored" not in m.group(0):
                    line = src.count("\n", 0, m.start()) + 1
                    deprecated.append("%s:%d Icons.%s.%s (use AutoMirrored.Filled.%s)" % (path, line, kind, icon, icon))
    for u in unknown:
        print("NOTE not in material-icons-core, will not compile: %s" % u)
    for d in deprecated:
        print("NOTE deprecated in Compose 1.6 (still available): %s" % d)
    print("check-compose-icons: %d kotlin files, %d not-in-core, %d deprecated references"
          % (files, len(unknown), len(deprecated)))
    if unknown:
        print("FIX: use an icon from material-icons-core, or add "
              "androidx.compose.material:material-icons-extended to app/build.gradle.kts "
              "knowing that isMinifyEnabled=false means its unused vectors ship too.")
    # Advisory by design: the compiler decides, this only names the trap early.
    return 0


if __name__ == "__main__":
    sys.exit(main())
