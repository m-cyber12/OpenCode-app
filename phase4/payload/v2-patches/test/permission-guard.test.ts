// Host test for the v2 config guard (permission-guard.ts). No dependencies.
// Run: node --test --experimental-strip-types phase4/payload/v2-patches/test/permission-guard.test.ts
import { test } from "node:test"
import assert from "node:assert/strict"
import { isProtectedPattern, isProtectedRequest } from "../permission-guard.ts"

test("edit of a config file is protected", () => {
  assert.equal(isProtectedPattern("edit", "/data/user/0/app/files/xdg/config/opencode/opencode.json"), true)
  assert.equal(isProtectedPattern("edit", "/work/proj/opencode.jsonc"), true)
  assert.equal(isProtectedPattern("edit", "/work/proj/.mcp.json"), true)
  assert.equal(isProtectedPattern("edit", "/work/proj/.opencode/agent.md"), true)
})

test("ordinary files are not protected", () => {
  assert.equal(isProtectedPattern("edit", "/work/proj/src/app.ts"), false)
  assert.equal(isProtectedPattern("edit", "/work/proj/README.md"), false)
})

test("shell commands that name a config file are protected", () => {
  assert.equal(isProtectedPattern("shell", "echo '{}' > opencode.json"), true)
  assert.equal(isProtectedPattern("shell", "cat opencode.json"), true)
  assert.equal(isProtectedPattern("shell", "cat README.md"), false)
})

test("external_directory on a config folder is protected", () => {
  assert.equal(isProtectedPattern("external_directory", "/sdcard/x/.opencode/y"), true)
})

test("actions outside the guarded set are never protected", () => {
  assert.equal(isProtectedPattern("read", "/work/proj/opencode.json"), false)
  assert.equal(isProtectedPattern("webfetch", "https://example.com/opencode.json"), false)
})

test("matching ignores case and treats backslashes as separators", () => {
  assert.equal(isProtectedPattern("edit", "C:\\proj\\OpenCode.JSON"), true)
  assert.equal(isProtectedPattern("edit", "C:\\proj\\.OPENCODE\\x"), true)
})

test("a request is protected when any one of its resources is", () => {
  const request = { action: "shell", resources: ["ls -l", "cat opencode.json"] }
  assert.equal(isProtectedRequest(request), true)
  assert.equal(isProtectedRequest({ action: "shell", resources: ["ls -l"] }), false)
  assert.equal(isProtectedRequest({ action: "shell", resources: [] }), false)
})
