package com.cursorforandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * The app's line icons. All glyphs sit on the 24-unit grid and are drawn as strokes, so one set scales from the
 * 12px composer buttons to the 22px logo slot without changing weight ratios.
 *
 * Geometry comes from Lucide (ISC, see `app/licenses/ISC_Lucide.txt`), the open icon family closest to the thin,
 * round-capped line style of Cursor's own UI; the strokes are rendered at 1.75 instead of Lucide's 2 to match the
 * weight Cursor draws its 16px icons at. Four shapes are Cursor's own: [Cube] is the official filled brand mark,
 * [Stop] the composer's stop square, and the composer menu's [Multitask] loop and upright [Paperclip] follow the
 * web glyphs Lucide has no match for.
 */
object CursorIcons {

    private const val Weight = 1.75f

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply(block).build()

    private fun nodes(data: String) = PathParser().parsePathString(data).toNodes()

    private fun ImageVector.Builder.path(data: String, width: Float = Weight, join: StrokeJoin = StrokeJoin.Round, cap: StrokeCap = StrokeCap.Round) = addPath(
        pathData = nodes(data),
        pathFillType = PathFillType.NonZero,
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = cap,
        strokeLineJoin = join,
    )

    private fun ImageVector.Builder.circle(cx: Float, cy: Float, r: Float, width: Float = Weight) =
        path("M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0", width)

    private fun ImageVector.Builder.rect(x: Float, y: Float, w: Float, h: Float, rx: Float, width: Float = Weight) = path(
        "M${x + rx} $y h${w - 2 * rx} a$rx $rx 0 0 1 $rx $rx v${h - 2 * rx} a$rx $rx 0 0 1 ${-rx} $rx h${-(w - 2 * rx)} a$rx $rx 0 0 1 ${-rx} ${-rx} v${-(h - 2 * rx)} a$rx $rx 0 0 1 $rx ${-rx} z",
        width,
    )

    private fun ImageVector.Builder.fill(data: String) = addPath(pathData = nodes(data), fill = SolidColor(Color.Black))

    private fun ImageVector.Builder.dot(cx: Float, cy: Float, r: Float) =
        fill("M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0z")

    // ------------------------------------------------------------------------------------------------------------
    // Cursor's own shapes
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The Cursor cube: the official 2D mark from the brand kit (https://cursor.com/brand, cursor-brand-assets.zip →
     * General Logos/Cube/SVG/CUBE_2D_*.svg), path data verbatim. Filled, so it takes the tint like every other icon;
     * the group scales the 466.73×532.09 artwork to the 16-unit width the sidebar glyphs occupy (18.24 tall, centred).
     */
    val Cube: ImageVector by lazy {
        icon("Cube") {
            group(scaleX = 0.034281f, scaleY = 0.034281f, translationX = 4f, translationY = 2.8797f) {
                fill(
                    "M457.43,125.94L244.42,2.96c-6.84-3.95-15.28-3.95-22.12,0L9.3,125.94c-5.75,3.32-9.3,9.46-9.3,16.11v247.99" +
                        "c0,6.65,3.55,12.79,9.3,16.11l213.01,122.98c6.84,3.95,15.28,3.95,22.12,0l213.01-122.98c5.75-3.32,9.3-9.46,9.3-16.11" +
                        "v-247.99c0-6.65-3.55-12.79-9.3-16.11h-.01ZM444.05,151.99l-205.63,356.16c-1.39,2.4-5.06,1.42-5.06-1.36v-233.21" +
                        "c0-4.66-2.49-8.97-6.53-11.31L24.87,145.67c-2.4-1.39-1.42-5.06,1.36-5.06h411.26c5.84,0,9.49,6.33,6.57,11.39h-.01Z",
                )
            }
        }
    }

    /** Filled stop square for the composer's stop button. */
    val Stop: ImageVector by lazy {
        icon("Stop") { fill("M7.5 6A1.5 1.5 0 0 0 6 7.5v9A1.5 1.5 0 0 0 7.5 18h9a1.5 1.5 0 0 0 1.5-1.5v-9A1.5 1.5 0 0 0 16.5 6h-9z") }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Git
    // ------------------------------------------------------------------------------------------------------------

    val GitBranch: ImageVector by lazy {
        icon("GitBranch") {
            path("M15 6a9 9 0 0 0-9 9V3")
            circle(18f, 6f, 3f)
            circle(6f, 18f, 3f)
        }
    }

    val GitPullRequest: ImageVector by lazy {
        icon("GitPullRequest") {
            circle(18f, 18f, 3f)
            circle(6f, 6f, 3f)
            path("M13 6h3a2 2 0 0 1 2 2v7")
            path("M6 9v12")
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Navigation and chrome
    // ------------------------------------------------------------------------------------------------------------

    val Sidebar: ImageVector by lazy {
        icon("Sidebar") {
            rect(3f, 3f, 18f, 18f, 2f)
            path("M9 3v18")
        }
    }

    val Filter: ImageVector by lazy {
        icon("Filter") {
            path("M2 5h20")
            path("M6 12h12")
            path("M9 19h6")
        }
    }

    val Search: ImageVector by lazy {
        icon("Search") {
            circle(11f, 11f, 8f)
            path("m21 21-4.34-4.34")
        }
    }

    val More: ImageVector by lazy {
        icon("More") {
            dot(5f, 12f, 1.9f)
            dot(12f, 12f, 1.9f)
            dot(19f, 12f, 1.9f)
        }
    }

    val ChevronDown: ImageVector by lazy { icon("ChevronDown") { path("m6 9 6 6 6-6", 2f) } }
    val ChevronRight: ImageVector by lazy { icon("ChevronRight") { path("m9 18 6-6-6-6", 2f) } }
    val ChevronLeft: ImageVector by lazy { icon("ChevronLeft") { path("m15 18-6-6 6-6", 2f) } }

    val ArrowUp: ImageVector by lazy {
        icon("ArrowUp") {
            path("m5 12 7-7 7 7", 2.25f)
            path("M12 19V5", 2.25f)
        }
    }

    val Plus: ImageVector by lazy {
        icon("Plus") {
            path("M5 12h14", 2f)
            path("M12 5v14", 2f)
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            path("M18 6 6 18", 2f)
            path("m6 6 12 12", 2f)
        }
    }

    val Check: ImageVector by lazy { icon("Check") { path("M20 6 9 17l-5-5", 2f) } }

    /** Lucide `check-check` — two ticks, the mark-all-read glyph. */
    val CheckCheck: ImageVector by lazy {
        icon("CheckCheck") {
            path("M18 6 7 17l-5-5", 2f)
            path("m22 10-7.5 7.5L13 16", 2f)
        }
    }

    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            path("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8")
            path("M21 3v5h-5")
            path("M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16")
            path("M8 16H3v5")
        }
    }

    val ExternalLink: ImageVector by lazy {
        icon("ExternalLink") {
            path("M15 3h6v6")
            path("M10 14 21 3")
            path("M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")
        }
    }

    val Copy: ImageVector by lazy {
        icon("Copy") {
            rect(8f, 8f, 14f, 14f, 2f)
            path("M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
        }
    }

    val Trash: ImageVector by lazy {
        icon("Trash") {
            path("M10 11v6")
            path("M14 11v6")
            path("M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6")
            path("M3 6h18")
            path("M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
        }
    }

    val Archive: ImageVector by lazy {
        icon("Archive") {
            rect(2f, 3f, 20f, 5f, 1f)
            path("M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8")
            path("M10 12h4")
        }
    }

    val Pin: ImageVector by lazy {
        icon("Pin") {
            path("M12 17v5")
            path("M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z")
        }
    }

    val Clock: ImageVector by lazy {
        icon("Clock") {
            circle(12f, 12f, 10f)
            path("M12 6v6l4 2")
        }
    }

    val Eye: ImageVector by lazy {
        icon("Eye") {
            path("M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0")
            circle(12f, 12f, 3f)
        }
    }

    val EyeOff: ImageVector by lazy {
        icon("EyeOff") {
            path("M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49")
            path("M14.084 14.158a3 3 0 0 1-4.242-4.242")
            path("M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143")
            path("m2 2 20 20")
        }
    }

    val Bell: ImageVector by lazy {
        icon("Bell") {
            path("M10.268 21a2 2 0 0 0 3.464 0")
            path("M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326")
        }
    }

    val Warning: ImageVector by lazy {
        icon("Warning") {
            path("m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3")
            path("M12 9v4")
            path("M12 17h.01", 2f)
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Agents, tools and context
    // ------------------------------------------------------------------------------------------------------------

    /** The four-point star Cursor uses for agents. */
    val Sparkle: ImageVector by lazy {
        icon("Sparkle") {
            path("M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z")
        }
    }

    /** Concentric rings: a goal the agent keeps working toward. */
    val Target: ImageVector by lazy {
        icon("Target") {
            circle(12f, 12f, 10f)
            circle(12f, 12f, 6f)
            circle(12f, 12f, 2f)
        }
    }

    val Cloud: ImageVector by lazy { icon("Cloud") { path("M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z") } }

    val Desktop: ImageVector by lazy {
        icon("Desktop") {
            rect(2f, 3f, 20f, 14f, 2f)
            path("M8 21h8")
            path("M12 17v4")
        }
    }

    val Folder: ImageVector by lazy {
        icon("Folder") { path("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z") }
    }

    /** Folder with a branch: a repository. */
    val Repo: ImageVector by lazy {
        icon("Repo") {
            path("M18 19a5 5 0 0 1-5-5v8")
            path("M9 20H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H20a2 2 0 0 1 2 2v5")
            circle(13f, 12f, 2f)
            circle(20f, 19f, 2f)
        }
    }

    val File: ImageVector by lazy {
        icon("File") {
            path("M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z")
            path("M14 2v5a1 1 0 0 0 1 1h5")
        }
    }

    val Pencil: ImageVector by lazy {
        icon("Pencil") {
            path("M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z")
            path("m15 5 4 4")
        }
    }

    val Terminal: ImageVector by lazy {
        icon("Terminal") {
            path("M12 19h8")
            path("m4 17 6-6-6-6")
        }
    }

    val Globe: ImageVector by lazy {
        icon("Globe") {
            circle(12f, 12f, 10f)
            path("M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20")
            path("M2 12h20")
        }
    }

    val Layers: ImageVector by lazy {
        icon("Layers") {
            path("M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z")
            path("M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12")
            path("M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17")
        }
    }

    val Image: ImageVector by lazy {
        icon("Image") {
            rect(3f, 3f, 18f, 18f, 2f)
            circle(9f, 9f, 2f)
            path("m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21")
        }
    }

    val ArrowDown: ImageVector by lazy {
        icon("ArrowDown") {
            path("M12 5v14", 2f)
            path("m19 12-7 7-7-7", 2f)
        }
    }

    val SignOut: ImageVector by lazy {
        icon("SignOut") {
            path("m16 17 5-5-5-5")
            path("M21 12H9")
            path("M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4")
        }
    }

    /** Lucide `play`, filled rather than stroked: it sits on a solid disc over a video poster. */
    val Play: ImageVector by lazy {
        icon("Play") {
            fill("M6 3.5a.5.5 0 0 1 .77-.42l14 8.5a.5.5 0 0 1 0 .84l-14 8.5A.5.5 0 0 1 6 20.5z")
        }
    }

    val Video: ImageVector by lazy {
        icon("Video") {
            path("m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5")
            rect(2f, 6f, 14f, 12f, 2f)
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // The composer's "+" menu
    // ------------------------------------------------------------------------------------------------------------

    /** Multitask: a ring with a second loop peeling off its top right. Cursor's own glyph; Lucide has no equivalent. */
    val Multitask: ImageVector by lazy {
        icon("Multitask") {
            path("M9.5 9a5.5 5.5 0 1 0 0 11 5.5 5.5 0 1 0 0-11z")
            path("M12.3 9.7a3.7 3.7 0 1 1 6.3 2.7")
        }
    }

    /** Plan mode: Lucide `list-checks`, a checklist — the steps drafted before anything is edited. */
    val Plan: ImageVector by lazy {
        icon("Plan") {
            path("m3 17 2 2 4-4")
            path("m3 7 2 2 4-4")
            path("M13 6h8")
            path("M13 12h8")
            path("M13 18h8")
        }
    }

    /** Files: an upright paperclip as the web's row draws it (Lucide's leans 45°), so the geometry is our own. */
    val Paperclip: ImageVector by lazy {
        icon("Paperclip") {
            path("M15.5 8.5v8a3.5 3.5 0 0 1-7 0V6.5a2.5 2.5 0 0 1 5 0v9a1.5 1.5 0 0 1-3 0V8.5")
        }
    }

    /** Skills: Lucide `book-open`. */
    val Book: ImageVector by lazy {
        icon("Book") {
            path("M12 5v16")
            path("M20.001 19A2 2 0 0 0 22 17V5a2 2 0 0 0-1.999-2L16 3.002A5 5 0 0 0 12 5a5 5 0 0 0-4-2H4a2 2 0 0 0-2 2v12a2 2 0 0 0 1.999 2H8a5 5 0 0 1 4 2 5 5 0 0 1 4-2z")
        }
    }

    /** MCP servers: Lucide `plug`, turned 45° so the prongs point up-right like the web glyph. */
    val Plug: ImageVector by lazy {
        icon("Plug") {
            group(rotate = 45f, pivotX = 12f, pivotY = 12f) {
                path("M12 22v-5")
                path("M15 8V2")
                path("M17 8a1 1 0 0 1 1 1v4a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1z")
                path("M9 8V2")
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Cursor Projects: the icons a Project can be given (the Agents Window's icon names, Lucide geometry)
    // ------------------------------------------------------------------------------------------------------------

    /** Lucide `zap`: the Projects palette's default icon (`lightning`). */
    val Lightning: ImageVector by lazy {
        icon("Lightning") {
            path("M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z")
        }
    }

    val Rocket: ImageVector by lazy {
        icon("Rocket") {
            path("M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09z")
            path("m12 15-3-3a22 22 0 0 1 2-3.95A12.88 12.88 0 0 1 22 2c0 2.72-.78 7.5-6 11a22.35 22.35 0 0 1-4 2z")
            path("M9 12H4s.55-3.03 2-4c1.62-1.08 5 0 5 0")
            path("M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5")
        }
    }

    val Star: ImageVector by lazy {
        icon("Star") {
            path("M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z")
        }
    }

    val Flag: ImageVector by lazy {
        icon("Flag") {
            path("M4 22V4a1 1 0 0 1 .4-.8A6 6 0 0 1 8 2c3 0 5 2 7.333 2q2 0 3.067-.8A1 1 0 0 1 20 4v10a1 1 0 0 1-.4.8A6 6 0 0 1 16 16c-3 0-5-2-8-2a6 6 0 0 0-4 1.528")
        }
    }

    val Code: ImageVector by lazy {
        icon("Code") {
            path("m16 18 6-6-6-6")
            path("m8 6-6 6 6 6")
        }
    }

    val Database: ImageVector by lazy {
        icon("Database") {
            path("M3 5a9 3 0 1 0 18 0a9 3 0 1 0-18 0")
            path("M3 5v14a9 3 0 0 0 18 0V5")
            path("M3 12a9 3 0 0 0 18 0")
        }
    }

    val Shield: ImageVector by lazy {
        icon("Shield") {
            path("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z")
        }
    }

    val Heart: ImageVector by lazy {
        icon("Heart") {
            path("M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5")
        }
    }

    val Moon: ImageVector by lazy {
        icon("Moon") {
            path("M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401")
        }
    }

    val Server: ImageVector by lazy {
        icon("Server") {
            rect(2f, 2f, 20f, 8f, 2f)
            rect(2f, 14f, 20f, 8f, 2f)
            path("M6 6h.01")
            path("M6 18h.01")
        }
    }

    val Calendar: ImageVector by lazy {
        icon("Calendar") {
            path("M8 2v4")
            path("M16 2v4")
            rect(3f, 4f, 18f, 18f, 2f)
            path("M3 10h18")
        }
    }

    val Link: ImageVector by lazy {
        icon("Link") {
            path("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71")
            path("M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71")
        }
    }

    val Bug: ImageVector by lazy {
        icon("Bug") {
            path("m8 2 1.88 1.88")
            path("M14.12 3.88 16 2")
            path("M9 7.13v-1a3.003 3.003 0 1 1 6 0v1")
            path("M12 20c-3.3 0-6-2.7-6-6v-3a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v3c0 3.3-2.7 6-6 6")
            path("M12 20v-9")
            path("M6.53 9C4.6 8.8 3 7.1 3 5")
            path("M6 13H2")
            path("M3 21c0-2.1 1.7-3.9 3.8-4")
            path("M20.97 5c0 2.1-1.6 3.8-3.5 4")
            path("M22 13h-4")
            path("M17.2 17c2.1.1 3.8 1.9 3.8 4")
        }
    }

    /**
     * The glyph for a Project's icon name as the account spells it (`lightning`, `rocket`, `folder`, …): the ones
     * drawn here, the app's own glyphs where they match, and [Lightning] — the default the Agents Window also falls
     * back to — for a name this build does not draw.
     */
    fun project(iconName: String?): ImageVector = when (iconName?.trim()?.lowercase()) {
        "rocket" -> Rocket
        "star" -> Star
        "flag" -> Flag
        "code", "brackets-curly" -> Code
        "database" -> Database
        "shield" -> Shield
        "heart" -> Heart
        "moon" -> Moon
        "server" -> Server
        "calendar" -> Calendar
        "link" -> Link
        "bug" -> Bug
        "folder" -> Folder
        "terminal" -> Terminal
        "git-branch" -> GitBranch
        "book-open", "library" -> Book
        "file-text", "files" -> File
        "globe", "browser" -> Globe
        "target" -> Target
        "cloud" -> Cloud
        "image", "camera" -> Image
        "sparkle", "magic-wand" -> Sparkle
        "layers" -> Layers
        else -> Lightning
    }
}
