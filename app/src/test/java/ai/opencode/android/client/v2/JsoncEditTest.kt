package ai.opencode.android.client.v2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The comment-preserving config edit and the v1 <-> v2 permission mapping, without a server. */
class JsoncEditTest {

    @Test
    fun replacesOneKeyAndKeepsEveryOtherByte() {
        val text = """
            // my config
            {
              /* the model */
              "model": "x/y", // trailing note
              "share": "disabled"
            }
        """.trimIndent() + "\n"
        val out = JsoncEdit.withKey(text, "share", "\"manual\"")
        assertEquals(text.replace("\"disabled\"", "\"manual\""), out)
        assertEquals("manual", JsoncEdit.parseObject(out).getString("share"))
    }

    @Test
    fun addsMissingKeyAfterTheLastMember() {
        val text = "{\n  \"model\": \"x/y\" // keep me\n}\n"
        val out = JsoncEdit.withKey(text, "permissions", "[]")
        assertTrue(out.contains("// keep me"))
        assertEquals(JSONArray(), JsoncEdit.parseObject(out).getJSONArray("permissions"))
    }

    @Test
    fun addsKeyToAnEmptyObject() {
        val out = JsoncEdit.withKey("{}\n", "a", "1")
        assertEquals(1, JsoncEdit.parseObject(out).getInt("a"))
    }

    @Test
    fun stripsCommentsButNotInsideStrings() {
        val text = "{\"url\": \"https://x.test/a//b\", /* gone */ \"n\": 2,}"
        val obj = JsoncEdit.parseObject(text)
        assertEquals("https://x.test/a//b", obj.getString("url"))
        assertEquals(2, obj.getInt("n"))
    }

    @Test
    fun replacesAKeyWhoseValueHoldsBraces() {
        val text = "{\n  \"mcp\": {\"a\": {\"command\": [\"}\"]}},\n  \"z\": 1\n}"
        val out = JsoncEdit.withKey(text, "mcp", "{}")
        val obj = JsoncEdit.parseObject(out)
        assertEquals(0, obj.getJSONObject("mcp").length())
        assertEquals(1, obj.getInt("z"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesANonObjectDocument() {
        JsoncEdit.withKey("[1,2]", "a", "1")
    }

    @Test
    fun shellRenameIsTheOnlyBashRename() {
        assertEquals("shell", V2Mapping.v2Action("bash"))
        assertEquals("bash", V2Mapping.v1Action("shell"))
        assertEquals("edit", V2Mapping.v2Action("write"))
        assertEquals("webfetch", V2Mapping.v2Action("webfetch"))
    }

    @Test
    fun policyWriteReplacesOnlyItsOwnActionsAndKeepsOthers() {
        val existing = JSONArray()
            .put(JSONObject().put("action", "shell").put("resource", "*").put("effect", "allow"))
            .put(JSONObject().put("action", "grep").put("resource", "*").put("effect", "allow"))
        val policy = JSONObject().put("bash", "ask").put("read", JSONObject().put("*.env", "deny"))
        val merged = V2Mapping.mergePermissionRules(existing, policy)

        val rules = (0 until merged.length()).map { merged.getJSONObject(it) }
        assertEquals(listOf("grep", "shell", "read"), rules.map { it.getString("action") })
        assertEquals("ask", rules[1].getString("effect"))
        assertEquals("*.env", rules[2].getString("resource"))
    }

    @Test
    fun readBackGivesTheV1PolicyShape() {
        val rules = JSONArray()
            .put(JSONObject().put("action", "shell").put("resource", "*").put("effect", "ask"))
            .put(JSONObject().put("action", "edit").put("resource", "*.md").put("effect", "allow"))
            .put(JSONObject().put("action", "edit").put("resource", "*").put("effect", "deny"))
        val policy = V2Mapping.permissionPolicy(rules)
        assertEquals("ask", policy.getString("bash"))
        assertEquals("allow", policy.getJSONObject("edit").getString("*.md"))
        assertEquals("deny", policy.getJSONObject("edit").getString("*"))
        assertFalse(policy.has("shell"))
    }
}
