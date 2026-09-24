package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** One widget's settings on disk: a round trip, what an older build left, and what a newer one may leave. */
class WidgetSettingsTest {

    @Test
    fun `settings survive a round trip through their JSON`() {
        val settings = ChatsWidgetSettings(
            mode = WidgetMode.Project,
            projectId = "bc-project",
            layout = WidgetLayout.Large,
            appearance = WidgetAppearance(WidgetTheme.Oled, opacity = 55),
            density = RowDensity.Compact,
            elements = setOf(RowElement.Status, RowElement.Time),
            cornerAction = CornerAction.Search,
            cornerStyle = CornerStyle.Glass,
        )
        assertThat(ChatsWidgetSettings.decode(settings.encode())).isEqualTo(settings)
        val projects = ChatsWidgetSettings(mode = WidgetMode.Projects, layout = WidgetLayout.Small, cornerAction = CornerAction.OpenApp)
        assertThat(ChatsWidgetSettings.decode(projects.encode())).isEqualTo(projects)
    }

    @Test
    fun `a widget placed by a build that kept only the list's name reads as that list on the defaults`() {
        assertThat(ChatsWidgetSettings.fromLegacyMode("Pinned")).isEqualTo(ChatsWidgetSettings(mode = WidgetMode.Pinned))
        assertThat(ChatsWidgetSettings.fromLegacyMode(null)).isEqualTo(ChatsWidgetSettings())
        assertThat(ChatsWidgetSettings.fromLegacyMode("NotAList")).isEqualTo(ChatsWidgetSettings())
    }

    @Test
    fun `a record from a later build costs the fields it cannot read their defaults and nothing else`() {
        val raw = """{"mode":"Running","layout":"Gigantic","density":"Compact","corner_action":"Teleport","sparkle":true,"elements":["Repo","Glitter"]}"""
        val decoded = ChatsWidgetSettings.decode(raw)
        assertThat(decoded.mode).isEqualTo(WidgetMode.Running)
        assertThat(decoded.density).isEqualTo(RowDensity.Compact)
        assertThat(decoded.layout).isEqualTo(WidgetLayout.Auto)
        assertThat(decoded.cornerAction).isEqualTo(CornerAction.NewChat)
        assertThat(decoded.elements).containsExactly(RowElement.Repo)
    }

    @Test
    fun `garbage and nothing both read as the defaults`() {
        assertThat(ChatsWidgetSettings.decode(null)).isEqualTo(ChatsWidgetSettings())
        assertThat(ChatsWidgetSettings.decode("not json")).isEqualTo(ChatsWidgetSettings())
    }

    @Test
    fun `an element toggles on and off`() {
        val without = ChatsWidgetSettings().toggled(RowElement.Repo)
        assertThat(without.shows(RowElement.Repo)).isFalse()
        assertThat(without.shows(RowElement.Time)).isTrue()
        assertThat(without.toggled(RowElement.Repo).shows(RowElement.Repo)).isTrue()
    }

    @Test
    fun `the header shows the list's name, refresh and new chat, hides the logo, and hides itself when short`() {
        val defaults = ChatsWidgetSettings()
        assertThat(defaults.header).containsExactly(HeaderElement.Title, HeaderElement.Refresh, HeaderElement.NewChat)
        assertThat(defaults.shows(HeaderElement.Logo)).isFalse()
        assertThat(defaults.headerAutoHide).isTrue()
        // A record from before the header was a choice reads as the defaults too.
        assertThat(ChatsWidgetSettings.decode("""{"mode":"Recent"}""").header).isEqualTo(ChatsWidgetSettings.DEFAULT_HEADER)
        assertThat(ChatsWidgetSettings.decode("""{"mode":"Recent"}""").headerAutoHide).isTrue()
    }

    @Test
    fun `the header's parts survive the round trip, and a part a later build added is dropped alone`() {
        val settings = ChatsWidgetSettings(header = setOf(HeaderElement.Logo, HeaderElement.Refresh), headerAutoHide = false)
        assertThat(ChatsWidgetSettings.decode(settings.encode())).isEqualTo(settings)
        val bare = ChatsWidgetSettings(header = emptySet())
        assertThat(ChatsWidgetSettings.decode(bare.encode())).isEqualTo(bare)
        val later = ChatsWidgetSettings.decode("""{"header":["Title","Clock"],"header_auto_hide":false}""")
        assertThat(later.header).containsExactly(HeaderElement.Title)
        assertThat(later.headerAutoHide).isFalse()
    }

    @Test
    fun `the header leaves out the button the corner button already is`() {
        val all = ChatsWidgetSettings(header = HeaderElement.entries.toSet())
        assertThat(all.headerParts).containsExactly(HeaderElement.Title, HeaderElement.Logo, HeaderElement.Refresh)
        assertThat(all.copy(cornerAction = CornerAction.Refresh).headerParts).containsExactly(HeaderElement.Title, HeaderElement.Logo, HeaderElement.NewChat)
        assertThat(all.copy(cornerAction = CornerAction.None).headerParts).containsExactlyElementsIn(HeaderElement.entries)
        // What is stored stays as it was, so moving the corner button back brings the part back.
        assertThat(all.copy(cornerAction = CornerAction.Refresh).header).containsExactlyElementsIn(HeaderElement.entries)
        assertThat(all.toggled(HeaderElement.Logo).shows(HeaderElement.Logo)).isFalse()
    }

    @Test
    fun `opacity is read within its bounds`() {
        assertThat(WidgetAppearance(opacity = 200).opacityFraction).isEqualTo(1f)
        assertThat(WidgetAppearance(opacity = 0).opacityFraction).isEqualTo(WidgetAppearance.MIN_OPACITY / 100f)
        assertThat(WidgetAppearance(opacity = 70).opacityFraction).isWithin(0.001f).of(0.7f)
    }
}
