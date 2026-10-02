package com.cursorforandroid.ui.shortcuts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorforandroid.domain.PaletteEntry
import com.cursorforandroid.domain.PaletteResult
import com.cursorforandroid.domain.PaletteSearch
import com.cursorforandroid.domain.TranscriptHit
import com.cursorforandroid.domain.TranscriptPassage
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.StylusTextInput
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.fadingVerticalScroll
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.scrollEdgeFade
import com.cursorforandroid.ui.components.stylusWriting
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the palette is showing: the search (Ctrl+F), the quick switcher (Ctrl+Tab), or the shortcuts (Ctrl+/). */
enum class PaletteMode { Search, Switcher, Shortcuts }

/** Whether the palette is up and in which mode, with the switcher's selection; the shell's, driven by the keys. */
@Stable
class PaletteState {
    var mode by mutableStateOf<PaletteMode?>(null)
        private set

    /** The quick switcher's selected row. */
    var switcherIndex by mutableIntStateOf(0)
        private set

    /** Bumped each time search is asked for: the field takes the focus again, what it holds selected. */
    var searchRequests by mutableIntStateOf(0)
        private set

    val isOpen: Boolean get() = mode != null

    fun openSearch() {
        mode = PaletteMode.Search
        searchRequests++
    }

    fun openShortcuts() {
        mode = PaletteMode.Shortcuts
    }

    fun openSwitcher(start: Int) {
        mode = PaletteMode.Switcher
        switcherIndex = start
    }

    /** The switcher's selection [by] rows on, around the ends of a list of [size]. */
    fun step(by: Int, size: Int) {
        if (size > 0) switcherIndex = Math.floorMod(switcherIndex + by, size)
    }

    fun close() {
        mode = null
    }
}

/**
 * The quick switcher's list: the open chat, then the chats visited in this session most recent first, then the rest by
 * their last activity. The first Ctrl+Tab lands on the row after the open chat — the chat before it — as a desktop
 * app switcher's first press does.
 */
object SwitcherOrder {
    const val LIMIT = 12

    fun of(visited: List<String>, entries: List<PaletteEntry>, current: String?, limit: Int = LIMIT): List<PaletteEntry> {
        val byId = entries.associateBy { it.agentId }
        val ids = LinkedHashSet<String>()
        current?.takeIf { it in byId }?.let(ids::add)
        visited.filterTo(ids) { it in byId }
        if (ids.size < limit) entries.sortedByDescending { it.updatedAtMillis }.mapTo(ids) { it.agentId }
        return ids.asSequence().take(limit).mapNotNull(byId::get).toList()
    }

    /** Where the switcher opens: one row on from the open chat ([forward]) or one back, around the ends. */
    fun start(order: List<PaletteEntry>, current: String?, forward: Boolean): Int {
        if (order.isEmpty()) return 0
        val onCurrent = current != null && order.first().agentId == current
        return when {
            forward -> if (onCurrent) 1 % order.size else 0
            else -> order.size - 1
        }
    }
}

object PaletteCopy {
    const val PLACEHOLDER = "Search chats, Projects and transcripts"
    const val RECENT = "Recent"
    const val SWITCH_TO = "Switch to"
    const val NO_RECENT = "No chats yet"
    const val READING = "Reading transcripts…"

    fun noMatches(query: String): String = "No chats match \u201C${query.trim()}\u201D"

    fun matches(count: Int): String = if (count == 1) "1 match" else "$count matches"
}

object PaletteTags {
    const val SCRIM = "palette_scrim"
    const val CARD = "palette_card"
    const val FIELD = "palette_field"
    const val SHORTCUTS = "palette_shortcuts"
    const val SWITCHER = "palette_switcher"

    fun result(index: Int): String = "palette_result_$index"
}

/**
 * The search's rows, the query they were found for, and the highlighted one. Rows found again for the same query — the
 * transcripts read in, the list moving on — keep the highlight on its chat, wherever that chat went, so Enter opens the
 * chat that was highlighted; a chat is one row, so it is the row's identity. A new query starts from the top.
 */
internal data class PaletteSelection(val query: String, val results: List<PaletteResult>, val index: Int = 0) {
    val current: PaletteResult? get() = results.getOrNull(index)

    fun moved(by: Int): PaletteSelection = copy(index = (index + by).coerceIn(0, results.lastIndex.coerceAtLeast(0)))

    /** [found] for [query] in these rows' place; a chat no longer found leaves the highlight where it was, on the last row at most. */
    fun next(query: String, found: List<PaletteResult>): PaletteSelection {
        if (query != this.query) return PaletteSelection(query, found)
        val id = current?.entry?.agentId
        val at = found.indexOfFirst { it.entry.agentId == id }
        return PaletteSelection(query, found, if (at >= 0) at else index.coerceAtMost(found.lastIndex).coerceAtLeast(0))
    }
}

/** How long typing rests before the palette searches; a burst of keys is searched once. */
private const val SEARCH_DEBOUNCE_MILLIS = 90L

/**
 * The palette: a card centred near the top of the window over a scrim, drawn in the window itself rather than as a
 * dialog so the activity keeps reading the keys (Esc, Ctrl+Tab's release) while it is up. Tapping the scrim closes it.
 *
 * [entries] are the chats and Projects the list has; [switcher] the quick switcher's rows (see [SwitcherOrder]);
 * [transcripts] what the device keeps of their transcripts, searched as [readingTranscripts] fills it in.
 */
@Composable
fun CommandPalette(
    state: PaletteState,
    entries: List<PaletteEntry>,
    switcher: List<PaletteEntry>,
    transcripts: Map<String, List<TranscriptPassage>>,
    readingTranscripts: Boolean,
    glyph: @Composable (agentId: String) -> Unit,
    onOpen: (entry: PaletteEntry, hit: TranscriptHit?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    // Opened from the keyboard, so it is up in the frame the key lands and gone in the frame it is put away: no fade.
    val mode = state.mode ?: return
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (colors.isDark) 0.5f else 0.28f))
            .pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(PaletteTags.SCRIM),
        contentAlignment = Alignment.TopCenter,
    ) {
        val width = if (mode == PaletteMode.Switcher) 460.dp else 640.dp
        Column(
            Modifier
                .padding(top = maxHeight * 0.1f, start = 20.dp, end = 20.dp)
                .widthIn(max = width)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.78f)
                .cursorSurface(colors.elevated, colors.stroke, CursorTheme.shapes.xl)
                // Taps on the card are the card's, not the scrim's.
                .pointerInput(Unit) { detectTapGestures { } }
                .testTag(PaletteTags.CARD),
        ) {
            when (mode) {
                PaletteMode.Search -> SearchPane(state, entries, switcher, transcripts, readingTranscripts, glyph, onOpen)
                PaletteMode.Switcher -> SwitcherPane(state, switcher, glyph, onOpen)
                PaletteMode.Shortcuts -> ShortcutsPane(onDismiss)
            }
        }
    }
}

@Composable
private fun ColumnScope.SearchPane(
    state: PaletteState,
    entries: List<PaletteEntry>,
    recent: List<PaletteEntry>,
    transcripts: Map<String, List<TranscriptPassage>>,
    reading: Boolean,
    glyph: @Composable (String) -> Unit,
    onOpen: (PaletteEntry, TranscriptHit?) -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.searchRequests) {
        field = field.copy(selection = TextRange(0, field.text.length))
        focus.requestFocus()
    }
    val query = field.text
    val blank = PaletteSearch.normalize(query).isEmpty()
    var shown by remember { mutableStateOf(PaletteSelection("", recent.map { PaletteResult(it) })) }
    LaunchedEffect(query, entries, transcripts, recent) {
        val found = if (blank) {
            recent.map { PaletteResult(it) }
        } else {
            delay(SEARCH_DEBOUNCE_MILLIS)
            withContext(Dispatchers.Default) { PaletteSearch.search(query, entries, transcripts) }
        }
        shown = shown.next(query, found)
    }
    val results = shown.results
    val selected = shown.index
    val listState = rememberLazyListState()
    KeepInView(listState, selected, results)

    fun openSelected() {
        shown.current?.let { onOpen(it.entry, it.hit) }
    }

    fun onKey(event: KeyEvent): Boolean {
        val down = event.type == KeyEventType.KeyDown
        return when (event.key) {
            Key.DirectionDown -> {
                if (down) shown = shown.moved(1)
                true
            }
            Key.DirectionUp -> {
                if (down) shown = shown.moved(-1)
                true
            }
            Key.Enter, Key.NumPadEnter -> {
                if (down && event.nativeKeyEvent.repeatCount == 0) openSelected()
                true
            }
            else -> false
        }
    }

    Row(Modifier.fillMaxWidth().stylusWriting().height(50.dp).padding(start = 16.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(CursorIcons.Search, null, tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        StylusTextInput {
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = type.title.copy(fontWeight = FontWeight.Normal, color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { openSelected() }),
                modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent(::onKey).testTag(PaletteTags.FIELD),
                decorationBox = { inner ->
                    Box {
                        if (field.text.isEmpty()) Text(PaletteCopy.PLACEHOLDER, style = type.title.copy(fontWeight = FontWeight.Normal), color = colors.textQuaternary, maxLines = 1)
                        inner()
                    }
                },
            )
        }
        if (reading) {
            Spacer(Modifier.width(8.dp))
            SpinnerRing(size = 12.dp)
        }
    }
    HairlineDivider()
    val label = when {
        blank -> PaletteCopy.RECENT.takeIf { results.isNotEmpty() } ?: PaletteCopy.NO_RECENT
        results.isEmpty() -> if (reading) PaletteCopy.READING else PaletteCopy.noMatches(query)
        else -> null
    }
    label?.let { Text(it, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp)) }
    LazyColumn(
        Modifier.fillMaxWidth().weight(1f, fill = false).scrollEdgeFade(listState, surface = colors.elevated),
        state = listState,
        contentPadding = PaddingValues(6.dp),
    ) {
        itemsIndexed(results, key = { _, result -> result.entry.agentId }) { index, result ->
            PaletteRow(
                title = highlighted(result.entry.title, result.titleMatch),
                snippet = result.snippet?.let { highlighted(it.text, it.matchStart until it.matchEnd) },
                trailing = result.entry.repo?.substringAfterLast('/'),
                note = result.hit?.let { PaletteCopy.matches(it.matches) },
                selected = index == selected,
                glyph = { glyph(result.entry.agentId) },
                onClick = { onOpen(result.entry, result.hit) },
                modifier = Modifier.testTag(PaletteTags.result(index)),
            )
        }
    }
    HairlineDivider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyHint(listOf("↑", "↓"), "to move")
        Spacer(Modifier.width(14.dp))
        KeyHint(listOf("Enter"), "to open")
        Spacer(Modifier.width(14.dp))
        KeyHint(listOf("Esc"), "to close")
        Spacer(Modifier.weight(1f))
        if (reading && results.isNotEmpty()) Text(PaletteCopy.READING, style = type.small, color = colors.textQuaternary, maxLines = 1)
    }
}

@Composable
private fun ColumnScope.SwitcherPane(state: PaletteState, rows: List<PaletteEntry>, glyph: @Composable (String) -> Unit, onOpen: (PaletteEntry, TranscriptHit?) -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val listState = rememberLazyListState()
    KeepInView(listState, state.switcherIndex)
    Text(
        if (rows.isEmpty()) PaletteCopy.NO_RECENT else PaletteCopy.SWITCH_TO,
        style = type.small,
        color = colors.textTertiary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
    )
    LazyColumn(
        Modifier.fillMaxWidth().weight(1f, fill = false).scrollEdgeFade(listState, surface = colors.elevated).testTag(PaletteTags.SWITCHER),
        state = listState,
        contentPadding = PaddingValues(6.dp),
    ) {
        itemsIndexed(rows, key = { _, entry -> entry.agentId }) { index, entry ->
            PaletteRow(
                title = AnnotatedString(entry.title),
                snippet = null,
                trailing = entry.repo?.substringAfterLast('/'),
                note = null,
                selected = index == state.switcherIndex,
                glyph = { glyph(entry.agentId) },
                onClick = { onOpen(entry, null) },
                modifier = Modifier.testTag(PaletteTags.result(index)),
            )
        }
    }
    HairlineDivider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyHint(listOf("Tab"), "next")
        Spacer(Modifier.width(14.dp))
        KeyHint(listOf("Shift", "Tab"), "back")
        Spacer(Modifier.width(14.dp))
        KeyHint(listOf("Ctrl"), "release to open")
    }
}

@Composable
private fun ColumnScope.ShortcutsPane(onClose: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(ShortcutsCopy.TITLE, style = type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
        FlatIconButton(CursorIcons.Close, "Close", onClick = onClose, size = 32.dp, iconSize = 14.dp)
    }
    HairlineDivider()
    Column(Modifier.weight(1f, fill = false).fadingVerticalScroll(surface = colors.elevated).padding(vertical = 6.dp).testTag(PaletteTags.SHORTCUTS)) {
        ShortcutsCopy.groups(LocalShortcutBindings.current).forEach { group ->
            Text(group.title, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp))
            group.lines.forEach { line -> ShortcutLineRow(line, Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) }
        }
    }
    HairlineDivider()
    Text(
        "${ShortcutsCopy.HARDWARE_ONLY} ${ShortcutsCopy.CHANGE_IN_SETTINGS}",
        style = type.small,
        color = colors.textQuaternary,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** One shortcut: what it does, with its detail under it, and the chords that do it at the end ("Not set" for none). */
@Composable
fun ShortcutLineRow(line: ShortcutsCopy.Line, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(line.label, style = type.base, color = colors.textPrimary)
            line.detail?.let { Text(it, style = type.small, color = colors.textTertiary) }
        }
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (line.chords.isEmpty()) Text(ShortcutsCopy.NOT_SET, style = type.small, color = colors.textQuaternary)
            line.chords.forEachIndexed { index, chord ->
                if (index > 0) Text("or", style = type.small, color = colors.textQuaternary)
                Chord(chord)
            }
        }
    }
}

/** A chord as key caps side by side: Ctrl, Shift, B. */
@Composable
fun Chord(keys: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        keys.forEach { Keycap(it) }
    }
}

@Composable
fun Keycap(label: String, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.sm
    Box(
        modifier
            .defaultMinSize(minWidth = 20.dp)
            .height(20.dp)
            .background(colors.fillFaint, shape)
            .border(CursorDimens.hairline, colors.stroke, shape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = CursorTheme.typography.small.copy(fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium), color = colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun KeyHint(keys: List<String>, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Chord(keys)
        Spacer(Modifier.width(5.dp))
        Text(label, style = CursorTheme.typography.small, color = CursorTheme.colors.textQuaternary, maxLines = 1)
    }
}

@Composable
private fun PaletteRow(
    title: AnnotatedString,
    snippet: AnnotatedString?,
    trailing: String?,
    note: String?,
    selected: Boolean,
    glyph: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    Row(
        modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .background(if (selected) colors.fillActive else Color.Transparent, shape)
            .pressable(onClick, shape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) { glyph() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = type.rowMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            snippet?.let { Text(it, style = type.small, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (trailing != null || note != null) {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                trailing?.let { Text(it, style = type.small, color = colors.textQuaternary, maxLines = 1, lineHeight = 18.sp) }
                note?.let { Text(it, style = type.small, color = colors.textQuaternary, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun highlighted(text: String, match: IntRange?): AnnotatedString {
    val colors = CursorTheme.colors
    if (match == null || match.first < 0 || match.last >= text.length || match.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, match.first))
        withStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.SemiBold, background = colors.accent.copy(alpha = 0.22f))) {
            append(text.substring(match.first, match.last + 1))
        }
        append(text.substring(match.last + 1))
    }
}

/**
 * Scrolls the list just enough for row [index] to be on screen, as the selection moves past its edges, and again once
 * [rows] changed under it have been laid out: rows above it growing (a longer snippet read in) push it off screen
 * without its index changing.
 */
@Composable
private fun KeepInView(listState: LazyListState, index: Int, rows: List<Any>? = null) {
    val latest by rememberUpdatedState(index)
    LaunchedEffect(index) { listState.reveal(index) }
    LaunchedEffect(rows) {
        if (rows == null) return@LaunchedEffect
        withFrameNanos { }
        listState.reveal(latest)
    }
}

private suspend fun LazyListState.reveal(index: Int) {
    val visible = layoutInfo.visibleItemsInfo
    val first = visible.firstOrNull()?.index ?: return
    val last = visible.last().index
    val fully = visible.filter { it.offset >= layoutInfo.viewportStartOffset && it.offset + it.size <= layoutInfo.viewportEndOffset }.map { it.index }
    when {
        index in fully -> Unit
        index <= first -> scrollToItem(index)
        index >= last -> scrollToItem((index - fully.size + 1).coerceAtLeast(0))
        else -> Unit
    }
}
