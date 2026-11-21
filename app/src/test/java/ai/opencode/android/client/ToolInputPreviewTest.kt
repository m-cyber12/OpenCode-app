package ai.opencode.android.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolInputPreviewTest {

    @Test
    fun a_finished_string_field_is_read_whole() {
        val json = """{"path":"a/b.kt","content":"fun main() {}"}"""
        assertEquals("a/b.kt", ToolInputPreview.path(json))
        assertEquals("fun main() {}", ToolInputPreview.writeContent(json))
    }

    @Test
    fun an_unfinished_string_field_is_read_up_to_what_has_arrived() {
        val json = """{"path":"a/b.kt","content":"line one\nline tw"""
        assertEquals("line one\nline tw", ToolInputPreview.writeContent(json))
    }

    @Test
    fun a_field_not_started_yet_is_empty_not_guessed() {
        assertEquals("", ToolInputPreview.writeContent("""{"pa"""))
        assertEquals("", ToolInputPreview.writeContent("""{"path":"a.kt","cont"""))
        assertEquals("", ToolInputPreview.writeContent("""{"path":"a.kt","content":"""))
        assertEquals("", ToolInputPreview.writeContent(""))
    }

    @Test
    fun an_escape_split_by_the_stream_is_not_decoded_until_complete() {
        // A lone backslash at the end of the stream is not decoded yet.
        assertEquals("a", ToolInputPreview.writeContent("""{"content":"a\"""))
        // An escaped quote that has arrived is decoded: the string is still open.
        assertEquals("a\"", ToolInputPreview.writeContent("""{"content":"a\""""))
        assertEquals("a", ToolInputPreview.writeContent("""{"content":"a\u00"""))
        assertEquals("é", ToolInputPreview.writeContent("""{"content":"\u00e9"""))
    }

    @Test
    fun escapes_decode_to_the_text_the_model_wrote() {
        val json = """{"content":"say \"hi\"\tand\\go\/now\nnext"}"""
        assertEquals("say \"hi\"\tand\\go/now\nnext", ToolInputPreview.writeContent(json))
    }

    @Test
    fun a_key_inside_a_nested_value_is_not_the_top_level_field() {
        val json = """{"meta":{"path":"wrong.kt"},"path":"right.kt"}"""
        assertEquals("right.kt", ToolInputPreview.path(json))
        assertEquals("", ToolInputPreview.path("""{"meta":{"path":"wrong.kt"}}"""))
    }

    @Test
    fun a_string_value_that_equals_a_key_name_is_not_taken_for_the_key() {
        val json = """{"title":"path","content":"x"}"""
        assertEquals("", ToolInputPreview.path(json))
        assertEquals("x", ToolInputPreview.writeContent(json))
    }

    @Test
    fun a_non_string_field_is_not_read_as_text() {
        assertEquals("", ToolInputPreview.path("""{"path":42}"""))
        assertEquals("", ToolInputPreview.path("""{"path":null}"""))
    }

    @Test
    fun a_brace_inside_a_string_does_not_move_the_depth() {
        val json = """{"content":"} {\"path\":\"fake\"}","path":"real.kt"}"""
        assertEquals("real.kt", ToolInputPreview.path(json))
    }

    @Test
    fun edit_fields_are_read_while_they_arrive() {
        val json = """{"path":"x.kt","oldString":"val a = 1","newString":"val a = 2\nval b = 3"""
        assertEquals("val a = 1", ToolInputPreview.editOld(json))
        assertEquals("val a = 2\nval b = 3", ToolInputPreview.editNew(json))
    }

    @Test
    fun edit_diff_lists_removed_then_added_lines() {
        assertEquals(
            "-old one\n-old two\n+new one",
            ToolInputPreview.editDiff("old one\nold two", "new one"),
        )
    }

    @Test
    fun edit_diff_is_empty_until_there_is_text() {
        assertEquals("", ToolInputPreview.editDiff("", ""))
        assertEquals("+added", ToolInputPreview.editDiff("", "added"))
        assertEquals("-gone", ToolInputPreview.editDiff("gone", ""))
    }

    @Test
    fun line_count_counts_a_partial_last_line() {
        assertEquals(0, ToolInputPreview.lineCount(""))
        assertEquals(1, ToolInputPreview.lineCount("one"))
        assertEquals(3, ToolInputPreview.lineCount("one\ntwo\nthr"))
        assertEquals(2, ToolInputPreview.lineCount("one\n"))
    }

    @Test
    fun a_patch_text_is_read_from_its_field() {
        assertEquals("*** Begin Patch\n+x", ToolInputPreview.patchText("""{"patchText":"*** Begin Patch\n+x"""))
    }
}
