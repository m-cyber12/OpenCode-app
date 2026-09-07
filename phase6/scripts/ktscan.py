#!/usr/bin/env python3
"""Shared Kotlin source scanner for the Phase 6 static UI checks.

Phase 6 has no local compiler in the development sandbox (no JDK, no Gradle, no
reachable Maven mirror), so the checks that CAN run locally have to carry more
weight than usual. Everything here works on a masked copy of the source - real
code kept, strings and comments replaced by spaces of the same length - which
makes rules like "no hardcoded user-visible text", "every Icon has a
contentDescription" and "brackets balance" decidable without a type checker,
while keeping offsets and line numbers aligned with the original file.

Kotlin specifics handled:
  * nested block comments (the trap that cost Phase 5 a CI run),
  * string templates: `"a ${f("b")} c"` has a string INSIDE a string, so the
    template expression is skipped recursively rather than ending the literal at
    the first inner quote (getting this wrong reported a false "unbalanced ("
    against runtime/Diagnostics.kt),
  * raw (triple-quoted) strings, including their templates,
  * character literals and escapes.
"""
import os
import re

CODE, STRING, RAWSTRING, CHAR = "code", "string", "rawstring", "char"

EXCLUDE_DIRS = {"out", "build", "node_modules", ".gradle", ".git", "generated"}


def walk(roots, suffixes=(".kt",)):
    files = []
    for root in roots:
        if os.path.isfile(root):
            if root.endswith(suffixes):
                files.append(root)
            continue
        for dp, dn, fn in os.walk(root):
            dn[:] = [d for d in dn if d not in EXCLUDE_DIRS]
            for f in fn:
                if f.endswith(suffixes):
                    files.append(os.path.join(dp, f))
    return sorted(files)


def _skip_template(src, i):
    """src[i] == '$' followed by '{'. Return the index just past the matching '}'."""
    depth = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == "{":
            depth += 1
            i += 1
            continue
        if c == "}":
            depth -= 1
            i += 1
            if depth == 0:
                return i
            continue
        if c == '"':
            i = _skip_string(src, i)
            continue
        if c == "'":
            i = _skip_char(src, i)
            continue
        i += 1
    return i


def _skip_string(src, i):
    """src[i] == '"' (a normal, single-line literal). Return the index past it."""
    n = len(src)
    i += 1
    while i < n:
        c = src[i]
        if c == "\\":
            i += 2
            continue
        if c == "\n":
            return i
        if src.startswith("${", i):
            i = _skip_template(src, i)
            continue
        if c == '"':
            return i + 1
        i += 1
    return i


def _skip_raw(src, i):
    """src[i:i+3] == '\"\"\"'. Return the index past the closing triple quote."""
    n = len(src)
    i += 3
    while i < n:
        if src.startswith("${", i):
            i = _skip_template(src, i)
            continue
        if src.startswith('"""', i):
            return i + 3
        i += 1
    return i


def _skip_char(src, i):
    n = len(src)
    i += 1
    while i < n:
        c = src[i]
        if c == "\\":
            i += 2
            continue
        if c in "'\n":
            return i + 1
        i += 1
    return i


def _skip_block_comment(src, i):
    """Kotlin block comments nest."""
    n = len(src)
    depth = 0
    while i < n:
        if src.startswith("/*", i):
            depth += 1
            i += 2
            continue
        if src.startswith("*/", i):
            depth -= 1
            i += 2
            if depth == 0:
                return i
            continue
        i += 1
    return i


def _blank(seg):
    return "".join("\n" if ch == "\n" else " " for ch in seg)


def code_text_of_source(src):
    """The source with every string and comment blanked (length- and line-preserving)."""
    out = []
    i, n = 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
            continue
        if src.startswith("/*", i):
            j = _skip_block_comment(src, i)
            out.append(_blank(src[i:j]))
            i = j
            continue
        if src.startswith('"""', i):
            j = _skip_raw(src, i)
            out.append(_blank(src[i:j]))
            i = j
            continue
        if src[i] == '"':
            j = _skip_string(src, i)
            out.append(" " * (j - i))
            i = j
            continue
        if src[i] == "'":
            j = _skip_char(src, i)
            out.append(" " * (j - i))
            i = j
            continue
        out.append(src[i])
        i += 1
    return "".join(out)


def code_text(path):
    src = open(path, encoding="utf-8", errors="replace").read()
    return code_text_of_source(src)


def string_literals(src):
    """Every string/char literal as (offset, line, kind, value).

    Offsets are into the ORIGINAL source so a checker can inspect the masked code
    immediately before an offset to decide the literal's context (`Text(`,
    `contentDescription =`, `testTag(`, ...). The value keeps template text
    verbatim, which is what the URL-literal and hardcoded-copy rules need.
    """
    out = []
    i, n, line = 0, len(src), 1
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if src.startswith("/*", i):
            j = _skip_block_comment(src, i)
            line += src.count("\n", i, j)
            i = j
            continue
        if src.startswith('"""', i):
            j = _skip_raw(src, i)
            out.append((i, line, RAWSTRING, src[i + 3:max(i + 3, j - 3)]))
            line += src.count("\n", i, j)
            i = j
            continue
        if c == '"':
            j = _skip_string(src, i)
            out.append((i, line, STRING, src[i + 1:max(i + 1, j - 1)]))
            line += src.count("\n", i, j)
            i = j
            continue
        if c == "'":
            j = _skip_char(src, i)
            out.append((i, line, CHAR, src[i + 1:max(i + 1, j - 1)]))
            i = j
            continue
        if c == "\n":
            line += 1
        i += 1
    return out


def balanced(src):
    """Net bracket counts over code only (strings/comments masked out first)."""
    code = code_text_of_source(src)
    net = {"()": 0, "{}": 0, "[]": 0}
    for ch in code:
        if ch == "(":
            net["()"] += 1
        elif ch == ")":
            net["()"] -= 1
        elif ch == "{":
            net["{}"] += 1
        elif ch == "}":
            net["{}"] -= 1
        elif ch == "[":
            net["[]"] += 1
        elif ch == "]":
            net["[]"] -= 1
    return net


def call_arguments(code, name, start):
    """Text of the argument list beginning at `start` (just after `name(`)."""
    depth = 1
    j = start
    while j < len(code):
        ch = code[j]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                return code[start:j]
        j += 1
    return code[start:]


def call_spans(code, name):
    """Every `name(` call site as (name_offset, args_start, args_end).

    args_end points PAST the closing paren so a caller can look for a trailing
    lambda (`Button(...) { Text(...) }`) immediately after it.
    """
    spans = []
    i = 0
    pat = name + "("
    while True:
        j = code.find(pat, i)
        if j < 0:
            break
        before = code[j - 1] if j else " "
        if before.isalnum() or before in "_.":
            i = j + 1
            continue
        args = call_arguments(code, name, j + len(pat))
        spans.append((j, j + len(pat), j + len(pat) + len(args) + 1))
        i = j + len(pat)
    return spans


def functions(code):
    """[(name, body_start, body_end)] for every `fun name(...) { ... }`.

    Expression-bodied functions are skipped: the Phase 6 checks that need bodies
    are about composable layout, which always has one.
    """
    out = []
    for m in re.finditer(r"\bfun\s+(?:[\w<>.]+\.)?(\w+)\s*\(", code):
        name = m.group(1)
        i = m.end() - 1
        depth, j = 0, i
        while j < len(code):
            if code[j] == "(":
                depth += 1
            elif code[j] == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        k = j + 1
        while k < len(code) and code[k] in " \n\r\t":
            k += 1
        if k >= len(code) or code[k] != "{":
            continue
        depth, b = 1, k + 1
        while b < len(code) and depth:
            if code[b] == "{":
                depth += 1
            elif code[b] == "}":
                depth -= 1
            b += 1
        out.append((name, k, b))
    return out
