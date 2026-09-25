package com.cursorforandroid.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import kotlinx.coroutines.launch

/**
 * Keeps the focus of a field inside this box, and so its keyboard, when the content it is in is moved rather than made
 * again: the shell's detail pane going between its narrow and wide layouts as a fold, an unfold or a turn changes the
 * window (the movable content in `AppShell`). A move detaches the field's nodes and attaches them again where the
 * content went, and Compose takes focus from a focused node as it is detached; a field that held focus then asks for
 * it back once it is attached again, its text and caret as they were. Focus let go any other way stays gone, as it
 * does for content that leaves the composition or is reset to show other content, as a lazy list's rows are.
 *
 * It belongs on a box around the field, not on the field: a node hears it is being detached before the nodes inside
 * it do, while the field still has focus.
 */
fun Modifier.keepsFocusWhenMoved(): Modifier = this then KeepsFocusWhenMovedElement

private object KeepsFocusWhenMovedElement : ModifierNodeElement<KeepsFocusWhenMovedNode>() {
    override fun create() = KeepsFocusWhenMovedNode()

    override fun update(node: KeepsFocusWhenMovedNode) = Unit

    override fun hashCode(): Int = "keepsFocusWhenMoved".hashCode()

    override fun equals(other: Any?): Boolean = other === this

    override fun InspectorInfo.inspectableProperties() {
        name = "keepsFocusWhenMoved"
    }
}

private class KeepsFocusWhenMovedNode : Modifier.Node(), FocusEventModifierNode, FocusRequesterModifierNode {
    private var focused = false

    /** Reset to show other content: the detach that follows is no move. */
    private var reset = false

    /** Detached while a field inside had focus, until it is attached again. */
    private var movedWithFocus = false

    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.hasFocus
    }

    override fun onReset() {
        reset = true
    }

    override fun onDetach() {
        movedWithFocus = focused && !reset
        reset = false
    }

    override fun onAttach() {
        if (!movedWithFocus) return
        movedWithFocus = false
        // Asked for once the move has been applied: until then the focus the detach let go is still being settled.
        coroutineScope.launch { requestFocus() }
    }
}
