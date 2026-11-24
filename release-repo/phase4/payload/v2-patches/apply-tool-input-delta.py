#!/usr/bin/env python3
"""Publish tool-argument fragments while the model writes them (v2.2.5).

Usage: apply-tool-input-delta.py <v2-root>

Upstream declares `session.tool.input.delta` (SessionEvent.Tool.Input.Delta, ephemeral) but never
publishes it. The runner's `toolInput` fragment buffer is built with an `ended` callback only, so
the text a model streams for a tool call stays inside the server until the call is complete. A
client therefore sees a Write or Edit only when it has finished: the card appears late, with its
content already known. (Checked against v2.2.5 and v2.3.2: neither publishes the delta.)

This patch adds the missing `delta` callback to that one fragment buffer. The batching that
upstream already has (100 ms, flushed before `Input.Ended`) then publishes each fragment in order,
and `Input.Ended` still carries the full raw text, so the durable record does not change.

The edit is one exact-text insertion in packages/core/src/session/runner/publish-llm-event.ts.
The anchor must occur exactly once; otherwise the script exits 1 and the bundle build stops.
"""
import os
import sys

TARGET = os.path.join("packages", "core", "src", "session", "runner", "publish-llm-event.ts")

# The end of the `toolInput` buffer, which is followed by the comment that opens the
# block-start section.
ANCHOR = "    }),\n  )\n\n  // Deltas are batched, but block starts are not."

REPLACEMENT = """    }),
    // Owner (2026-11-21): publish argument text as it streams, so the app can show a write's
    // content while it is written. Ephemeral: live only; Input.Ended is the durable record.
    (id, value) =>
      Effect.gen(function* () {
        yield* bus.publish(SessionEvent.Tool.Input.Delta, {
          sessionID: input.sessionID,
          assistantMessageID,
          id,
          delta: value,
        })
      }),
  )

  // Deltas are batched, but block starts are not."""

MARKER = "Tool.Input.Delta"


def fail(msg: str) -> None:
    print(f"FATAL v2 tool-input-delta patch: {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: apply-tool-input-delta.py <v2-root>")
    root = sys.argv[1]
    target = os.path.join(root, TARGET)
    if not os.path.isfile(target):
        fail(f"missing {target}")
    with open(target, encoding="utf-8") as fh:
        text = fh.read()

    # The runner already publishes the delta, or this patch was applied before: do not add it twice.
    if MARKER in text:
        fail("publish-llm-event.ts already references Tool.Input.Delta; apply the patch to a fresh checkout")
    count = text.count(ANCHOR)
    if count != 1:
        fail(f"tool input buffer anchor found {count} times (need exactly 1); upstream moved")

    text = text.replace(ANCHOR, REPLACEMENT, 1)
    with open(target, "w", encoding="utf-8") as fh:
        fh.write(text)
    print("v2 tool-input-delta patch applied to", target)


if __name__ == "__main__":
    main()
