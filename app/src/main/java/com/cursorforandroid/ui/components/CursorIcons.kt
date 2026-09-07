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

    /** The web sidebar's "working" glyph: a cluster of four dots that rotates while a run is active. */
    val Working: ImageVector by lazy {
        stroke("Working") {
            fill("M12 4.2a1.8 1.8 0 1 0 0 3.6 1.8 1.8 0 1 0 0-3.6z")
            fill("M18.2 10.2a1.8 1.8 0 1 0 0 3.6 1.8 1.8 0 1 0 0-3.6z")
            fill("M12 16.2a1.8 1.8 0 1 0 0 3.6 1.8 1.8 0 1 0 0-3.6z")
            fill("M5.8 10.2a1.8 1.8 0 1 0 0 3.6 1.8 1.8 0 1 0 0-3.6z")
        }
    }

    val NewAgent: ImageVector by lazy {
        stroke("NewAgent") {
            line("M4 12l16-8-4 16-4-6-8-2z")
            line("M12 14l8-10")
        }
    }


    val Cloud: ImageVector by lazy {
        stroke("Cloud") {
            line("M6 18a4.5 4.5 0 0 1-.6-8.95A6.5 6.5 0 0 1 17.5 7.5a4.25 4.25 0 0 1 .5 8.5H6z")
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

    /** Outline isometric cube — the Cursor mark as drawn at the top of the web sidebar. */
    val Cube: ImageVector by lazy {
        stroke("Cube") {
            line("M12 3l8 4.5v9L12 21l-8-4.5v-9L12 3z", 1.5f)
            line("M4 7.5l8 4.5 8-4.5", 1.5f)
            line("M12 12v9", 1.5f)
        }
    }

    val Archive: ImageVector by lazy {
        stroke("Archive") {
            line("M3.5 6.5h17v3h-17z")
            line("M5 9.5v9a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-9")
            line("M10 13h4")
        }
    }

    val Clock: ImageVector by lazy {
        stroke("Clock") {
            line("M12 4a8 8 0 1 0 0 16 8 8 0 1 0 0-16z")
            line("M12 8v4l3 2")
        }
    }

    val Pin: ImageVector by lazy {
        stroke("Pin") {
            line("M9 4h6l-.7 6.3 2.7 2.7v1H7v-1l2.7-2.7L9 4z")
            line("M12 14v6")
        }
    }

    val Stop: ImageVector by lazy {
        stroke("Stop") {
            line("M6.5 6.5h11v11h-11z", 2f)
        }
    }

    val ArrowUp: ImageVector by lazy {
        stroke("ArrowUp") {
            line("M12 20V4", 2f)
            line("M5 11l7-7 7 7", 2f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        stroke("ChevronDown") {
            line("M6 9l6 6 6-6")
        }
    }

    val ChevronRight: ImageVector by lazy {
        stroke("ChevronRight") {
            line("M9 6l6 6-6 6")
        }
    }

    val ChevronLeft: ImageVector by lazy {
        stroke("ChevronLeft") {
            line("M15 6l-6 6 6 6")
        }
    }

    val More: ImageVector by lazy {
        stroke("More") {
            line("M5 12h.01", 2.4f)
            line("M12 12h.01", 2.4f)
            line("M19 12h.01", 2.4f)
        }
    }

    val Search: ImageVector by lazy {
        stroke("Search") {
            line("M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 1 0 0-13z")
            line("M15.5 15.5L20 20")
        }
    }

    val Plus: ImageVector by lazy {
        stroke("Plus") {
            line("M12 5v14", 1.8f)
            line("M5 12h14", 1.8f)
        }
    }

    val Mic: ImageVector by lazy {
        stroke("Mic") {
            line("M12 2.5a3.5 3.5 0 0 1 3.5 3.5v6a3.5 3.5 0 0 1-7 0V6A3.5 3.5 0 0 1 12 2.5z", 1.8f)
            line("M5 11.5a7 7 0 0 0 14 0", 1.8f)
            line("M12 18.5v3", 1.8f)
        }
    }

    val Close: ImageVector by lazy {
        stroke("Close") {
            line("M6 6l12 12")
            line("M18 6L6 18")
        }
    }

    val Check: ImageVector by lazy {
        stroke("Check") {
            line("M5 12.5l4.5 4.5L19 7", 1.8f)
        }
    }

    val File: ImageVector by lazy {
        stroke("File") {
            line("M7 3h7l5 5v13H7z")
            line("M14 3v5h5")
        }
    }

    val Folder: ImageVector by lazy {
        stroke("Folder") {
            line("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z")
        }
    }

    val Pencil: ImageVector by lazy {
        stroke("Pencil") {
            line("M4 20l4-1 11-11-3-3L5 16l-1 4z")
            line("M13 6l3 3")
        }
    }

    val Globe: ImageVector by lazy {
        stroke("Globe") {
            line("M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18z")
            line("M3 12h18")
            line("M12 3c3 3 3 15 0 18")
            line("M12 3c-3 3-3 15 0 18")
        }
    }

    val Bell: ImageVector by lazy {
        stroke("Bell") {
            line("M6 17V11a6 6 0 0 1 12 0v6l1.5 1.5H4.5L6 17z")
            line("M10 20a2 2 0 0 0 4 0")
        }
    }

    val Warning: ImageVector by lazy {
        stroke("Warning") {
            line("M12 4l9 16H3l9-16z")
            line("M12 10v4")
            line("M12 17h.01", 2f)
        }
    }

    val Eye: ImageVector by lazy {
        stroke("Eye") {
            line("M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z")
            line("M12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 1 0 0-5z")
        }
    }

    val EyeOff: ImageVector by lazy {
        stroke("EyeOff") {
            line("M3 3l18 18")
            line("M10.6 6.1A9.8 9.8 0 0 1 12 6c6 0 9.5 6 9.5 6a17 17 0 0 1-3 3.6")
            line("M6.4 6.4A16 16 0 0 0 2.5 12S6 18 12 18a9.5 9.5 0 0 0 3.5-.7")
        }
    }

    val ExternalLink: ImageVector by lazy {
        stroke("ExternalLink") {
            line("M14 4h6v6")
            line("M20 4l-9 9")
            line("M18 13v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h6")
        }
    }

    val Copy: ImageVector by lazy {
        stroke("Copy") {
            line("M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1z")
            line("M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1")
        }
    }

    val Trash: ImageVector by lazy {
        stroke("Trash") {
            line("M4 7h16")
            line("M9 7V4h6v3")
            line("M6 7l1 13h10l1-13")
        }
    }

    val Refresh: ImageVector by lazy {
        stroke("Refresh") {
            line("M20 11a8 8 0 1 0 2 5.3")
            line("M20 4v7h-7")
        }
    }

    /** Solid play triangle for the video poster button. */
    val Play: ImageVector by lazy {
        stroke("Play") {
            fill("M9 5.5v13a.5.5 0 0 0 .77.42l10-6.5a.5.5 0 0 0 0-.84l-10-6.5A.5.5 0 0 0 9 5.5z")
        }
    }

    val Image: ImageVector by lazy {
        stroke("Image") {
            line("M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z")
            line("M3.5 16.5l4.5-4.5 4 4 3-3 5.5 5.5")
            line("M15.5 9.5a1.3 1.3 0 1 0 0-2.6 1.3 1.3 0 1 0 0 2.6z")
        }
    }

    val Video: ImageVector by lazy {
        stroke("Video") {
            line("M3 8a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8z")
            line("M16 10.5l5-3v9l-5-3")
        }
    }
}
