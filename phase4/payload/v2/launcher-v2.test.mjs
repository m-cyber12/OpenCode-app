// Tests for launcher-v2.mjs. Run: node --test phase4/payload/v2/
import { test } from "node:test"
import assert from "node:assert/strict"
import { spawn } from "node:child_process"
import { mkdtempSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join, dirname } from "node:path"
import { fileURLToPath } from "node:url"
import { Readable } from "node:stream"
import { parseArgs, readPasswordLine } from "./launcher-v2.mjs"

const here = dirname(fileURLToPath(import.meta.url))
const LAUNCHER = join(here, "launcher-v2.mjs")
const PW = "0123456789abcdef0123456789abcdef"

test("parseArgs accepts loopback hostnames and a valid port", () => {
  assert.deepEqual(parseArgs(["--hostname", "127.0.0.1", "--port", "4111"]), { hostname: "127.0.0.1", port: 4111 })
  assert.deepEqual(parseArgs(["--hostname", "localhost", "--port", "1"]), { hostname: "localhost", port: 1 })
  assert.deepEqual(parseArgs(["--hostname", "::1", "--port", "65535"]), { hostname: "::1", port: 65535 })
})

test("parseArgs refuses non-loopback binds", () => {
  for (const host of ["0.0.0.0", "::", "192.168.1.5", "10.0.0.1"]) {
    assert.throws(() => parseArgs(["--hostname", host, "--port", "4111"]), /non-loopback/)
  }
})

test("parseArgs rejects bad ports and unknown flags", () => {
  assert.throws(() => parseArgs(["--hostname", "127.0.0.1", "--port", "0"]), /invalid --port/)
  assert.throws(() => parseArgs(["--hostname", "127.0.0.1", "--port", "70000"]), /invalid --port/)
  assert.throws(() => parseArgs(["--hostname", "127.0.0.1", "--port", "abc"]), /invalid --port/)
  assert.throws(() => parseArgs(["--hostname", "127.0.0.1", "--port", "1", "--mdns"]), /unsupported argument/)
  assert.throws(() => parseArgs(["--port", "1"]), /--hostname is required/)
})

test("readPasswordLine reads one line and stops", async () => {
  const s = Readable.from([Buffer.from(`${PW}\nignored-next-line\n`)])
  assert.equal(await readPasswordLine(s), PW)
})

test("readPasswordLine strips CRLF and rejects short or missing passwords", async () => {
  assert.equal(await readPasswordLine(Readable.from([Buffer.from(`${PW}\r\n`)])), PW)
  await assert.rejects(readPasswordLine(Readable.from([Buffer.from("short\n")])), /missing or too short/)
  await assert.rejects(readPasswordLine(Readable.from([])), /missing or too short/)
})

test("readPasswordLine rejects an overlong line", async () => {
  const big = Buffer.alloc(5000, 0x61)
  await assert.rejects(readPasswordLine(Readable.from([big])), /too long/)
})

function runLauncher(args, stdinText) {
  const dir = mkdtempSync(join(tmpdir(), "launcher-v2-"))
  const stub = join(dir, "opencode.mjs")
  writeFileSync(
    stub,
    'process.stdout.write(JSON.stringify({ pw: process.env.OPENCODE_SERVER_PASSWORD, argv: process.argv.slice(1) }) + "\\n")\n',
  )
  return new Promise((resolve) => {
    // The child environment has no password: it must arrive on stdin only.
    const child = spawn(process.execPath, [LAUNCHER, stub, ...args], {
      env: { PATH: process.env.PATH ?? "" },
    })
    let out = ""
    let err = ""
    child.stdout.on("data", (d) => (out += d))
    child.stderr.on("data", (d) => (err += d))
    child.on("close", (code) => resolve({ code, out, err }))
    child.stdin.end(stdinText)
  })
}

test("end to end: password from stdin reaches the server, argv is serve on loopback", async () => {
  const r = await runLauncher(["--hostname", "127.0.0.1", "--port", "4111"], `${PW}\n`)
  assert.equal(r.code, 0, r.err)
  const got = JSON.parse(r.out.trim())
  assert.equal(got.pw, PW)
  assert.deepEqual(got.argv.slice(1), ["serve", "--hostname", "127.0.0.1", "--port", "4111"])
})

test("end to end: a non-loopback bind exits non-zero before the server loads", async () => {
  const r = await runLauncher(["--hostname", "0.0.0.0", "--port", "4111"], `${PW}\n`)
  assert.notEqual(r.code, 0)
  assert.match(r.err, /non-loopback/)
  assert.equal(r.out, "")
})

test("end to end: missing password exits non-zero", async () => {
  const r = await runLauncher(["--hostname", "127.0.0.1", "--port", "4111"], "")
  assert.notEqual(r.code, 0)
  assert.match(r.err, /missing or too short/)
})
