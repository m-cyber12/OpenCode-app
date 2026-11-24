// Config guard for upstream's permission service (copied to
// packages/opencode/src/permission/permission-guard.ts by apply-permission-guard.py).
//
// Why: upstream's "allow for session" (reply "always") stores patterns that are
// checked AFTER the configured rules, so one approval of an edit with pattern "*"
// (what the edit tool sends) or of a shell prefix such as "cat *" silently allowed
// later writes to opencode.json and .mcp.json, which set permissions and start
// MCP servers. Those requests must always prompt.
//
// What this guard does, for requests whose permission is edit, bash or
// external_directory and whose pattern names a config file (below):
//  - session approvals are not consulted (the request is judged by the configured
//    rules alone, which default to "ask"),
//  - a session "always" on such a request approves only that request.
//
// Limits (also stated in docs/progress/sandbox-comparison-and-agent-plan-review.md):
// the shell check is textual. A command that writes the file without naming it
// (for example by building the name in a variable) is not caught. The edit check
// uses the path the tool was given, so a symlink to a config file is not caught.

const GUARDED_PERMISSIONS = new Set(["edit", "bash", "external_directory"])

// Lower-case substrings that identify config and extension files.
const CONFIG_TOKENS = ["opencode.json", "opencode.jsonc", ".mcp.json", ".opencode", "config/opencode"]

export function isProtectedPattern(permission: string, pattern: string): boolean {
  if (!GUARDED_PERMISSIONS.has(permission)) return false
  const text = pattern.replaceAll("\\", "/").toLowerCase()
  return CONFIG_TOKENS.some((token) => text.includes(token))
}

export function isProtectedRequest(request: { permission: string; patterns: string[] }): boolean {
  return request.patterns.some((pattern) => isProtectedPattern(request.permission, pattern))
}
