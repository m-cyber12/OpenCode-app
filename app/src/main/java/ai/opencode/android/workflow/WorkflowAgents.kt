package ai.opencode.android.workflow

import org.json.JSONObject

/**
 * Workflow mode (2026-11-27, owner): a manager agent splits a task between workers. Each
 * worker has a name, a duty, and a model. The manager runs on the model chosen in the
 * model selector.
 *
 * Everything lives in the global OpenCode config, under `agents`, so OpenCode itself runs
 * the workers (its `subagent` tool). The app owns only the entries it writes: the manager
 * `workflow` and the `wf-*` workers. Any other agent the user wrote by hand is kept.
 *
 * Pure Kotlin (org.json only) so the JVM tests can check every rule.
 */
data class Worker(val name: String, val duty: String, val model: String)

object WorkflowAgents {
    /** The manager's agent id: a primary agent the prompt selects (`PromptInput.agent`). */
    const val MANAGER = "workflow"

    /** Worker agent ids are `wf-<slug of the name>`. */
    const val PREFIX = "wf-"

    const val MAX_WORKERS = 8
    const val NAME_MAX = 40
    const val DUTY_MAX = 600

    /** The agent id a worker is registered under. Two names that map to one id are refused. */
    fun idFor(name: String): String {
        val slug = name.trim().lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .take(32)
            .trimEnd('-')
        return PREFIX + slug.ifEmpty { "worker" }
    }

    /** Why these workers cannot be saved. Empty when they can. */
    fun problems(workers: List<Worker>): List<String> {
        val out = ArrayList<String>()
        if (workers.size > MAX_WORKERS) out.add("at most $MAX_WORKERS workers")
        val ids = HashSet<String>()
        for (w in workers) {
            val name = w.name.trim()
            if (name.isEmpty()) out.add("a worker has no name")
            if (name.length > NAME_MAX) out.add("a name is longer than $NAME_MAX characters")
            if (name.contains(':')) out.add("a name cannot contain ':' ($name)")
            if (w.duty.trim().isEmpty()) out.add("$name has no duty")
            if (w.duty.trim().length > DUTY_MAX) out.add("the duty of $name is longer than $DUTY_MAX characters")
            if (!validModel(w.model)) out.add("$name has no model")
            if (name.isNotEmpty() && !ids.add(idFor(name))) out.add("two workers would share the id of $name")
        }
        return out
    }

    /** `provider/model`: one slash, not at either end, no spaces. */
    fun validModel(model: String): Boolean =
        model.count { it == '/' } == 1 &&
            model.indexOf('/') > 0 &&
            !model.endsWith("/") &&
            model.none { it.isWhitespace() }

    /** What the manager reads in the subagent list: `name: duty`. */
    fun description(w: Worker): String = "${w.name.trim()}: ${w.duty.trim()}"

    fun managerPrompt(workers: List<Worker>): String = buildString {
        appendLine("You are the manager of a small team of workers. Understand the request, split it into parts,")
        appendLine("give each part to the worker whose duty fits, check what comes back, and report one clear result.")
        appendLine()
        appendLine("Your workers (start one with the subagent tool; the agent is the id in brackets):")
        for (w in workers) appendLine("- ${w.name.trim()} [${idFor(w.name)}]: ${w.duty.trim()}")
        appendLine()
        appendLine("Rules:")
        appendLine("- Give each worker a complete task: the goal, the files, and what to report back. A worker starts with no memory of this chat.")
        appendLine("- Run independent parts together. Wait for the results when one part needs another.")
        appendLine("- Do a part yourself only when no worker fits, and say so.")
        appendLine("- Check each result. If a worker failed or the result is wrong, send it back with the problem.")
        appendLine("- Keep changes small, and tell the user which worker did what, in plain words.")
    }

    fun workerPrompt(w: Worker): String =
        "You are ${w.name.trim()}, one worker in a team. Your duty: ${w.duty.trim()}. " +
            "Do only this duty. Use the files and tools you have, then report in a few short sentences " +
            "what you did and what is left."

    /**
     * The `agents` entries the app writes for [workers]. Empty when there are no workers: then
     * the manager is removed as well, and workflow mode cannot be chosen.
     */
    fun additions(workers: List<Worker>): JSONObject {
        val out = JSONObject()
        if (workers.isEmpty()) return out
        out.put(
            MANAGER,
            JSONObject()
                .put("mode", "primary")
                .put("description", "Manager: splits the work between your workers")
                .put("system", managerPrompt(workers)),
        )
        for (w in workers) {
            out.put(
                idFor(w.name),
                JSONObject()
                    .put("mode", "subagent")
                    .put("description", description(w))
                    .put("model", w.model.trim())
                    .put("system", workerPrompt(w)),
            )
        }
        return out
    }

    /** True for the keys the app owns. */
    fun owned(key: String): Boolean = key == MANAGER || key.startsWith(PREFIX)

    /**
     * The new `agents` object: every entry the app does not own is kept as it was, then the
     * app's [additions] are added. Workers removed in the editor disappear this way.
     */
    fun mergeAgents(existing: JSONObject, additions: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = existing.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!owned(k)) out.put(k, existing.get(k))
        }
        val add = additions.keys()
        while (add.hasNext()) {
            val k = add.next()
            out.put(k, additions.get(k))
        }
        return out
    }

    /**
     * The workers in the config, read back from `agents`. Only subagents with the app's prefix
     * and a `name: duty` description count, so a hand-written agent is never shown as a worker.
     * Sorted by name, because JSON keys carry no order.
     */
    fun fromConfig(agents: JSONObject?): List<Worker> {
        if (agents == null) return emptyList()
        val out = ArrayList<Worker>()
        val keys = agents.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!k.startsWith(PREFIX)) continue
            val a = agents.optJSONObject(k) ?: continue
            if (a.optString("mode") != "subagent") continue
            val desc = a.optString("description")
            val split = desc.indexOf(": ")
            if (split <= 0) continue
            out.add(
                Worker(
                    name = desc.substring(0, split),
                    duty = desc.substring(split + 2),
                    model = modelOf(a),
                ),
            )
        }
        return out.sortedBy { it.name }
    }

    /** The model as `provider/model`, whether the config stores it as text or as an object. */
    private fun modelOf(a: JSONObject): String {
        // Check the object form first: optString on an object returns its JSON text, not "".
        a.optJSONObject("model")?.let { obj ->
            val p = obj.optString("providerID")
            val m = obj.optString("modelID")
            return if (p.isEmpty() || m.isEmpty()) "" else "$p/$m"
        }
        return a.optString("model")
    }
}
