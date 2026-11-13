// Host test for the v2 no-exec rewrite (noexec-shell.ts), with the real v2 scanner.
//
// The scanner is core/src/shell/scan.ts from the pinned v2 checkout. It starts with a
// self re-export line that Node cannot load, so the caller passes a copy with that
// first line removed:  V2_SHELL_SCAN=/path/to/scan-copy.ts
// Without V2_SHELL_SCAN the scanner cases are skipped (the unit cases still run).
//
// Run: V2_SHELL_SCAN=... node --test --experimental-strip-types phase4/payload/v2-patches/test/noexec-shell.test.ts
import { test, before } from "node:test"
import assert from "node:assert/strict"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { pathToFileURL } from "node:url"
import { rewriteNoexec, type Probe, type Scan } from "../noexec-shell.ts"

// A probe that treats every existing file as non-executable, like a noexec mount.
// A missing file counts as executable, as in the real probe (ENOENT is not EACCES).
const noexecProbe: Probe = {
  executable: (file) => !fs.existsSync(file),
  head: (file) => {
    try {
      return fs.readFileSync(file, "utf8").split("\n")[0]
    } catch {
      return undefined
    }
  },
}

const nothingScanned: Scan = () => ({ kind: "opaque" })

let scan: Scan | undefined
let dir = ""

before(async () => {
  const file = process.env.V2_SHELL_SCAN
  if (file) {
    const mod = await import(pathToFileURL(file).href)
    scan = (command) => mod.scan(command, "posix")
  }
  dir = fs.mkdtempSync(path.join(os.tmpdir(), "noexec-test-"))
  fs.writeFileSync(path.join(dir, "run.mjs"), "console.log('hi')\n")
  fs.writeFileSync(path.join(dir, "t.sh"), "#!/bin/sh\necho hi\n")
  fs.writeFileSync(path.join(dir, "bin-tool"), "#!/usr/bin/env node\nconsole.log(1)\n")
})

const withScanner = (name: string, fn: () => void) =>
  test(name, { skip: process.env.V2_SHELL_SCAN ? false : "V2_SHELL_SCAN not set" }, fn)

// ---- unit cases (no scanner) -------------------------------------------------

test("a command without a slash is returned unchanged", () => {
  assert.equal(rewriteNoexec("ls -l", dir, nothingScanned, noexecProbe), "ls -l")
})

test("an opaque scan leaves the command unchanged", () => {
  assert.equal(rewriteNoexec("./run.mjs <<EOF", dir, nothingScanned, noexecProbe), "./run.mjs <<EOF")
})

test("a scanner that throws leaves the command unchanged", () => {
  const broken: Scan = () => {
    throw new Error("boom")
  }
  assert.equal(rewriteNoexec("./run.mjs", dir, broken, noexecProbe), "./run.mjs")
})

// ---- cases with the real v2 scanner --------------------------------------------

withScanner("a non-executable .mjs runs through node", () => {
  const out = rewriteNoexec(`./run.mjs --a`, dir, scan!, noexecProbe)
  assert.equal(out, "node ./run.mjs --a")
})

withScanner("a non-executable shell script runs through sh by its extension", () => {
  assert.equal(rewriteNoexec("./t.sh", dir, scan!, noexecProbe), "sh ./t.sh")
})

withScanner("a file with a node shebang runs through node", () => {
  assert.equal(rewriteNoexec("./bin-tool", dir, scan!, noexecProbe), "node ./bin-tool")
})

withScanner("an executable program is left byte-for-byte unchanged", () => {
  const cmd = "./run.mjs --a"
  assert.equal(rewriteNoexec(cmd, dir, scan!, { executable: () => true, head: () => undefined }), cmd)
})

withScanner("a leading VAR=value does not hide the program", () => {
  assert.equal(rewriteNoexec("FOO=1 ./run.mjs", dir, scan!, noexecProbe), "FOO=1 node ./run.mjs")
})

withScanner("every command in a pipeline and a list is rewritten", () => {
  assert.equal(
    rewriteNoexec("./t.sh | ./run.mjs; ./t.sh", dir, scan!, noexecProbe),
    "sh ./t.sh | node ./run.mjs; sh ./t.sh",
  )
})

withScanner("a relative path after cd is skipped, an absolute one is rewritten", () => {
  assert.equal(rewriteNoexec("cd sub && ./run.mjs", dir, scan!, noexecProbe), "cd sub && ./run.mjs")
  assert.equal(
    rewriteNoexec(`cd /tmp && ${path.join(dir, "run.mjs")}`, dir, scan!, noexecProbe),
    `cd /tmp && node ${path.join(dir, "run.mjs")}`,
  )
})

withScanner("a bare name found through PATH is not rewritten", () => {
  assert.equal(rewriteNoexec("node ./run.mjs", dir, scan!, { executable: () => true, head: () => undefined }), "node ./run.mjs")
})

withScanner("a quoted program name is not rewritten", () => {
  assert.equal(rewriteNoexec('"./run.mjs" x', dir, scan!, noexecProbe), '"./run.mjs" x')
})

withScanner("a missing file is not rewritten (the shell reports it)", () => {
  assert.equal(rewriteNoexec("./missing.mjs", dir, scan!, noexecProbe), "./missing.mjs")
})
