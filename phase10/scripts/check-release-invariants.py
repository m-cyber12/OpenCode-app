#!/usr/bin/env python3
"""Phase 10 release invariants: the things that must be true about the TREE
before anything is built, signed or uploaded.

Static, no JDK/SDK/network needed - it runs in the authoring sandbox, in CI and
on the human's signing machine, and it is the gate that makes the Phase 10 rules
mechanical rather than aspirational:

  1. NO KEY MATERIAL, EVER. No keystore/private-key file, no literal password,
     no `keystore.properties` in the tree; the Gradle signing block reads only
     from outside the repo; `.gitignore` covers the file names; the release doc
     contains no real secret (only placeholders).
  2. THE PUBLISHED IDENTITY IS THE INTENDED ONE. applicationId, versionName,
     versionCode, icon resources and the manifest's icon/roundIcon references all
     agree with `versions.lock` (the single source of truth), and no harness
     script still drives the *old* applicationId.
  3. THE STORE PACKAGE EXISTS AND FITS THE RULES. Listing copy within Play's
     length limits, privacy policy present and asserting the properties this
     architecture actually has, icon 512x512, feature graphic 1024x500, and at
     least two screenshots of a real screen size.
  4. CI STILL CANNOT SIGN. The Phase 10 workflow references no keystore secret.

Usage: check-release-invariants.py <repo-root> [--require-store-assets]
Exit 0 = all invariants hold. Findings are printed as FAIL lines.
"""
import os
import re
import struct
import subprocess
import sys

REQUIRED_DOCS = [
    "docs/RELEASE.md",
    "docs/BRANDING.md",
    "docs/PRIVACY-POLICY.md",
    "docs/STORE-LISTING.md",
    "docs/THIRD-PARTY-NOTICES.md",
]
FORBIDDEN_FILE_PATTERNS = [
    r".*\.jks$", r".*\.keystore$", r".*\.p12$", r".*\.pfx$", r".*\.pepk$",
    r"(^|/)keystore\.properties$", r".*\.b64\.keystore$",
]
# Things that look like a real secret in a text file.
SECRET_IN_TEXT = [
    re.compile(r"-----BEGIN (RSA|EC|DSA|OPENSSH|PGP) PRIVATE KEY-----"),
    re.compile(r"storePassword\s*=\s*(?!\.\.\.|<|\$\{)[^\s#]{3,}"),
    re.compile(r"keyPassword\s*=\s*(?!\.\.\.|<|\$\{)[^\s#]{3,}"),
    re.compile(r"P9_KEYSTORE_B64\s*[:=]\s*[A-Za-z0-9+/]{40,}"),
]
# The privacy policy has to describe THIS app: loopback-only server, Keystore,
# no account, no analytics, and the only egress being the model provider the user
# configures. If the architecture ever changes, this check fails and the policy
# gets revisited instead of drifting.
PRIVACY_REQUIRED_PHRASES = [
    "127.0.0.1",
    "Android Keystore",
    "no account",
    "analytics",
    "provider",
    "crash",
]
COPY_LIMITS = {"short": 80, "full": 4000}

SKIP_DIRS = {".git", "build", "out", "node_modules", ".gradle", "dist", ".idea"}


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return None


def walk_files(root):
    for dp, dn, fn in os.walk(root):
        dn[:] = [d for d in dn if d not in SKIP_DIRS]
        for f in fn:
            yield os.path.join(dp, f)


def png_size(path):
    """PNG width/height from the IHDR chunk: no imaging library required."""
    try:
        with open(path, "rb") as fh:
            head = fh.read(26)
        if head[:8] != b"\x89PNG\r\n\x1a\n":
            return None
        w, h = struct.unpack(">II", head[16:24])
        return w, h
    except OSError:
        return None


def git_grep(root, pattern):
    try:
        p = subprocess.run(["git", "-C", root, "grep", "-nIE", pattern, "--", "."],
                           capture_output=True, text=True, timeout=120)
        return [l for l in p.stdout.splitlines() if l.strip()]
    except Exception:
        return []


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    require_store = "--require-store-assets" in sys.argv
    fails = []
    notes = []

    def fail(msg):
        fails.append(msg)

    # ---------------------------------------------------------------- 1. secrets
    for path in walk_files(root):
        rel = os.path.relpath(path, root)
        for pat in FORBIDDEN_FILE_PATTERNS:
            if re.match(pat, rel):
                fail("key material in the tree: %s" % rel)
        if os.path.getsize(path) > 2_000_000:
            continue
        text = read(path)
        if text is None or "\x00" in text[:1024]:
            continue
        if os.path.basename(path) in ("check-release-invariants.py", "check-apk.py",
                                      "test-check-apk.py", "30-static-checks.sh",
                                      "phase9-release.yml"):
            continue  # these files NAME the patterns they forbid
        for rx in SECRET_IN_TEXT:
            for m in rx.finditer(text):
                val = m.group(0).split("=", 1)[-1].strip()
                # A bare identifier (releaseStorePassword) or an expression
                # (System.getenv(...), ${...}) is wiring, not a literal secret.
                if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", val) or "(" in val:
                    continue
                fail("looks like a real secret in %s: %s" % (rel, m.group(0)[:60]))
                break

    gi = read(os.path.join(root, ".gitignore")) or ""
    for needed in ("keystore.properties", "*.jks", "*.keystore"):
        if needed not in gi:
            fail(".gitignore does not cover %s" % needed)

    gradle = read(os.path.join(root, "app/build.gradle.kts")) or ""
    if not gradle:
        fail("app/build.gradle.kts missing")
    for bad in ('storePassword = "', 'keyPassword = "'):
        if bad in gradle:
            fail("build.gradle.kts contains a literal password assignment (%s)" % bad)
    for needed in ("keystore.properties", "System.getenv", "RELEASE_KEYSTORE_FILE"):
        if needed not in gradle:
            fail("build.gradle.kts does not document/read signing input %s" % needed)

    release_doc = read(os.path.join(root, "docs/RELEASE.md"))
    if release_doc is None:
        fail("docs/RELEASE.md missing")
    else:
        for needed in ("keytool -genkeypair", "keystore.properties", "apksigner",
                       "zipalign", "Play App Signing", "never commit"):
            if needed.lower() not in release_doc.lower():
                fail("docs/RELEASE.md does not cover %r" % needed)

    # ------------------------------------------------------------- 2. identity
    lock = read(os.path.join(root, "versions.lock")) or ""
    app_block = re.search(r"^app:[^\n]*\n((?:[ \t].*\n|[ \t]*\n)*)", lock, re.M)
    want = {}
    if app_block:
        for line in app_block.group(1).splitlines():
            m = re.match(r"\s+([a-zA-Z_]+):\s*\"?([^\"\s#]+)\"?", line)
            if m:
                want[m.group(1)] = m.group(2)
    else:
        fail("versions.lock has no `app:` block (Phase 10 adds the published identity)")

    def gradle_value(key, text):
        """Read a Gradle assignment as text, with or without quotes.

        `versionName = "x"` and `versionCode = 8` are both normal; comparing them
        to the lock file only works if both sides end up as the same kind of
        string, so the quotes are stripped and not required.
        """
        m = re.search(r"^\s*%s\s*=\s*\"?([^\"\n]+?)\"?\s*(?://.*)?$" % key, text, re.M)
        return m.group(1).strip() if m else None

    got_appid = gradle_value("applicationId", gradle)
    got_vname = gradle_value("versionName", gradle)
    got_vcode = gradle_value("versionCode", gradle)
    if want:
        if got_appid != want.get("applicationId"):
            fail("applicationId=%r but versions.lock says %r" % (got_appid, want.get("applicationId")))
        if got_vname != want.get("versionName"):
            fail("versionName=%r but versions.lock says %r" % (got_vname, want.get("versionName")))
        if got_vcode != want.get("versionCode"):
            fail("versionCode=%r but versions.lock says %r" % (got_vcode, want.get("versionCode")))
    if got_appid and got_appid.startswith("ai.opencode."):
        fail("applicationId %r uses upstream's own domain - an impersonation risk "
             "(docs/BRANDING.md explains why the published ID is io.github.<owner>.*)" % got_appid)

    manifest = read(os.path.join(root, "app/src/main/AndroidManifest.xml")) or ""
    for needed in ('android:icon="@mipmap/ic_launcher"',
                   'android:roundIcon="@mipmap/ic_launcher_round"'):
        if needed not in manifest:
            fail("AndroidManifest.xml lacks %s" % needed)

    res = os.path.join(root, "app/src/main/res")
    for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        for name in ("ic_launcher.png", "ic_launcher_round.png", "ic_launcher_foreground.png"):
            if not os.path.isfile(os.path.join(res, "mipmap-%s" % density, name)):
                fail("missing launcher asset mipmap-%s/%s" % (density, name))
    for name in ("mipmap-anydpi-v26/ic_launcher.xml",
                 "mipmap-anydpi-v26/ic_launcher_round.xml",
                 "values/ic_launcher_background.xml"):
        if not os.path.isfile(os.path.join(res, name)):
            fail("missing adaptive-icon resource %s" % name)

    # Only the live harness matters here: docs/progress/**-evidence holds the
    # historical logs of the phases that ran under the old applicationId, and
    # rewriting evidence is exactly what this project does not do.
    stale = []
    for sub in ("phase4/scripts", "phase5/scripts", "phase6/scripts", "phase7/scripts",
                "phase8/scripts", "phase9/scripts", "phase10/scripts", ".github/workflows"):
        d = os.path.join(root, sub)
        for path in walk_files(d) if os.path.isdir(d) else []:
            if not path.endswith((".sh", ".py", ".js", ".yml")):
                continue
            if os.path.basename(path) == "check-release-invariants.py":
                continue  # this file names the pattern it forbids
            text = read(path) or ""
            if "ai.opencode.android.debug" in text:
                stale.append(os.path.relpath(path, root))
    if stale:
        fail("harness still drives the pre-Phase-10 applicationId: %s" % stale[:4])

    # ------------------------------------------------- 3. store package exists
    for doc in REQUIRED_DOCS:
        if not read(os.path.join(root, doc)):
            fail("missing release document %s" % doc)

    policy = read(os.path.join(root, "docs/PRIVACY-POLICY.md")) or ""
    for phrase in PRIVACY_REQUIRED_PHRASES:
        if phrase.lower() not in policy.lower():
            fail("privacy policy does not mention %r - it must describe what the app does" % phrase)
    if re.search(r"\b(unknown|TBD|TODO|placeholder)\b", policy, re.I):
        notes.append("privacy policy contains a placeholder word - review before upload")

    listing = read(os.path.join(root, "docs/STORE-LISTING.md")) or ""
    short = re.search(r"^short_description:\s*(.+)$", listing, re.M)
    full = re.search(r"^full_description:\s*\|?\n((?:  .*\n|\n)+)", listing, re.M)
    if not short:
        fail("docs/STORE-LISTING.md has no `short_description:` line")
    elif len(short.group(1).strip()) > COPY_LIMITS["short"]:
        fail("short description is %d chars (Play limit %d)"
             % (len(short.group(1).strip()), COPY_LIMITS["short"]))
    if not full:
        fail("docs/STORE-LISTING.md has no `full_description: |` block")
    else:
        body = "\n".join(l[2:] for l in full.group(1).splitlines())
        if len(body) > COPY_LIMITS["full"]:
            fail("full description is %d chars (Play limit %d)" % (len(body), COPY_LIMITS["full"]))
        if "not affiliated" not in body.lower() and "independent" not in body.lower():
            fail("full description does not say this is an independent client")

    store = os.path.join(root, "docs/store")
    icon = os.path.join(store, "icon-512.png")
    feature = os.path.join(store, "feature-graphic-1024x500.png")
    shots = sorted(p for p in (os.path.join(store, "screenshots"), ) if os.path.isdir(p))
    have_icon = os.path.isfile(icon) and png_size(icon) == (512, 512)
    have_feature = os.path.isfile(feature) and png_size(feature) == (1024, 500)
    shot_files = []
    for d in shots:
        shot_files = sorted(os.path.join(d, f) for f in os.listdir(d) if f.endswith(".png"))
    big_enough = [s for s in shot_files
                  if (png_size(s) or (0, 0))[0] >= 320 and (png_size(s) or (0, 0))[1] >= 320]
    if not have_icon:
        (fail if require_store else notes.append)(
            "docs/store/icon-512.png must be a 512x512 PNG (Play requirement)")
    if not have_feature:
        (fail if require_store else notes.append)(
            "docs/store/feature-graphic-1024x500.png must be a 1024x500 PNG (Play requirement)")
    if len(big_enough) < 2:
        (fail if require_store else notes.append)(
            "docs/store/screenshots needs at least 2 PNGs of a real screen size (have %d)"
            % len(big_enough))

    # --------------------------------------------------------- 4. CI cannot sign
    for wf in ("phase10/workflow/phase10-release.yml",
               ".github/workflows/phase10-release.yml"):
        text = read(os.path.join(root, wf))
        if text is None:
            if wf.startswith(".github"):
                notes.append("%s not installed yet (needs the GitHub web UI; see the report)" % wf)
            continue
        # Naming a variable is not receiving it: the workflow is REQUIRED to
        # explain which secrets must never be added, and its own guard step checks
        # that they are absent. What must never appear is an expression that would
        # PULL the secret in, or an env: block that would inject it.
        for m in re.finditer(r"secrets\.([A-Za-z0-9_]*)", text):
            name = m.group(1)
            if re.search(r"KEYSTORE|KEY_ALIAS|KEY_PASSWORD|KEYSTORE_PASSWORD", name, re.I):
                fail("%s pulls %s from secrets - CI must never receive signing material "
                     "(docs/RELEASE.md s8)" % (wf, name))
        for m in re.finditer(r"^\s*([A-Z0-9_]*(?:KEYSTORE|KEY_PASSWORD|KEY_ALIAS)[A-Z0-9_]*):\s*\S",
                             text, re.M):
            if m.group(1) not in ("P9_KEYSTORE_B64", "P9_KEYSTORE_FILE", "P9_KEY_ALIAS",
                                  "P9_KEY_PASSWORD", "RELEASE_KEYSTORE_FILE",
                                  "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD",
                                  "RELEASE_KEYSTORE_PASSWORD"):
                continue
            # An env: entry whose value is an EMPTY expansion (the guard step's
            # printenv loop) is allowed only when the same line is inside the
            # "confirm no signing material" step; anything else is a real feed.
            line_start = text.rfind("\n", 0, m.start()) + 1
            line = text[line_start:text.find("\n", m.start())]
            if "secrets." in line:
                fail("%s injects %s from secrets - CI must never receive signing material"
                     % (wf, m.group(1)))

    for n in notes:
        print("NOTE %s" % n)
    for f in fails:
        print("FAIL %s" % f)
    print("INVARIANTS %s (%d findings, %d notes)"
          % ("PASS" if not fails else "FAIL", len(fails), len(notes)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
