// Config guard for OpenCode v2's permission service (copied to
// packages/core/src/permission-guard.ts by apply-permission-guard.py).
//
// Port of phase4/payload/patches/permission-guard.ts (v1) to the v2 names:
// the shell tool is `shell` (v1 `bash`), the edit tool is `edit`, and a request
// carries `resources` (v2) instead of `patterns` (v1).
//
// Why: v2 stores "always" approvals as saved allow rules and evaluates them after
// the configured rules (last match wins). One approval of an edit with resource
// "*" (what the edit tool sends) or of a shell prefix such as "cat *" would then
// silently allow later writes to opencode.json and .mcp.json, which set
// permissions and start MCP servers. Those requests must always prompt.
//
// What this guard does, for requests whose action is edit, shell or
// external_directory and whose resource names a config file (below):
//  - saved approvals are not consulted (the request is judged by the configured
//    rules alone, which default to "ask"),
//  - an "always" on such a request approves only that request, and saves nothing.
//
// Limits: the shell check is textual. A command that writes the file without
// naming it (for example by building the name in a variable) is not caught. The
// edit check uses the path the tool was given, so a symlink to a config file is
// not caught.

const GUARDED_ACTIONS = new Set(["edit", "shell", "external_directory"])

// Lower-case substrings that identify config and extension files.
const CONFIG_TOKENS = ["opencode.json", "opencode.jsonc", ".mcp.json", ".opencode", "config/opencode"]

export function isProtectedPattern(action: string, resource: string): boolean {
  if (!GUARDED_ACTIONS.has(action)) return false
  const text = resource.replaceAll("\\", "/").toLowerCase()
  return CONFIG_TOKENS.some((token) => text.includes(token))
}

export function isProtectedRequest(request: { action: string; resources: readonly string[] }): boolean {
  return request.resources.some((resource) => isProtectedPattern(request.action, resource))
}
