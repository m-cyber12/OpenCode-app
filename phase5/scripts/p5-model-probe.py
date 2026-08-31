#!/usr/bin/env python3
"""p5-model-probe.py - decide, by measurement, whether a live agent turn can run.

Why this exists: the Phase 5 re-runs of the Phase 3 drivers (G7/G10/G11/G12) each
take a `model_available` flag that turns their "the model really streamed text /
really called a tool" assertions on or off. Phase 4 could set that flag from the
presence of OPENROUTER_API_KEY; Phase 5 is deliberately key-free (the pinned build
ships the free `opencode/big-pickle` default), so the flag has to come from
somewhere else.

Asking `GET /provider` whether it has a `default` is NOT that somewhere else - that
was run #13's heuristic and it cannot mean anything. Upstream fills `default` from
the models.dev catalog: Provider.defaultModelIDs()
(packages/opencode/src/provider/provider.ts:1132) maps EVERY catalogued provider to
`sort(Object.values(item.models))[0].id`, so the key is present whether or not a
provider can actually serve a turn; and `connected`
(packages/opencode/src/server/routes/instance/httpapi/handlers/provider.ts:60) is
`id in Provider.list() || auth.json[id]`, which depends on SDK init behaviour this
gate must not reason about indirectly.

So probe the only contract that matters: send one tiny prompt through OpenCode's own
`POST /session/:id/prompt_async` and look for an assistant message with text, using
nothing but the HTTP API a client would use (no privileged access, no model name
hardcoded - the server picks its own default).

Stdlib only (urllib), because it runs on the CI host before/independently of any
runner download, and talks to the server through the existing `adb forward`.

Exit 0 -> PROBE ok  : a real assistant reply arrived (model-dependent assertions on)
Exit 1 -> PROBE unavailable : error/timeout (those assertions stay off, loudly)
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import base64

BASE = os.environ.get("OPENCODE_BASE", "http://127.0.0.1:4111")
USER = os.environ.get("OPENCODE_SERVER_USERNAME", "opencode")
PASS = os.environ.get("OPENCODE_SERVER_PASSWORD", "")
DIRECTORY = os.environ.get("OPENCODE_DIRECTORY", "")
TIMEOUT = int(os.environ.get("P5_MODEL_PROBE_TIMEOUT", "150"))
POLL = 3
TEXT = "Reply with exactly this token and nothing else: P5PROBEOK"


def call(method, path, body=None):
    url = BASE + path
    if DIRECTORY and path not in ("/global/health", "/global/config", "/global/event"):
        sep = "&" if "?" in path else "?"
        url += sep + "directory=" + urllib.parse.quote(DIRECTORY, safe="")
    data = None
    headers = {"Authorization": "Basic " + base64.b64encode(f"{USER}:{PASS}".encode()).decode()}
    if body is not None:
        data = json.dumps(body).encode()
        headers["content-type"] = "application/json"
    elif method != "GET":
        headers["content-length"] = "0"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw[:300]


def main():
    if not PASS:
        print("PROBE unavailable :: no server password (P5-04 did not export one)")
        return 1
    code, health = call("GET", "/global/health")
    if not (isinstance(health, dict) and health.get("healthy")):
        print(f"PROBE unavailable :: server not healthy (http {code} {str(health)[:120]})")
        return 1
    code, sess = call("POST", "/session", {"title": "P5 model pre-flight"})
    sid = sess.get("id") if isinstance(sess, dict) else None
    if not sid:
        print(f"PROBE unavailable :: POST /session -> http {code} {str(sess)[:200]}")
        return 1
    code, _ = call("POST", f"/session/{urllib.parse.quote(sid)}/prompt_async",
                   {"parts": [{"type": "text", "text": TEXT}]})
    if code not in (200, 204):
        print(f"PROBE unavailable :: prompt_async -> http {code} {str(_)[:200]}")
        return 1
    deadline = time.time() + TIMEOUT
    while time.time() < deadline:
        time.sleep(POLL)
        code, msgs = call("GET", f"/session/{urllib.parse.quote(sid)}/message")
        items = msgs if isinstance(msgs, list) else (msgs or {}).get("data") if isinstance(msgs, dict) else None
        if not isinstance(items, list):
            continue
        for m in items:
            info = m.get("info", m) if isinstance(m, dict) else {}
            parts = m.get("parts", []) if isinstance(m, dict) else []
            if info.get("role") != "assistant":
                continue
            err = info.get("error")
            if err:
                txt = json.dumps(err)[:300]
                print(f"PROBE unavailable :: assistant turn errored: {txt}")
                return 1
            for p in parts:
                if isinstance(p, dict) and p.get("type") == "text" and TEXT.split()[-1] in (p.get("text") or ""):
                    model = info.get("modelID") or info.get("model") or "?"
                    print(f"PROBE ok :: model={model} replied after <= {TIMEOUT - int(deadline - time.time())}s")
                    return 0
        # Text may have arrived without the exact token (a chatty model still proves
        # the turn ran); treat any non-empty assistant text as usable, but say so.
        for m in items:
            info = m.get("info", m) if isinstance(m, dict) else {}
            if info.get("role") != "assistant":
                continue
            for p in (m.get("parts") or []):
                if isinstance(p, dict) and p.get("type") == "text" and (p.get("text") or "").strip():
                    print("PROBE ok :: assistant text present (token not verbatim) "
                          f"model={info.get('modelID') or info.get('model') or '?'}")
                    return 0
    print(f"PROBE unavailable :: no assistant reply within {TIMEOUT}s")
    return 1


if __name__ == "__main__":
    sys.exit(main())
