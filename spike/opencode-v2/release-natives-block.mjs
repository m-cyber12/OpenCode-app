// RELEASE native paths (switch-over 2026-11-16). Inserted by build-v2-bundle.sh in V2_RELEASE=1 mode
// ahead of phase4/payload/v2/launcher-v2.mjs. It sets only the two native paths that the device-tested
// bundle used (terminal addon, file watcher), and only when the files are in the bundle. It writes no
// config, starts no model stub, and does not touch opencode.json.
import { existsSync as relExists } from "node:fs"
import { dirname as relDirname, join as relJoin } from "node:path"
import { fileURLToPath as relFileURL } from "node:url"

const REL_DIR = relDirname(relFileURL(import.meta.url))
const REL_PTY = relJoin(REL_DIR, "assets", "@lydell", "node-pty-linux-arm64", "lib", "index.js")
const REL_WATCHER = relJoin(REL_DIR, "assets", "native", "watcher.node")
if (relExists(REL_PTY) && process.env.OPENCODE_NODE_PTY_PATH === undefined) {
  process.env.OPENCODE_NODE_PTY_PATH = REL_PTY
}
if (relExists(REL_WATCHER) && process.env.OPENCODE_PARCEL_WATCHER_PATH === undefined) {
  process.env.OPENCODE_PARCEL_WATCHER_PATH = REL_WATCHER
}
