package com.cursorforandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Line icons that Material Symbols does not ship in the shape Cursor uses: the git branch / pull-request
 * glyphs, the sidebar toggle, the "agent" sparkle and the Cursor cube. All 24x24, 1.6px strokes.
 */
object CursorIcons {

    private fun stroke(name: String, block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply(block).build()

    private fun nodes(data: String) = PathParser().parsePathString(data).toNodes()

    private fun ImageVector.Builder.line(data: String, width: Float = 1.6f) = addPath(
        pathData = nodes(data),
        pathFillType = PathFillType.NonZero,
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )

    @Suppress("unused")
    private fun ImageVector.Builder.fill(data: String) = addPath(pathData = nodes(data), fill = SolidColor(Color.Black))

    val GitBranch: ImageVector by lazy {
        stroke("GitBranch") {
            line("M6 3v12")
            line("M6 15a3 3 0 1 0 0 6 3 3 0 1 0 0-6z")
            line("M18 6a3 3 0 1 0 0-6 3 3 0 1 0 0 6z")
            line("M18 6c0 4-3 6-6 7s-6 3-6 5")
        }
    }

    val GitPullRequest: ImageVector by lazy {
        stroke("GitPullRequest") {
            line("M6 9v12")
            line("M6 3a3 3 0 1 0 0 6 3 3 0 1 0 0-6z")
            line("M18 15a3 3 0 1 0 0 6 3 3 0 1 0 0-6z")
            line("M13 6h3a2 2 0 0 1 2 2v7")
            line("M15 4l-2 2 2 2")
        }
    }

    val GitCommit: ImageVector by lazy {
        stroke("GitCommit") {
            line("M12 8a4 4 0 1 0 0 8 4 4 0 1 0 0-8z")
            line("M2 12h6")
            line("M16 12h6")
        }
    }

    val Sidebar: ImageVector by lazy {
        stroke("Sidebar") {
            line("M4.5 5.5h15a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17V7a1.5 1.5 0 0 1 1.5-1.5z")
            line("M9.5 5.5v13")
        }
    }

    val Filter: ImageVector by lazy {
        stroke("Filter") {
            line("M4 7h16")
            line("M7 12h10")
            line("M10 17h4")
        }
    }

    /** The four-point sparkle Cursor uses for agents. */
    val Sparkle: ImageVector by lazy {
        stroke("Sparkle") {
            line("M12 3c.6 4.6 3.4 7.4 8 8-4.6.6-7.4 3.4-8 8-.6-4.6-3.4-7.4-8-8 4.6-.6 7.4-3.4 8-8z")
        }
    }

    val NewAgent: ImageVector by lazy {
        stroke("NewAgent") {
            line("M4 12l16-8-4 16-4-6-8-2z")
            line("M12 14l8-10")
        }
    }

    val Inbox: ImageVector by lazy {
        stroke("Inbox") {
            line("M4 13h4l2 3h4l2-3h4")
            line("M5.5 5h13a1 1 0 0 1 1 .8l1.5 7.2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-5l1.5-7.2a1 1 0 0 1 1-.8z")
        }
    }

    val Cloud: ImageVector by lazy {
        stroke("Cloud") {
            line("M7 18a4 4 0 0 1-.6-7.95A5.5 5.5 0 0 1 17 8.5a3.75 3.75 0 0 1 .5 7.5H7z")
        }
    }

    val Repo: ImageVector by lazy {
        stroke("Repo") {
            line("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z")
        }
    }

    val Terminal: ImageVector by lazy {
        stroke("Terminal") {
            line("M4 17l6-5-6-5")
            line("M12 19h8")
        }
    }

    val Layers: ImageVector by lazy {
        stroke("Layers") {
            line("M12 3l9 5-9 5-9-5 9-5z")
            line("M3 12l9 5 9-5")
            line("M3 16l9 5 9-5")
        }
    }

    val Wave: ImageVector by lazy {
        stroke("Wave") {
            line("M4 12c2-4 4-4 6 0s4 4 6 0 4-4 4 0")
        }
    }

    val Desktop: ImageVector by lazy {
        stroke("Desktop") {
            line("M4 5h16a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z")
            line("M9 20h6")
            line("M12 16v4")
        }
    }

    /** Filled isometric cube, used for the account row and empty state. */
    val Cube: ImageVector by lazy {
        stroke("Cube") {
            line("M12 3l8 4.5v9L12 21l-8-4.5v-9L12 3z")
            line("M4 7.5l8 4.5 8-4.5")
            line("M12 12v9")
        }
    }
}
