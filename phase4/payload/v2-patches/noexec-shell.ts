// OpenCode-Android patch (phase4/payload/v2-patches). Copied into upstream
// packages/core/src/tool/plugin/ at bundle build time by apply-noexec-shell.py.
//
// Port of phase4/payload/patches/noexec-shell.ts (v1). The v1 version parsed the
// command with the Bun-era tree-sitter bash grammar. v2 has no bash grammar in
// its bundle; it scans shell commands with its own scanner (core/src/shell/scan.ts),
// whose command resources are exact substrings of the command. This file uses
// that scanner through a `scan` argument, so it has no imports of its own.
//
// Why: on Android, shared-storage folders (Documents/OpenCode, or any folder the
// user picks there) are mounted noexec. The kernel refuses execve() for every file
// there, so `./script.sh` and `node_modules/.bin/*` shims fail with "Permission
// denied" (exit 126). Nothing a tool does inside that folder can change the mount.
// So before the shell runs a command, any path-like program name that is not
// executable (access(X_OK) gives EACCES) is rewritten to run through its
// interpreter: `./x.mjs args` -> `node ./x.mjs args`.
//
// The rewrite runs after the permission check, so approvals still see the command
// the user was asked about. Commands whose program is executable are left
// unchanged, so nothing changes on folders where execution works.
//
// Limits: a relative path after `cd` in the same command is skipped, because it
// resolves against a different folder; bare names found through PATH are not
// rewritten; a command the scanner cannot read (opaque) is left unchanged.

import fs from "node:fs"
import path from "node:path"

export interface Probe {
  // false only when the file exists and the kernel refuses to execute it
  executable(file: string): boolean
  // first line of the file (follows symlinks); undefined when unreadable
  head(file: string): string | undefined
}

export const defaultProbe: Probe = {
  executable(file) {
    try {
      fs.accessSync(file, fs.constants.X_OK)
      return true
    } catch (err) {
      return (err as NodeJS.ErrnoException).code !== "EACCES"
    }
  },
  head(file) {
    let fd: number | undefined
    try {
      fd = fs.openSync(file, "r")
      const buf = Buffer.alloc(256)
      const n = fs.readSync(fd, buf, 0, buf.length, 0)
      return buf.toString("utf8", 0, n).split("\n")[0]
    } catch {
      return undefined
    } finally {
      if (fd !== undefined) fs.closeSync(fd)
    }
  },
}

// Interpreter argv for a file, or undefined when we do not know how to run it.
export function interpreterFor(file: string, first: string | undefined): string | undefined {
  if (first && first.startsWith("#!")) {
    const line = first.slice(2).trim()
    if (/(^|[\s/])node(\s|$)/.test(line)) return "node"
    if (/(^|[\s/])python3?(\s|$)/.test(line)) return "python3"
    if (/(^|[\s/])(ba|z|da)?sh(\s|$)/.test(line)) return "sh"
    return undefined
  }
  const ext = path.extname(file)
  if (ext === ".js" || ext === ".mjs" || ext === ".cjs") return "node"
  if (ext === ".py") return "python3"
  if (ext === ".sh") return "sh"
  return undefined
}

// The subset of the v2 scanner result this patch reads.
export interface ScannedCommand {
  resource: string
  rawWords: string[]
}
export type ScanResult = { kind: "scanned"; commands: ScannedCommand[] } | { kind: "opaque" }
export type Scan = (command: string) => ScanResult

const PLAIN_NAME = /^[A-Za-z0-9_./~+@%:,-]+$/
const CWD = new Set(["cd", "pushd", "popd"])

// The program that runs a file whose interpreter is node. On the device the server's own binary
// (process.execPath, libnode26.so) is used: no `node` is on the shell's PATH (the v2 bin/ folder
// has no node link), so a bare `node` would exit 127. The path can hold characters the shell
// treats specially (`=` and `~` appear in Android app paths), so it is quoted when needed.
export function shellWord(text: string): string {
  if (/^[A-Za-z0-9_./~+@%:,=-]+$/.test(text)) return text
  return "'" + text.replace(/'/g, "'\\''") + "'"
}

// Escape a literal for use in a RegExp.
function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
}

export function rewriteNoexec(
  command: string,
  cwd: string,
  scan: Scan,
  probe: Probe = defaultProbe,
  nodeProgram: string = shellWord(process.execPath),
): string {
  if (process.platform === "win32") return command
  if (!command.includes("/")) return command

  let result: ScanResult
  try {
    result = scan(command)
  } catch {
    return command
  }
  if (result.kind !== "scanned") return command

  // Locate each command's program name in the original text, walking forward so a
  // repeated resource is matched at its own position.
  const names: { start: number; end: number; text: string }[] = []
  let changesDirectory = false
  let cursor = 0
  for (const cmd of result.commands) {
    const text = cmd.rawWords[0]
    if (text === undefined) continue
    if (CWD.has(text)) changesDirectory = true
    const at = command.indexOf(cmd.resource, cursor)
    if (at < 0) continue
    cursor = at + cmd.resource.length
    // The program follows any leading VAR=value words, so match it as a whole word.
    const match = new RegExp(`(^|\\s)${escapeRegExp(text)}(\\s|$)`).exec(cmd.resource)
    if (!match) continue
    const start = at + match.index + (match[1] ?? "").length
    if (command.slice(start, start + text.length) !== text) continue
    names.push({ start, end: start + text.length, text })
  }

  const edits: { start: number; end: number; text: string }[] = []
  for (const name of names) {
    const { text } = name
    if (!text.includes("/") || !PLAIN_NAME.test(text)) continue
    if (changesDirectory && !path.isAbsolute(text)) continue
    const file = path.resolve(cwd, text)
    if (probe.executable(file)) continue
    const interpreter = interpreterFor(file, probe.head(file))
    if (!interpreter) continue
    const program = interpreter === "node" ? nodeProgram : interpreter
    edits.push({ start: name.start, end: name.end, text: `${program} ${text}` })
  }
  if (edits.length === 0) return command

  // Apply from the end so earlier offsets stay valid.
  let out = command
  for (const edit of edits.sort((a, b) => b.start - a.start)) {
    out = out.slice(0, edit.start) + edit.text + out.slice(edit.end)
  }
  return out
}
