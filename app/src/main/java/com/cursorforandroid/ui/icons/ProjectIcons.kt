package com.cursorforandroid.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorIcons
import java.util.concurrent.ConcurrentHashMap

/**
 * The icons a Cursor Project can be given, by the names the account records (`ProjectAppearance.icon`).
 *
 * The desktop's Agents Window offers every glyph of its own icon font: 660 of them once aliases are folded
 * (Cursor 3.20.17, `cursor-icons.js`), from `account` to `zzz`, brand and product marks (`logo-notion`,
 * `github`, `file-type-rust`, …) included, and it accepts the 135 alias spellings as well (`plus` for `add`,
 * `mark-github` for `github`). That font is Anysphere's and not redistributable, so this app draws each name
 * with an open-licensed glyph of the same meaning — Lucide for the line icons, Simple Icons for the marks
 * (see [ProjectIconCatalog] and `scripts/project-icons/generate.py`). A handful of marks no open library carries
 * (Slack, the Microsoft and Adobe products, a few languages) are drawn with a neutral glyph; [isStandIn] says which.
 *
 * Names are matched the way the desktop matches them: exact, after trimming. A name this build has never heard
 * of draws as [DEFAULT_ICON], the cube the Agents Window itself falls back to (`project-agents.js`), so a Project
 * given an icon by a newer Cursor still reads as a Project here.
 */
object ProjectIcons {

    /** What the Agents Window draws when a Project's appearance is missing or unknown. */
    const val DEFAULT_ICON = "cube"

    /** What the desktop's picker starts a Project on when nothing was chosen (`IconColorPickerPanel.js`). */
    const val PICKER_DEFAULT_ICON = "lightning"

    /** Every id the picker offers, in the desktop's order. */
    val ids: List<String> = ProjectIconCatalog.PICKER.asList()

    /** The picker's sections; together they hold every id of [ids] exactly once. */
    val groups: List<ProjectIconGroup> = ProjectIconCatalog.GROUPS

    private val specs: HashMap<String, ProjectIconSpec> by lazy { ProjectIconCatalog.specs() }
    private val vectors = ConcurrentHashMap<String, ImageVector>()

    /** The picker id [name] stands for — itself, or the id its alias points at — or null for a name the account would refuse. */
    fun canonical(name: String?): String? {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed in specs) return trimmed
        return ProjectIconCatalog.ALIASES[trimmed]
    }

    /** True when [name] is one of the catalog's ids or aliases: exactly the names the account accepts. */
    fun isKnown(name: String?): Boolean = canonical(name) != null

    /** True when [name] is drawn with a neutral glyph because its mark has no open-licensed rendering. */
    fun isStandIn(name: String?): Boolean = canonical(name) in ProjectIconCatalog.STAND_INS

    /** The glyph for [name], or the [DEFAULT_ICON] cube for a name the catalog does not know. */
    fun vector(name: String?): ImageVector {
        val id = canonical(name) ?: DEFAULT_ICON
        return vectors.getOrPut(id) { build(id, specs.getValue(id)) }
    }

    /** A readable name for [id]: the brand's own spelling where it has one, the id's words otherwise. */
    fun label(id: String): String {
        val key = canonical(id) ?: id
        ProjectIconCatalog.LABELS[key]?.let { return it }
        var text = key
        for (prefix in listOf("logo-", "file-type-")) text = text.removePrefix(prefix)
        text = text.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('-', ' ').trim().lowercase()
        return text.replaceFirstChar { it.uppercase() }
    }

    /**
     * The ids matching [query], as the desktop searches: every whitespace-, slash- or underscore-separated term
     * has to occur in the id or in its label. A blank query matches everything.
     */
    fun search(query: String, within: List<String> = ids): List<String> {
        val terms = query.trim().lowercase().split(Regex("[\\s/_]+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return within
        return within.filter { id ->
            val haystack = id.lowercase() + " " + label(id).lowercase()
            terms.all { it in haystack }
        }
    }

    private fun build(id: String, spec: ProjectIconSpec): ImageVector {
        if (spec.style == ProjectIconSpec.Style.CURSOR_CUBE) return CursorIcons.Cube
        return ImageVector.Builder(name = "project-icon:$id", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            group(rotate = spec.rotation, pivotX = 12f, pivotY = 12f) {
                val stroke = spec.style != ProjectIconSpec.Style.FILL
                val fill = spec.style != ProjectIconSpec.Style.STROKE
                for (data in spec.paths) {
                    addPath(
                        pathData = PathParser().parsePathString(data).toNodes(),
                        pathFillType = PathFillType.NonZero,
                        fill = if (fill) SolidColor(Color.Black) else null,
                        stroke = if (stroke) SolidColor(Color.Black) else null,
                        strokeLineWidth = if (stroke) STROKE_WEIGHT else 0f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
        }.build()
    }

    /** Lucide draws at 2; the app renders its line icons at 1.75 to match Cursor's 16px weight (see `CursorIcons`). */
    private const val STROKE_WEIGHT = 1.75f
}
