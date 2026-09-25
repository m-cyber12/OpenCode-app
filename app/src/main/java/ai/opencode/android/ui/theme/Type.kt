@file:OptIn(ExperimentalTextApi::class)

package ai.opencode.android.ui.theme

import ai.opencode.android.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Typefaces (v8 beauty pass, owner-picked): Sora for the display voice -
// headlines and titles, the "designed" personality of the app - Space Grotesk
// for everything read at length (body and labels), and JetBrains Mono for code,
// commands and paths. All three are OFL-licensed; the files are bundled under
// res/font so the look does not depend on Play services or a network fetch.
//
// Sora ships as a variable font (google/fonts carries no statics), so each
// weight is instantiated explicitly via the wght axis - supported from API 26,
// and minSdk is 29. Space Grotesk and JetBrains Mono are static instances.
internal val SoraFamily = FontFamily(
    Font(
        R.font.sora_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.sora_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

internal val GroteskFamily = FontFamily(
    Font(R.font.space_grotesk_regular, weight = FontWeight.Normal),
    Font(R.font.space_grotesk_medium, weight = FontWeight.Medium),
    Font(R.font.space_grotesk_bold, weight = FontWeight.Bold),
)

internal val MonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, weight = FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, weight = FontWeight.Medium),
)

// Type scale. Chat-sized: the transcript body is the largest thing on screen,
// labels and metadata are the smallest, and every size is a real step (no two
// roles sharing a size by accident). Sizes are sp so the system font scale
// applies - the Phase 6 brief calls for a 200% font-scale pass, which only means
// something if nothing here is hardcoded in dp.
internal val OpenCodeTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = SoraFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = SoraFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SoraFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = GroteskFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = GroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = GroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = GroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = GroteskFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = GroteskFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = GroteskFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp,
    ),
)

/** Code and tool output: monospace, slightly tighter leading, no ligature surprises. */
internal val MonoBody = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    letterSpacing = 0.sp,
)

internal val MonoSmall = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.sp,
)
