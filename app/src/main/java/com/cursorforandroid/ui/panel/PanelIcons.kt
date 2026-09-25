package com.cursorforandroid.ui.panel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The glyphs only the panel draws, on [com.cursorforandroid.ui.components.CursorIcons]' grid: Lucide's 24-unit
 * viewport at Cursor's 1.75 stroke, round caps and joins. The GitHub mark is the one filled shape, as the web's
 * notes set it before a release link.
 */
internal object PanelIcons {

    private const val Weight = 1.75f

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply(block).build()

    private fun nodes(data: String) = PathParser().parsePathString(data).toNodes()

    private fun ImageVector.Builder.path(data: String, width: Float = Weight) = addPath(
        pathData = nodes(data),
        pathFillType = PathFillType.NonZero,
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )

    private fun ImageVector.Builder.rect(x: Float, y: Float, w: Float, h: Float, rx: Float) = path(
        "M${x + rx} $y h${w - 2 * rx} a$rx $rx 0 0 1 $rx $rx v${h - 2 * rx} a$rx $rx 0 0 1 ${-rx} $rx h${-(w - 2 * rx)} a$rx $rx 0 0 1 ${-rx} ${-rx} v${-(h - 2 * rx)} a$rx $rx 0 0 1 $rx ${-rx} z",
    )

    private fun ImageVector.Builder.fill(data: String, type: PathFillType = PathFillType.NonZero) =
        addPath(pathData = nodes(data), pathFillType = type, fill = SolidColor(Color.Black))

    /** The Project tab's mark: Lucide's square-kanban, as the web's strip draws it. */
    val Kanban: ImageVector by lazy {
        icon("PanelKanban") {
            rect(3f, 3f, 18f, 18f, 2f)
            path("M8 7v7")
            path("M12 7v4")
            path("M16 7v9")
        }
    }

    /** The chat's own sections: Lucide's list. */
    val Details: ImageVector by lazy {
        icon("PanelDetails") {
            path("M8 6h13")
            path("M8 12h13")
            path("M8 18h13")
            path("M3 6h.01", 2.5f)
            path("M3 12h.01", 2.5f)
            path("M3 18h.01", 2.5f)
        }
    }

    /** A markdown document's tab: the web's `M↓`. */
    val Markdown: ImageVector by lazy {
        icon("PanelMarkdown") {
            path("M3 17V7l4.5 5L12 7v10")
            path("M18 7v10")
            path("m15 14 3 3 3-3")
        }
    }

    /** An open folder of the Context tree: Lucide's folder-open. */
    val FolderOpen: ImageVector by lazy {
        icon("PanelFolderOpen") {
            path("m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2")
        }
    }

    /** The Project header's All Files toggle: Lucide's notebook. */
    val Notebook: ImageVector by lazy {
        icon("PanelNotebook") {
            path("M2 6h4")
            path("M2 10h4")
            path("M2 14h4")
            path("M2 18h4")
            rect(4f, 2f, 16f, 20f, 2f)
            path("M16 2v20")
        }
    }

    /** Widens the panel over the chat: Lucide's maximize-2. */
    val Expand: ImageVector by lazy {
        icon("PanelExpand") {
            path("M15 3h6v6")
            path("M9 21H3v-6")
            path("M21 3l-7 7")
            path("M3 21l7-7")
        }
    }

    /** Back to the panel's own width: Lucide's minimize-2. */
    val Collapse: ImageVector by lazy {
        icon("PanelCollapse") {
            path("M4 14h6v6")
            path("M20 10h-6V4")
            path("M14 10l7-7")
            path("M3 21l7-7")
        }
    }

    /** Puts the panel away: Lucide's panel-right, the web's toggle at the strip's end. */
    val PanelRight: ImageVector by lazy {
        icon("PanelRight") {
            rect(3f, 3f, 18f, 18f, 2f)
            path("M15 3v18")
        }
    }

    val ArrowLeft: ImageVector by lazy {
        icon("PanelArrowLeft") {
            path("m12 19-7-7 7-7")
            path("M19 12H5")
        }
    }

    /** Opens the tab's chat in place of this one: Lucide's arrow-up-right. */
    val ArrowUpRight: ImageVector by lazy {
        icon("PanelArrowUpRight") {
            path("M7 7h10v10")
            path("M7 17 17 7")
        }
    }

    /** GitHub's mark, filled: what the web's notes set before a link to a release or a repository on GitHub. */
    val GitHub: ImageVector by lazy {
        icon("PanelGitHub") {
            fill(
                "M12 .3a12 12 0 0 0-3.8 23.38c.6.12.83-.26.83-.57L9 21.07c-3.34.72-4.04-1.61-4.04-1.61-.55-1.39-1.34-1.76-1.34-1.76-1.09-.74.08-.73.08-.73 1.2.09 1.84 1.24 1.84 1.24 1.07 1.83 2.81 1.3 3.5 1 .1-.78.42-1.31.76-1.61-2.67-.3-5.47-1.33-5.47-5.93 0-1.31.47-2.38 1.24-3.22-.14-.3-.54-1.52.1-3.18 0 0 1-.32 3.3 1.23a11.5 11.5 0 0 1 6 0c2.28-1.55 3.29-1.23 3.29-1.23.64 1.66.24 2.88.12 3.18a4.65 4.65 0 0 1 1.23 3.22c0 4.61-2.8 5.63-5.48 5.92.42.36.81 1.1.81 2.22l-.01 3.29c0 .31.2.69.82.57A12 12 0 0 0 12 .3",
                PathFillType.EvenOdd,
            )
        }
    }
}
