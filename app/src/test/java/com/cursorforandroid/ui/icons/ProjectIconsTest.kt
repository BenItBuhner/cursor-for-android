package com.cursorforandroid.ui.icons

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
import org.junit.Test
import org.junit.runner.RunWith

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
        // Distinct ids may share a glyph on purpose (`squares` and `copy`, the six letter icons), but the catalog is
        // not a handful of glyphs under many names: well over four hundred different drawings.
        assertThat(distinct.size).isAtLeast(450)
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
        assertThat(ProjectIconCatalog.STAND_INS.size).isAtMost(40)
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
