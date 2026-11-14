// ---- TEST ONLY: inserted by spike/opencode-v2/build-v2-bundle.sh into the staged launcher.
// The release launcher (phase4/payload/v2/launcher-v2.mjs) does not contain this block.
//
// 1. Native paths for the Android test build, when the bundle has the real files:
//    OPENCODE_NODE_PTY_PATH (terminal) only when the marker says the Bionic addon is in,
//    OPENCODE_PARCEL_WATCHER_PATH (file watching) when watcher.node is present.
// 2. A scripted model stub on loopback (test-stub/stub-model.mjs) and a custom provider
//    that points at it, merged into the global opencode.json. The provider is set as the
//    default model, so a test build's prompts reach the stub. Existing keys are kept.
import { existsSync as testExists, readFileSync as testRead, writeFileSync as testWrite, renameSync as testRename, mkdirSync as testMkdir } from "node:fs"
import { dirname as testDirname, join as testJoin } from "node:path"
import { fileURLToPath as testFileURL, pathToFileURL as testPathURL } from "node:url"

const TEST_DIR = testDirname(testFileURL(import.meta.url))
const TEST_STUB_PORT = 4799
const TEST_PTY = testJoin(TEST_DIR, "assets", "@lydell", "node-pty-linux-arm64", "lib", "index.js")
const TEST_PTY_MARK = testJoin(TEST_DIR, "assets", "test-native", "pty-real")
const TEST_WATCHER = testJoin(TEST_DIR, "assets", "test-native", "watcher.node")

function testWriteConfig(log) {
  const base = process.env.XDG_CONFIG_HOME
  if (!base) {
    log("test stub: XDG_CONFIG_HOME is not set; no provider written")
    return
  }
  const dir = testJoin(base, "opencode")
  const file = testJoin(dir, "opencode.json")
  let cfg = {}
  if (testExists(file)) {
    try {
      cfg = JSON.parse(testRead(file, "utf8"))
    } catch {
      log("test stub: opencode.json is not plain JSON (comments?); no provider written, the stub is not the default model")
      return
    }
  }
  const stubId = "stub"
  const modelId = "stub-model"
  cfg.providers = {
    ...(cfg.providers ?? {}),
    [stubId]: {
      name: "Scripted stub (test build)",
      package: "@ai-sdk/openai-compatible",
      settings: { baseURL: `http://127.0.0.1:${TEST_STUB_PORT}/v1`, apiKey: "stub" },
      models: { [modelId]: { name: "Scripted stub model" } },
    },
  }
  cfg.model = `${stubId}/${modelId}`
  testMkdir(dir, { recursive: true })
  testWrite(`${file}.tmp`, JSON.stringify(cfg, null, 2) + "\n")
  testRename(`${file}.tmp`, file)
  log(`test stub: wrote provider ${stubId} and default model ${cfg.model} to ${file}`)
}

async function startTestStub() {
  const log = (line) => process.stderr.write(`[launcher-v2] ${line}\n`)
  if (testExists(TEST_PTY_MARK) && testExists(TEST_PTY) && process.env.OPENCODE_NODE_PTY_PATH === undefined) {
    process.env.OPENCODE_NODE_PTY_PATH = TEST_PTY
    log(`test native: OPENCODE_NODE_PTY_PATH=${TEST_PTY}`)
  }
  if (testExists(TEST_WATCHER) && process.env.OPENCODE_PARCEL_WATCHER_PATH === undefined) {
    process.env.OPENCODE_PARCEL_WATCHER_PATH = TEST_WATCHER
    log(`test native: OPENCODE_PARCEL_WATCHER_PATH=${TEST_WATCHER}`)
  }
  try {
    testWriteConfig(log)
  } catch (err) {
    log(`test stub: config not written: ${err?.message ?? err}`)
  }
  try {
    const { startStub } = await import(testPathURL(testJoin(TEST_DIR, "test-stub", "stub-model.mjs")).href)
    await startStub({ port: TEST_STUB_PORT, log: (l) => log(l) })
    log(`test stub: scripted model on 127.0.0.1:${TEST_STUB_PORT}`)
  } catch (err) {
    log(`test stub: not started: ${err?.message ?? err}`)
  }
}
// ---- end TEST ONLY
