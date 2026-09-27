package ai.opencode.android.ui.common

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * v9 premium pass: Undo and Redo as icons (the owner asked for the last-turn
 * actions to be glyphs, not text). Neither ships in `material-icons-core` -
 * the only icons artifact this project depends on (the `-extended` artifact is
 * deliberately avoided; see check-compose-icons.py) - so the two Material
 * paths are drawn here verbatim (Apache 2.0, same as the artifact would be).
 * Plain vals, not composables: an ImageVector is immutable data.
 */
val UndoGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Undo") {
        materialPath {
            moveTo(12.5f, 8.0f)
            curveToRelative(-2.65f, 0.0f, -5.05f, 0.99f, -6.9f, 2.6f)
            lineTo(2.0f, 7.0f)
            verticalLineToRelative(9.0f)
            horizontalLineToRelative(9.0f)
            lineToRelative(-3.62f, -3.62f)
            curveToRelative(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f)
            curveToRelative(3.54f, 0.0f, 6.55f, 2.31f, 7.6f, 5.5f)
            lineToRelative(2.37f, -0.78f)
            curveTo(21.08f, 11.03f, 17.15f, 8.0f, 12.5f, 8.0f)
            close()
        }
    }
}

val RedoGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Redo") {
        materialPath {
            moveTo(18.4f, 10.6f)
            curveTo(16.55f, 8.99f, 14.15f, 8.0f, 11.5f, 8.0f)
            curveToRelative(-4.65f, 0.0f, -8.58f, 3.03f, -9.96f, 7.22f)
            lineTo(3.9f, 16.0f)
            curveToRelative(1.05f, -3.19f, 4.05f, -5.5f, 7.6f, -5.5f)
            curveToRelative(1.95f, 0.0f, 3.73f, 0.72f, 5.12f, 1.88f)
            lineTo(13.0f, 16.0f)
            horizontalLineToRelative(9.0f)
            verticalLineTo(7.0f)
            lineToRelative(-3.6f, 3.6f)
            close()
        }
    }
}

/**
 * v9.2 (owner): Copy / Like / Dislike beside Retry and Undo under the final
 * response. `ThumbUp` ships in material-icons-core, but `ContentCopy` and
 * `ThumbDown` do not, so - same rule as Undo/Redo above - their Material
 * paths are drawn here verbatim instead of pulling the -extended artifact.
 */
val CopyGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.ContentCopy") {
        materialPath {
            moveTo(16.0f, 1.0f)
            horizontalLineTo(4.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            horizontalLineToRelative(2.0f)
            verticalLineTo(3.0f)
            horizontalLineToRelative(12.0f)
            verticalLineTo(1.0f)
            close()
            moveTo(19.0f, 5.0f)
            horizontalLineTo(8.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(11.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(7.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(19.0f, 21.0f)
            horizontalLineTo(8.0f)
            verticalLineTo(7.0f)
            horizontalLineToRelative(11.0f)
            verticalLineToRelative(14.0f)
            close()
        }
    }
}

val ThumbDownGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.ThumbDown") {
        materialPath {
            moveTo(15.0f, 3.0f)
            horizontalLineTo(6.0f)
            curveToRelative(-0.83f, 0.0f, -1.54f, 0.5f, -1.84f, 1.22f)
            lineToRelative(-3.02f, 7.05f)
            curveToRelative(-0.09f, 0.23f, -0.14f, 0.47f, -0.14f, 0.73f)
            verticalLineToRelative(2.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(6.31f)
            lineToRelative(-0.95f, 4.57f)
            lineToRelative(-0.03f, 0.32f)
            curveToRelative(0.0f, 0.41f, 0.17f, 0.79f, 0.44f, 1.06f)
            lineTo(9.83f, 23.0f)
            lineToRelative(6.59f, -6.59f)
            curveToRelative(0.36f, -0.36f, 0.58f, -0.86f, 0.58f, -1.41f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(19.0f, 3.0f)
            verticalLineToRelative(12.0f)
            horizontalLineToRelative(4.0f)
            verticalLineTo(3.0f)
            horizontalLineToRelative(-4.0f)
            close()
        }
    }
}
