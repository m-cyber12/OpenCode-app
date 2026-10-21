package ai.opencode.android.ui.chat

/**
 * v9.35 (owner): the empty conversation stopped saying the same two static
 * lines forever.
 *
 *  * The headline is one of the owner's 30 messages - VERBATIM, supplied in
 *    the v9.35 feedback - and the pick rotates once per day ("every day, one
 *    of these messages should be chosen at random and displayed"). The pick
 *    is a pure function of the epoch day, so the whole day shows one stable
 *    headline, every render agrees, and the JVM test can pin the behaviour.
 *
 *  * Under it, the FIRST chat of a freshly created project types a subtitle
 *    in character by character, holds it, deletes it the same way - and then
 *    the tip carousel takes over: 30 short tips about the app and about the
 *    craft, typed in, held, deleted, next. Later chats skip the subtitle and
 *    go straight to the tips. The copy lives here, as plain Kotlin, so the
 *    unit tests can pin every string without an Android resource lookup.
 *
 * Pure data + pure functions only: no Compose, no Android imports.
 */
object EmptyChatCopy {

    /** The owner's 30 headline messages, word for word. Do not edit. */
    val HEADLINES: List<String> = listOf(
        "Let's build something great.",
        "What's on your mind?",
        "Turn ideas into reality.",
        "Ready when you are.",
        "Let's make it happen.",
        "Your next idea starts here.",
        "What are we creating today?",
        "Think it. Build it.",
        "Every great project starts somewhere.",
        "Let's get to work.",
        "Your code, your vision.",
        "Let's solve something tricky.",
        "Make your next move.",
        "From bug to breakthrough.",
        "What needs fixing today?",
        "One prompt. Endless possibilities.",
        "Let's turn code into craft.",
        "Build smarter, not harder.",
        "Your next breakthrough awaits.",
        "Small changes. Big impact.",
        "The canvas is yours.",
        "Imagine what's possible.",
        "Make today count.",
        "Let's create the unexpected.",
        "Your ideas deserve to exist.",
        "What's the next big thing?",
        "Start simple. Go further.",
        "A fresh mind. A fresh build.",
        "The next move is yours.",
        "Today is a good day to build.",
    )

    /** One headline per day: stable all day, different the next. */
    fun headlineFor(epochDay: Long): String {
        val index = ((epochDay % HEADLINES.size) + HEADLINES.size) % HEADLINES.size
        return HEADLINES[index.toInt()]
    }

    /**
     * The subtitle a brand-new project's first chat types in under the
     * headline - the polished replacement for the old static body copy.
     */
    const val SUBTITLE: String =
        "Say what should exist, in your own words. The agent plans it, " +
            "writes it, runs it and shows you every step - a full workshop " +
            "in your pocket."

    /** 30 short tips - half about this app, half about the craft. */
    val TIPS: List<String> = listOf(
        "Plan mode first: a minute of planning saves ten of refactoring.",
        "Attach a screenshot - the agent reads pixels better than guesses.",
        "Small prompts, small diffs: ship one change at a time.",
        "The Diff tab shows every line the agent touched, stage by stage.",
        "Long chat feeling slow? Compact it from the menu and keep going.",
        "Name things like you will read them at 3 a.m. - future you says thanks.",
        "The Terminal tab replays every command the agent ran, exit codes and all.",
        "Switch models mid-chat - the capsule up top remembers your favorites.",
        "A failing test is a gift: it tells you exactly where to look.",
        "Ask why, not just what - the agent explains its own code gladly.",
        "GitHub projects get their own branch - experiment without fear.",
        "The sandbox is a real folder on your phone - any file manager opens it.",
        "Commit early, commit often; small commits are easy apologies.",
        "Stuck? Ask the agent to write the test first, then make it pass.",
        "Thinking levels trade speed for depth - pick per question, not per day.",
        "Readable beats clever. Clever is a bug you have not met yet.",
        "The live preview runs on a real local server - what you see is what ships.",
        "Let the agent read the error - paste it whole, stack trace and all.",
        "Delete dead code. The repository remembers it so you do not have to.",
        "One todo list per task: watch the panel tick while the agent works.",
        "Rubber-duck the agent: explaining the bug often reveals the fix.",
        "Add-ons download once and every project reuses them.",
        "Prefer boring technology; excitement belongs in the product.",
        "Undo and redo work on whole turns - rewind a conversation like code.",
        "Say the goal, not the steps - the agent is better at steps than you think.",
        "Measure before you optimize - the slow part is never where you think.",
        "Like or dislike a reply - it sharpens what you ask for next.",
        "Edge cases first: empty, one, many, huge. Most bugs live at the ends.",
        "The best refactor reads like nothing happened - same behavior, calmer code.",
        "Ship the smallest thing that works, then make it beautiful.",
    )

    /** One rendered frame of the typewriter line: the visible text and whether
     * the caret blinks with it. */
    data class Frame(val text: String, val caret: Boolean)

    /**
     * v9.41 (owner item 20): the whole empty-chat cycle as ONE pure function
     * of elapsed time. The old composable drove it with a coroutine that
     * restarted on every (re)entry, so returning to the chat replayed the
     * typing from zero; now the caller samples `frameAt(now - anchor)` and the
     * line simply IS where the clock says it is - mid-type, mid-hold, whatever
     * the moment. [firstChat] prepends the polished subtitle with its long
     * hold; [tipStart] pins which tip the day starts on (the v9.35 rule).
     */
    fun frameAt(elapsedMs: Long, firstChat: Boolean, tipStart: Int = 0): Frame {
        val typeMs = 26L
        val wipeMs = 13L
        val gapMs = 400L
        var t = if (elapsedMs < 0L) 0L else elapsedMs
        var i = 0
        while (true) {
            val line: String
            val hold: Long
            if (firstChat && i == 0) {
                line = SUBTITLE
                hold = 15_000L
            } else {
                val k = if (firstChat) i - 1 else i
                line = TIPS[((tipStart + k) % TIPS.size + TIPS.size) % TIPS.size]
                hold = 7_500L
            }
            val typeLen = line.length * typeMs
            val wipeLen = line.length * wipeMs
            val cycle = typeLen + hold + wipeLen + gapMs
            if (t < cycle) {
                return when {
                    t < typeLen -> Frame(line.substring(0, (t / typeMs).toInt() + 1), caret = true)
                    t < typeLen + hold -> Frame(line, caret = false)
                    t < typeLen + hold + wipeLen ->
                        Frame(line.dropLast(((t - typeLen - hold) / wipeMs).toInt() + 1), caret = true)
                    else -> Frame("", caret = false)
                }
            }
            t -= cycle
            i++
        }
    }
}
