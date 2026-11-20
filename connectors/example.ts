/**
 * Example wiring for a React Native / Expo app.
 * Swap the adapters for your own platform (Swift/Kotlin ports follow the same shape).
 */
import * as SecureStore from "expo-secure-store";
import * as WebBrowser from "expo-web-browser";
import {
  ConnectorManager,
  ByokTransport,
  ProxyTransport,
  chat,
  type ConnectorDef,
  type SecureStore as Store,
  type AuthLauncher,
} from "./harness";

// --- Platform adapters ------------------------------------------------

const store: Store = {
  get: (k) => SecureStore.getItemAsync(k.replace(/[^\w.-]/g, "_")),
  set: (k, v) => SecureStore.setItemAsync(k.replace(/[^\w.-]/g, "_"), v),
  delete: (k) => SecureStore.deleteItemAsync(k.replace(/[^\w.-]/g, "_")),
};

const launcher: AuthLauncher = {
  async open(authUrl, redirectUri) {
    const r = await WebBrowser.openAuthSessionAsync(authUrl, redirectUri);
    if (r.type !== "success") throw new Error("Login cancelled");
    return r.url;
  },
};

// Prefer an https App Link / Universal Link if a server rejects custom schemes.
const REDIRECT_URI = "myapp://oauth";

// --- Connector registry (URLs only; check each service's docs for the real endpoint) ---

export const CONNECTORS: ConnectorDef[] = [
  { id: "asana", name: "Asana", url: "https://mcp.asana.com/sse", mode: "remote" },
  { id: "notes", name: "My Notes", url: "https://notes.example.com/mcp", mode: "local" },
];

// A user-supplied custom connector is just another entry:
export function addCustomConnector(name: string, url: string): ConnectorDef {
  const def: ConnectorDef = { id: name.toLowerCase().replace(/\W+/g, "_"), name, url, mode: "local" };
  CONNECTORS.push(def);
  return def;
}

// --- Wiring -----------------------------------------------------------

const manager = new ConnectorManager(store, launcher, REDIRECT_URI, "My App");

// Option A: proxy (recommended for production)
const transport = new ProxyTransport("https://my-proxy.example.workers.dev", async () => "USER_SESSION_TOKEN");

// Option B: BYOK (power users enter their own key in settings)
// const transport = new ByokTransport(async () => (await store.get("anthropic_key"))!);

// --- UI handlers ------------------------------------------------------

export const onConnectPress = (def: ConnectorDef) => manager.connect(def);
export const onDisconnectPress = (def: ConnectorDef) => manager.disconnect(def);

let history: any[] = [];

export async function send(text: string) {
  const { text: reply, messages } = await chat({
    transport,
    manager,
    connectors: CONNECTORS,
    history,
    userText: text,
    // Ask the user before any local tool runs (replace with a real dialog).
    confirm: async (tool, args) => {
      console.log("Confirm tool call:", tool, args);
      return true;
    },
  });
  history = messages; // keep unchanged so mcp_tool_listing blocks survive between turns
  return reply;
}
