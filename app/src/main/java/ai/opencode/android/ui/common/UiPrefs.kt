package ai.opencode.android.ui.common

/**
 * The only presentation choices the app persists for itself.
 *
 * Deliberately tiny: a theme and whether to use Material You colours. Everything
 * else the UI shows is server state, and the Phase 6 fence is that the UI layer
 * does not grow a state model of its own.
 */
enum class ThemeChoice { DARK, LIGHT, SYSTEM }
