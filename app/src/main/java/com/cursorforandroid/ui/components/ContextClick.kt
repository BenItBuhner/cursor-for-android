package com.cursorforandroid.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round

/**
 * A context click on this node: a mouse's secondary button, a trackpad's two-finger click. [onMenu] gets where it
 * went in window coordinates, for a [CursorMenu]'s `at`, so the menu a long press opens here opens at the pointer.
 * Shift+F10 or the Menu key, while this node or something in it has focus, calls [onMenu] with null: the menu opens
 * beside its anchor, as it does for a long press.
 *
 * The press, the release and whatever moves between them are consumed on their way in (the initial pass), so nothing
 * under this node, a click, a link or a long press, sees them. A touch or the primary button goes by untouched.
 * Put it before the node's own click and focus modifiers, so it sees the pointer first and the focused node's keys.
 */
fun Modifier.onContextClick(enabled: Boolean = true, onMenu: (at: IntOffset?) -> Unit): Modifier =
    if (enabled) this then ContextClickElement(onMenu) else this

/** Whether [event] is a context click going down: the secondary button pressed with the primary not held. */
internal fun isContextPress(event: PointerEvent): Boolean =
    event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !event.buttons.isPrimaryPressed

private data class ContextClickElement(val onMenu: (IntOffset?) -> Unit) : ModifierNodeElement<ContextClickNode>() {
    override fun create() = ContextClickNode(onMenu)

    override fun update(node: ContextClickNode) {
        node.onMenu = onMenu
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "onContextClick"
    }
}

private class ContextClickNode(var onMenu: (IntOffset?) -> Unit) : DelegatingNode(), KeyInputModifierNode {
    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitPointerEventScope {
                    while (true) {
                        val press = awaitPointerEvent(PointerEventPass.Initial)
                        if (!isContextPress(press)) continue
                        press.changes.forEach { it.consume() }
                        val at = press.changes.first().position
                        // The menu opens on the release, as the desktop's context menus do; everything up to it is ours.
                        while (true) {
                            val next = awaitPointerEvent(PointerEventPass.Initial)
                            next.changes.forEach { it.consume() }
                            if (next.changes.none { it.pressed }) break
                        }
                        open(at)
                    }
                }
            },
        )
    }

    private fun open(at: Offset) {
        if (!isAttached) return
        onMenu(requireLayoutCoordinates().localToWindow(at).round())
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val menuKey = event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed)
        if (menuKey) onMenu(null)
        return menuKey
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean = false
}
