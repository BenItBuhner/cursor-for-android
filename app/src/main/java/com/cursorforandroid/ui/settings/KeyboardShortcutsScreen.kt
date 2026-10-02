package com.cursorforandroid.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.fadingVerticalScroll
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.shortcuts.ConflictResolution
import com.cursorforandroid.ui.shortcuts.KeyChord
import com.cursorforandroid.ui.shortcuts.LocalKeyboardShortcuts
import com.cursorforandroid.ui.shortcuts.Shortcut
import com.cursorforandroid.ui.shortcuts.ShortcutBindings
import com.cursorforandroid.ui.shortcuts.ShortcutLineRow
import com.cursorforandroid.ui.shortcuts.ShortcutsCopy
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

object KeyboardShortcutsTags {
    const val PAGE = "keyboard_shortcuts_page"
    const val CAPTURE = "keyboard_shortcuts_capture"
    const val SWAP = "keyboard_shortcuts_swap"
    const val REPLACE = "keyboard_shortcuts_replace"
    const val CANCEL = "keyboard_shortcuts_cancel"
    const val RESET_ALL = "keyboard_shortcuts_reset_all"

    fun row(shortcut: Shortcut) = "keyboard_shortcut_${shortcut.id}"

    /** The outlined field on [shortcut]'s row that shows its keys, and waits for new ones. */
    fun field(shortcut: Shortcut) = "keyboard_shortcut_field_${shortcut.id}"

    fun reset(shortcut: Shortcut) = "keyboard_shortcut_reset_${shortcut.id}"

    /** The card of a group of shortcuts, by its title: General, Composer, Media. */
    fun group(title: String) = "keyboard_shortcuts_group_${title.lowercase()}"
}

object KeyboardShortcutsCopy {
    const val HINT = "Tap a shortcut to change it."
    const val FOOTER = "Shortcuts work from a hardware keyboard, with the composer focused too. The on-screen keyboard is left alone."
    const val PRESS = "Press the new keys. Esc cancels."
    const val PLACEHOLDER = "Press keys…"
    const val WAITING = "Waiting for keys"
    const val SWAP = "Swap"
    const val REPLACE = "Replace"
    const val CANCEL = "Cancel"
    const val RESET_ALL = "Reset all shortcuts"
    const val RESET_ALL_DETAIL = "Puts every shortcut back on its default keys."
    const val ALL_DEFAULT = "Every shortcut is on its default keys."

    fun keys(chords: List<KeyChord>): String = chords.joinToString(" or ") { it.label }

    fun resetTo(shortcut: Shortcut) = "Reset to ${keys(shortcut.defaults)}"

    /** "Ctrl+B is already on “Show or hide the sidebar”." */
    fun taken(change: PendingChange): String = change.conflicts.joinToString(" ") { (chord, owner) ->
        if (change.reset) "${chord.label} is on “${owner.label}” now." else "${chord.label} is already on “${owner.label}”."
    }

    /** "Swap moves it to Ctrl+Shift+E. Replace leaves it with no shortcut." */
    fun choice(swapTo: List<KeyChord>, replaceLeaves: List<KeyChord>): String {
        val replace = if (replaceLeaves.isEmpty()) "Replace leaves it with no shortcut." else "Replace leaves it on ${keys(replaceLeaves)}."
        return if (swapTo.isEmpty()) replace else "Swap moves it to ${keys(swapTo)}. $replace"
    }
}

private val FieldMinWidth = 104.dp
private val FieldHeight = 34.dp

/**
 * Settings › Keyboard shortcuts. Every shortcut that can move shows its keys in an outlined field on the right of its
 * row, as an input would: tapped, the field waits for the new keys ([ShortcutCapture]), says so when they are kept for
 * something else, and asks whether to swap or replace when another shortcut is on them — never leaving two on the
 * same keys. A moved shortcut has a reset beside its field. The keys that never move (Ctrl+Tab, Ctrl+1 … 0, Esc, the
 * composer's and the media viewer's own) sit among them in their group — General, Composer, Media — as plain key caps
 * with no field. [onChange] saves; [bindings] is what is saved.
 */
@Composable
fun KeyboardShortcutsScreen(
    bindings: ShortcutBindings,
    onChange: (ShortcutBindings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    // Shown at once, ahead of the store's write coming back, so a second change builds on the first.
    var shown by remember { mutableStateOf(bindings) }
    LaunchedEffect(bindings) { shown = bindings }
    val save by rememberUpdatedState(onChange)
    val capture = remember {
        ShortcutCapture(bindings = { shown }) { next ->
            shown = next
            save(next)
        }
    }
    val capturing = capture.target != null
    val keyboard = LocalKeyboardShortcuts.current
    if (capturing && keyboard != null) {
        DisposableEffect(keyboard) {
            val release = keyboard.capture(capture::onKey)
            onDispose { release() }
        }
    }
    BackHandler(enabled = capturing) { capture.cancel() }

    val groups = ShortcutsCopy.groups(shown)

    Column(modifier.fillMaxSize().background(colors.canvas).testTag(KeyboardShortcutsTags.PAGE)) {
        CursorHeader(
            title = ShortcutsCopy.TITLE,
            leading = { FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onBack) },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .navigationBarsPadding()
                .fadingVerticalScroll(surface = colors.canvas)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A reading column, centred on a wide pane.
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Row(Modifier.padding(top = 12.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(CursorIcons.Pencil, null, tint = colors.iconTertiary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(KeyboardShortcutsCopy.HINT, style = type.small, color = colors.textSecondary)
                }
                groups.forEach { group ->
                    Group(group.title)
                    group.note?.let { Text(it, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)) }
                    SettingsCard(Modifier.testTag(KeyboardShortcutsTags.group(group.title))) {
                        group.lines.forEachIndexed { index, line ->
                            if (index > 0) HairlineDivider()
                            val shortcut = line.shortcut
                            if (shortcut != null) {
                                BindingRow(line, shortcut, customized = !shown.isDefault(shortcut), capture = capture)
                            } else {
                                ShortcutLineRow(line, Modifier.heightIn(min = CursorDimens.listRow).padding(horizontal = RowInset, vertical = 11.dp))
                            }
                        }
                    }
                }
                SettingsCard(Modifier.padding(top = 18.dp)) {
                    SettingsRow(
                        title = KeyboardShortcutsCopy.RESET_ALL,
                        description = if (shown.isAllDefault) KeyboardShortcutsCopy.ALL_DEFAULT else KeyboardShortcutsCopy.RESET_ALL_DETAIL,
                        modifier = Modifier.testTag(KeyboardShortcutsTags.RESET_ALL),
                        onClick = capture::resetAll,
                        enabled = !shown.isAllDefault,
                        leading = { RowGlyph(CursorIcons.Reset) },
                    )
                }
                Text(
                    "${KeyboardShortcutsCopy.FOOTER} ${ShortcutsCopy.TEXT_EDITING}",
                    style = type.small,
                    color = colors.textQuaternary,
                    modifier = Modifier.padding(top = 12.dp, start = 2.dp),
                )
            }
        }
    }
}

/** A shortcut whose keys can change: its keys in a field to tap, which then waits for new ones above the panel. */
@Composable
private fun BindingRow(line: ShortcutsCopy.Line, shortcut: Shortcut, customized: Boolean, capture: ShortcutCapture) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val capturing = capture.target == shortcut
    Column(Modifier.fillMaxWidth().background(if (capturing) colors.fillFaint else Color.Transparent)) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag(KeyboardShortcutsTags.row(shortcut))
                .pressable({ if (capturing) capture.cancel() else capture.start(shortcut) }, RectangleShape)
                .semantics { if (capturing) stateDescription = KeyboardShortcutsCopy.WAITING }
                .heightIn(min = CursorDimens.listRow)
                .padding(horizontal = RowInset, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(line.label, style = type.base, color = colors.textPrimary)
                line.detail?.let { Text(it, style = type.small, color = colors.textTertiary) }
            }
            Spacer(Modifier.width(12.dp))
            if (customized && !capturing) {
                FlatIconButton(
                    CursorIcons.Reset,
                    KeyboardShortcutsCopy.resetTo(shortcut),
                    onClick = { capture.reset(shortcut) },
                    modifier = Modifier.testTag(KeyboardShortcutsTags.reset(shortcut)),
                    size = 28.dp,
                    iconSize = 14.dp,
                    tint = colors.iconTertiary,
                )
                Spacer(Modifier.width(4.dp))
            }
            KeysField(
                active = capturing,
                warning = capturing && capture.problem != null,
                modifier = Modifier.testTag(KeyboardShortcutsTags.field(shortcut)),
            ) {
                if (capturing) CapturingKeys(capture) else IdleKeys(line.chords)
            }
        }
        if (capturing) CapturePanel(capture)
    }
}

/**
 * The outlined box a shortcut's keys sit in, drawn as an input: a faint well with a clear border, the keys at its end.
 * [active] while it waits for keys — the accent border breathing, the well tinted; [warning] once the keys pressed
 * cannot be used.
 */
@Composable
private fun KeysField(active: Boolean, warning: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.base
    val pulse = if (active && !warning) {
        rememberInfiniteTransition(label = "keysFieldPulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "border",
        ).value
    } else {
        1f
    }
    val border = when {
        warning -> colors.orange
        active -> colors.accent.copy(alpha = colors.accent.alpha * pulse)
        else -> colors.strokeStrong
    }
    Row(
        modifier
            .widthIn(min = FieldMinWidth)
            .height(FieldHeight)
            .background(if (active) colors.accent.copy(alpha = 0.08f) else colors.fillFaint, shape)
            .border(if (active) 1.5.dp else 1.dp, border, shape)
            .padding(start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** A shortcut's keys at rest, with the pencil that says they can change; "Not set" in an empty field for none. */
@Composable
private fun IdleKeys(chords: List<List<String>>) {
    val colors = CursorTheme.colors
    if (chords.isEmpty()) {
        Text(ShortcutsCopy.NOT_SET, style = CursorTheme.typography.small, color = colors.textQuaternary, maxLines = 1)
    }
    chords.forEachIndexed { index, chord ->
        if (index > 0) Text("or", style = CursorTheme.typography.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 2.dp))
        chord.forEach { FieldKey(it) }
    }
    Spacer(Modifier.width(2.dp))
    Icon(CursorIcons.Pencil, null, tint = colors.iconQuaternary, modifier = Modifier.size(12.dp))
}

/** What the field shows while it waits: the chord waiting on a choice, the modifiers held so far, or the placeholder. */
@Composable
private fun CapturingKeys(capture: ShortcutCapture) {
    val pending = capture.pending?.takeUnless { it.reset }?.chords?.single()
    when {
        pending != null -> pending.keys.forEach { FieldKey(it) }
        capture.held.isNotEmpty() -> {
            capture.held.forEach { FieldKey(it) }
            Text("…", style = CursorTheme.typography.small, color = CursorTheme.colors.accent)
        }
        else -> Text(KeyboardShortcutsCopy.PLACEHOLDER, style = CursorTheme.typography.small, color = CursorTheme.colors.accent, maxLines = 1)
    }
}

/** One key inside the field: a filled cap with no border of its own, the field being the outline. */
@Composable
private fun FieldKey(label: String) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.sm
    Box(
        Modifier.defaultMinSize(minWidth = 20.dp).height(20.dp).background(colors.fill, shape).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = CursorTheme.typography.small.copy(fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary, maxLines = 1)
    }
}

/**
 * Under the row being changed: the prompt, why the keys pressed cannot be used, or who is on them already with the
 * swap and the replace spelled out; and the buttons. With no hardware-keyboard reader around it (previews, tests of the
 * page alone) it reads the keys itself, focused as it opens.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun CapturePanel(capture: ShortcutCapture) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val focus = remember { FocusRequester() }
    val inView = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val clearFade = with(LocalDensity.current) { CursorDimens.scrollFade.toPx() }
    LaunchedEffect(capture.target) { runCatching { focus.requestFocus() } }
    val pending = capture.pending
    // Opened on the last rows, or grown by a conflict's buttons, the panel would sit under the page's bottom edge.
    LaunchedEffect(capture.target, pending, capture.problem, size) {
        if (size != IntSize.Zero) inView.bringIntoView(Rect(0f, 0f, size.width.toFloat(), size.height + clearFade))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { size = it }
            .bringIntoViewRequester(inView)
            .focusRequester(focus)
            .onPreviewKeyEvent { capture.onKey(it.nativeKeyEvent) }
            .focusable()
            .testTag(KeyboardShortcutsTags.CAPTURE)
            .padding(start = RowInset, end = RowInset, bottom = 12.dp),
    ) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when {
                pending != null -> {
                    Text(KeyboardShortcutsCopy.taken(pending), style = type.small, color = colors.textPrimary)
                    val owner = pending.conflicts.first().second
                    Text(
                        KeyboardShortcutsCopy.choice(capture.swapGives(pending), capture.replaceLeaves(pending, owner)),
                        style = type.small,
                        color = colors.textTertiary,
                    )
                }
                capture.problem != null -> Text(capture.problem.orEmpty(), style = type.small, color = colors.orange)
                else -> Text(KeyboardShortcutsCopy.PRESS, style = type.small, color = colors.textTertiary)
            }
        }
        Spacer(Modifier.height(10.dp))
        // Wraps on a narrow pane rather than pushing Swap out of sight.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CursorButton(KeyboardShortcutsCopy.CANCEL, onClick = capture::cancel, height = 30.dp, modifier = Modifier.testTag(KeyboardShortcutsTags.CANCEL))
            if (pending != null) {
                val swap = capture.swapGives(pending).isNotEmpty()
                CursorButton(
                    KeyboardShortcutsCopy.REPLACE,
                    onClick = { capture.resolve(ConflictResolution.Replace) },
                    primary = !swap,
                    height = 30.dp,
                    modifier = Modifier.testTag(KeyboardShortcutsTags.REPLACE),
                )
                if (swap) {
                    CursorButton(
                        KeyboardShortcutsCopy.SWAP,
                        onClick = { capture.resolve(ConflictResolution.Swap) },
                        primary = true,
                        height = 30.dp,
                        modifier = Modifier.testTag(KeyboardShortcutsTags.SWAP),
                    )
                }
            }
        }
    }
}
