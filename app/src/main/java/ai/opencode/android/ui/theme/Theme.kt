package ai.opencode.android.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The colour roles the syntax highlighter needs.
 *
 * `ui/markdown/CodeHighlight.kt` is pure Kotlin (no androidx) so it can be unit
 * tested on the JVM; it returns token ROLES. This palette is the only place those
 * roles become colours, and it is theme-aware so code stays readable in both.
 */
@Immutable
data class CodePalette(
    val plain: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val function: Color,
    val type: Color,
    val punctuation: Color,
    val blockBackground: Color,
    /** Foreground for an added diff line ("+"). */
    val diffAdded: Color,
    /** Line background behind an added diff line - green, per the owner's brief. */
    val diffAddedBackground: Color,
    /** Foreground for a removed diff line ("-"). */
    val diffRemoved: Color,
    /** Line background behind a removed diff line - red, per the owner's brief. */
    val diffRemovedBackground: Color,
)

internal val DarkCodePalette = CodePalette(
    plain = CodeDarkPlain,
    keyword = CodeDarkKeyword,
    string = CodeDarkString,
    comment = CodeDarkComment,
    number = CodeDarkNumber,
    function = CodeDarkFunction,
    type = CodeDarkType,
    punctuation = CodeDarkPunct,
    blockBackground = CodeBlockDark,
    diffAdded = DiffDarkAdded,
    diffAddedBackground = DiffDarkAddedBg,
    diffRemoved = DiffDarkRemoved,
    diffRemovedBackground = DiffDarkRemovedBg,
)

internal val LightCodePalette = CodePalette(
    plain = CodeLightPlain,
    keyword = CodeLightKeyword,
    string = CodeLightString,
    comment = CodeLightComment,
    number = CodeLightNumber,
    function = CodeLightFunction,
    type = CodeLightType,
    punctuation = CodeLightPunct,
    blockBackground = CodeBlockLight,
    diffAdded = DiffLightAdded,
    diffAddedBackground = DiffLightAddedBg,
    diffRemoved = DiffLightRemoved,
    diffRemovedBackground = DiffLightRemovedBg,
)

internal val LocalCodePalette = staticCompositionLocalOf { DarkCodePalette }

/** Roles the chat UI needs that Material's scheme does not name. */
@Immutable
data class ChatPalette(
    /**
     * The user's own turn bubble. A quiet neutral (the reference direction): the
     * user's words matter, the bubble itself should not shout.
     */
    val userBubble: Color,
    val onUserBubble: Color,
    /** "This row is the active/selected one": gold-tinted, used by lists only. */
    val selectedContainer: Color,
    /** The assistant's turn surface (near-background, so text is the focus). */
    val agentBubble: Color,
    val onAgentBubble: Color,
    /** "The agent needs an answer from you": permission asks and questions. */
    val attention: Color,
    val attentionContainer: Color,
    val onAttentionContainer: Color,
    /** Tool-call cards: neutral, so a tool never competes with prose. */
    val toolContainer: Color,
    val onToolContainer: Color,
    val toolBorder: Color,
    val success: Color,
    val muted: Color,
    /** Bright end of the golden accent gradient (send circle, active tab, identity tile). */
    val goldBright: Color,
    /** Deep end of the golden accent gradient. */
    val goldDeep: Color,
    /** Top of the screen backdrop (true black in the dark theme). */
    val backdropTop: Color,
    /** The faint golden bloom the backdrop fades into at the base. */
    val backdropGlow: Color,
)

internal val DarkChatPalette = ChatPalette(
    userBubble = InkSurfaceVariant,
    onUserBubble = InkOnSurface,
    selectedContainer = AccentGoldContainer,
    agentBubble = InkSurface,
    onAgentBubble = InkOnSurface,
    attention = AccentAmber,
    attentionContainer = AccentAmberContainer,
    onAttentionContainer = AccentOnAmberContainer,
    toolContainer = InkSurfaceHigh,
    onToolContainer = InkOnSurface,
    toolBorder = InkHairline,
    success = AccentGreen,
    muted = InkOnSurfaceMuted,
    goldBright = AccentGold,
    goldDeep = AccentGoldDeep,
    backdropTop = InkBackground,
    backdropGlow = InkGlowGold,
)

internal val LightChatPalette = ChatPalette(
    userBubble = PaperSurfaceVariant,
    onUserBubble = PaperOnSurface,
    selectedContainer = DayGoldContainer,
    agentBubble = PaperSurface,
    onAgentBubble = PaperOnSurface,
    attention = DayAmber,
    attentionContainer = DayAmberContainer,
    onAttentionContainer = DayOnAmberContainer,
    toolContainer = PaperSurfaceHigh,
    onToolContainer = PaperOnSurface,
    toolBorder = PaperOutlineSoft,
    success = DayGreen,
    muted = PaperOnSurfaceMuted,
    goldBright = DayGold,
    goldDeep = DayGoldDeep,
    backdropTop = PaperBackground,
    backdropGlow = PaperGlowGold,
)

internal val LocalChatPalette = staticCompositionLocalOf { DarkChatPalette }

private val DarkScheme = darkColorScheme(
    primary = AccentGold,
    onPrimary = AccentOnGold,
    primaryContainer = AccentGoldContainer,
    onPrimaryContainer = AccentOnGoldContainer,
    inversePrimary = DayGold,
    secondary = AccentGreen,
    onSecondary = AccentOnGold,
    secondaryContainer = AccentGreenContainer,
    onSecondaryContainer = AccentOnGreenContainer,
    tertiary = AccentAmber,
    onTertiary = AccentOnGold,
    tertiaryContainer = AccentAmberContainer,
    onTertiaryContainer = AccentOnAmberContainer,
    background = InkBackground,
    onBackground = InkOnSurface,
    surface = InkSurface,
    onSurface = InkOnSurface,
    surfaceVariant = InkSurfaceVariant,
    onSurfaceVariant = InkOnSurfaceMuted,
    surfaceTint = AccentGold,
    inverseSurface = InkOnSurface,
    inverseOnSurface = InkBackground,
    error = AlertRed,
    onError = Color(0xFF3B0906),
    errorContainer = AlertRedContainer,
    onErrorContainer = AlertOnRedContainer,
    outline = InkOutline,
    outlineVariant = InkOutlineSoft,
)

private val LightScheme = lightColorScheme(
    primary = DayGold,
    onPrimary = DayOnGold,
    primaryContainer = DayGoldContainer,
    onPrimaryContainer = DayOnGoldContainer,
    inversePrimary = AccentGold,
    secondary = DayGreen,
    onSecondary = Color.White,
    secondaryContainer = DayGreenContainer,
    onSecondaryContainer = DayOnGreenContainer,
    tertiary = DayAmber,
    onTertiary = Color.White,
    tertiaryContainer = DayAmberContainer,
    onTertiaryContainer = DayOnAmberContainer,
    background = PaperBackground,
    onBackground = PaperOnSurface,
    surface = PaperSurface,
    onSurface = PaperOnSurface,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = PaperOnSurfaceMuted,
    surfaceTint = DayGold,
    inverseSurface = Color(0xFF332C1E),
    inverseOnSurface = Color(0xFFF7F3E8),
    error = DayRed,
    onError = Color.White,
    errorContainer = DayRedContainer,
    onErrorContainer = DayOnRedContainer,
    outline = PaperOutline,
    outlineVariant = PaperOutlineSoft,
)

// Phase 10 polish: one step softer everywhere. The Phase 6 radii were slightly
// angular next to the surfaces they sit on (a tool card inside a list item inside
// a rounded screen), and this project's visual language is "calm, modern chat
// app" - generous whitespace and soft surfaces - fused with terminal affordances
// for anything the agent actually executes. Radii move up, nothing else does:
// no colour role, no layout role and no test tag is touched by this change, which
// is what keeps the Phase 6 gate suite (F1-F4, U1-U8) meaningful across it.
private val OpenCodeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * App theme. Dark by default (a terminal-adjacent product reads better on a
 * phone at night), Material You on Android 12+ unless the caller turns it off -
 * the chat palettes above are provided either way so tool cards, asks and code
 * keep their meaning under a dynamic scheme.
 */
@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // The SDK check stays inline in the branch expression: Material You is API 31+
    // and minSdk is 29, and the platform's own analysis (and any future lint run)
    // only recognises the guard when it reads it here rather than through a val.
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(
        LocalCodePalette provides if (darkTheme) DarkCodePalette else LightCodePalette,
        LocalChatPalette provides if (darkTheme) DarkChatPalette else LightChatPalette,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = OpenCodeTypography,
            shapes = OpenCodeShapes,
            content = content,
        )
    }
}

/** Convenience accessor used by the chat components. */
object ChatTheme {
    val chat: ChatPalette
        @Composable @ReadOnlyComposable get() = LocalChatPalette.current
    val code: CodePalette
        @Composable @ReadOnlyComposable get() = LocalCodePalette.current
}

/**
 * v9 premium pass: the screen backdrop. True black through most of the screen,
 * settling into a faint golden bloom at the base - the reference's signature
 * "the interface floats over a glow" move, in this product's gold. A static
 * brush, not an animation: the glow never pulses (the finite-animation rule).
 */
@Composable
@ReadOnlyComposable
fun goldenBackdrop(): Brush {
    val chat = LocalChatPalette.current
    return Brush.verticalGradient(
        0.00f to chat.backdropTop,
        0.62f to chat.backdropTop,
        1.00f to chat.backdropGlow,
    )
}

/** The golden accent gradient: bright brass into deep brass, top-left to bottom-right. */
@Composable
@ReadOnlyComposable
fun goldAccentBrush(): Brush {
    val chat = LocalChatPalette.current
    return Brush.linearGradient(listOf(chat.goldBright, chat.goldDeep))
}
