package ai.opencode.android.ui.theme

import androidx.compose.ui.graphics.Color

// Palette, v7: the owner asked for a full visual redesign around a yellow/gold
// identity (reference: a dark, card-based coding-agent UI - deliberately NOT the
// reference's red/pink, and still not another product's colours). The system is
// unchanged from Phase 6 - semantic roles, two themes, code palette separate -
// only the values move:
//   * surfaces go from cool ink/slate to warm charcoal (a gold accent on a blue
//     background looks dirty; on warm near-black it reads as brass on graphite);
//   * the single accent is gold, used for primary actions and "the agent is
//     working" - the product's colour of agency;
//   * "the agent needs YOU" (permission asks, questions) stays a SEPARATE hue, a
//     warm orange, so a blocking ask never disappears into ordinary gold chrome
//     (copy still says what it is - colour is never the only signal);
//   * success/"on device" moves from teal to green (the reference's one idea worth
//     keeping verbatim: green = alive), errors stay red.

// ---- dark (default) ----
internal val InkBackground = Color(0xFF120F0A)
internal val InkSurface = Color(0xFF1B1712)
internal val InkSurfaceHigh = Color(0xFF241E16)
internal val InkSurfaceVariant = Color(0xFF2C2620)
internal val InkOnSurface = Color(0xFFEDE7DA)
internal val InkOnSurfaceMuted = Color(0xFFB3A98F)
internal val InkOutline = Color(0xFF51462D)
internal val InkOutlineSoft = Color(0xFF2F2822)

// Glass edge: the thin top-light line a floating dark pane catches. Used for
// card/composer borders in the dark theme so depth comes from light, not from
// drawn outlines.
internal val InkHairline = Color(0x1FFFFFFF)

internal val AccentGold = Color(0xFFF0C24F)
internal val AccentGoldDeep = Color(0xFFC79A2A)
internal val AccentOnGold = Color(0xFF221803)
internal val AccentGoldContainer = Color(0xFF4A3A12)
internal val AccentOnGoldContainer = Color(0xFFFFE7AC)

internal val AccentAmber = Color(0xFFFFAD70)
internal val AccentAmberContainer = Color(0xFF4C2D12)
internal val AccentOnAmberContainer = Color(0xFFFFD9B8)

internal val AccentGreen = Color(0xFF8FD991)
internal val AccentGreenContainer = Color(0xFF1E3B22)
internal val AccentOnGreenContainer = Color(0xFFD2F2D2)

internal val AlertRed = Color(0xFFFF8A80)
internal val AlertRedContainer = Color(0xFF4A1D1A)
internal val AlertOnRedContainer = Color(0xFFFFDAD6)

// ---- light ----
internal val PaperBackground = Color(0xFFFAF7F0)
internal val PaperSurface = Color(0xFFFFFFFF)
internal val PaperSurfaceHigh = Color(0xFFF1ECDF)
internal val PaperSurfaceVariant = Color(0xFFE8E1CF)
internal val PaperOnSurface = Color(0xFF1C1810)
internal val PaperOnSurfaceMuted = Color(0xFF6B6250)
internal val PaperOutline = Color(0xFFC9BFA6)
internal val PaperOutlineSoft = Color(0xFFE2DAC6)

internal val DayGold = Color(0xFF7A5C00)
internal val DayOnGold = Color(0xFFFFFFFF)
internal val DayGoldContainer = Color(0xFFFFE7AC)
internal val DayOnGoldContainer = Color(0xFF271C00)

internal val DayAmber = Color(0xFF8A4E00)
internal val DayAmberContainer = Color(0xFFFFD9B8)
internal val DayOnAmberContainer = Color(0xFF2E1800)

internal val DayGreen = Color(0xFF2E6B34)
internal val DayGreenContainer = Color(0xFFD2F2D2)
internal val DayOnGreenContainer = Color(0xFF07210B)

internal val DayRed = Color(0xFFA3271C)
internal val DayRedContainer = Color(0xFFFFDAD6)
internal val DayOnRedContainer = Color(0xFF410002)

// ---- syntax highlighting (used by ui/markdown/CodeHighlight.kt) ----
// Two token palettes, one per theme, because a single set cannot keep contrast on
// both a warm-charcoal background and paper. These are semantic roles, not a
// brand scheme - only "keyword" leans toward the brand gold, softened so a wall
// of keywords does not shout at the primary buttons.
internal val CodeDarkComment = Color(0xFF7D7460)
internal val CodeDarkString = Color(0xFFA9D68A)
internal val CodeDarkKeyword = Color(0xFFE6C077)
internal val CodeDarkNumber = Color(0xFFFFB27A)
internal val CodeDarkFunction = Color(0xFFD9BEFF)
internal val CodeDarkType = Color(0xFF93D6BE)
internal val CodeDarkPunct = Color(0xFFC4BBA4)
internal val CodeDarkPlain = Color(0xFFEDE7DA)

internal val CodeLightComment = Color(0xFF6E6553)
internal val CodeLightString = Color(0xFF2E6B2E)
internal val CodeLightKeyword = Color(0xFF7A5C00)
internal val CodeLightNumber = Color(0xFF9A4B00)
internal val CodeLightFunction = Color(0xFF6B2FA0)
internal val CodeLightType = Color(0xFF14635A)
internal val CodeLightPunct = Color(0xFF57503F)
internal val CodeLightPlain = Color(0xFF1C1810)

internal val CodeBlockDark = Color(0xFF0C0A06)
internal val CodeBlockLight = Color(0xFFF4F0E4)

// v8 fix round (owner): diff lines carry their meaning as a line BACKGROUND -
// green behind additions, red behind deletions - the way every code review
// tool renders a patch. The red is a diff semantic, not a theme accent, so it
// does not contradict the "gold, not red" theme rule. Translucent backgrounds
// so they sit correctly on either code-block surface.
internal val DiffDarkAdded = Color(0xFFB9E8A6)
internal val DiffDarkAddedBg = Color(0x3327A148)
internal val DiffDarkRemoved = Color(0xFFF3B8B2)
internal val DiffDarkRemovedBg = Color(0x38E5534B)
internal val DiffLightAdded = Color(0xFF1A6B2F)
internal val DiffLightAddedBg = Color(0x3345C463)
internal val DiffLightRemoved = Color(0xFF9C2318)
internal val DiffLightRemovedBg = Color(0x30F0655D)
