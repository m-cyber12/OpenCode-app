/**
 * Thin proxy (Cloudflare Worker) so the Anthropic API key never ships in the app.
 *
 * Deploy:  wrangler secret put ANTHROPIC_API_KEY
 *
 * NOTE: in "remote" connector mode the request body contains the user's MCP
 * access tokens. Never log request bodies here.
 */
export interface Env {
  ANTHROPIC_API_KEY: string;
}

const ALLOWED_MODELS = new Set(["claude-sonnet-5-5", "claude-haiku-5-5"]);
const MAX_TOKENS_CAP = 4000;
const ALLOWED_BETA_PREFIXES = ["mcp-client-"]; // only forward betas we expect

/** Replace with real auth (Firebase / Supabase JWT, App Attest, your own session, ...). */
async function verifySession(token: string | null): Promise<{ userId: string } | null> {
  if (!token) return null;
  // TODO: validate the token and return the user id.
  return { userId: "demo-user" };
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    if (req.method !== "POST") return new Response("Method not allowed", { status: 405 });

    // 1) Authenticate the app user (not the API key).
    const token = req.headers.get("authorization")?.replace(/^Bearer /, "") ?? null;
    const session = await verifySession(token);
    if (!session) return new Response("Unauthorized", { status: 401 });

    // 2) Per-user rate limiting / quota goes here (KV, Durable Object, or Rate Limiting binding).

    // 3) Validate the body so users can't abuse your key.
    const body: any = await req.json();
    if (!ALLOWED_MODELS.has(body.model)) return new Response("Model not allowed", { status: 400 });
    body.max_tokens = Math.min(body.max_tokens ?? 1000, MAX_TOKENS_CAP);

    // 4) Forward only whitelisted beta flags.
    const betas = (req.headers.get("anthropic-beta") ?? "")
      .split(",")
      .map((b) => b.trim())
      .filter((b) => ALLOWED_BETA_PREFIXES.some((p) => b.startsWith(p)));

    const headers: Record<string, string> = {
      "content-type": "application/json",
      "x-api-key": env.ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    };
    if (betas.length) headers["anthropic-beta"] = betas.join(",");

    // 5) Pass the upstream response straight through.
    const upstream = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });
    return new Response(upstream.body, {
      status: upstream.status,
      headers: { "content-type": "application/json" },
    });
  },
};
