package ai.opencode.android.client.v2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings view of a config file's policy. The server applies the legacy `permission` block
 * and then the native `permissions` rules; the view must agree, or the ask-by-default write
 * overrides a value the user set on purpose (see OpenCodeRepository.enforceAskDefaults).
 */
class V2PolicyTest {

    private fun rule(action: String, resource: String, effect: String) =
        JSONObject().put("action", action).put("resource", resource).put("effect", effect)

    /** A legacy `bash: allow` with no native rule is shown as allow, so no ask default is written over it. */
    @Test
    fun legacyValueIsShownWhenNoNativeRuleNamesTheAction() {
        val legacy = JSONObject().put("bash", "allow")
        val view = V2Mapping.effectivePolicy(legacy, null)
        assertEquals("allow", view.getString("bash"))
        assertFalse(view.has("shell"))
    }

    /** A native rule after the legacy block wins for the action it names. */
    @Test
    fun nativeRuleWinsOverLegacyForTheSameAction() {
        val legacy = JSONObject().put("bash", "allow")
        val rules = JSONArray().put(rule("shell", "*", "ask"))
        assertEquals("ask", V2Mapping.effectivePolicy(legacy, rules).getString("bash"))
    }

    /** Legacy `write` is the `edit` action on v2, so the view shows it under `edit`. */
    @Test
    fun legacyWriteIsShownAsEdit() {
        val view = V2Mapping.effectivePolicy(JSONObject().put("write", "deny"), null)
        assertEquals("deny", view.getString("edit"))
        assertFalse(view.has("write"))
    }

    /** Per-pattern rules from both sides are kept; the native one wins for the same pattern. */
    @Test
    fun objectRulesMergeByPattern() {
        val legacy = JSONObject().put("bash", JSONObject().put("rm *", "deny").put("ls *", "allow"))
        val rules = JSONArray().put(rule("shell", "ls *", "ask")).put(rule("shell", "git *", "allow"))
        val bash = V2Mapping.effectivePolicy(legacy, rules).getJSONObject("bash")
        assertEquals("deny", bash.getString("rm *"))
        assertEquals("ask", bash.getString("ls *"))
        assertEquals("allow", bash.getString("git *"))
    }

    /** A string `permission` applies to every policy action in the view. */
    @Test
    fun stringPermissionAppliesToEveryPolicyAction() {
        val view = V2Mapping.effectivePolicy("allow", null)
        for (key in listOf("bash", "edit", "read", "webfetch", "external_directory")) {
            assertEquals("allow", view.getString(key))
        }
    }

    /** No legacy block and no native rules: nothing is shown, so the ask defaults are written. */
    @Test
    fun emptyFileShowsNothing() {
        val view = V2Mapping.effectivePolicy(null, null)
        assertTrue(view.length() == 0)
    }
}
