// p8-large.js — P8 large-project gate driver (Phase 8).
//
// Builds a genuinely large project inside the app's own workspaces root, then
// measures the API latencies that scale with project size (file listing,
// file content, session ops). The host runs the git half with the payload's
// own native Git (same as the Phase 5 fixture), so this driver stays pure
// HTTP + file creation.
//
// Modes:
//   build <dir> -> create N small files + one large file; P8LARGEBUILD ok=1 files=<n> bytes=<b>
//   measure <dir> -> API latency suite; P8LARGEMEASURE createMs=.. listMs=.. listEntries=.. contentMs=..

import { call, get, post, createSession, log } from "./gates-lib.js"

const [mode, dirArg] = process.argv.slice(2)
const DIR = dirArg || process.env.OPENCODE_DIRECTORY
const N = Number(process.env.P8_LARGE_FILES || 2000)
const BIG_MB = Number(process.env.P8_LARGE_FILE_MB || 50)

if (mode === "build") {
  const t0 = Date.now()
  const sub = DIR + "/src"
  const fs = require("fs")
  fs.mkdirSync(sub, { recursive: true })
  let bytes = 0
  for (let i = 0; i < N; i++) {
    const body = `module p8 large file ${i}\nexport const n = ${i}\n`
    fs.writeFileSync(`${sub}/file_${String(i).padStart(4, "0")}.js`, body)
    bytes += Buffer.byteLength(body)
  }
  const big = Buffer.alloc(BIG_MB * 1024 * 1024, 7)
  fs.writeFileSync(`${DIR}/big.bin`, big)
  bytes += big.length
  console.log(`P8LARGEBUILD ok=1 files=${N + 1} bytes=${bytes} dirMs=${Date.now() - t0} dir=${DIR}`)
  process.exit(0)
}

if (mode === "measure") {
  const t = (fn) => { const a = Date.now(); return fn().then((v) => ({ v, ms: Date.now() - a })) }
  const create = await t(() => createSession("p8 large perf"))
  // The file API resolves `path` inside the REQUEST's directory (project);
  // an absolute path outside the default directory 500s. Pass the directory
  // explicitly and use project-relative paths.
  const list = await t(async () => {
    const r = await get(`/file?path=${encodeURIComponent("src")}`, { directory: DIR })
    if (!r.ok) throw new Error("file list http " + r.status + " " + r.text.slice(0, 200))
    return JSON.parse(r.text)
  })
  const entries = Array.isArray(list.v) ? list.v : (list.v?.children ?? [])
  let contentMs = -1
  let contentOk = false
  try {
    const cr = await t(async () => {
        const r = await get(`/file/content?path=${encodeURIComponent("src/file_0000.js")}`, { directory: DIR })
      if (!r.ok) throw new Error("file content http " + r.status)
      return JSON.parse(r.text)
    })
    contentMs = cr.ms
    contentOk = (cr.v?.content ?? "").includes("module p8 large file 0")
  } catch (e) {
    console.log(`P8LARGEMEASURE createMs=${create.ms} listMs=${list.ms} listEntries=${entries.length} contentMs=-1 error=${String(e.message ?? e).slice(0, 120)}`)
    process.exit(1)
  }
  console.log(
    `P8LARGEMEASURE createMs=${create.ms} listMs=${list.ms} listEntries=${entries.length} contentMs=${contentMs} contentOk=${contentOk ? 1 : 0}`,
  )
  process.exit(0)
}

console.log("P8LARGEUSAGE unknown mode: " + mode)
process.exit(2)
