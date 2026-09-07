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
)

internal val LocalCodePalette = staticCompositionLocalOf { DarkCodePalette }

/** Roles the chat UI needs that Material's scheme does not name. */
@Immutable
data class ChatPalette(
    /** The user's own turn bubble. */
    val userBubble: Color,
    val onUserBubble: Color,
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
)

internal val DarkChatPalette = ChatPalette(
    userBubble = AccentBlueContainer,
    onUserBubble = AccentOnBlueContainer,
    agentBubble = InkSurface,
    onAgentBubble = InkOnSurface,
    attention = AccentAmber,
    attentionContainer = AccentAmberContainer,
    onAttentionContainer = AccentOnAmberContainer,
    toolContainer = InkSurfaceHigh,
    onToolContainer = InkOnSurface,
    toolBorder = InkOutlineSoft,
    success = AccentTeal,
    muted = InkOnSurfaceMuted,
)

internal val LightChatPalette = ChatPalette(
    userBubble = DayBlueContainer,
    onUserBubble = DayOnBlueContainer,
    agentBubble = PaperSurface,
    onAgentBubble = PaperOnSurface,
    attention = DayAmber,
    attentionContainer = DayAmberContainer,
    onAttentionContainer = DayOnAmberContainer,
    toolContainer = PaperSurfaceHigh,
    onToolContainer = PaperOnSurface,
    toolBorder = PaperOutlineSoft,
    success = DayTeal,
    muted = PaperOnSurfaceMuted,
)

internal val LocalChatPalette = staticCompositionLocalOf { DarkChatPalette }

private val DarkScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = AccentOnBlue,
    primaryContainer = AccentBlueContainer,
    onPrimaryContainer = AccentOnBlueContainer,
    inversePrimary = DayBlue,
    secondary = AccentTeal,
    onSecondary = AccentOnBlue,
    secondaryContainer = AccentTealContainer,
    onSecondaryContainer = AccentOnTealContainer,
    tertiary = AccentAmber,
    onTertiary = AccentOnBlue,
    tertiaryContainer = AccentAmberContainer,
    onTertiaryContainer = AccentOnAmberContainer,
    background = InkBackground,
    onBackground = InkOnSurface,
    surface = InkSurface,
    onSurface = InkOnSurface,
    surfaceVariant = InkSurfaceVariant,
    onSurfaceVariant = InkOnSurfaceMuted,
    surfaceTint = AccentBlue,
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
    primary = DayBlue,
    onPrimary = DayOnBlue,
    primaryContainer = DayBlueContainer,
    onPrimaryContainer = DayOnBlueContainer,
    inversePrimary = AccentBlue,
    secondary = DayTeal,
    onSecondary = Color.White,
    secondaryContainer = DayTealContainer,
    onSecondaryContainer = DayOnTealContainer,
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
    surfaceTint = DayBlue,
    inverseSurface = Color(0xFF2C3240),
    inverseOnSurface = Color(0xFFF1F3F9),
    error = DayRed,
    onError = Color.White,
    errorContainer = DayRedContainer,
    onErrorContainer = DayOnRedContainer,
    outline = PaperOutline,
    outlineVariant = PaperOutlineSoft,
)

private val OpenCodeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
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
