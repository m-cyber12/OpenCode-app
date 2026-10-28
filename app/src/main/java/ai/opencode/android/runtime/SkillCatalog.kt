package ai.opencode.android.runtime

import java.io.File

/**
 * v11 (owner, B.107): skills, the way Claude Code users know them. A skill is a
 * folder holding a `SKILL.md`: a short header that says WHEN to use it (name and
 * description), then the instructions. The agent sees only the header up front
 * and loads the rest on demand, so a large skill costs little until it is needed.
 *
 * This object is the app's own view of them, for the Settings card: where they
 * are found (the same folders upstream searches: a skill or skills folder under
 * each config directory, holding SKILL.md files), what each header says, and how a new one is
 * written. Plain file IO with no Android types, so it is unit-tested on the JVM.
 */
object SkillCatalog {

    /** One skill as the Settings card shows it. */
    data class Skill(
        val name: String,
        val description: String,
        /** `project` (this project's `.opencode/`) or `global` (every project). */
        val scope: String,
        val file: File,
    )

    /** The outcome of [create]. */
    sealed class Created {
        data class Ok(val file: File) : Created()
        data class Invalid(val reason: String) : Created()
        data class Exists(val name: String) : Created()
    }

    /** Folder names upstream accepts, both spellings. */
    private val FOLDERS = listOf("skills", "skill")

    /** How deep below a skills folder a SKILL.md may sit (`skills/<a>/<b>/SKILL.md`). */
    private const val MAX_DEPTH = 4

    /** Header bytes read per file: a header is short, and a huge file is not read whole. */
    private const val HEADER_BYTES = 8_000

    /** The project-level skills folders (`<project>/.opencode/skills`). */
    fun projectRoots(projectDir: File): List<File> =
        FOLDERS.map { File(projectDir, ".opencode/$it") }

    /** The global skills folders, inside the app's global config directory. */
    fun globalRoots(globalConfigDir: File): List<File> =
        FOLDERS.map { File(globalConfigDir, it) }

    /**
     * Every skill visible to the active project: its own first, then the global
     * ones. Sorted by name inside each scope. A folder that does not exist is
     * simply empty; nothing here can throw into the UI.
     */
    fun scan(projectDir: File?, globalConfigDir: File): List<Skill> {
        val project = projectDir?.let { dir -> projectRoots(dir).flatMap { scanRoot(it, "project") } }
            .orEmpty()
        val global = globalRoots(globalConfigDir).flatMap { scanRoot(it, "global") }
        return (project.sortedBy { it.name.lowercase() } + global.sortedBy { it.name.lowercase() })
    }

    private fun scanRoot(root: File, scope: String): List<Skill> {
        if (!root.isDirectory) return emptyList()
        return runCatching {
            root.walkTopDown()
                .maxDepth(MAX_DEPTH)
                .filter { it.isFile && it.name == "SKILL.md" }
                .map { parse(it, scope) }
                .toList()
        }.getOrDefault(emptyList())
    }

    /** Read one SKILL.md's header. A missing name falls back to the folder name. */
    fun parse(file: File, scope: String): Skill {
        val head = runCatching {
            file.bufferedReader().use { reader ->
                val buf = CharArray(HEADER_BYTES)
                val n = reader.read(buf)
                if (n <= 0) "" else String(buf, 0, n)
            }
        }.getOrDefault("")
        val header = frontmatter(head)
        val name = header["name"]?.takeIf { it.isNotBlank() } ?: file.parentFile?.name.orEmpty()
        return Skill(name = name, description = header["description"].orEmpty(), scope = scope, file = file)
    }

    /**
     * The `key: value` pairs of a leading `---` block. Only top-level scalars are
     * read; nested YAML is skipped, which is all a skill header needs. Quotes are
     * removed, and `\"` / `\\` inside a quoted value are unescaped.
     */
    fun frontmatter(text: String): Map<String, String> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap()
        val close = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (close < 0) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in lines.subList(1, close + 1)) {
            if (line.startsWith(" ") || line.startsWith("\t")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            out[key] = unquote(line.substring(colon + 1).trim())
        }
        return out
    }

    private fun unquote(raw: String): String {
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        if (raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
            return raw.substring(1, raw.length - 1)
        }
        return raw
    }

    /** A folder-safe skill name: lower-case letters, digits and single dashes. */
    fun sanitizeName(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)

    /**
     * Write a new GLOBAL skill (`<global>/skills/<name>/SKILL.md`). Refuses an
     * empty name or description, and never overwrites an existing skill: the
     * owner's own file is never replaced by this screen.
     */
    fun create(globalConfigDir: File, rawName: String, description: String, body: String): Created {
        val name = sanitizeName(rawName)
        if (name.isEmpty()) return Created.Invalid("name")
        val oneLine = description.replace('\n', ' ').replace('\r', ' ').trim()
        if (oneLine.isEmpty()) return Created.Invalid("description")
        val dir = File(File(globalConfigDir, "skills"), name)
        val file = File(dir, "SKILL.md")
        if (file.exists()) return Created.Exists(name)
        if (!dir.isDirectory && !dir.mkdirs()) return Created.Invalid("folder")
        val quoted = "\"" + oneLine.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        file.writeText("---\nname: $name\ndescription: $quoted\n---\n\n${body.trim()}\n")
        return Created.Ok(file)
    }
}
