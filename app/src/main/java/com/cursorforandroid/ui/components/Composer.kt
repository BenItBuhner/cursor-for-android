package com.cursorforandroid.ui.components

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cursor's prompt box as measured on cursor.com/agents: `--cursor-editor` surface, 8 % stroke (20 % focused),
 * 12px padding, 14/22 text, and a footer of round buttons — "+" on the left, opening the
 * Multitask / Files / Skills / MCP Servers menu ([ComposerPlusMenu]), send / stop on the right — with the 13px
 * model selector hugging send. The text is the largest thing in the box and the round buttons the smallest
 * controls ([CursorDimens.roundButton] beside [CursorTypography.input]), as on the web; the chips sit in between.
 * Typing `/` opens the [SlashCommandPopover] under the cursor with [commands] — `/goal`, the skills, the machine's
 * commands — narrowed by what follows the slash; the same catalog backs the "+" menu's Skills page.
 * The corners are [CursorDimens.composerRadius] rather than the web's 12px: concentric with the two discs in the
 * bottom corners, so the box wraps them evenly instead of pinching in behind them.
 *
 * While [isSending] the send slot shows a busy ring; once the request has been in flight for a moment it turns into
 * a Stop button that calls [onCancelSend] (when given), so a launch that drags on can be abandoned without a
 * nervous double-tap cancelling one that is about to succeed.
 *
 * The field is a [TextFieldState] editor so Android can paste images into it — from the clipboard, the keyboard
 * clipboard, or an IME `commitContent`. Those become [PendingAttachment]s through the same path as Files.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposerBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    canSend: Boolean = value.isNotBlank(),
    isRunning: Boolean = false,
    onStop: (() -> Unit)? = null,
    isSending: Boolean = false,
    onCancelSend: (() -> Unit)? = null,
    /** Shows the "+" button and backs its menu; null hides the button. */
    plusMenu: ComposerMenuActions? = null,
    /** What `/` offers here — the built-ins, or the chat's own list once the account has answered (see [SlashCommandPopover]). */
    commands: SlashCatalog = SlashCatalog.BUILT_IN,
    attachments: List<PendingAttachment> = emptyList(),
    onRemoveAttachment: ((PendingAttachment) -> Unit)? = null,
    /** Clipboard / IME image paste; null leaves the field text-only. */
    onAddAttachments: ((List<PendingAttachment>) -> Unit)? = null,
    onAttachmentError: ((String) -> Unit)? = null,
    modelLabel: String? = null,
    onModel: (() -> Unit)? = null,
    footerExtra: (@Composable RowScope.() -> Unit)? = null,
    minLines: Int = 1,
    focusRequester: FocusRequester? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = remember { RoundedCornerShape(CursorDimens.composerRadius) }
    // Saved alongside the text and the caret, so a composer rebuilt from instance state is left the way the reader
    // had it. The want is taken from it once, before the field has reported its own state over the top; a field that
    // was not focused is never given focus, since arriving on a screen must not throw the keyboard up.
    var focused by rememberSaveable(saver = FocusedSaver) { mutableStateOf(false) }
    var wantsFocus by remember { mutableStateOf(focused) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val ownFocus = remember { FocusRequester() }
    // The caller's requester where it drives focus itself, the composer's own otherwise: the "+" menu hands the field
    // back after inserting a command, which needs one either way.
    val focus = focusRequester ?: ownFocus
    // Waits for the "+" menu to be gone: its popup holds focus while it is up, and a request made under it is lost.
    LaunchedEffect(wantsFocus, menuOpen) {
        if (wantsFocus && !menuOpen) {
            wantsFocus = false
            focus.requestFocus()
        }
    }
    var cancelOffered by remember { mutableStateOf(false) }
    LaunchedEffect(isSending, onCancelSend != null) {
        cancelOffered = false
        if (isSending && onCancelSend != null) {
            delay(CancelOfferDelayMillis)
            cancelOffered = true
        }
    }
    val pad = CursorDimens.composerPadding
    val border by animateColorAsState(if (focused) colors.strokeStrong else colors.strokeSubtle, tween(160), label = "border")
    // The field owns the text and the selection; [value] only says what the owner last made of it. Comparing the two
    // directly would mean rewriting the field whenever they disagree, which is wrong while they are meant to: the
    // owner may answer onValueChange a frame late — a debounce, a trim, a length cap, a flow — and the rewrite would
    // put the stale text back and throw the caret to the end mid-word. So what is tracked is the last value that came
    // from outside, and only a change in that is adopted, with the cursor at the end so typing continues after a
    // slash command from the "+" menu instead of wherever the cursor happened to be. Both are saved: the field keeps
    // its caret across a rotation, and adopted keeps a restored draft from outliving the owner that cleared it.
    // A TextFieldState field is also what makes image paste possible at all: a value/onValueChange BasicTextField
    // cannot advertise image MIME types to the IME or receive clipboard images.
    val field = rememberTextFieldState(initialText = value, initialSelection = TextRange(value.length))
    var adopted by rememberSaveable { mutableStateOf(value) }
    SideEffect {
        if (value != adopted) {
            adopted = value
            if (value != field.text.toString()) field.setTextAndPlaceCursorAtEnd(value)
        }
    }
    val receiveImages = rememberImagePasteReceiver(
        enabled = onAddAttachments != null,
        currentCount = attachments.size,
        onAddAttachments = onAddAttachments,
        onAttachmentError = onAttachmentError,
    )
    // The `/` token under the cursor, while the field has focus: what the popover lists completions for. A token the
    // popover closed on (nothing matched) is not reopened until the cursor moves on to another. The state's text and
    // selection are snapshot state, so the token follows every keystroke and cursor move.
    val slashToken = if (focused) SlashTokens.at(field.text.toString(), field.selection) else null
    var dismissedToken by remember { mutableStateOf<SlashToken?>(null) }
    val recentSkills = plusMenu?.recentSkills.orEmpty()
    // The popover's rows keep the click handler they were composed with, so the handler reads the token and catalog
    // as they are when the row is tapped, not as they were when the row first appeared (typing "/", then "g", then
    // "o" composes the row once, under the "/" token; completing that token would leave the "go" in place).
    val currentToken by rememberUpdatedState(slashToken)
    val currentCommands by rememberUpdatedState(commands)
    val currentMenu by rememberUpdatedState(plusMenu)

    fun complete(entry: SlashCommand) {
        val token = currentToken ?: return
        // A name the catalog does not list — typed, or picked before — is remembered so it is one tap away next time.
        if (currentCommands.byName(entry.name) == null) currentMenu?.onSkillUsed?.invoke(entry.name)
        val next = SlashTokens.complete(field.text.toString(), token, entry.name)
        field.edit {
            replace(0, length, next.text)
            if (next.selection.start >= length) placeCursorAtEnd() else placeCursorBeforeCharAt(next.selection.start)
        }
        // An edit made here does not pass through the input transformation, so the owner is told directly.
        if (next.text != value) onValueChange(next.text)
    }

    Column(
        modifier
            .fillMaxWidth()
            .cursorSurface(colors.elevated, border, shape)
            .padding(start = pad, end = pad, top = pad, bottom = pad - 2.dp),
    ) {
        if (attachments.isNotEmpty() && onRemoveAttachment != null) {
            AttachmentStrip(attachments, onRemoveAttachment)
        }
        // The Box is the popover's anchor: it drops from the text, over the footer, like the web's.
        Box {
            BasicTextField(
                state = field,
                textStyle = type.input.copy(color = colors.textPrimary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                cursorBrush = SolidColor(colors.textPrimary),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = 10),
                inputTransformation = InputTransformation {
                    val next = toString()
                    if (next != value) onValueChange(next)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // One line of `input` at the default font scale, so the box does not shrink under a small system font.
                    .heightIn(min = 22.dp)
                    .then(if (receiveImages != null) Modifier.contentReceiver(receiveImages) else Modifier)
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused },
                decorator = { inner ->
                    Box {
                        // The field's own text, not the owner's: a placeholder that follows a lagging owner blinks
                        // back over the first character typed.
                        if (field.text.isEmpty()) Text(placeholder, style = type.input, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
            SlashCommandPopover(
                token = slashToken?.takeIf { it != dismissedToken },
                catalog = commands,
                recent = recentSkills,
                onPick = { complete(it) },
                onDismiss = { dismissedToken = slashToken },
            )
        }
        Spacer(Modifier.height(10.dp))
        // The footer's designed height is a minimum: the model chip and anything [footerExtra] adds are sp-sized, and
        // an exact constraint here would hold them to 28dp however much taller they asked to be.
        Row(Modifier.fillMaxWidth().heightIn(min = CursorDimens.composerFooter), verticalAlignment = Alignment.CenterVertically) {
            if (plusMenu != null) {
                // The Box is the anchor: the menu drops from the "+" like the web's popover.
                Box {
                    ComposerRoundButton(CursorIcons.Plus, "Add to prompt", onClick = { menuOpen = true })
                    ComposerPlusMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        prompt = value,
                        onPromptChange = { next ->
                            onValueChange(next)
                            // The command goes in at the front of the prompt and the caret follows the adopted text
                            // to the end, which is where the reader carries on writing; the field is handed back with it.
                            wantsFocus = true
                        },
                        actions = plusMenu,
                        commands = commands,
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            // Leftover width (and optional extras) stay on the left so the model chip can sit next to send.
            // The chip takes what it needs and ellipsises only when send would otherwise be pushed out.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                footerExtra?.invoke(this)
                if (modelLabel != null) {
                    SelectorChip(modelLabel, onClick = onModel ?: {}, enabled = onModel != null, showChevron = onModel != null, modifier = Modifier.weight(1f, fill = false))
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                isSending && cancelOffered && onCancelSend != null -> ComposerRoundButton(CursorIcons.Stop, "Cancel sending", onClick = onCancelSend, prominent = true)
                isSending -> ComposerBusyButton()
                isRunning && onStop != null && !canSend -> ComposerRoundButton(CursorIcons.Stop, "Stop", onClick = onStop, prominent = true)
                else -> ComposerRoundButton(CursorIcons.ArrowUp, "Send", onClick = onSend, prominent = canSend, enabled = canSend)
            }
        }
    }
}

/** The send slot while a prompt is in flight: the prominent disc with a canvas-coloured ring instead of a glyph. */
@Composable
private fun ComposerBusyButton(modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    Box(
        modifier
            .size(CursorDimens.roundButton)
            .background(colors.textPrimary, CircleShape)
            .semantics { contentDescription = "Sending" },
        contentAlignment = Alignment.Center,
    ) {
        SpinnerRing(color = colors.canvas, size = CursorDimens.roundButtonGlyph, strokeWidth = 1.5.dp)
    }
}

/** How long a send has to be in flight before the busy ring becomes a cancel button. */
private const val CancelOfferDelayMillis = 2_500L

/**
 * Saves whether the field had focus, rather than the state it is held in. `rememberSaveable { mutableStateOf(…) }`
 * puts the holder itself into the saved state, and the field is blurred as the composition comes down — after the
 * save has been taken but while the holder is still the live one, which turns a focused composer into an unfocused
 * one on the way back.
 */
private val FocusedSaver = Saver<MutableState<Boolean>, Boolean>(save = { it.value }, restore = { mutableStateOf(it) })

/**
 * Advertises image MIME types to the IME and turns clipboard / keyboard / drag-and-drop images into attachments.
 * [ReceiveContentListener.onReceive] is called on the main thread, so only the URIs are taken there; the bytes go
 * through the photo picker's bounded reader on IO, which refuses an oversized selection before it is all in memory
 * instead of freezing the composer for the length of a cloud provider's download.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberImagePasteReceiver(
    enabled: Boolean,
    currentCount: Int,
    onAddAttachments: ((List<PendingAttachment>) -> Unit)?,
    onAttachmentError: ((String) -> Unit)?,
): ReceiveContentListener? {
    if (!enabled || onAddAttachments == null) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val addAttachments = rememberUpdatedState(onAddAttachments)
    val attachmentError = rememberUpdatedState(onAttachmentError)
    val count = rememberUpdatedState(currentCount)
    return remember {
        ReceiveContentListener { transferableContent ->
            val resolver = context.contentResolver
            val clipIsImage = transferableContent.hasMediaType(MediaType.Image)
            val uris = mutableListOf<Uri>()
            val remaining = transferableContent.consume { item ->
                val uri = item.uri ?: return@consume false
                if (!isImageUri(resolver, uri, clipIsImage)) return@consume false
                uris += uri
                true
            }
            if (uris.isEmpty()) {
                if (clipIsImage) {
                    attachmentError.value?.invoke("Couldn't read the image.")
                    return@ReceiveContentListener remaining
                }
                return@ReceiveContentListener transferableContent
            }
            val add = addAttachments.value
            val taken = count.value
            scope.launch {
                val imported = withContext(Dispatchers.IO) { importAttachments(context, uris, taken) }
                if (imported.attachments.isNotEmpty()) add(imported.attachments)
                imported.error?.let { attachmentError.value?.invoke(it) }
            }
            remaining
        }
    }
}

/**
 * Plain-text selector with a small chevron: "codex-poly-bot ⌄", "main ⌄", "Claude Fable 5.1 ⌄". Nothing is
 * painted around it until pressed; a chip that cannot be changed drops the chevron instead of greying out.
 */
@Composable
fun SelectorChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    showChevron: Boolean = enabled,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .pressable(onClick, CursorTheme.shapes.base, enabled = enabled)
            // The composer footer's height: a fair tap height for a chip that paints nothing until pressed.
            .heightIn(min = CursorDimens.composerFooter)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(15.dp))
        if (label.isNotEmpty()) {
            Text(
                label,
                style = type.base,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showChevron) Icon(CursorIcons.ChevronDown, null, tint = colors.iconTertiary, modifier = Modifier.size(14.dp))
    }
}

/** The row of context selectors that sits above the home composer. */
@Composable
fun SelectorRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(start = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}
