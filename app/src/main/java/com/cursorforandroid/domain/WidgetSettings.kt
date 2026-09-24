package com.cursorforandroid.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The look every home-screen widget of this app shares — theme and how much of the wallpaper shows through —
 * kept apart from what a widget shows so that another widget kind (the new-chat shortcut, say) carries the same
 * two settings, edited by the same section of the configuration screen (see `WidgetOptionsRegistry`).
 */
@Serializable
data class WidgetAppearance(
    val theme: WidgetTheme = WidgetTheme.App,
    /** Surface opacity, [MIN_OPACITY]..100 percent; the wallpaper shows through the rest. */
    val opacity: Int = 100,
) {
    val opacityFraction: Float get() = opacity.coerceIn(MIN_OPACITY, 100) / 100f

    companion object {
        const val MIN_OPACITY = 40
        val OPACITY_STEPS: List<Int> = listOf(100, 85, 70, 55, 40)
    }
}

/**
 * Which colours a widget draws with. [App] is the app's own theme setting — what every widget did before this was a
 * choice — and the rest pin one: [System] is a day / night pair the launcher resolves itself, [Oled] is Cursor Dark
 * on true black.
 */
@Serializable
enum class WidgetTheme(val label: String) {
    App("App"),
    System("System"),
    Light("Light"),
    Dark("Dark"),
    Oled("OLED"),
}

/** How the widget lays itself out. [Auto] follows the cell size the launcher gives it; the rest force one arrangement. */
@Serializable
enum class WidgetLayout(val label: String) {
    Auto("Auto"),
    /** A single line: the list's name, how many rows it has, the corner action. For a 2x1 cell. */
    Small("Small"),
    /** The header and one-line rows with trailing metadata. The 4x2 default. */
    Medium("Medium"),
    /** The header and two-line rows — the metadata under the title — for a tall placement. */
    Large("Large"),
}

/** The pitch of the rows. The sidebar's is [Regular]. */
@Serializable
enum class RowDensity(val label: String, val rowHeightDp: Int, val titleSp: Int) {
    Compact("Compact", 30, 12),
    Regular("Regular", 36, 13),
    Comfortable("Comfortable", 42, 14),
}

/** What the round button in the widget's bottom-right corner does. */
@Serializable
enum class CornerAction(val label: String) {
    NewChat("New chat"),
    Refresh("Refresh"),
    Search("Search"),
    OpenApp("Open app"),
    None("None"),
}

/** How that button is drawn. */
@Serializable
enum class CornerStyle(val label: String) {
    /** A white disc with a dark glyph, whatever the theme: the launcher-idiom shortcut button. */
    White("White"),
    /** The app's accent as the fill, the glyph in the colour drawn on it. */
    Tinted("Tinted"),
    /** A translucent disc in the text colour's faint alpha, the glyph in the secondary icon tone. */
    Glass("Glass"),
}

/** The parts of a row that can be shown or hidden. */
@Serializable
enum class RowElement(val label: String) {
    /** The state glyph in the leading slot: working, failed, pull request, branch. */
    Status("Status glyph"),
    /** The blue unread dot in the same slot. */
    UnreadDot("Unread dot"),
    /** The repository's short name. */
    Repo("Repository"),
    /** How long ago the chat was active. */
    Time("Time"),
}

/** Reads the elements a later build may have added as nothing, rather than as a record that cannot be read. */
object RowElementSetSerializer : LenientEnumSetSerializer<RowElement>(RowElement.entries)

/**
 * Everything one "Chats" widget instance is set to. Kept as JSON in the widget's own Glance state, per instance:
 * two widgets on two home screens can list two Projects in two themes. Unknown fields and names from a later
 * build decode to the defaults rather than failing the whole record (see [decode]).
 */
@Serializable
data class ChatsWidgetSettings(
    val mode: WidgetMode = WidgetMode.Default,
    /** The Project whose chats a [WidgetMode.Project] widget lists; null until one is picked. */
    val projectId: String? = null,
    val layout: WidgetLayout = WidgetLayout.Auto,
    val appearance: WidgetAppearance = WidgetAppearance(),
    val density: RowDensity = RowDensity.Regular,
    @Serializable(with = RowElementSetSerializer::class) val elements: Set<RowElement> = DEFAULT_ELEMENTS,
    @SerialName("corner_action") val cornerAction: CornerAction = CornerAction.NewChat,
    @SerialName("corner_style") val cornerStyle: CornerStyle = CornerStyle.White,
) {
    fun shows(element: RowElement): Boolean = element in elements

    fun toggled(element: RowElement): ChatsWidgetSettings =
        copy(elements = if (element in elements) elements - element else elements + element)

    fun encode(json: Json = WidgetSettingsJson): String = json.encodeToString(serializer(), this)

    companion object {
        val DEFAULT_ELEMENTS: Set<RowElement> = setOf(RowElement.Status, RowElement.UnreadDot, RowElement.Repo, RowElement.Time)

        /** The settings a stored record stands for; the defaults for a record no build of this app wrote. */
        fun decode(raw: String?, json: Json = WidgetSettingsJson): ChatsWidgetSettings =
            raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: ChatsWidgetSettings()

        /** A widget placed by a build that kept only the list's name (`mode`), read into the settings it stands for. */
        fun fromLegacyMode(modeName: String?): ChatsWidgetSettings = ChatsWidgetSettings(mode = WidgetMode.parse(modeName))
    }
}

/**
 * Lenient on purpose: a setting this build does not know, or a value renamed since, costs that one field its
 * default and nothing else. `coerceInputValues` is what turns an unknown enum name into the default (for the set of
 * elements, [RowElementSetSerializer] does the same one entry at a time).
 */
val WidgetSettingsJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}
