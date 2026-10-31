// Host test for noexec-shell.ts (the bash-tool rewrite for noexec folders).
// Needs the same parser the pinned upstream uses, installed in a scratch dir:
//   mkdir -p /tmp/nxtest && cd /tmp/nxtest && npm install web-tree-sitter@0.25.10 tree-sitter-bash@0.25.0
//   NOEXEC_TEST_DEPS=/tmp/nxtest node --test phase4/payload/patches/noexec-shell.test.mjs
// Node 22.18+ strips the TypeScript types on import.
import { test } from "node:test"
import assert from "node:assert/strict"
import path from "node:path"
import { pathToFileURL } from "node:url"

const deps = process.env.NOEXEC_TEST_DEPS
if (!deps) throw new Error("set NOEXEC_TEST_DEPS to a dir with web-tree-sitter and tree-sitter-bash")
const { Parser, Language } = await import(pathToFileURL(path.join(deps, "node_modules/web-tree-sitter/tree-sitter.js")).href)
await Parser.init()
const bashLang = await Language.load(path.join(deps, "node_modules/tree-sitter-bash/tree-sitter-bash.wasm"))
const bash = new Parser()
bash.setLanguage(bashLang)
const parse = async (command) => bash.parse(command)

const { rewriteNoexec, interpreterFor } = await import("./noexec-shell.ts")

const CWD = "/storage/emulated/0/Documents/OpenCode/p"
const abs = (name) => path.resolve(CWD, name)

// A probe that says these files are on a noexec mount, with the given first lines.
function noexecProbe(heads, executableFiles = []) {
  return {
    executable: (file) => executableFiles.includes(file) || !(file in heads),
    head: (file) => heads[file],
  }
}

const SH = "#!/bin/sh\necho hi\n"
const NODE = "#!/usr/bin/env node\nconsole.log(1)\n"
const PY = "#!/usr/bin/env python3\nprint(1)\n"
const PERL = "#!/usr/bin/perl\nprint 1\n"

test("commands without a path are returned unchanged", async () => {
  const out = await rewriteNoexec("echo hello && ls", CWD, parse, noexecProbe({}))
  assert.equal(out, "echo hello && ls")
})

test("an executable program is left byte-for-byte alone", async () => {
  const probe = noexecProbe({ [abs("./run.sh")]: SH }, [abs("./run.sh")])
  assert.equal(await rewriteNoexec("./run.sh --x", CWD, parse, probe), "./run.sh --x")
})

test("a non-executable shell script runs through sh", async () => {
  const probe = noexecProbe({ [abs("./t.sh")]: SH })
  assert.equal(await rewriteNoexec("./t.sh", CWD, parse, probe), "sh ./t.sh")
})

test("a .bin shim (symlink to a node script) runs through node", async () => {
  const probe = noexecProbe({ [abs("node_modules/.bin/rea")]: NODE })
  assert.equal(
    await rewriteNoexec("node_modules/.bin/rea mcp --help", CWD, parse, probe),
    "node node_modules/.bin/rea mcp --help",
  )
})

test("every path-like command in a chain or pipe is rewritten", async () => {
  const probe = noexecProbe({ [abs("./a.mjs")]: NODE, [abs("./b.py")]: PY, [abs("./c.sh")]: SH })
  assert.equal(
    await rewriteNoexec("./a.mjs x | grep y && ./b.py > out.txt 2>&1; ./c.sh", CWD, parse, probe),
    "node ./a.mjs x | grep y && python3 ./b.py > out.txt 2>&1; sh ./c.sh",
  )
})

test("non-ASCII text before the command keeps the offsets right", async () => {
  const probe = noexecProbe({ [abs("./t.sh")]: SH })
  assert.equal(await rewriteNoexec('echo "héllo 🙂" && ./t.sh', CWD, parse, probe), 'echo "héllo 🙂" && sh ./t.sh')
})

test("after a cd in the same command, relative paths are not rewritten", async () => {
  const probe = noexecProbe({ [abs("sub/t.sh")]: SH })
  assert.equal(await rewriteNoexec("cd sub && ./t.sh", CWD, parse, probe), "cd sub && ./t.sh")
})

test("an unknown interpreter is left alone", async () => {
  const probe = noexecProbe({ [abs("./p.pl")]: PERL })
  assert.equal(await rewriteNoexec("./p.pl", CWD, parse, probe), "./p.pl")
})

test("a missing file is left alone (command not found is the real error)", async () => {
  const probe = noexecProbe({})
  assert.equal(await rewriteNoexec("./nope.sh", CWD, parse, probe), "./nope.sh")
})

test("a quoted program name is not rewritten", async () => {
  const probe = noexecProbe({ [abs("./t.sh")]: SH })
  assert.equal(await rewriteNoexec('"./t.sh"', CWD, parse, probe), '"./t.sh"')
})

test("a parser failure returns the original command", async () => {
  const boom = async () => {
    throw new Error("parser down")
  }
  assert.equal(await rewriteNoexec("./t.sh", CWD, boom, noexecProbe({ [abs("./t.sh")]: SH })), "./t.sh")
})

test("interpreterFor picks by shebang, then by extension", () => {
  assert.equal(interpreterFor("/x", "#!/usr/bin/env node"), "node")
  assert.equal(interpreterFor("/x", "#!/usr/local/bin/node"), "node")
  assert.equal(interpreterFor("/x", "#!/bin/bash"), "sh")
  assert.equal(interpreterFor("/x", "#!/usr/bin/env python3"), "python3")
  assert.equal(interpreterFor("/x", "#!/usr/bin/perl"), undefined)
  assert.equal(interpreterFor("/x.mjs", undefined), "node")
  assert.equal(interpreterFor("/x.py", undefined), "python3")
  assert.equal(interpreterFor("/x.sh", undefined), "sh")
  assert.equal(interpreterFor("/x.bin", undefined), undefined)
})
