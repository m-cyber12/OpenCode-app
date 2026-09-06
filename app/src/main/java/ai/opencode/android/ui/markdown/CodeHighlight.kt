package ai.opencode.android.ui.markdown

/**
 * Syntax highlighting for code blocks and tool output, without a dependency.
 *
 * Like [Markdown], this file is pure Kotlin: it maps source text to a list of
 * [CodeSpan]s tagged with a token ROLE, and the Compose layer (`MarkdownText.kt`)
 * is the only place a role becomes a colour. That split is what lets the
 * tokeniser be covered by JVM unit tests with real fixtures instead of only being
 * exercised on a device, and it keeps `ui/theme` free of parsing logic.
 *
 * Scope, honestly: this is a lightweight lexical tokeniser, not a grammar. It
 * knows line and block comments, strings (including triple-quoted and template
 * literals), numbers, identifiers classified against a per-language keyword set,
 * call sites, capitalised type names and punctuation runs. It does not resolve
 * scopes, so a keyword-looking identifier inside a construct it does not model can
 * be coloured as a keyword. Getting that wrong is a cosmetic failure and never
 * rewrites the text: the concatenation of every span's text is always byte-equal
 * to the input, which is what the unit tests assert.
 *
 * `diff`/`patch` output is special-cased because an agent's file edits arrive that
 * way and the sign is the meaning: added lines take the string role (green),
 * removed lines the number role (amber/orange), headers the type role.
 */
enum class CodeToken { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, FUNCTION, TYPE, PUNCTUATION }

data class CodeSpan(val text: String, val token: CodeToken)

object CodeHighlight {

    /** Canonical language ids this tokeniser has a keyword set or rule for. */
    private val ALIASES: Map<String, String> = mapOf(
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "node" to "javascript",
        "jsx" to "javascript", "ts" to "typescript", "tsx" to "typescript",
        "py" to "python", "python3" to "python", "py3" to "python",
        "sh" to "bash", "shell" to "bash", "zsh" to "bash", "console" to "bash", "shellsession" to "bash",
        "kts" to "kotlin", "kt" to "kotlin",
        "rs" to "rust", "golang" to "go", "c++" to "cpp", "cc" to "cpp", "cxx" to "cpp",
        "h" to "c", "hpp" to "cpp", "hh" to "cpp",
        "cs" to "csharp", "rb" to "ruby", "yml" to "yaml", "md" to "markdown",
        "html" to "xml", "htm" to "xml", "svg" to "xml", "vue" to "xml",
        "patch" to "diff", "dockerfile" to "bash", "make" to "makefile", "mk" to "makefile",
        "gradle" to "kotlin", "properties" to "ini", "conf" to "ini", "env" to "ini",
        "text" to "plain", "txt" to "plain", "log" to "plain", "" to "plain",
    )

    /** Languages with a real keyword set (everything else still gets comments/strings). */
    private val KEYWORDS: Map<String, Set<String>> = mapOf(
        "kotlin" to setOf(
            "package", "import", "val", "var", "fun", "class", "object", "interface", "return", "if", "else",
            "when", "for", "while", "do", "break", "continue", "null", "true", "false", "this", "super", "is",
            "as", "in", "out", "by", "where", "data", "sealed", "enum", "companion", "internal", "private",
            "public", "protected", "override", "open", "lateinit", "const", "suspend", "inline", "noinline",
            "crossinline", "typealias", "annotation", "constructor", "init", "get", "set", "throw", "try",
            "catch", "finally", "it", "reified", "external", "operator", "infix", "tailrec", "vararg",
        ),
        "java" to setOf(
            "public", "private", "protected", "class", "interface", "enum", "extends", "implements", "static",
            "final", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short", "new",
            "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
            "null", "true", "false", "this", "super", "import", "package", "throws", "throw", "try", "catch",
            "finally", "abstract", "synchronized", "volatile", "transient", "instanceof", "record", "var",
        ),
        "javascript" to setOf(
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch", "case",
            "default", "break", "continue", "new", "class", "extends", "import", "export", "from", "async",
            "await", "yield", "try", "catch", "finally", "throw", "typeof", "instanceof", "null", "undefined",
            "true", "false", "this", "super", "delete", "void", "in", "of", "as", "static", "get", "set",
        ),
        "typescript" to setOf(
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch", "case",
            "default", "break", "continue", "new", "class", "extends", "import", "export", "from", "async",
            "await", "yield", "try", "catch", "finally", "throw", "typeof", "instanceof", "null", "undefined",
            "true", "false", "this", "super", "delete", "void", "in", "of", "as", "static", "get", "set",
            "type", "interface", "enum", "implements", "readonly", "public", "private", "protected", "declare",
            "namespace", "abstract", "keyof", "infer", "satisfies",
        ),
        "python" to setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue", "import",
            "from", "as", "with", "try", "except", "finally", "raise", "pass", "lambda", "None", "True",
            "False", "and", "or", "not", "in", "is", "global", "nonlocal", "yield", "assert", "del", "async",
            "await", "self", "cls",
        ),
        "bash" to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
            "function", "in", "select", "return", "exit", "export", "local", "readonly", "declare", "typeset",
            "echo", "printf", "read", "cd", "set", "unset", "shift", "trap", "source", "eval", "exec", "test",
            "true", "false", "sudo", "adb", "grep", "sed", "awk", "curl", "git",
        ),
        "go" to setOf(
            "package", "import", "func", "var", "const", "type", "struct", "interface", "map", "chan", "go",
            "defer", "return", "if", "else", "for", "range", "switch", "case", "default", "break", "continue",
            "nil", "true", "false", "string", "int", "int64", "float64", "bool", "byte", "error", "make",
            "new", "append", "len", "cap", "select", "fallthrough",
        ),
        "rust" to setOf(
            "fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait", "pub", "use", "mod",
            "crate", "self", "super", "match", "if", "else", "for", "while", "loop", "break", "continue",
            "return", "where", "as", "in", "ref", "move", "dyn", "async", "await", "unsafe", "true", "false",
            "None", "Some", "Ok", "Err", "type", "extern",
        ),
        "sql" to setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
            "table", "alter", "drop", "index", "join", "left", "right", "inner", "outer", "full", "on",
            "group", "by", "order", "having", "limit", "offset", "distinct", "as", "and", "or", "not", "null",
            "primary", "key", "foreign", "references", "default", "union", "all", "case", "when", "then",
            "else", "end", "count", "sum", "avg", "min", "max", "asc", "desc", "with",
        ),
        "json" to setOf("true", "false", "null"),
        "yaml" to setOf("true", "false", "null", "yes", "no", "on", "off"),
        "c" to setOf(
            "int", "char", "float", "double", "void", "long", "short", "unsigned", "signed", "const",
            "static", "struct", "union", "enum", "typedef", "return", "if", "else", "for", "while", "do",
            "switch", "case", "default", "break", "continue", "sizeof", "include", "define", "ifdef",
            "ifndef", "endif", "NULL", "extern", "volatile", "register",
        ),
        "cpp" to setOf(
            "int", "char", "float", "double", "void", "long", "bool", "class", "struct", "public", "private",
            "protected", "template", "typename", "namespace", "using", "new", "delete", "return", "if", "else",
            "for", "while", "do", "switch", "case", "default", "break", "continue", "const", "constexpr",
            "static", "virtual", "override", "nullptr", "true", "false", "auto", "this", "include", "define",
        ),
        "ruby" to setOf(
            "def", "end", "class", "module", "if", "elsif", "else", "unless", "while", "until", "for", "do",
            "begin", "rescue", "ensure", "return", "yield", "require", "include", "attr_accessor", "nil",
            "true", "false", "self", "then", "case", "when",
        ),
        "php" to setOf(
            "function", "class", "public", "private", "protected", "static", "return", "if", "else", "elseif",
            "foreach", "for", "while", "do", "switch", "case", "break", "continue", "new", "echo", "print",
            "require", "include", "namespace", "use", "null", "true", "false", "array", "const", "var",
        ),
        "swift" to setOf(
            "func", "let", "var", "class", "struct", "enum", "protocol", "extension", "return", "if", "else",
            "guard", "for", "while", "switch", "case", "default", "break", "continue", "nil", "true", "false",
            "import", "self", "init", "deinit", "public", "private", "internal", "static", "override",
        ),
        "csharp" to setOf(
            "using", "namespace", "class", "struct", "interface", "enum", "public", "private", "protected",
            "internal", "static", "void", "int", "string", "bool", "var", "new", "return", "if", "else",
            "for", "foreach", "while", "do", "switch", "case", "break", "continue", "null", "true", "false",
            "async", "await", "try", "catch", "finally", "throw", "override", "virtual", "abstract", "sealed",
        ),
    )

    /** Languages whose line comment is `#` (rather than `//`). */
    private val HASH_COMMENT = setOf(
        "bash", "python", "ruby", "yaml", "toml", "ini", "makefile", "r", "perl", "elixir", "shell",
    )

    /** Languages whose line comment is `//` and that support block comments. */
    private val SLASH_COMMENT = setOf(
        "kotlin", "java", "javascript", "typescript", "go", "rust", "c", "cpp", "csharp", "swift", "php",
        "scala", "dart", "css", "scss", "less", "jsonc", "proto", "gradle",
    )

    /** `--` starts a line comment. */
    private val DASH_COMMENT = setOf("sql", "lua", "haskell")

    /** Languages where a backtick starts a template literal. */
    private val TEMPLATE_LITERAL = setOf("javascript", "typescript")

    /** Languages with `"""` / `'''` multi-line strings. */
    private val TRIPLE_STRING = setOf("python")

    fun language(raw: String): String {
        val key = raw.trim().lowercase()
        return ALIASES[key] ?: key
    }

    /**
     * Whether the language is one this tokeniser models. Everything else is still
     * tokenised generically (comments, strings, numbers, punctuation) - the UI uses
     * this only to decide whether to advertise the language name.
     */
    fun isSupported(raw: String): Boolean {
        val l = language(raw)
        return KEYWORDS.containsKey(l) || l in HASH_COMMENT || l in SLASH_COMMENT || l in DASH_COMMENT ||
            l == "xml" || l == "diff" || l == "plain"
    }

    /** Tokenise [source]; the concatenation of the returned spans equals the input. */
    fun tokenize(rawLanguage: String, source: String): List<CodeSpan> {
        val lang = language(rawLanguage)
        val spans = if (lang == "diff") diffSpans(source) else lexical(lang, source)
        return merge(spans)
    }

    // ---- diff / patch ------------------------------------------------------

    private fun diffSpans(source: String): List<CodeSpan> {
        val out = ArrayList<CodeSpan>()
        for (line in source.split("\n")) {
            val token = when {
                line.startsWith("+++") || line.startsWith("---") -> CodeToken.TYPE
                line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("new file") ||
                    line.startsWith("deleted file") || line.startsWith("similarity ") ||
                    line.startsWith("rename ") -> CodeToken.TYPE
                line.startsWith("@@") -> CodeToken.FUNCTION
                line.startsWith("+") -> CodeToken.STRING
                line.startsWith("-") -> CodeToken.NUMBER
                line.startsWith(" ") -> CodeToken.PLAIN
                else -> CodeToken.COMMENT
            }
            out.add(CodeSpan(line, token))
            out.add(CodeSpan("\n", CodeToken.PLAIN))
        }
        if (out.isNotEmpty() && out.last().text == "\n") out.removeAt(out.size - 1)
        return out
    }

    // ---- generic lexical scan ---------------------------------------------

    private fun lexical(lang: String, source: String): List<CodeSpan> {
        val out = ArrayList<CodeSpan>()
        val keywords = KEYWORDS[lang] ?: emptySet()
        val hash = lang in HASH_COMMENT
        val slash = lang in SLASH_COMMENT
        val dash = lang in DASH_COMMENT
        val xml = lang == "xml"
        val template = lang in TEMPLATE_LITERAL
        val triple = lang in TRIPLE_STRING
        val n = source.length
        var i = 0

        fun add(text: String, token: CodeToken) {
            if (text.isNotEmpty()) out.add(CodeSpan(text, token))
        }

        while (i < n) {
            val c = source[i]

            // Comments.
            if (slash && c == '/' && i + 1 < n && source[i + 1] == '/') {
                val end = lineEnd(source, i)
                add(source.substring(i, end), CodeToken.COMMENT)
                i = end
                continue
            }
            if (slash && c == '/' && i + 1 < n && source[i + 1] == '*') {
                val close = source.indexOf("*/", i + 2)
                val end = if (close < 0) n else close + 2
                add(source.substring(i, end), CodeToken.COMMENT)
                i = end
                continue
            }
            if (hash && c == '#') {
                val end = lineEnd(source, i)
                add(source.substring(i, end), CodeToken.COMMENT)
                i = end
                continue
            }
            if (dash && c == '-' && i + 1 < n && source[i + 1] == '-') {
                val end = lineEnd(source, i)
                add(source.substring(i, end), CodeToken.COMMENT)
                i = end
                continue
            }
            if (xml && c == '<' && source.startsWith("<!--", i)) {
                val close = source.indexOf("-->", i + 4)
                val end = if (close < 0) n else close + 3
                add(source.substring(i, end), CodeToken.COMMENT)
                i = end
                continue
            }

            // Strings.
            if (triple && (c == '"' || c == '\'') && source.startsWith("$c$c$c", i)) {
                val marker = "$c$c$c"
                val close = source.indexOf(marker, i + 3)
                val end = if (close < 0) n else close + 3
                add(source.substring(i, end), CodeToken.STRING)
                i = end
                continue
            }
            if (c == '"' || c == '\'' || (template && c == '`')) {
                var k = i + 1
                while (k < n) {
                    if (source[k] == '\\') {
                        k += 2
                        continue
                    }
                    if (source[k] == c) {
                        k += 1
                        break
                    }
                    // An unterminated quote on this line is not a string: stop at the
                    // newline rather than swallowing the rest of the file.
                    if (source[k] == '\n' && !(template && c == '`')) break
                    k += 1
                }
                val end = minOf(k, n)
                // In JSON a quoted member name is a key, not a value: upstream's own
                // `json` blocks (config, tool input) read better that way.
                val tok = if (lang == "json" && c == '"' && isJsonKey(source, end)) CodeToken.TYPE else CodeToken.STRING
                add(source.substring(i, end), tok)
                i = end
                continue
            }

            // Numbers (including hex/binary and separators).
            if (c.isDigit()) {
                var k = i
                while (k < n && (source[k].isLetterOrDigit() || source[k] == '_' || source[k] == '.')) k += 1
                add(source.substring(i, k), CodeToken.NUMBER)
                i = k
                continue
            }

            // Identifiers and keywords.
            if (c.isLetter() || c == '_') {
                var k = i
                while (k < n && (source[k].isLetterOrDigit() || source[k] == '_' || source[k] == '$')) k += 1
                val word = source.substring(i, k)
                val token = when {
                    word in keywords -> CodeToken.KEYWORD
                    // A capitalised identifier is a type even when it is followed by
                    // `(` - that is a constructor call, and colouring `File(path)` as
                    // a function reads worse than colouring it as the type it names.
                    word[0].isUpperCase() -> CodeToken.TYPE
                    nextNonSpaceIs(source, k, '(') -> CodeToken.FUNCTION
                    else -> CodeToken.PLAIN
                }
                add(word, token)
                i = k
                continue
            }

            // Punctuation runs (`=>`, `::`, `!=`, `&&`).
            if (isPunct(c)) {
                var k = i
                while (k < n && isPunct(source[k])) k += 1
                add(source.substring(i, k), CodeToken.PUNCTUATION)
                i = k
                continue
            }

            // Whitespace and everything unmodelled: plain text.
            add(c.toString(), CodeToken.PLAIN)
            i += 1
        }
        return out
    }

    private fun lineEnd(source: String, from: Int): Int {
        val nl = source.indexOf('\n', from)
        return if (nl < 0) source.length else nl
    }

    private fun nextNonSpaceIs(source: String, from: Int, c: Char): Boolean {
        var k = from
        while (k < source.length && source[k] == ' ') k += 1
        return k < source.length && source[k] == c
    }

    /** True when the next non-space character after [from] is `:` (a JSON key). */
    private fun isJsonKey(source: String, from: Int): Boolean = nextNonSpaceIs(source, from, ':')

    private fun isPunct(c: Char): Boolean =
        c == '(' || c == ')' || c == '{' || c == '}' || c == '[' || c == ']' || c == '<' || c == '>' ||
            c == ',' || c == ';' || c == ':' || c == '.' || c == '=' || c == '+' || c == '-' || c == '*' ||
            c == '/' || c == '%' || c == '!' || c == '?' || c == '&' || c == '|' || c == '^' || c == '~' ||
            c == '@' || c == '#' || c == '$'

    /** Join neighbouring spans with the same role: fewer nodes, identical text. */
    private fun merge(spans: List<CodeSpan>): List<CodeSpan> {
        val out = ArrayList<CodeSpan>(spans.size)
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.token == s.token) {
                out[out.size - 1] = last.copy(text = last.text + s.text)
            } else {
                out.add(s)
            }
        }
        return out
    }

    /** The text a span list renders, which must equal the input (test invariant). */
    fun text(spans: List<CodeSpan>): String = spans.joinToString("") { it.text }
}
