package com.cursorforandroid.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The ten tones a Cursor Project can be given (`ProjectAppearance.colorId`), with the colours the Agents Window
 * paints them in. The desktop resolves an id to `--cursor-icon-<id>-primary` (`agent-appearance.js`), which in
 * the Agents Window comes from Cursor's own design tokens rather than the editor theme: the `dark` and `light`
 * palettes of `cursor-core-themes.js` (Cursor 3.20.17), OKLCH values converted to sRGB here. `default` is not a
 * colour of its own but the secondary icon tone (66 % of the theme foreground), and `brand` is the Cursor orange in
 * both themes. An id this build has not heard of reads as `default`.
 */
object ProjectPalette {

    /** One tone: its id as the account records it, its label as the picker shows it, and its colour per theme. */
    data class Tone(val id: String, val label: String, val dark: Color, val light: Color)

    const val DEFAULT_ID = "default"

    /** The tones in the picker's order. */
    val tones: List<Tone> = listOf(
        Tone("default", "Default", Color.Unspecified, Color.Unspecified),
        Tone("green", "Green", Color(0xFF3FA266), Color(0xFF007041)),
        Tone("cyan", "Cyan", Color(0xFF81A1C1), Color(0xFF166C74)),
        Tone("blue", "Blue", Color(0xFF7BAFE9), Color(0xFF2678C1)),
        Tone("purple", "Purple", Color(0xFF9386F2), Color(0xFF7565CC)),
        Tone("magenta", "Magenta", Color(0xFFB48EAD), Color(0xFF92156A)),
        Tone("orange", "Orange", Color(0xFFD08770), Color(0xFFCD4500)),
        Tone("yellow", "Yellow", Color(0xFFF1B467), Color(0xFFA46701)),
        Tone("red", "Red", Color(0xFFFC6B83), Color(0xFFBE1744)),
        Tone("brand", "Brand", Color(0xFFF54E00), Color(0xFFF54E00)),
    )

    /** The ids in the picker's order. */
    val ids: List<String> = tones.map { it.id }

    private val byId: Map<String, Tone> = tones.associateBy { it.id }

    /** The tone [colorId] names, or null for `default` and for an id the palette does not know. */
    fun tone(colorId: String?): Tone? = byId[colorId?.trim()?.lowercase()]?.takeIf { it.id != DEFAULT_ID }

    /** The colour of [colorId] in a dark or light theme, or null where the theme's own secondary icon tone applies. */
    fun color(colorId: String?, dark: Boolean): Color? = tone(colorId)?.let { if (dark) it.dark else it.light }

    /** True when the account would accept [colorId]. */
    fun isKnown(colorId: String?): Boolean = colorId?.trim()?.lowercase() in byId

    /** The label the picker shows for [colorId]; unknown ids read as their own text. */
    fun label(colorId: String): String = byId[colorId.trim().lowercase()]?.label ?: colorId
}
