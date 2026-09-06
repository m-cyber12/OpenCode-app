#!/usr/bin/env python3
"""Phase 6 static check: gross Kotlin syntax sanity, without a compiler.

The development sandbox for this project has no JDK, no Gradle and no reachable
Maven mirror, so the first compiler feedback for new Kotlin arrives from CI about
fifteen minutes after a push. That is fine for type errors and useless for a
truncated file. This check catches the mechanical failures locally:

  * unbalanced (), {} or [] in code (strings and comments masked out first),
  * a stray unterminated string literal,
  * a file that does not start with `package`,
  * in the UI layer: a capitalized function that calls composables but is not
    annotated @Composable (Compose reports this as a type error deep inside the
    call graph; naming makes it visible here), unless the line above carries the
    explicit marker `// not-composable`.

Usage: python3 phase6/scripts/check-kotlin-balance.py [root ...]
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ktscan  # noqa: E402

COMPOSABLE_CALLS = re.compile(r"\b(Text|Column|Row|Box|Surface|Card|ElevatedCard|OutlinedCard|LazyColumn|"
                              r"LazyRow|Button|TextButton|OutlinedButton|FilledTonalButton|IconButton|Icon|"
                              r"TextField|OutlinedTextField|CircularProgressIndicator|LinearProgressIndicator|"
                              r"HorizontalDivider|VerticalDivider|Scaffold|AnimatedVisibility|Spacer|Divider|"
                              r"SelectionContainer|Canvas|Badge|Switch|Checkbox|RadioButton|Slider|TabRow)\s*\(")


def norm(path):
    return path.replace(os.sep, "/")


def main():
    roots = sys.argv[1:] or [os.path.join("app", "src")]
    files = ktscan.walk(roots, (".kt",))
    fails = []
    for path in files:
        src = open(path, encoding="utf-8", errors="replace").read()
        stripped = src.lstrip()
        if not stripped.startswith("package ") and not stripped.startswith("@file:"):
            fails.append("%s: does not start with a package declaration" % path)
        net = ktscan.balanced(src)
        for k, v in net.items():
            if v != 0:
                fails.append("%s: unbalanced %s (net %+d) - truncated or mis-braced file" % (path, k, v))
        # Unterminated string literal: an odd number of unescaped quotes on a
        # code line, outside a raw string. The scanner already reports raw
        # strings, so this only looks at single-line literals.
        for offset, line, kind, value in ktscan.string_literals(src):
            # A template may legitimately span lines ("...${x.apply {\n...}\n}..."),
            # so only a literal with a raw newline and no template is suspicious.
            if kind == ktscan.STRING and "\n" in value and "${" not in value:
                fails.append("%s:%d string literal crosses a line boundary" % (path, line))
        if "/ui/" not in norm(path):
            continue
        code = ktscan.code_text_of_source(src)
        lines = src.split("\n")
        for m in re.finditer(r"^(?:private |internal |public )?fun\s+([A-Z]\w*)\s*\(", code, re.MULTILINE):
            name = m.group(1)
            line_no = code.count("\n", 0, m.start()) + 1
            window = code[m.start():m.start() + 4000]
            if not COMPOSABLE_CALLS.search(window):
                continue
            preceding = "\n".join(lines[max(0, line_no - 6):line_no - 1])
            if "@Composable" not in preceding and "not-composable" not in preceding:
                fails.append("%s:%d fun %s calls composables but is not @Composable" % (path, line_no, name))
    for f in fails:
        print("FAIL " + f)
    print("check-kotlin-balance: %d files, %d findings" % (len(files), len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
