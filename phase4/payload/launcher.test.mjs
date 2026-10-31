// Host test for launcher.js: the server password must not reach anything the
// server starts, and must not be in the process's exec-time environment.
//
// It runs the REAL launcher under Bun with a fake OpenCode bundle (OPENCODE_BUNDLE)
// whose Server.listen() reports what it can see. Needs Bun 1.3.14:
//   LAUNCHER_TEST_BUN=/path/to/bun node --test phase4/payload/launcher.test.mjs
// Control case: the same probe run without the launcher must show the key, so
// the test would notice if the protection were missing.
import { test } from "node:test"
import assert from "node:assert/strict"
import { spawn, spawnSync } from "node:child_process"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { fileURLToPath } from "node:url"

const bun = process.env.LAUNCHER_TEST_BUN
if (!bun) throw new Error("set LAUNCHER_TEST_BUN to a Bun 1.3.14 binary")
const here = path.dirname(fileURLToPath(import.meta.url))
const launcher = path.join(here, "launcher.js")
const SECRET = "s3cret-line-for-test"

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "launcher-test-"))
const bundle = path.join(dir, "fake-node.js")
// The probe runs inside the "server". Each check prints one PROBE line.
fs.writeFileSync(
  bundle,
  `
import fs from "node:fs";
import cpNs from "node:child_process";
export const Server = {
  listen: async () => {
    const has = (s) => s.includes("OPENCODE_SERVER_PASSWORD");
    const r = {};
    r.procEnviron = has(fs.readFileSync("/proc/self/environ", "utf8"));
    r.direct = process.env.OPENCODE_SERVER_PASSWORD || "";
    r.spread = has(Object.keys({ ...process.env }).join(" "));
    r.childDefaultImport = has(cpNs.execFileSync("sh", ["-c", "env"]).toString());
    r.childBunSpawn = has(await new Response(Bun.spawn(["sh", "-c", "env"], { stdout: "pipe" }).stdout).text());
    r.childBunSpawnSync = has(Bun.spawnSync(["sh", "-c", "env"]).stdout.toString());
    r.childBunShell = has(await Bun.$\`env\`.quiet().text());
    r.childExplicitEnv = has(cpNs.execFileSync("sh", ["-c", "env"], { env: { ...process.env, A: "1" } }).toString());
    console.log("PROBE " + JSON.stringify(r));
    return { url: "http://127.0.0.1:4111" };
  },
};
`,
)

function baseEnv(extra) {
  const env = {
    PATH: process.env.PATH || "/usr/bin:/bin",
    HOME: dir,
    OPENCODE_FILES_DIR: dir,
    OPENCODE_BUNDLE: bundle,
    OPENCODE_SERVER_PORT: "4111",
    OPENCODE_SERVER_HOSTNAME: "127.0.0.1",
    OPENCODE_SECCOMP_SHIM: path.join(dir, "absent.so"),
    ...extra,
  }
  return env
}

function probeLine(output) {
  const line = output.split("\n").find((l) => l.startsWith("PROBE "))
  assert.ok(line, "no PROBE line in launcher output:\n" + output)
  return JSON.parse(line.slice("PROBE ".length))
}

function runLauncher(env, stdinLine) {
  return new Promise((resolve, reject) => {
    const child = spawn(bun, [launcher], { env, stdio: ["pipe", "pipe", "pipe"] })
    let out = ""
    let err = ""
    child.stdout.on("data", (d) => (out += d))
    child.stderr.on("data", (d) => (err += d))
    child.on("error", reject)
    child.on("close", (code) => resolve({ code, out, err }))
    if (stdinLine !== undefined) child.stdin.end(stdinLine)
    else child.stdin.end()
  })
}

test("password from stdin: server sees it, nothing it starts sees it", async () => {
  const env = baseEnv({})
  assert.equal("OPENCODE_SERVER_PASSWORD" in env, false)
  const { code, out, err } = await runLauncher(env, SECRET + "\n")
  assert.equal(code, 0, err)
  const r = probeLine(out)
  assert.equal(r.direct, SECRET, "upstream must still read the password")
  assert.equal(r.procEnviron, false, "exec-time environment must not contain it")
  assert.equal(r.spread, false)
  for (const k of ["childDefaultImport", "childBunSpawn", "childBunSpawnSync", "childBunShell", "childExplicitEnv"]) {
    assert.equal(r[k], false, `${k} leaked the password`)
  }
})

test("environment value (gate scripts): still hidden from children", async () => {
  const env = baseEnv({ OPENCODE_SERVER_PASSWORD: SECRET })
  const { code, out, err } = await runLauncher(env, undefined)
  assert.equal(code, 0, err)
  const r = probeLine(out)
  assert.equal(r.direct, SECRET)
  for (const k of ["childDefaultImport", "childBunSpawn", "childBunSpawnSync", "childBunShell", "childExplicitEnv"]) {
    assert.equal(r[k], false, `${k} leaked the password`)
  }
})

test("no password on stdin and none in env: launcher refuses to start", async () => {
  const { code, err } = await runLauncher(baseEnv({}), "")
  assert.equal(code, 2)
  assert.match(err, /no server password/)
})

test("control: without the launcher the key is visible to children (test is sensitive)", () => {
  const probe = path.join(dir, "control.js")
  fs.writeFileSync(
    probe,
    `import cpNs from "node:child_process";
     console.log("CONTROL " + (cpNs.execFileSync("sh", ["-c", "env"]).toString().includes("OPENCODE_SERVER_PASSWORD")));`,
  )
  const r = spawnSync(bun, [probe], { env: baseEnv({ OPENCODE_SERVER_PASSWORD: SECRET }), encoding: "utf8" })
  assert.match(r.stdout, /CONTROL true/)
})
