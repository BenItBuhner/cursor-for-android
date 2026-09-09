package com.cursorforandroid.ui.components

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cursor's prompt box as measured on cursor.com/agents: `--cursor-editor` surface, 8 % stroke (20 % focused),
 * radius 12, 12px padding, 14/22 text, and a footer of round buttons — "+" on the left, opening the
 * Multitask / Files / Skills / MCP Servers menu ([ComposerPlusMenu]), send / stop on the right — with the 13px
 * model selector hugging send. The text is the largest thing in the box and the round buttons the smallest
 * controls ([CursorDimens.roundButton] beside [CursorTypography.input]), as on the web; the chips sit in between.
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
    val shape = CursorTheme.shapes.xl
    var focused by remember { mutableStateOf(false) }
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
    // The field owns the selection. Text that changes from outside (a slash command from the "+" menu, the draft
    // being cleared after send) is adopted with the cursor at the end, so typing continues after the command instead
    // of wherever the cursor happened to be. TextFieldState is required for image paste; the value/onValueChange
    // BasicTextField cannot advertise image MIME types to the IME or receive clipboard images.
    val field = remember { TextFieldState(initialText = value, initialSelection = TextRange(value.length)) }
    SideEffect {
        if (field.text.toString() != value) field.setTextAndPlaceCursorAtEnd(value)
    }
    val receiveImages = rememberImagePasteReceiver(
        enabled = onAddAttachments != null,
        currentCount = attachments.size,
        onAddAttachments = onAddAttachments,
        onAttachmentError = onAttachmentError,
    )

    Column(
        modifier
            .fillMaxWidth()
            .cursorSurface(colors.elevated, border, shape)
            .padding(start = pad, end = pad, top = pad, bottom = pad - 2.dp),
    ) {
        if (attachments.isNotEmpty() && onRemoveAttachment != null) {
            AttachmentStrip(attachments, onRemoveAttachment)
        }
        BasicTextField(
            state = field,
            textStyle = type.input.copy(color = colors.textPrimary),
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
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused },
            decorator = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = type.input, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(CursorDimens.composerFooter), verticalAlignment = Alignment.CenterVertically) {
            if (plusMenu != null) {
                var menuOpen by remember { mutableStateOf(false) }
                // The Box is the anchor: the menu drops from the "+" like the web's popover.
                Box {
                    ComposerRoundButton(CursorIcons.Plus, "Add to prompt", onClick = { menuOpen = true })
                    ComposerPlusMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        prompt = value,
                        onPromptChange = onValueChange,
                        actions = plusMenu,
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
 * Advertises image MIME types to the IME and turns clipboard / keyboard / drag-and-drop images into attachments.
 * Bytes are read before [ReceiveContentListener.onReceive] returns so a clipboard URI grant cannot expire on the
 * hop to IO; decode and downscale happen off the main thread afterwards.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberImagePasteReceiver(
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
            val payloads = mutableListOf<ImagePayload>()
            var readFailed = false
            val remaining = transferableContent.consume { item ->
                val uri = item.uri ?: return@consume false
                if (!isImageUri(resolver, uri, clipIsImage)) return@consume false
                val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                if (bytes == null) {
                    readFailed = true
                    true
                } else {
                    payloads += ImagePayload(
                        id = uri.toString() + "@" + System.nanoTime(),
                        bytes = bytes,
                        declaredMime = resolver.getType(uri),
                    )
                    true
                }
            }
            if (payloads.isEmpty()) {
                if (readFailed || clipIsImage) {
                    attachmentError.value?.invoke("Couldn't read the image.")
                    return@ReceiveContentListener remaining
                }
                return@ReceiveContentListener transferableContent
            }
            val add = addAttachments.value
            scope.launch {
                val imported = withContext(Dispatchers.IO) { importPayloads(payloads, count.value) }
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
