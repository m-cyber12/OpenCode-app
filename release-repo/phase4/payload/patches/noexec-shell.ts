// OpenCode-Android patch (phase4/payload/patches). Copied into upstream
// packages/opencode/src/tool/ at payload build time by apply-noexec-shell.py.
//
// Why: on Android, shared-storage folders (Documents/OpenCode, or any folder
// the user picks there) are mounted noexec. The kernel refuses execve() for
// every file there, so `./script.sh` and `node_modules/.bin/*` shims fail with
// "Permission denied" (exit 126). Nothing a tool does inside that folder can
// change the mount. So before the shell runs a command, any path-like program
// name that is not executable (access(X_OK) gives EACCES) is rewritten to run
// through its interpreter: `./x.mjs args` -> `node ./x.mjs args`.
//
// Commands whose program is executable are left byte-for-byte unchanged, so the
// rewrite changes nothing on folders where execution works.
//
// Limits (documented in the brief): a relative path after `cd` in the same
// command is skipped, because it resolves against a different folder; bare
// names found through PATH are not rewritten.

import fs from "node:fs"
import path from "node:path"
import type { Node } from "web-tree-sitter"

export type Parse = (command: string) => Promise<{ rootNode: Node } | null | undefined>

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

const PLAIN_NAME = /^[A-Za-z0-9_./~+@%:,-]+$/

export async function rewriteNoexec(
  command: string,
  cwd: string,
  parse: Parse,
  probe: Probe = defaultProbe,
): Promise<string> {
  if (process.platform === "win32") return command
  if (!command.includes("/")) return command

  let root: Node | undefined
  try {
    const tree = await parse(command)
    root = tree?.rootNode
  } catch {
    return command
  }
  if (!root) return command

  const commands = root.descendantsOfType("command")
  const names: Node[] = []
  let changesDirectory = false
  for (const cmd of commands) {
    let name: Node | undefined
    for (let i = 0; i < cmd.childCount; i++) {
      const child = cmd.child(i)
      if (child?.type === "command_name") {
        name = child
        break
      }
    }
    if (!name) continue
    if (name.text === "cd" || name.text === "pushd" || name.text === "popd") changesDirectory = true
    names.push(name)
  }

  const edits: { start: number; end: number; text: string }[] = []
  for (const name of names) {
    const text = name.text
    if (!text.includes("/") || !PLAIN_NAME.test(text)) continue
    if (changesDirectory && !path.isAbsolute(text)) continue
    const file = path.resolve(cwd, text)
    if (probe.executable(file)) continue
    const interpreter = interpreterFor(file, probe.head(file))
    if (!interpreter) continue
    edits.push({ start: name.startIndex, end: name.endIndex, text: `${interpreter} ${text}` })
  }
  if (edits.length === 0) return command

  // Apply from the end so earlier offsets stay valid.
  let out = command
  for (const edit of edits.sort((a, b) => b.start - a.start)) {
    out = out.slice(0, edit.start) + edit.text + out.slice(edit.end)
  }
  return out
}
