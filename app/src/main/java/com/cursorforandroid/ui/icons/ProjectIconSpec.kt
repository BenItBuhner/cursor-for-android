package com.cursorforandroid.ui.icons

/**
 * How one Project icon is drawn: its path data on the 24-unit grid and the style it takes. [Style.STROKE] is a
 * Lucide line glyph, [Style.FILLED_STROKE] the same outline filled as well (the solid star, the small triangles,
 * which [rotation] turns about the centre), [Style.FILL] a Simple Icons silhouette, and [Style.CURSOR_CUBE] the
 * app's own Cursor mark (`CursorIcons.Cube`), which carries no paths of its own here.
 */
internal class ProjectIconSpec(val style: Style, val paths: Array<out String>, val rotation: Float) {
    enum class Style { STROKE, FILLED_STROKE, FILL, CURSOR_CUBE }
}

/** A section of the icon picker: a heading and the ids under it, in the desktop's order. */
data class ProjectIconGroup(val label: String, val ids: List<String>)
