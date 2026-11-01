package ai.opencode.android.task

import org.json.JSONArray
import org.json.JSONObject

/**
 * Intent-to-tests (agent.md section 11), first slice.
 *
 * Before a non-trivial build the agent writes `.acceptance/criteria.json`: the
 * request, what is unclear, and one measurable criterion per behaviour. As the
 * work goes, it sets each criterion to pass or fail with the evidence. This
 * object checks the sheet's shape and counts it, and writes
 * `.acceptance/report.txt`.
 *
 * What it does NOT do: run the checks, or read the code. A "pass" is still the
 * agent's claim, backed by evidence the agent wrote. The report says so. A pass
 * without evidence is shown as UNVERIFIED, and the verdict is COMPLETE only when
 * every criterion is a pass with evidence and nothing is unclear. Running the
 * checks from the app is the next step (agent.md "Verification Engine").
 */
object AcceptanceCheck {
    const val DIR = ".acceptance"
    const val SHEET_FILE = "criteria.json"
    const val REPORT_FILE = "report.txt"
    const val MAX_CRITERIA = 30
    const val MAX_UNCLEAR = 10
    private val ID = Regex("[A-Za-z0-9_-]{1,20}")

    enum class Status { OPEN, PASS, FAIL }

    data class Criterion(
        val id: String,
        val text: String,
        val check: String,
        val status: Status,
        val evidence: String,
    ) {
        /** A pass counts only with evidence. */
        val verified: Boolean get() = status == Status.PASS && evidence.isNotEmpty()
    }

    data class Sheet(val request: String, val unclear: List<String>, val criteria: List<Criterion>) {
        val complete: Boolean get() = unclear.isEmpty() && criteria.all { it.verified }
    }

    sealed class Result {
        data class Ok(val sheet: Sheet) : Result()
        data class Invalid(val reason: String) : Result()
    }

    /** Parse and validate the sheet. Anything unclear is rejected with a reason. */
    fun parse(text: String): Result {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Result.Invalid("criteria.json is not valid JSON")
        }
        val request = root.optString("request", "").trim().take(500)
        val unclearArray = root.optJSONArray("unclear") ?: JSONArray()
        if (unclearArray.length() > MAX_UNCLEAR) return Result.Invalid("at most $MAX_UNCLEAR unclear items")
        val unclear = ArrayList<String>()
        for (i in 0 until unclearArray.length()) {
            val item = unclearArray.optString(i, "").trim()
            if (item.isEmpty() || item.length > 300) {
                return Result.Invalid("unclear item ${i + 1} must be 1-300 characters")
            }
            unclear += item
        }
        val array = root.optJSONArray("criteria") ?: return Result.Invalid("criteria must be an array")
        if (array.length() == 0) return Result.Invalid("criteria is empty: write the criteria before coding")
        if (array.length() > MAX_CRITERIA) return Result.Invalid("at most $MAX_CRITERIA criteria")
        val seen = HashSet<String>()
        val criteria = ArrayList<Criterion>(array.length())
        for (i in 0 until array.length()) {
            val n = i + 1
            val o = array.optJSONObject(i) ?: return Result.Invalid("criterion $n is not an object")
            val id = o.optString("id", "")
            if (!ID.matches(id)) return Result.Invalid("criterion $n: id must be 1-20 letters, digits, '-' or '_'")
            if (!seen.add(id)) return Result.Invalid("criterion $n: duplicate id $id")
            val body = o.optString("text", "").trim()
            if (body.isEmpty() || body.length > 300) return Result.Invalid("$id: text must be 1-300 characters")
            val status = when (o.optString("status", "")) {
                "open" -> Status.OPEN
                "pass" -> Status.PASS
                "fail" -> Status.FAIL
                else -> return Result.Invalid("$id: status must be open, pass or fail")
            }
            criteria += Criterion(
                id = id,
                text = body,
                check = o.optString("check", "").trim().take(200),
                status = status,
                evidence = o.optString("evidence", "").trim().take(500),
            )
        }
        return Result.Ok(Sheet(request, unclear, criteria))
    }

    /** The text written to `.acceptance/report.txt`. The first line is the verdict. */
    fun report(result: Result): String = when (result) {
        is Result.Invalid -> "ACCEPTANCE INVALID: ${result.reason}\n"
        is Result.Ok -> report(result.sheet)
    }

    fun report(sheet: Sheet): String {
        val c = sheet.criteria
        val verdict = if (sheet.complete) "COMPLETE" else "INCOMPLETE"
        val sb = StringBuilder()
        sb.append(
            "ACCEPTANCE $verdict pass=${c.count { it.verified }} open=${c.count { it.status == Status.OPEN }} " +
                "fail=${c.count { it.status == Status.FAIL }} " +
                "unverified=${c.count { it.status == Status.PASS && !it.verified }} " +
                "unclear=${sheet.unclear.size}\n",
        )
        for (x in c) {
            val mark = when {
                x.verified -> "pass"
                x.status == Status.PASS -> "UNVERIFIED"
                x.status == Status.FAIL -> "FAIL"
                else -> "open"
            }
            sb.append("  ${x.id} $mark ${x.text}")
            if (x.check.isNotEmpty()) sb.append(" [check: ${x.check}]")
            if (x.evidence.isNotEmpty()) sb.append(" -- ${x.evidence}")
            sb.append('\n')
        }
        if (sheet.unclear.isNotEmpty()) {
            sb.append("UNCLEAR (ask the user before you finish)\n")
            sheet.unclear.forEach { sb.append("  - $it\n") }
        }
        sb.append("The app checks this file's shape and counts, not the code. A pass is your claim plus its evidence.\n")
        return sb.toString()
    }
}
