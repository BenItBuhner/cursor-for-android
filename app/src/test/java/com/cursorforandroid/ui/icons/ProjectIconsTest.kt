package com.cursorforandroid.ui.icons

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.theme.CursorDarkColors
import com.cursorforandroid.ui.theme.CursorLightColors
import com.cursorforandroid.ui.theme.CursorOledColors
import com.cursorforandroid.ui.theme.ProjectPalette
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

/**
 * The Project icon catalog against what the desktop offers (Cursor 3.20.17, `cursor-icons.js` and
 * `IconColorPickerPanel.js`): every id and alias resolves to a glyph of its own, nothing known falls back, and the
 * ten tones carry the Agents Window's colours.
 */
@RunWith(AndroidJUnit4::class)
class ProjectIconsTest {

    private fun paths(node: VectorNode): List<VectorPath> = when (node) {
        is VectorPath -> listOf(node)
        is VectorGroup -> node.flatMap(::paths)
    }

    /** The catalog draws every icon inside one group that carries its rotation, under the vector's own root. */
    private fun rotation(vector: androidx.compose.ui.graphics.vector.ImageVector): Float = vector.root.filterIsInstance<VectorGroup>().single().rotation

    @Test
    fun `the catalog is the desktop's picker, 660 unique ids in its order, every one in exactly one group`() {
        assertThat(ProjectIcons.ids).hasSize(660)
        assertThat(ProjectIcons.ids.toSet()).hasSize(660)
        assertThat(ProjectIcons.ids.first()).isEqualTo("account")
        assertThat(ProjectIcons.ids.last()).isEqualTo("zzz")
        assertThat(ProjectIcons.ids).containsAtLeast("lightning", "rocket", "cube", "logo-notion", "logo-slack", "github", "file-type-rust", "mcp", "cursor-logo")

        val grouped = ProjectIcons.groups.flatMap { it.ids }
        assertThat(grouped).containsExactlyElementsIn(ProjectIcons.ids)
        assertThat(ProjectIcons.groups.map { it.label }).containsNoDuplicates()
        ProjectIcons.groups.forEach { assertThat(it.ids).isNotEmpty() }
    }

    @Test
    fun `every picker id draws a glyph of its own and never the fallback`() {
        val fallback = ProjectIcons.vector("no-such-icon-ever")
        assertThat(fallback).isSameInstanceAs(ProjectIcons.vector(ProjectIcons.DEFAULT_ICON))
        val distinct = HashSet<String>()
        for (id in ProjectIcons.ids) {
            assertThat(ProjectIcons.isKnown(id)).isTrue()
            assertThat(ProjectIcons.canonical(id)).isEqualTo(id)
            val vector = ProjectIcons.vector(id)
            if (id != ProjectIcons.DEFAULT_ICON) assertThat(vector).isNotSameInstanceAs(fallback)
            val drawn = paths(vector.root)
            assertThat(drawn).isNotEmpty()
            drawn.forEach { assertThat(it.pathData).isNotEmpty() }
            distinct += drawn.joinToString { it.pathData.toString() } + if (id == "cursor-logo") "" else rotation(vector)
        }
        // Distinct ids may share a glyph on purpose (`squares` and `copy`, `chevron-down` and its `-small` twin), but
        // the catalog is not a handful of glyphs under many names: well over five hundred different drawings.
        assertThat(distinct.size).isAtLeast(500)
    }

    /**
     * Ids that name a Lucide icon but are drawn with another glyph on purpose: the desktop draws a different object
     * under that name, checked against its own font glyph by glyph (2026-09-15 audit). Anything else that shares a
     * name with a Lucide icon has to be drawn with it.
     */
    private val divergent = mapOf(
        "target" to "own:target", // the desktop's target is a dartboard with the dart in it; Lucide's is three plain rings
        "beaker" to "lucide:flask-conical", // a conical flask on the desktop; Lucide's beaker is the straight-sided kind
        "check-square" to "lucide:square-check", // the check sits inside the square; Lucide's breaks out of it
        "cog" to "lucide:settings", // one plain gear; Lucide's cog is a gear within a gear
        "diff" to "lucide:columns-2", // two side-by-side panes; Lucide's diff is a plus over a minus
        "edit" to "lucide:pencil", // a pencil alone; Lucide's edit is a pencil over a square
        "git-commit" to "lucide:git-commit-vertical", // the branch runs top to bottom; Lucide's runs left to right
        "grid" to "lucide:layout-grid", // two by two; Lucide's grid is three by three
        "redo" to "lucide:rotate-cw", // a clockwise circular arrow; Lucide's redo is a hooked arrow
        "signal" to "lucide:radio", // radiating waves; Lucide's signal is ascending bars
        "sliders" to "lucide:sliders-horizontal", // horizontal tracks; Lucide's sliders are vertical
        "sparkle" to "lucide:sparkles", // a large four-point star with two small ones; Lucide's sparkle is the one star
    )

    @Test
    fun `an id that names a Lucide icon is drawn with that icon, unless the desktop's glyph is a different object`() {
        assertThat(ProjectIconCatalog.LUCIDE_NAMESAKES.size).isAtLeast(190)
        ProjectIconCatalog.LUCIDE_NAMESAKES.forEach { (id, lucideGlyph) ->
            val expected = divergent[id] ?: lucideGlyph
            assertWithMessage("glyph for $id").that(ProjectIcons.glyphName(id)).isEqualTo(expected)
        }
        divergent.keys.forEach { id -> assertWithMessage("$id is a Lucide namesake").that(ProjectIconCatalog.LUCIDE_NAMESAKES).containsKey(id) }
        // Aliases fold: Lucide's `filter` file is its `funnel`, `home` its `house`, so those count as the same glyph.
        assertThat(ProjectIconCatalog.LUCIDE_NAMESAKES["filter"]).isEqualTo("lucide:funnel")
        assertThat(ProjectIconCatalog.LUCIDE_NAMESAKES["home"]).isEqualTo("lucide:house")
        assertThat(ProjectIcons.glyphName("rocket")).isEqualTo("lucide:rocket")
        assertThat(ProjectIcons.glyphName("bell-dot")).isEqualTo("lucide:bell-dot")
    }

    @Test
    fun `the audit's corrections draw the desktop's object rather than a look-alike`() {
        // Bennett's two: a dartboard, not three rings; a layered triangle, not a prism.
        assertThat(ProjectIcons.glyphName("target")).isEqualTo("own:target")
        assertThat(ProjectIcons.glyphName("bullseye")).isEqualTo("own:target")
        assertThat(ProjectIcons.glyphName("chart-pyramid")).isEqualTo("own:chart-pyramid")
        // Objects no open library carries, drawn for the catalog.
        for ((id, glyph) in listOf(
            "bowtie" to "own:bowtie", "chess-king" to "lucide:chess-king", "cost-high" to "own:cost-high", "cost-medium" to "own:cost-medium",
            "crystal-ball" to "own:crystal-ball", "deckchair-umbrella" to "own:deckchair-umbrella", "elephant" to "own:elephant", "film-reel" to "own:film-reel",
            "flag-hill" to "own:flag-hill", "moon-z" to "own:moon-z", "one-circle" to "own:one-circle", "owl" to "own:owl", "pipe" to "own:pipe",
            "question" to "own:question", "remote-control" to "own:remote-control", "review" to "own:review", "sprint" to "own:sprint",
            "text-c" to "own:text-c", "text-y" to "own:text-y", "thinking-high" to "own:thinking-high", "treasure-chest" to "own:treasure-chest",
            "vr-headset" to "own:vr-headset", "zipper" to "own:zipper", "zzz" to "own:zzz", "agents-swarm" to "own:agents-swarm",
            "arrow-square-from-left" to "own:arrow-square-from-down", "board-kanban" to "lucide:square-kanban", "file-type-bicep" to "lucide:biceps-flexed",
            "file-type-liquid" to "simple:shopify", "file-type-groovy" to "lucide:star", "file-type-mustache" to "own:mustache",
        )) {
            assertWithMessage("glyph for $id").that(ProjectIcons.glyphName(id)).isEqualTo(glyph)
        }
        // The four square-exit arrows are one drawing turned: down, left (a quarter turn), up, right.
        assertThat(rotation(ProjectIcons.vector("arrow-square-from-down"))).isEqualTo(0f)
        assertThat(rotation(ProjectIcons.vector("arrow-square-from-left"))).isEqualTo(90f)
        assertThat(rotation(ProjectIcons.vector("arrow-square-from-up"))).isEqualTo(180f)
        assertThat(rotation(ProjectIcons.vector("arrow-square-from-right"))).isEqualTo(270f)
        // The letters and the head profiles are no longer one shared glyph.
        assertThat(listOf("text-c", "text-d", "text-j", "text-r", "text-s", "text-y").map { paths(ProjectIcons.vector(it).root).joinToString { p -> p.pathData.toString() } }.toSet()).hasSize(6)
        assertThat(ProjectIcons.isStandIn("text-c")).isFalse()
        assertThat(ProjectIcons.isStandIn("elephant")).isFalse()
        // Lucide's book-open: arc flags written as `0 0022` were once read as one number and drawn as a folded page.
        val bookOpen = paths(ProjectIcons.vector("book-open").root)
        assertThat(bookOpen).hasSize(2)
        assertThat(bookOpen.map { it.pathData.size }.sum()).isAtLeast(10)
    }

    @Test
    fun `every id is drawn with a named glyph, and an unknown id is logged once and named in the export as unknown`() {
        ProjectIcons.ids.forEach { id ->
            val glyph = ProjectIcons.glyphName(id)
            assertWithMessage(id).that(glyph).matches("(lucide|simple|own|cursor):[a-z0-9-]+")
            assertThat(ProjectIconCatalog.GLYPHS[id]).isEqualTo(glyph)
        }
        assertThat(ProjectIcons.glyphName("logo-notion")).isEqualTo("simple:notion")
        assertThat(ProjectIcons.glyphName("mark-github")).isEqualTo("simple:github")
        assertThat(ProjectIcons.glyphName("cursor-logo")).isEqualTo("cursor:cube")
        assertThat(ProjectIcons.glyphName("hologram-from-cursor-4")).isNull()
        assertThat(ProjectIcons.glyphName(null)).isNull()

        ShadowLog.clear()
        ProjectIcons.vector(" hologram-from-cursor-4 ")
        ProjectIcons.vector("hologram-from-cursor-4")
        ProjectIcons.vector("rocket")
        ProjectIcons.vector(null)
        val logged = ShadowLog.getLogsForTag(ProjectIcons.LOG_TAG)
        assertThat(logged.map { it.msg }).containsExactly(
            "Unknown Project icon id \"hologram-from-cursor-4\" (catalog: Cursor ${ProjectIconCatalog.CURSOR_VERSION}, 660 ids); drawing the cube instead",
        )
        assertThat(logged.single().type).isEqualTo(Log.WARN)
        assertThat(ProjectIcons.unknownNamesSeen()).contains("hologram-from-cursor-4")
        assertThat(ProjectIcons.unknownNamesSeen()).doesNotContain("rocket")
    }

    @Test
    fun `every alias the account accepts resolves to its picker id and the same glyph`() {
        assertThat(ProjectIconCatalog.ALIASES).hasSize(135)
        ProjectIconCatalog.ALIASES.forEach { (alias, canonical) ->
            assertThat(alias).isNotIn(ProjectIcons.ids)
            assertThat(canonical).isIn(ProjectIcons.ids)
            assertThat(ProjectIcons.canonical(alias)).isEqualTo(canonical)
            assertThat(ProjectIcons.isKnown(alias)).isTrue()
            assertThat(ProjectIcons.vector(alias)).isSameInstanceAs(ProjectIcons.vector(canonical))
        }
        assertThat(ProjectIcons.canonical("plus")).isEqualTo("add")
        assertThat(ProjectIcons.canonical("mark-github")).isEqualTo("github")
        assertThat(ProjectIcons.canonical("logo-mcp")).isEqualTo("mcp")
        assertThat(ProjectIcons.canonical("organization-filled")).isEqualTo("people")
    }

    @Test
    fun `names are matched after trimming, and anything else falls back to the cube in a deterministic way`() {
        assertThat(ProjectIcons.canonical("  rocket ")).isEqualTo("rocket")
        assertThat(ProjectIcons.canonical(null)).isNull()
        assertThat(ProjectIcons.canonical("")).isNull()
        assertThat(ProjectIcons.canonical("ROCKET")).isNull()
        assertThat(ProjectIcons.isKnown("some-icon-a-later-cursor-added")).isFalse()
        assertThat(ProjectIcons.vector(null)).isSameInstanceAs(ProjectIcons.vector("cube"))
        assertThat(ProjectIcons.vector("some-icon-a-later-cursor-added")).isSameInstanceAs(ProjectIcons.vector("cube"))
        assertThat(ProjectIcons.DEFAULT_ICON).isEqualTo("cube")
        assertThat(ProjectIcons.PICKER_DEFAULT_ICON).isEqualTo("lightning")
        assertThat(CursorIcons.project("logo-notion")).isSameInstanceAs(ProjectIcons.vector("logo-notion"))
    }

    @Test
    fun `brand marks are drawn as filled silhouettes, line icons as strokes, and the Cursor mark is the app's own cube`() {
        val notion = paths(ProjectIcons.vector("logo-notion").root).single()
        assertThat(notion.fill).isEqualTo(androidx.compose.ui.graphics.SolidColor(Color.Black))
        assertThat(notion.stroke).isNull()
        val rocket = paths(ProjectIcons.vector("rocket").root)
        assertThat(rocket.size).isAtLeast(2)
        rocket.forEach {
            assertThat(it.stroke).isEqualTo(androidx.compose.ui.graphics.SolidColor(Color.Black))
            assertThat(it.fill).isNull()
            assertThat(it.strokeLineWidth).isEqualTo(1.75f)
        }
        val star = paths(ProjectIcons.vector("star-full").root).single()
        assertThat(star.fill).isNotNull()
        assertThat(star.stroke).isNotNull()
        assertThat(ProjectIcons.vector("cursor-logo")).isSameInstanceAs(CursorIcons.Cube)
        assertThat(rotation(ProjectIcons.vector("triangle-small-down"))).isEqualTo(180f)
        assertThat(rotation(ProjectIcons.vector("triangle-small-up"))).isEqualTo(0f)
    }

    @Test
    fun `stand-ins are picker ids, few, and named for the marks no open library carries`() {
        assertThat(ProjectIconCatalog.STAND_INS).containsAtLeast("logo-slack", "logo-vscode", "logo-microsoft-teams", "logo-azure", "file-type-java", "file-type-c-sharp")
        assertThat(ProjectIconCatalog.STAND_INS.size).isAtMost(30)
        ProjectIconCatalog.STAND_INS.forEach { assertThat(it).isIn(ProjectIcons.ids) }
        assertThat(ProjectIcons.isStandIn("logo-slack")).isTrue()
        assertThat(ProjectIcons.isStandIn("logo-notion")).isFalse()
        assertThat(ProjectIcons.isStandIn("rocket")).isFalse()
    }

    @Test
    fun `labels use the brand's spelling and read the ids as words`() {
        assertThat(ProjectIcons.label("logo-notion")).isEqualTo("Notion")
        assertThat(ProjectIcons.label("github-actions")).isEqualTo("GitHub Actions")
        assertThat(ProjectIcons.label("file-type-c-plus-plus")).isEqualTo("C++")
        assertThat(ProjectIcons.label("file-type-vue")).isEqualTo("Vue.js")
        assertThat(ProjectIcons.label("logo-slack")).isEqualTo("Slack")
        assertThat(ProjectIcons.label("board-kanban")).isEqualTo("Board kanban")
        assertThat(ProjectIcons.label("chatBubble")).isEqualTo("Chat bubble")
        assertThat(ProjectIcons.label("mark-github")).isEqualTo("GitHub")
        assertThat(ProjectIcons.label("rocket")).isEqualTo("Rocket")
    }

    @Test
    fun `search matches every term against the id and the label, the way the desktop does`() {
        assertThat(ProjectIcons.search("")).isEqualTo(ProjectIcons.ids)
        assertThat(ProjectIcons.search("notion")).containsExactly("logo-notion")
        assertThat(ProjectIcons.search("NOTION")).containsExactly("logo-notion")
        assertThat(ProjectIcons.search("git pull")).containsExactly("git-pull", "git-pull-request", "git-pull-request-closed", "git-pull-request-create", "git-pull-request-done", "git-pull-request-draft")
        assertThat(ProjectIcons.search("pull/request draft")).containsExactly("git-pull-request-draft")
        assertThat(ProjectIcons.search("vue")).containsExactly("file-type-vue")
        assertThat(ProjectIcons.search("no such thing")).isEmpty()
        assertThat(ProjectIcons.search("rocket", within = listOf("rocket", "star"))).containsExactly("rocket")
    }

    @Test
    fun `the palette is the desktop's ten tones with the Agents Window's dark and light colours`() {
        assertThat(ProjectPalette.ids).containsExactly("default", "green", "cyan", "blue", "purple", "magenta", "orange", "yellow", "red", "brand").inOrder()
        assertThat(ProjectPalette.color("purple", dark = true)).isEqualTo(Color(0xFF9386F2))
        assertThat(ProjectPalette.color("purple", dark = false)).isEqualTo(Color(0xFF7565CC))
        assertThat(ProjectPalette.color("blue", dark = true)).isEqualTo(Color(0xFF7BAFE9))
        assertThat(ProjectPalette.color("red", dark = true)).isEqualTo(Color(0xFFFC6B83))
        assertThat(ProjectPalette.color("red", dark = false)).isEqualTo(Color(0xFFBE1744))
        assertThat(ProjectPalette.color("brand", dark = true)).isEqualTo(Color(0xFFF54E00))
        assertThat(ProjectPalette.color("brand", dark = false)).isEqualTo(Color(0xFFF54E00))
        assertThat(ProjectPalette.color("default", dark = true)).isNull()
        assertThat(ProjectPalette.color("chartreuse", dark = true)).isNull()
        assertThat(ProjectPalette.color(" Green ", dark = true)).isEqualTo(Color(0xFF3FA266))
        assertThat(ProjectPalette.isKnown("magenta")).isTrue()
        assertThat(ProjectPalette.isKnown("pink")).isFalse()
        assertThat(ProjectPalette.label("cyan")).isEqualTo("Cyan")
        assertThat(ProjectPalette.label("pink")).isEqualTo("pink")
    }

    @Test
    fun `the theme paints a Project's tone from the palette for its darkness, and default as the secondary icon tone`() {
        assertThat(CursorDarkColors.projectTone("green")).isEqualTo(Color(0xFF3FA266))
        assertThat(CursorOledColors.projectTone("green")).isEqualTo(Color(0xFF3FA266))
        assertThat(CursorLightColors.projectTone("green")).isEqualTo(Color(0xFF007041))
        assertThat(CursorDarkColors.projectTone("default")).isEqualTo(CursorDarkColors.iconSecondary)
        assertThat(CursorDarkColors.projectTone(null)).isEqualTo(CursorDarkColors.iconSecondary)
        assertThat(CursorLightColors.projectTone("no-such-tone")).isEqualTo(CursorLightColors.iconSecondary)
    }
}
