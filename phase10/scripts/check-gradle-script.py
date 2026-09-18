#!/usr/bin/env python3
"""Phase 10: static checks for app/build.gradle.kts (no JDK, no Gradle needed).

Why this exists: three of the six red Phase 10 CI runs were caused by shapes in
THIS file that Gradle only reveals at build time - `signingLine` used before it
was defined (run 1), the `smokeImplementation` accessor that cannot exist for a
build type created in the same script (run 2), a duplicate `BuildConfig` import
(run 3) - and the packaging fix (run 6) is likewise a shape, not a type. The
Phase 9 static layer deliberately scans `app/src` only, so the build script sat
outside every check. This file closes that hole: no build here, just the
lexical facts the CI pipeline keeps depending on.

Check groups (findings are printed as `FAIL ...` lines; exit 0 = none):

  1. DELIMITERS - every {} [] () balances, computed over a string/comment-aware
     scan (this is what `check-kotlin-balance.py` does for app/src).
  2. NO DUPLICATE top-level imports (the run-3 failure).
  3. ORDERING - `val signingLine` is defined before its use; the
     `verifyAndStagePayload` registration precedes every `dependsOn(...)` of it.
  4. THE RUN-#6 PACKAGING FIX IS STILL PRESENT, line-for-line:
     staging can never be up-to-date; the payload tarball fails the build when
     missing; STAGED_ASSETS is printed; merge/compress asset tasks carry an
     explicit dependsOn. "Fixed locally" has already once failed to survive the
     next edit; a grep-level memory makes that cheap to catch instead of
     expensive to rediscover in CI.
  5. IDENTITY EXTRACTION AGREEMENT - the shell gates extract
     versionName/versionCode/applicationId with an ANCHORED grep
     (^[[:space:]]*name); this checks the anchored grep still returns exactly
     what the build script assigns, i.e. that no new comment line has crept in
     between the start of a line and the property and that the scripts did not
     regress to the unanchored form.

Usage:
  python3 phase10/scripts/check-gradle-script.py [REPO_ROOT]
"""
import os
import re
import sys

FINDINGS = []
NOTES = []


def fail(msg):
    FINDINGS.append(msg)


def note(msg):
    NOTES.append(msg)


def strip_code(text):
    """Return `text` with string LITERALS blanked (placeholders keep offsets)
    and comments removed, so lexical scans see only code. The build script uses
    ordinary double-quoted strings and `//`/block comments; no triple-quoted
    strings (asserted below as its own check, since a raw string would make the
    simplification here wrong).
    """
    if '"""' in text:
        fail('app/build.gradle.kts contains a triple-quoted string; this checker\'s '
             'string handling does not support it - extend strip_code() or avoid them')
        return text
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            if j >= n:
                fail("unterminated string literal in app/build.gradle.kts")
                break
            out.append('"' + " " * (j - i - 1) + '"')
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def main(argv):
    root = os.path.abspath(argv[1] if len(argv) > 1 else os.path.join(os.path.dirname(__file__), "..", ".."))
    gradle_path = os.path.join(root, "app", "build.gradle.kts")
    if not os.path.isfile(gradle_path):
        print("FAIL missing %s" % gradle_path)
        return 1
    with open(gradle_path, encoding="utf-8") as fh:
        raw = fh.read()
    code = strip_code(raw)

    # ---- 1. delimiter balance over the code-only text ------------------------
    pairs = {")": "(", "]": "[", "}": "{"}
    openers = set(pairs.values())
    stack = []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in openers:
            stack.append((ch, line))
        elif ch in pairs:
            if not stack or stack[-1][0] != pairs[ch]:
                fail("unbalanced '%s' at line %d (depth never came back up to it)" % (ch, line))
                break
            stack.pop()
    if stack:
        for ch, l in stack[:6]:
            fail("'%s' opened at line %d is never closed" % (ch, l))

    # ---- 2. no duplicate imports ---------------------------------------------
    imports = [ln.strip() for ln in raw.splitlines() if ln.strip().startswith("import ")]
    dups = sorted({i for i in imports if imports.count(i) > 1})
    for d in dups:
        fail("duplicate import: %s (it made run #3 fail to compile)" % d)

    # ---- 3. ordering: define-before-use, register-before-referenced ----------
    def first(pat, text):
        m = re.search(pat, text, re.S)
        return text[: m.start()].count("\n") + 1 if m else None

    def all_lines(pat, text):
        return [i + 1 for i, ln in enumerate(text.splitlines()) if re.search(pat, ln)]

    def_first = first(r"val\s+signingLine\s*=", code)
    uses = all_lines(r"logger\.lifecycle\(\s*signingLine\s*\)", code)
    if def_first is None:
        fail("`val signingLine` not found - run #1's bug was this variable escaping its scope; "
             "if it was renamed, update this checker deliberately")
    for u in uses:
        if def_first is not None and u < def_first:
            fail("signingLine used at line %d, defined at line %d (use-before-definition = run #1)" % (u, def_first))
    if len(uses) > 1:
        note("signingLine logged %d times: one line per build is the contract; %d is a change worth a comment"
             % (len(uses), len(uses)))
    reg = first(r"val\s+verifyAndStagePayload\s*=\s*tasks\.register", code)
    for u in all_lines(r"dependsOn\(\s*verifyAndStagePayload\s*\)", code):
        if reg is None or u < reg:
            fail("dependsOn(verifyAndStagePayload) at line %d precedes the task registration at line %s"
                 % (u, reg))

    # ---- 4. the run-#6 packaging fix must still be in the file ---------------
    # `code` matches where the fix is STRUCTURE; `raw` matches where it is a
    # string literal (strip_code blanks string contents, by design).
    required = [
        (code, r"outputs\.upToDateWhen\s*\{\s*false\s*\}",
         "verifyAndStagePayload no longer has outputs.upToDateWhen { false } (staged-then-skipped bug returns)"),
        (raw, r"runtime-payload\.tar\.gz",
         "the fail-fast check for the missing runtime-payload.tar.gz is gone from the build script"),
        (raw, r"STAGED_ASSETS",
         "the STAGED_ASSETS diagnostic is gone (it is what makes the next payload question answerable from a log)"),
        (raw, r"startsWith\(\"merge\"\)",
         "the explicit merge/compress-asset dependsOn guard is gone (run #6: FROM-CACHE merge won the race)"),
        (code, r"cacheIf",
         "the asset tasks' cacheIf(false) is gone (run #6 served compress*Assets FROM-CACHE without the payload)"),
    ]
    for text, pat, msg in required:
        if not re.search(pat, text):
            fail(msg)

    # ---- 5. the shell gates' anchored identity extraction agrees -------------
    # Runs against the RAW file (comments excluded): this must mirror exactly
    # what the shell `grep -E '^[[:space:]]*versionName = "'` would return.
    def anchored(prop):
        for ln in raw.splitlines():
            if ln.strip().startswith("//"):
                continue
            if not re.match(r"^[ \t]*%s = " % prop, ln):
                continue
            rest = ln.split("=", 1)[1].strip()
            if rest.startswith('"'):
                end = rest.find('"', 1)
                if end > 0:
                    return rest[1:end]
            else:
                return rest.split("//")[0].split()[0]
        return None

    want_vname = anchored("versionName")
    want_vcode = anchored("versionCode")
    want_appid = anchored("applicationId")
    for label, got in (("versionName", want_vname), ("versionCode", want_vcode),
                       ("applicationId", want_appid)):
        if got is None:
            fail("no single-line `%s = ` assignment visible to the anchored grep in "
                 "phase10/scripts/*.sh - the gates would extract nothing" % label)
    # The scripts must keep the ANCHORED form; the unanchored one read the
    # `// versionName = "<pinned OpenCode version>-phase10"` comment first.
    scripts = ["40-release-verify.sh", "50-smoke-gates.sh", "90-real-device-signed.sh",
               "sign-release-local.sh"]
    for s in scripts:
        sp = os.path.join(root, "phase10", "scripts", s)
        if not os.path.isfile(sp):
            continue
        with open(sp, encoding="utf-8") as fh:
            st = fh.read()
        for prop in ("versionName",):
            for ln in st.splitlines():
                if ln.strip().startswith("#"):
                    continue  # prose about the old bug is not the bug
                if "grep" in ln and (prop + " = ") in ln:
                    if "[[:space:]]" not in ln and "^\\s*" not in ln:
                        fail("%s greps %s unanchored again - it will match the placeholder comment (run #6)" % (s, prop))
    # versions.lock is the source of truth for the pair; compare if readable.
    lock = os.path.join(root, "versions.lock")
    if os.path.isfile(lock):
        with open(lock, encoding="utf-8") as fh:
            lt = fh.read()
        m = re.search(r"^\s+versionName:\s*(\S+)", lt, re.M)
        if m and want_vname and m.group(1) != want_vname:
            fail("versionName mismatch: versions.lock %r vs build.gradle.kts %r" % (m.group(1), want_vname))
        m = re.search(r'^\s+versionCode:\s*"(\d+)"', lt, re.M)
        if m and want_vcode and m.group(1) != want_vcode:
            fail("versionCode mismatch: versions.lock %r vs build.gradle.kts %r" % (m.group(1), want_vcode))
        m = re.search(r"^\s+applicationId:\s*(\S+)", lt, re.M)
        if m and want_appid and m.group(1) != want_appid:
            fail("applicationId mismatch: versions.lock %r vs build.gradle.kts %r" % (m.group(1), want_appid))

    print("GRADLE_CHECK file=app/build.gradle.kts findings=%d notes=%d "
          "identity=[versionName=%s versionCode=%s applicationId=%s]"
          % (len(FINDINGS), len(NOTES), want_vname, want_vcode, want_appid))
    for n in NOTES:
        print("NOTE %s" % n)
    for f in FINDINGS:
        print("FAIL %s" % f)
    verdict = "PASS" if not FINDINGS else "FAIL"
    print("GRADLE_CHECK %s" % verdict)
    return 0 if not FINDINGS else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
