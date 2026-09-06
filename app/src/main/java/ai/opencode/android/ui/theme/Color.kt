package ai.opencode.android.ui.theme

import androidx.compose.ui.graphics.Color

// Palette. Deliberately NOT another product's identity: the Phase 6 brief is a
// conversation-first layout, not ChatGPT's green-teal or Claude's terracotta.
// This is an ink/slate surface with a single cool blue accent, plus a warm amber
// reserved for "the agent needs you" (permission asks, questions) so a blocking
// prompt is distinguishable from an error at a glance and without colour alone
// (the copy always says what it is too).

// ---- dark (default) ----
internal val InkBackground = Color(0xFF0C0F16)
internal val InkSurface = Color(0xFF131822)
internal val InkSurfaceHigh = Color(0xFF1B2230)
internal val InkSurfaceVariant = Color(0xFF232B3B)
internal val InkOnSurface = Color(0xFFE6E9F0)
internal val InkOnSurfaceMuted = Color(0xFFA6AEC2)
internal val InkOutline = Color(0xFF3A4356)
internal val InkOutlineSoft = Color(0xFF262E3D)

internal val AccentBlue = Color(0xFF6FA8FF)
internal val AccentBlueDeep = Color(0xFF2F6FE0)
internal val AccentOnBlue = Color(0xFF07101F)
internal val AccentBlueContainer = Color(0xFF1D3358)
internal val AccentOnBlueContainer = Color(0xFFD6E4FF)

internal val AccentAmber = Color(0xFFFFC46B)
internal val AccentAmberContainer = Color(0xFF4A3413)
internal val AccentOnAmberContainer = Color(0xFFFFE0B0)

internal val AccentTeal = Color(0xFF6FD3C4)
internal val AccentTealContainer = Color(0xFF14393A)
internal val AccentOnTealContainer = Color(0xFFC9F2EC)

internal val AlertRed = Color(0xFFFF8A80)
internal val AlertRedContainer = Color(0xFF4A1D1A)
internal val AlertOnRedContainer = Color(0xFFFFDAD6)

// ---- light ----
internal val PaperBackground = Color(0xFFF7F8FB)
internal val PaperSurface = Color(0xFFFFFFFF)
internal val PaperSurfaceHigh = Color(0xFFEDEFF5)
internal val PaperSurfaceVariant = Color(0xFFE2E6EF)
internal val PaperOnSurface = Color(0xFF14181F)
internal val PaperOnSurfaceMuted = Color(0xFF5A6273)
internal val PaperOutline = Color(0xFFB9C0CF)
internal val PaperOutlineSoft = Color(0xFFDCE1EA)

internal val DayBlue = Color(0xFF1E4FA8)
internal val DayOnBlue = Color(0xFFFFFFFF)
internal val DayBlueContainer = Color(0xFFD9E4FF)
internal val DayOnBlueContainer = Color(0xFF0B2154)

internal val DayAmber = Color(0xFF7A5200)
internal val DayAmberContainer = Color(0xFFFFE0B0)
internal val DayOnAmberContainer = Color(0xFF332100)

internal val DayTeal = Color(0xFF14635A)
internal val DayTealContainer = Color(0xFFC9F2EC)
internal val DayOnTealContainer = Color(0xFF00211D)

internal val DayRed = Color(0xFFA3271C)
internal val DayRedContainer = Color(0xFFFFDAD6)
internal val DayOnRedContainer = Color(0xFF410002)

// ---- syntax highlighting (used by ui/markdown/CodeHighlight.kt) ----
// Two token palettes, one per theme, because a single set cannot keep contrast on
// both an ink background and paper. These are semantic roles, not a brand scheme.
internal val CodeDarkComment = Color(0xFF6B7488)
internal val CodeDarkString = Color(0xFF9FDB8A)
internal val CodeDarkKeyword = Color(0xFF82AAFF)
internal val CodeDarkNumber = Color(0xFFFFB27A)
internal val CodeDarkFunction = Color(0xFFDCC2FF)
internal val CodeDarkType = Color(0xFF6FD3C4)
internal val CodeDarkPunct = Color(0xFFB7BFD0)
internal val CodeDarkPlain = Color(0xFFE6E9F0)

internal val CodeLightComment = Color(0xFF6A7280)
internal val CodeLightString = Color(0xFF2E6B2E)
internal val CodeLightKeyword = Color(0xFF1E4FA8)
internal val CodeLightNumber = Color(0xFF9A4B00)
internal val CodeLightFunction = Color(0xFF6B2FA0)
internal val CodeLightType = Color(0xFF14635A)
internal val CodeLightPunct = Color(0xFF4A5160)
internal val CodeLightPlain = Color(0xFF14181F)

internal val CodeBlockDark = Color(0xFF0A0D13)
internal val CodeBlockLight = Color(0xFFF2F4F9)
