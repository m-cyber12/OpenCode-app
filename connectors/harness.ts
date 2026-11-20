/**
 * Hybrid MCP harness (TypeScript, platform-agnostic core).
 *
 * Combines every mode we discussed:
 *  - LLM access:   BYOK (user's own API key)  OR  thin proxy (your key stays server-side)
 *  - MCP tools:    "local"  -> your app is the MCP client and runs the tool loop itself
 *                  "remote" -> Anthropic's MCP connector (mcp_servers) calls the server for you
 *  - Auth:         OAuth 2.1 + PKCE + dynamic client registration, tokens in secure storage
 *  - Safety:       optional user confirmation before any local tool call
 *
 * Deps: @modelcontextprotocol/sdk  (use fetch polyfills if your runtime lacks them)
 */
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { auth, type OAuthClientProvider } from "@modelcontextprotocol/sdk/client/auth.js";

/* ------------------------------------------------------------------ */
/* Platform adapters: implement these once per platform                 */
/* ------------------------------------------------------------------ */

/** Secure storage (iOS Keychain / Android Keystore / expo-secure-store ...). */
export interface SecureStore {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  delete(key: string): Promise<void>;
}

/**
 * Opens the system browser for the OAuth consent screen and resolves with the
 * full callback URL (e.g. "myapp://oauth?code=...").
 */
export interface AuthLauncher {
  open(authUrl: string, redirectUri: string): Promise<string>;
}

/* ------------------------------------------------------------------ */
/* Connector registry                                                   */
/* ------------------------------------------------------------------ */

export interface ConnectorDef {
  id: string; // short, unique, [a-z0-9_-]; used as tool-name prefix
  name: string; // display name
  url: string; // remote MCP endpoint (streamable HTTP)
  /**
   * "local":  app talks to the MCP server directly (tokens never leave the device,
   *           works for any server the phone can reach, lets you gate tool calls).
   * "remote": Anthropic's servers talk to the MCP server (fewer round trips, but the
   *           server must be publicly reachable and the token is sent with each request).
   */
  mode: "local" | "remote";
}

/* ------------------------------------------------------------------ */
/* OAuth provider backed by SecureStore                                 */
/* ------------------------------------------------------------------ */

class StoredOAuthProvider implements OAuthClientProvider {
  /** Filled by the SDK when it needs the user to visit the consent page. */
  pendingAuthUrl?: URL;

  constructor(
    private id: string,
    private store: SecureStore,
    private redirectUri: string,
    private clientName: string
  ) {}

  get redirectUrl() {
    return this.redirectUri;
  }

  get clientMetadata() {
    return {
      client_name: this.clientName,
      redirect_uris: [this.redirectUri],
      grant_types: ["authorization_code", "refresh_token"],
      response_types: ["code"],
      token_endpoint_auth_method: "none", // native apps are public clients (PKCE)
    };
  }

  private key(k: string) {
    return `mcp:${this.id}:${k}`;
  }
  private async load<T>(k: string): Promise<T | undefined> {
    const v = await this.store.get(this.key(k));
    return v ? (JSON.parse(v) as T) : undefined;
  }
  private save(k: string, v: unknown) {
    return this.store.set(this.key(k), JSON.stringify(v));
  }

  clientInformation() {
    return this.load<any>("client");
  }
  saveClientInformation(info: any) {
    return this.save("client", info);
  }
  tokens() {
    return this.load<any>("tokens");
  }
  saveTokens(t: any) {
    return this.save("tokens", t);
  }
  saveCodeVerifier(v: string) {
    return this.save("verifier", v);
  }
  async codeVerifier() {
    const v = await this.load<string>("verifier");
    if (!v) throw new Error("Missing PKCE code verifier");
    return v;
  }
  redirectToAuthorization(url: URL) {
    this.pendingAuthUrl = url; // we launch the browser ourselves in connect()
  }

  async clear() {
    for (const k of ["client", "tokens", "verifier"]) await this.store.delete(this.key(k));
  }
}

/* ------------------------------------------------------------------ */
/* Connector manager                                                    */
/* ------------------------------------------------------------------ */

type RoutedTool = { connectorId: string; originalName: string };

export class ConnectorManager {
  private clients = new Map<string, Client>();
  private providers = new Map<string, StoredOAuthProvider>();

  constructor(
    private store: SecureStore,
    private launcher: AuthLauncher,
    private redirectUri: string, // e.g. "myapp://oauth" or an https App Link
    private clientName = "My Native Harness"
  ) {}

  private provider(def: ConnectorDef) {
    let p = this.providers.get(def.id);
    if (!p) {
      p = new StoredOAuthProvider(def.id, this.store, this.redirectUri, this.clientName);
      this.providers.set(def.id, p);
    }
    return p;
  }

  /** The "Connect" button handler: runs the whole OAuth flow. */
  async connect(def: ConnectorDef): Promise<void> {
    const p = this.provider(def);
    let result = await auth(p, { serverUrl: def.url });

    if (result === "REDIRECT") {
      if (!p.pendingAuthUrl) throw new Error("OAuth redirect requested but no URL was provided");
      const callback = await this.launcher.open(p.pendingAuthUrl.toString(), this.redirectUri);
      const params = new URL(callback).searchParams;
      const code = params.get("code");
      if (!code) throw new Error(params.get("error") ?? "No authorization code returned");
      result = await auth(p, { serverUrl: def.url, authorizationCode: code });
    }
    if (result !== "AUTHORIZED") throw new Error("Authorization failed");
  }

  async isConnected(def: ConnectorDef) {
    return !!(await this.provider(def).tokens());
  }

  async disconnect(def: ConnectorDef) {
    await this.clients.get(def.id)?.close().catch(() => {});
    this.clients.delete(def.id);
    await this.provider(def).clear();
  }

  /** Lazily creates (and caches) a direct MCP client. Transport refreshes tokens automatically. */
  async client(def: ConnectorDef): Promise<Client> {
    const cached = this.clients.get(def.id);
    if (cached) return cached;
    const client = new Client({ name: this.clientName, version: "1.0.0" });
    await client.connect(
      new StreamableHTTPClientTransport(new URL(def.url), { authProvider: this.provider(def) })
    );
    this.clients.set(def.id, client);
    return client;
  }

  /** Returns a fresh access token for "remote" mode (refreshes it if needed). */
  async freshAccessToken(def: ConnectorDef): Promise<string> {
    const p = this.provider(def);
    const r = await auth(p, { serverUrl: def.url }); // refreshes silently when possible
    if (r !== "AUTHORIZED") throw new Error(`${def.name} needs to be reconnected`);
    const t = await p.tokens();
    if (!t?.access_token) throw new Error(`No access token for ${def.name}`);
    return t.access_token;
  }

  /** Collects tools from all connected "local" connectors, namespaced as `<id>__<tool>`. */
  async localTools(defs: ConnectorDef[]) {
    const tools: any[] = [];
    const routes = new Map<string, RoutedTool>();
    for (const def of defs.filter((d) => d.mode === "local")) {
      if (!(await this.isConnected(def))) continue;
      try {
        const { tools: list } = await (await this.client(def)).listTools();
        for (const t of list) {
          const name = `${def.id}__${t.name}`;
          routes.set(name, { connectorId: def.id, originalName: t.name });
          tools.push({ name, description: t.description ?? "", input_schema: t.inputSchema });
        }
      } catch (e) {
        console.warn(`Skipping ${def.name}:`, e); // one broken server must not break the chat
      }
    }
    return { tools, routes };
  }

  /** Builds the `mcp_servers` + `mcp_toolset` entries for connected "remote" connectors. */
  async remoteConfig(defs: ConnectorDef[]) {
    const mcp_servers: any[] = [];
    const toolsets: any[] = [];
    for (const def of defs.filter((d) => d.mode === "remote")) {
      if (!(await this.isConnected(def))) continue;
      mcp_servers.push({
        type: "url",
        url: def.url,
        name: def.id,
        authorization_token: await this.freshAccessToken(def),
      });
      toolsets.push({ type: "mcp_toolset", mcp_server_name: def.id });
    }
    return { mcp_servers, toolsets };
  }
}

/* ------------------------------------------------------------------ */
/* LLM transports: BYOK or proxy                                        */
/* ------------------------------------------------------------------ */

export interface LlmTransport {
  send(body: unknown, betas: string[]): Promise<any>;
}

async function postJson(url: string, headers: Record<string, string>, body: unknown) {
  const res = await fetch(url, { method: "POST", headers, body: JSON.stringify(body) });
  const json = await res.json();
  if (!res.ok) throw new Error(json?.error?.message ?? `HTTP ${res.status}`);
  return json;
}

/** Bring-your-own-key: the user's key lives in SecureStore on the device. */
export class ByokTransport implements LlmTransport {
  constructor(private getApiKey: () => Promise<string>) {}
  async send(body: unknown, betas: string[]) {
    const headers: Record<string, string> = {
      "content-type": "application/json",
      "x-api-key": await this.getApiKey(),
      "anthropic-version": "2023-06-01",
    };
    if (betas.length) headers["anthropic-beta"] = betas.join(",");
    return postJson("https://api.anthropic.com/v1/messages", headers, body);
  }
}

/** Your own thin proxy: the Anthropic key never ships inside the app. */
export class ProxyTransport implements LlmTransport {
  constructor(private proxyUrl: string, private getSessionToken: () => Promise<string>) {}
  async send(body: unknown, betas: string[]) {
    const headers: Record<string, string> = {
      "content-type": "application/json",
      authorization: `Bearer ${await this.getSessionToken()}`,
    };
    if (betas.length) headers["anthropic-beta"] = betas.join(",");
    return postJson(this.proxyUrl, headers, body);
  }
}

/* ------------------------------------------------------------------ */
/* Chat loop                                                            */
/* ------------------------------------------------------------------ */

export interface ChatOptions {
  transport: LlmTransport;
  manager: ConnectorManager;
  connectors: ConnectorDef[];
  history: any[]; // prior messages; pass the returned `messages` back in on the next turn
  userText: string;
  model?: string;
  system?: string;
  /** Called before every local tool call. Return false to deny. */
  confirm?: (toolName: string, args: unknown) => Promise<boolean>;
  maxSteps?: number;
}

export async function chat(opts: ChatOptions) {
  const { transport, manager, connectors, confirm, maxSteps = 8 } = opts;
  const messages = [...opts.history, { role: "user", content: opts.userText }];

  // Gather tools from both modes once per turn.
  const local = await manager.localTools(connectors);
  const remote = await manager.remoteConfig(connectors);
  const hasRemote = remote.mcp_servers.length > 0;

  // The newer beta header is a superset of mcp-client-2025-11-20; only needed for remote mode.
  const betas = hasRemote ? ["mcp-client-2026-09-15"] : [];

  for (let step = 0; step < maxSteps; step++) {
    const response = await transport.send(
      {
        model: opts.model ?? "claude-sonnet-5-5",
        max_tokens: 2000,
        system: opts.system,
        messages,
        tools: [...local.tools, ...remote.toolsets],
        ...(hasRemote ? { mcp_servers: remote.mcp_servers } : {}),
      },
      betas
    );

    // IMPORTANT: store the assistant content unchanged. With remote mode it includes
    // mcp_tool_use / mcp_tool_result / mcp_tool_listing blocks that must be echoed back.
    messages.push({ role: "assistant", content: response.content });

    // Server-side tool loop hit its iteration cap: just ask the model to continue.
    if (response.stop_reason === "pause_turn") continue;

    // Only LOCAL tools surface as plain tool_use blocks; remote ones were already executed.
    const calls = response.content.filter((b: any) => b.type === "tool_use");
    if (response.stop_reason !== "tool_use" || calls.length === 0) {
      const text = response.content
        .filter((b: any) => b.type === "text")
        .map((b: any) => b.text)
        .join("\n");
      return { text, messages };
    }

    const results = await Promise.all(
      calls.map(async (call: any) => {
        const route = local.routes.get(call.name);
        if (!route) {
          return { type: "tool_result", tool_use_id: call.id, content: "Unknown tool", is_error: true };
        }
        if (confirm && !(await confirm(call.name, call.input))) {
          return { type: "tool_result", tool_use_id: call.id, content: "User denied this action", is_error: true };
        }
        try {
          const def = connectors.find((c) => c.id === route.connectorId)!;
          const client = await manager.client(def);
          const out: any = await client.callTool({ name: route.originalName, arguments: call.input });
          // Pass text blocks through; stringify anything else (images, resources...).
          const content = (out.content ?? []).map((c: any) =>
            c.type === "text" ? { type: "text", text: c.text } : { type: "text", text: JSON.stringify(c) }
          );
          return { type: "tool_result", tool_use_id: call.id, content, is_error: !!out.isError };
        } catch (e: any) {
          // Return errors to the model so it can retry or explain instead of crashing the loop.
          return { type: "tool_result", tool_use_id: call.id, content: String(e?.message ?? e), is_error: true };
        }
      })
    );
    messages.push({ role: "user", content: results });
  }

  return { text: "Stopped: too many tool steps.", messages };
}
