// One-shot MCP stdio probe: start a server command, run initialize + tools/list, report.
// Usage: node mcp-probe.mjs <timeout-seconds> <command> [args...]
import { spawn } from "child_process";

const [, , secs, cmd, ...args] = process.argv;
const started = Date.now();
const child = spawn(cmd, args, { stdio: ["pipe", "pipe", "pipe"], env: process.env });
let buf = "";
let stderr = "";
const pending = new Map();
let id = 0;

child.stdout.on("data", (d) => {
  buf += d.toString();
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { continue; }
    if (msg.id !== undefined && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    }
  }
});
child.stderr.on("data", (d) => { stderr += d.toString(); });

function rpc(method, params) {
  const reqId = ++id;
  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id: reqId, method, params }) + "\n");
  return new Promise((resolve, reject) => {
    pending.set(reqId, resolve);
    setTimeout(() => reject(new Error("timeout waiting for " + method)), secs * 1000);
  });
}

const done = (code, report) => {
  console.log(JSON.stringify({ ...report, seconds: Math.round((Date.now() - started) / 1000) }, null, 2));
  child.kill("SIGTERM");
  process.exit(code);
};

try {
  const init = await rpc("initialize", {
    protocolVersion: "2024-11-05",
    capabilities: {},
    clientInfo: { name: "probe", version: "0" },
  });
  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }) + "\n");
  const tools = await rpc("tools/list", {});
  const names = (tools.result?.tools ?? []).map((t) => t.name);
  done(0, {
    command: [cmd, ...args].join(" "),
    serverInfo: init.result?.serverInfo ?? null,
    error: init.error ?? tools.error ?? null,
    toolCount: names.length,
    tools: names,
    stderrTail: stderr.split("\n").slice(-6).join("\n"),
  });
} catch (e) {
  done(1, { command: [cmd, ...args].join(" "), error: String(e.message), stderrTail: stderr.split("\n").slice(-12).join("\n") });
}
