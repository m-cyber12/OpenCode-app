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

/**
 * v9.38 (owner): the Sandbox screen became a real file manager, and a real
 * file manager shows WHAT a file is at a glance. The `-extended` icons
 * artifact is deliberately not a dependency (see check-compose-icons.py), so
 * the Material paths below are drawn here, in the file-manager's own order:
 * folder, document, code, image, archive, web, audio, video, pdf, sheet,
 * help and close.
 */
val FolderGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Folder") {
        materialPath {
            moveTo(10.0f, 4.0f)
            horizontalLineTo(4.0f)
            curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
            lineTo(2.0f, 18.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(8.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            horizontalLineToRelative(-8.0f)
            lineToRelative(-2.0f, -2.0f)
            close()
        }
    }
}

val DocGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Doc") {
        materialPath {
            moveTo(14.0f, 2.0f)
            horizontalLineTo(6.0f)
            curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
            lineTo(4.0f, 20.0f)
            curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(8.0f)
            lineToRelative(-6.0f, -6.0f)
            close()
            moveTo(6.0f, 20.0f)
            verticalLineTo(4.0f)
            horizontalLineToRelative(7.0f)
            verticalLineToRelative(5.0f)
            horizontalLineToRelative(5.0f)
            verticalLineToRelative(11.0f)
            horizontalLineTo(6.0f)
            close()
        }
    }
}

val CodeGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Code") {
        materialPath {
            moveTo(9.4f, 16.6f)
            lineTo(4.8f, 12.0f)
            lineToRelative(4.6f, -4.6f)
            lineTo(8.0f, 6.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(1.4f, -1.4f)
            close()
            moveTo(14.6f, 16.6f)
            lineToRelative(4.6f, -4.6f)
            lineToRelative(-4.6f, -4.6f)
            lineTo(16.0f, 6.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(-1.4f, -1.4f)
            close()
        }
    }
}

val ImageGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Image") {
        materialPath {
            moveTo(21.0f, 19.0f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            horizontalLineTo(5.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            close()
            moveTo(8.5f, 13.5f)
            lineToRelative(2.5f, 3.01f)
            lineTo(14.5f, 12.0f)
            lineToRelative(4.5f, 6.0f)
            horizontalLineTo(5.0f)
            lineToRelative(3.5f, -4.5f)
            close()
        }
    }
}

val ArchiveGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Archive") {
        materialPath {
            moveTo(20.54f, 5.23f)
            lineToRelative(-1.39f, -1.68f)
            curveTo(18.88f, 3.21f, 18.47f, 3.0f, 18.0f, 3.0f)
            horizontalLineTo(6.0f)
            curveToRelative(-0.47f, 0.0f, -0.88f, 0.21f, -1.16f, 0.55f)
            lineToRelative(-1.39f, 1.68f)
            curveTo(3.17f, 5.57f, 3.0f, 6.02f, 3.0f, 6.5f)
            verticalLineTo(19.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(6.5f)
            curveToRelative(0.0f, -0.48f, -0.17f, -0.93f, -0.46f, -1.27f)
            close()
            moveTo(12.0f, 17.5f)
            lineTo(6.5f, 12.0f)
            horizontalLineTo(10.0f)
            verticalLineToRelative(-1.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(1.0f)
            horizontalLineToRelative(3.5f)
            lineTo(12.0f, 17.5f)
            close()
            moveTo(5.12f, 5.0f)
            lineToRelative(0.81f, -1.0f)
            horizontalLineToRelative(12.0f)
            lineToRelative(0.94f, 1.0f)
            horizontalLineTo(5.12f)
            close()
        }
    }
}

val WebGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Web") {
        materialPath {
            moveTo(12.0f, 2.0f)
            curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
            reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
            reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(11.0f, 19.93f)
            curveToRelative(-3.95f, -0.49f, -7.0f, -3.85f, -7.0f, -7.93f)
            curveToRelative(0.0f, -0.64f, 0.08f, -1.26f, 0.21f, -1.86f)
            lineTo(9.0f, 15.0f)
            verticalLineToRelative(1.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            verticalLineToRelative(3.93f)
            close()
            moveTo(17.9f, 17.39f)
            curveToRelative(-0.26f, -0.81f, -1.0f, -1.39f, -1.9f, -1.39f)
            horizontalLineToRelative(-1.0f)
            verticalLineToRelative(-3.0f)
            curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
            horizontalLineTo(8.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
            verticalLineTo(7.0f)
            horizontalLineToRelative(2.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(4.59f)
            curveToRelative(2.93f, 1.19f, 5.0f, 4.06f, 5.0f, 7.41f)
            curveToRelative(0.0f, 2.08f, -0.8f, 3.97f, -2.1f, 5.39f)
            close()
        }
    }
}

val AudioGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Audio") {
        materialPath {
            moveTo(12.0f, 3.0f)
            verticalLineToRelative(10.55f)
            curveToRelative(-0.59f, -0.34f, -1.27f, -0.55f, -2.0f, -0.55f)
            curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
            reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
            reflectiveCurveToRelative(4.0f, -1.79f, 4.0f, -4.0f)
            lineTo(14.0f, 7.0f)
            horizontalLineToRelative(4.0f)
            verticalLineTo(3.0f)
            horizontalLineToRelative(-6.0f)
            close()
        }
    }
}

val VideoGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Video") {
        materialPath {
            moveTo(10.0f, 16.5f)
            verticalLineToRelative(-9.0f)
            lineToRelative(6.0f, 4.5f)
            lineToRelative(-6.0f, 4.5f)
            close()
            moveTo(12.0f, 2.0f)
            curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
            reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
            reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(12.0f, 20.0f)
            curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
            reflectiveCurveToRelative(3.59f, -8.0f, 8.0f, -8.0f)
            reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
            reflectiveCurveToRelative(-3.59f, 8.0f, -8.0f, 8.0f)
            close()
        }
    }
}

val PdfGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Pdf") {
        materialPath {
            moveTo(20.0f, 2.0f)
            horizontalLineTo(8.0f)
            curveTo(6.9f, 2.0f, 6.0f, 2.9f, 6.0f, 4.0f)
            verticalLineToRelative(12.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(12.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(4.0f)
            curveTo(22.0f, 2.9f, 21.1f, 2.0f, 20.0f, 2.0f)
            close()
            moveTo(11.5f, 9.5f)
            curveToRelative(0.0f, 0.83f, -0.67f, 1.5f, -1.5f, 1.5f)
            lineTo(9.0f, 11.0f)
            verticalLineToRelative(2.0f)
            lineTo(7.5f, 13.0f)
            lineTo(7.5f, 7.0f)
            horizontalLineTo(10.0f)
            curveToRelative(0.83f, 0.0f, 1.5f, 0.67f, 1.5f, 1.5f)
            verticalLineToRelative(1.0f)
            close()
            moveTo(16.5f, 11.5f)
            horizontalLineTo(14.0f)
            verticalLineToRelative(2.0f)
            lineTo(12.5f, 13.5f)
            lineTo(12.5f, 7.0f)
            horizontalLineToRelative(2.5f)
            curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
            verticalLineToRelative(0.5f)
            curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
            close()
            moveTo(20.5f, 8.5f)
            horizontalLineTo(19.0f)
            verticalLineToRelative(1.0f)
            horizontalLineToRelative(1.5f)
            verticalLineToRelative(1.5f)
            horizontalLineTo(19.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-1.5f)
            lineTo(17.5f, 7.0f)
            horizontalLineToRelative(3.0f)
            verticalLineToRelative(1.5f)
            close()
        }
    }
}

val SheetGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Sheet") {
        materialPath {
            moveTo(3.0f, 3.0f)
            verticalLineToRelative(18.0f)
            horizontalLineToRelative(18.0f)
            verticalLineTo(3.0f)
            horizontalLineTo(3.0f)
            close()
            moveTo(19.0f, 19.0f)
            horizontalLineToRelative(-8.0f)
            verticalLineTo(11.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(8.0f)
            close()
            moveTo(19.0f, 9.0f)
            horizontalLineToRelative(-8.0f)
            verticalLineTo(5.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(4.0f)
            close()
            moveTo(9.0f, 19.0f)
            horizontalLineTo(5.0f)
            verticalLineToRelative(-4.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(4.0f)
            close()
            moveTo(9.0f, 13.0f)
            horizontalLineTo(5.0f)
            verticalLineTo(5.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(8.0f)
            close()
        }
    }
}

val QuestionGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Question") {
        materialPath {
            moveTo(11.0f, 18.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(12.0f, 2.0f)
            curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
            reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
            reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(12.0f, 20.0f)
            curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
            reflectiveCurveToRelative(3.59f, -8.0f, 8.0f, -8.0f)
            reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
            reflectiveCurveToRelative(-3.59f, 8.0f, -8.0f, 8.0f)
            close()
            moveTo(12.0f, 6.0f)
            curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
            horizontalLineToRelative(2.0f)
            curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
            reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
            curveToRelative(0.0f, 2.0f, -3.0f, 1.75f, -3.0f, 5.0f)
            horizontalLineToRelative(2.0f)
            curveToRelative(0.0f, -2.25f, 3.0f, -2.5f, 3.0f, -5.0f)
            curveToRelative(0.0f, -2.21f, -1.79f, -4.0f, -4.0f, -4.0f)
            close()
        }
    }
}

val CloseGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Close") {
        materialPath {
            moveTo(19.0f, 6.41f)
            lineTo(17.59f, 5.0f)
            lineTo(12.0f, 10.59f)
            lineTo(6.41f, 5.0f)
            lineTo(5.0f, 6.41f)
            lineTo(10.59f, 12.0f)
            lineTo(5.0f, 17.59f)
            lineTo(6.41f, 19.0f)
            lineTo(12.0f, 13.41f)
            lineTo(17.59f, 19.0f)
            lineTo(19.0f, 17.59f)
            lineTo(13.41f, 12.0f)
            close()
        }
    }
}

val StopGlyph: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon(name = "OpenCode.Stop") {
        materialPath {
            moveTo(6.0f, 6.0f)
            horizontalLineToRelative(12.0f)
            verticalLineToRelative(12.0f)
            horizontalLineTo(6.0f)
            close()
        }
    }
}
