// Behaviour test for the config guard (permission-guard.ts), run inside the pinned
// upstream checkout after apply-permission-guard.py:
//   cp this file to packages/opencode/test/permission/config-guard.test.ts
//   cd packages/opencode && bun test test/permission/config-guard.test.ts
// Uses the same harness as upstream's test/permission/next.test.ts.
import { PermissionV1 } from "@opencode-ai/core/v1/permission"
import { test, expect } from "bun:test"
import { Cause, Effect, Exit, Fiber, Layer } from "effect"
import { EventV2Bridge } from "../../src/event-v2-bridge"
import { CrossSpawnSpawner } from "@opencode-ai/core/cross-spawn-spawner"
import { Permission } from "../../src/permission"
import { InstanceBootstrap } from "../../src/project/bootstrap"
import { InstanceStore } from "../../src/project/instance-store"
import { testEffect } from "../lib/effect"
import { MessageID, SessionID } from "../../src/session/schema"
import { AppNodeBuilder } from "@opencode-ai/core/effect/app-node-builder"
import { LayerNode } from "@opencode-ai/core/effect/layer-node"

const noopBootstrap = Layer.succeed(InstanceBootstrap.Service, InstanceBootstrap.Service.of({ run: Effect.void }))
const env = AppNodeBuilder.build(
  LayerNode.group([Permission.node, EventV2Bridge.node, CrossSpawnSpawner.node, InstanceStore.node]),
  [[InstanceStore.bootstrapNode, noopBootstrap]],
)
const it = testEffect(env)

const SESSION = SessionID.make("session_guard")

const ask = (input: Partial<Parameters<Permission.Interface["ask"]>[0]> & Pick<PermissionV1.AskInput, "permission" | "patterns">) =>
  Effect.gen(function* () {
    const permission = yield* Permission.Service
    return yield* permission.ask({
      sessionID: SESSION,
      metadata: {},
      always: [],
      tool: { messageID: MessageID.make("msg_guard"), callID: "call_guard" },
      ruleset: [],
      ...input,
    } as any)
  })

const reply = (requestID: PermissionV1.ID, value: "once" | "always" | "reject") =>
  Effect.gen(function* () {
    const permission = yield* Permission.Service
    return yield* permission.reply({ requestID, reply: value })
  })

const list = () =>
  Effect.gen(function* () {
    const permission = yield* Permission.Service
    return yield* permission.list()
  })

const rejectAll = () =>
  Effect.gen(function* () {
    for (const req of yield* list()) yield* reply(req.id, "reject")
  })

// Starts an ask, lets it settle for a moment, and reports whether it is still
// waiting for a human (pending) or was allowed straight through (not pending).
// The pending request, if any, is rejected so the fiber can finish.
const askSettlesAllowed = (input: Parameters<typeof ask>[0]) =>
  Effect.gen(function* () {
    const fiber = yield* ask(input).pipe(Effect.forkScoped)
    yield* Effect.sleep("60 millis")
    const pending = (yield* list()).length
    yield* rejectAll()
    yield* Fiber.await(fiber)
    return pending === 0
  })

// Returns the pending request id for a fresh ask (so the test can answer it).
const askPending = (input: Parameters<typeof ask>[0]) =>
  Effect.gen(function* () {
    const fiber = yield* ask(input).pipe(Effect.forkScoped)
    yield* Effect.sleep("60 millis")
    const items = yield* list()
    return { fiber, items }
  })

it.instance(
  "ordinary edits: a session 'always' still lets later ordinary edits through",
  () =>
    Effect.gen(function* () {
      const first = yield* askPending({ permission: "edit", patterns: ["src/a.ts"], always: ["*"] })
      expect(first.items).toHaveLength(1)
      yield* reply(first.items[0].id, "always")
      yield* Fiber.await(first.fiber)
      expect(yield* askSettlesAllowed({ permission: "edit", patterns: ["src/b.ts"], always: ["*"] })).toBe(true)
    }),
  { git: true },
)

it.instance(
  "config edits keep prompting after a session 'always' on ordinary edits",
  () =>
    Effect.gen(function* () {
      const first = yield* askPending({ permission: "edit", patterns: ["src/a.ts"], always: ["*"] })
      yield* reply(first.items[0].id, "always")
      yield* Fiber.await(first.fiber)
      for (const file of ["opencode.json", "opencode.jsonc", ".mcp.json", "sub/opencode.json", ".opencode/plugins/x.ts"]) {
        expect(yield* askSettlesAllowed({ permission: "edit", patterns: [file], always: ["*"] })).toBe(false)
      }
    }),
  { git: true },
)

it.instance(
  "a session 'always' on a config edit approves only that request",
  () =>
    Effect.gen(function* () {
      const first = yield* askPending({ permission: "edit", patterns: ["opencode.json"], always: ["*"] })
      yield* reply(first.items[0].id, "always")
      yield* Fiber.await(first.fiber)
      // A second config write still prompts: the 'always' did not persist.
      expect(yield* askSettlesAllowed({ permission: "edit", patterns: [".mcp.json"], always: ["*"] })).toBe(false)
    }),
  { git: true },
)

it.instance(
  "a pending config prompt is not unblocked by a later session approval",
  () =>
    Effect.gen(function* () {
      const pendingConfig = yield* askPending({ permission: "edit", patterns: ["opencode.json"], always: ["*"] })
      expect(pendingConfig.items).toHaveLength(1)
      const other = yield* askPending({ permission: "edit", patterns: ["src/a.ts"], always: ["*"] })
      const otherItem = other.items.find((i) => i.patterns[0] === "src/a.ts")
      expect(otherItem).toBeDefined()
      yield* reply(otherItem!.id, "always")
      yield* Fiber.await(other.fiber)
      // The config prompt is still waiting.
      const still = yield* list()
      expect(still.map((i) => i.patterns[0])).toEqual(["opencode.json"])
      yield* reply(still[0].id, "reject")
      yield* Fiber.await(pendingConfig.fiber).pipe(Effect.exit)
    }),
  { git: true },
)

it.instance(
  "shell: a session approval for 'cat *' does not allow a command that writes opencode.json",
  () =>
    Effect.gen(function* () {
      const first = yield* askPending({ permission: "bash", patterns: ["cat README.md"], always: ["cat *"] })
      yield* reply(first.items[0].id, "always")
      yield* Fiber.await(first.fiber)
      expect(yield* askSettlesAllowed({ permission: "bash", patterns: ["cat README.md"], always: ["cat *"] })).toBe(true)
      expect(
        yield* askSettlesAllowed({ permission: "bash", patterns: ["cat notes.txt > opencode.json"], always: ["cat *"] }),
      ).toBe(false)
    }),
  { git: true },
)

it.instance(
  "external directory: a session approval does not cover the app's config folder",
  () =>
    Effect.gen(function* () {
      const dir = "/data/app/files/xdg/config/opencode/*"
      const first = yield* askPending({ permission: "external_directory", patterns: [dir], always: [dir] })
      yield* reply(first.items[0].id, "always")
      yield* Fiber.await(first.fiber)
      expect(yield* askSettlesAllowed({ permission: "external_directory", patterns: [dir], always: [dir] })).toBe(false)
    }),
  { git: true },
)

it.instance(
  "configured deny on a config file still applies, even after a session 'always' on edits",
  () =>
    Effect.gen(function* () {
      const first = yield* askPending({ permission: "edit", patterns: ["src/a.ts"], always: ["*"] })
      yield* reply(first.items[0].id, "always")
      yield* Fiber.await(first.fiber)
      const exit = yield* ask({
        permission: "edit",
        patterns: ["opencode.json"],
        always: ["*"],
        ruleset: [{ permission: "edit", pattern: "opencode.json", action: "deny" }],
      }).pipe(Effect.exit)
      expect(Exit.isFailure(exit)).toBe(true)
      if (Exit.isFailure(exit)) expect(Cause.squash(exit.cause)).toBeInstanceOf(PermissionV1.DeniedError)
    }),
  { git: true },
)

it.instance(
  "a configured allow for plan files (.opencode/plans) still applies: plan mode does not prompt",
  () =>
    Effect.gen(function* () {
      const ruleset = [{ permission: "edit", pattern: ".opencode/plans/*.md", action: "allow" as const }]
      expect(
        yield* askSettlesAllowed({ permission: "edit", patterns: [".opencode/plans/plan.md"], always: ["*"], ruleset }),
      ).toBe(true)
    }),
  { git: true },
)
