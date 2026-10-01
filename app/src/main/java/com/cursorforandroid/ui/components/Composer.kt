package com.cursorforandroid.ui.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.ui.shortcuts.LocalKeyboardShortcuts
import com.cursorforandroid.ui.shortcuts.LocalShortcutBindings
import com.cursorforandroid.ui.shortcuts.Shortcut
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.ioThenMain
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cursor's prompt box as measured on cursor.com/agents: `--cursor-editor` surface, 8 % stroke (20 % focused),
 * 12px padding, 14/22 text, and a footer of round buttons — "+" on the left, opening the
 * Plan / Files / Skills / MCP Servers menu ([ComposerPlusMenu]), send / stop on the right — with the 13px
 * model selector hugging send. The field is inset a further [CursorDimens.composerTextInset] on every side so
 * the placeholder and typed text share the edges of the glyphs in those discs, not the discs themselves: the
 * 24dp corners would otherwise leave the first letter sitting in the arc, and the 12dp top pad alone reads
 * tighter than the 16dp left. The text is the largest thing in the box and the round buttons the smallest
 * controls ([CursorDimens.roundButton] beside [CursorTypography.input]), as on the web; the chips sit in between.
 * Typing `/` opens the [SlashCommandPopover] under the cursor with [commands] — `/goal`, the skills, the machine's
 * commands — then the modes this composer can wear and the [models] it can switch to, narrowed by what follows the
 * slash ("/Opus 4" leaves the matching models); the same catalog backs the "+" menu's Skills page. A `/command`
 * standing in the text is painted in its tint ([CommandTints]) over the field's own glyphs, without the field
 * editing anything differently. A few commands are not text at all but pills right of "+", as on the web
 * ([ModePills]): `/multitask`, which the owner's [value] still carries in front so the request is unchanged, and
 * the modes — `/plan`, and with [extendedModes] `/ask` and `/debug` — which are [modePill]. Typing one with a space
 * after it, or picking it from the popover, turns it into its pill and takes the token out of the
 * text (Plan is also the "+" menu's first row); the pill's cross puts the mode off again. They are one slot — the one turned on last replaces the other, in
 * the owner's state as well — so at most one pill is ever worn.
 * The corners are [CursorDimens.composerRadius] rather than the web's 12px: concentric with the two discs in the
 * bottom corners, so the box wraps them evenly instead of pinching in behind them.
 *
 * While [isSending] the send slot shows a busy ring; once the request has been in flight for a moment it turns into
 * a Stop button that calls [onCancelSend] (when given), so a launch that drags on can be abandoned without a
 * nervous double-tap cancelling one that is about to succeed.
 *
 * The field is a [TextFieldState] editor so Android can paste images into it — from the clipboard, the keyboard
 * clipboard, or an IME `commitContent`. Those become [PendingAttachment]s through the same path as Files. In
 * Extended mode the "+" menu also attaches [files] of any type ([PendingFile]), shown as chips with their name, kind
 * and size. Images and files share one row above the text ([ComposerAttachments]) that scrolls sideways, its ends
 * fading, once it runs past the composer's width; each file goes up the moment it is attached, its chip filling
 * meanwhile, the footer saying so in [sendHint]. The chat's composer sends regardless — the message finishes its
 * uploads on its own bubble — and empties at the tap; the New Chat composer holds its launch until the files are up.
 *
 * A physical keyboard's Enter presses send whenever send could be tapped, and does nothing otherwise; Shift+Enter, and
 * the on-screen keyboard's Enter, put in a newline ([sendOnHardwareEnter]). While the `/` popover is up the keyboard
 * drives it instead, as on the desktop: the arrows move its highlight, Enter or Tab picks the highlighted row, Esc
 * closes it ([popoverKeys]). Otherwise Shift+Tab steps through the modes, as the desktop's Cycle Mode does.
 *
 * Once the text runs past the ten lines the field shows and scrolls inside it, a button slides in right of "+" that
 * grows the composer over nearly all the height it can have ([expansion]): the room its parent allows — in a chat, all
 * of it above the keyboard or the navigation bar, the transcript giving way — or [expandRoom] where the owner measures it.
 * The same button, Back, Ctrl+Shift+E or a send brings it back down; see [ComposerExpansion]. Collapsed or expanded,
 * text that runs past the field's top or bottom fades there ([scrollEdgeFade]), as every scrolling list in the app does.
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
    /** Files of any type attached in Extended mode, as chips in the attachment row beside the images; see [FileChip]. */
    files: List<PendingFile> = emptyList(),
    onRemoveFile: ((PendingFile) -> Unit)? = null,
    /** Where each file's upload stands, by [PendingFile.id], from the moment it is attached; a failed one offers [onRetryFile]. */
    fileUploads: Map<String, FileUploadState> = emptyMap(),
    onRetryFile: ((PendingFile) -> Unit)? = null,
    /**
     * Where the attached files stand, in a few words beside the model chip — "Uploading 2 of 3…" while one is still
     * going up; null when none is. Whether send waits on it is the owner's ([canSend]): the New Chat composer holds
     * its launch; the chat's composer sends, the uploads finishing on the message's bubble.
     */
    sendHint: String? = null,
    /** For the attachment row's pictures and recordings opening in the app's viewer: the chat they belong to, and the loader that reads a restored recording's poster. */
    mediaAgentId: String? = null,
    media: MediaLoader? = null,
    modelLabel: String? = null,
    onModel: (() -> Unit)? = null,
    /**
     * The mode the owner holds — Plan, or with [extendedModes] Ask or Debug — worn as a pill; [onModePill] puts one on
     * from its `/command` and takes it off (null) from the pill's cross. Null leaves the mode commands as text.
     */
    modePill: ModePills.Pill? = null,
    onModePill: ((ModePills.Pill?) -> Unit)? = null,
    /** Whether `/ask` and `/debug` become pills here (Extended mode); off, they stay in the text like any other command. */
    extendedModes: Boolean = false,
    /**
     * The catalog's models, listed under "Models" in the `/` popover with [currentModel] checked; a pick hands
     * [onPickModel] the model at the variant its words spelled. Null [onPickModel] lists none.
     */
    models: List<ModelOption> = emptyList(),
    currentModel: ModelChoice? = null,
    onPickModel: ((ModelChoice) -> Unit)? = null,
    footerExtra: (@Composable RowScope.() -> Unit)? = null,
    minLines: Int = 1,
    /**
     * Takes focus — and so the keyboard — as it first appears: for a composer that is the whole point of its screen
     * (the quick composer over the launcher). Off, arriving on a screen never throws the keyboard up.
     */
    focusOnOpen: Boolean = false,
    /**
     * Bumped to put the caret in the field now (Ctrl+N, a chat switched to from the keyboard); a composer composed after
     * a bump does not answer it again.
     */
    focusRequests: Int = 0,
    /**
     * Dictation (offered in Extended mode, see `AppGraph.voiceInput`); null leaves the send slot as it
     * always was. Given, a microphone joins it — see [composerButtons] — and the words go in at the caret, unsent.
     */
    voice: VoiceInput? = null,
    /** Where the text stands, for a send that lifts it off into its bubble (see [SendMotion]); null for a composer that never does. */
    anchor: ComposerAnchor? = null,
    /** Whether the composer is grown over the window's height; hoisted by an owner that lays out around it. */
    expansion: ComposerExpansion = rememberComposerExpansion(),
    /** The height, in pixels, the composer grows to when expanded, read as it lays out; null takes what its parent allows. */
    expandRoom: (() -> Int)? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = remember { RoundedCornerShape(CursorDimens.composerRadius) }
    // The owner's text split into what the field shows and the Multitask pill; the field never holds the token.
    val presented = remember(value) { ModePills.present(value) }
    // The modes and Multitask are one slot (see ModePills), and the owner keeps them so; should it ever hold both for
    // a frame, the token in the text is the one shown, since it is the one the field is hiding.
    val wornMode = modePill?.takeIf { it.agentMode != null && onModePill != null && !presented.multitask }
    // Saved alongside the text and the caret, so a composer rebuilt from instance state is left the way the reader
    // had it. The want is taken from it once, before the field has reported its own state over the top; a field that
    // was not focused is never given focus, since arriving on a screen must not throw the keyboard up.
    var focused by rememberSaveable(saver = FocusedSaver) { mutableStateOf(false) }
    var wantsFocus by remember { mutableStateOf(focused || focusOnOpen) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    // Whether the field held focus as the "+" menu opened: a menu put away with nothing picked hands it back, keyboard
    // and all, whatever took either while the menu was up.
    var focusedAtMenu by rememberSaveable { mutableStateOf(false) }
    var wantsKeyboard by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Waits for the "+" menu to be gone: its popup holds focus while it is up, and a request made under it is lost.
    LaunchedEffect(wantsFocus, menuOpen) {
        if (wantsFocus && !menuOpen) {
            wantsFocus = false
            focus.requestFocus()
            if (wantsKeyboard) {
                wantsKeyboard = false
                keyboard?.show()
            }
        }
    }
    var focusRequestsSeen by remember { mutableIntStateOf(focusRequests) }
    LaunchedEffect(focusRequests) {
        if (focusRequests != focusRequestsSeen) {
            focusRequestsSeen = focusRequests
            wantsFocus = true
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
    // Exactly when the send slot below is an enabled Send: a physical Enter presses it then and at no other time, so
    // it never stops a run, cancels a launch, or sends past files still going up.
    val sendsNow = canSend && !isSending
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
    // What is adopted is the presented text: `/multitask` put in front of the owner's value changes
    // nothing the field shows, so the caret stays where it was and only the pill appears.
    val field = rememberTextFieldState(initialText = presented.text, initialSelection = TextRange(presented.text.length))
    var adopted by rememberSaveable { mutableStateOf(value) }
    SideEffect {
        if (value != adopted) {
            adopted = value
            if (!field.text.contentEquals(presented.text)) field.setTextAndPlaceCursorAtEnd(presented.text)
        }
    }
    // Everything below the body that reaches the owner's text or callbacks reads them through these, so the lambdas
    // handed to the field, the popover and the footer are the same ones from one keystroke to the next and skip.
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    // Whether the field holds anything to send, for the send slot: read as it flips, not on every keystroke.
    val hasText by remember(field) { derivedStateOf { field.text.isNotBlank() } }
    val textScroll = rememberScrollState()
    // The field's layout, handed over as it is measured and read back as the command highlight draws.
    val textLayout = remember { TextLayoutHandle() }
    val lineCount = remember { mutableIntStateOf(0) }
    // Stretched from the first frame of expanding to the last of collapsing: the field then fills what the composer's
    // height leaves it instead of holding to its ten lines.
    val stretched by remember(expansion) { derivedStateOf { expansion.progress.value > 0f } }
    // Past ten lines — or squeezed by a short window into scrolling sooner — the field scrolls inside the composer.
    // Stretched, it may not scroll at all, so its line count is what says the text would overflow again collapsed.
    val overflowing by remember(expansion, textScroll) {
        derivedStateOf { lineCount.intValue > CollapsedMaxLines || (expansion.progress.value == 0f && textScroll.maxValue > 0) }
    }
    val density = LocalDensity.current
    val minGainPx = with(density) { ComposerExpansion.MinGain.roundToPx() }
    val expandOffered by remember(expansion, minGainPx) { derivedStateOf { expansion.offered(overflowing, minGainPx) } }
    val minFieldPx = with(density) { maxOf(22.dp.roundToPx(), (type.input.lineHeight.toPx() * minLines).roundToInt()) }
    BackHandler(enabled = expansion.expanded) { expansion.collapse() }
    // Collapsing, the field shrinks from the bottom; the caret is kept in sight as it does, so the line being written
    // is still the one showing when the composer is back to its own height. Only then (through the frame it lands on):
    // at rest the field stays scrolled wherever the reader left it.
    LaunchedEffect(expansion, textScroll) {
        var last = 0f
        snapshotFlow { expansion.progress.value to textScroll.maxValue }.collect { (p, _) ->
            if (!expansion.expanded && (p > 0f && p < 1f || last > 0f)) keepCaretInView(textLayout.get?.invoke(), field.selection, textScroll)
            last = p
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
    // selection are snapshot state, so the token follows every keystroke and cursor move; it is derived, and looks
    // only at the word or line the cursor is in, so a keystroke that leaves it as it was costs the body nothing.
    // A phrase ("/Opus 4") counts only while it names a model; otherwise it is the message typed after a command.
    val offeredModes = remember(onModePill != null, extendedModes) {
        ModePills.Pill.entries.filter { ModePills.pillFor(it.command, planEnabled = onModePill != null, extended = extendedModes) != null }
    }
    val pickableModels = if (onPickModel != null) models else emptyList()
    val multitask = presented.multitask
    val wornPill = wornMode ?: ModePills.Pill.Multitask.takeIf { multitask }
    val offer = remember(offeredModes, wornPill, pickableModels, currentModel) { SlashOffer(offeredModes, wornPill, pickableModels, currentModel) }
    val tokenAtCursor by remember(field) { derivedStateOf { if (focused) SlashTokens.at(field.text, field.selection) else null } }
    val phrase by remember(field) { derivedStateOf { if (focused) SlashTokens.phraseAt(field.text, field.selection) else null } }
    val phraseNamesModel = remember(phrase?.query, offer) { phrase?.let { offer.modelSearch.search(it.query).isNotEmpty() } == true }
    val slashToken = tokenAtCursor ?: phrase?.takeIf { phraseNamesModel }
    var dismissedToken by remember { mutableStateOf<SlashToken?>(null) }
    val recentSkills = plusMenu?.recentSkills.orEmpty()
    val popoverToken = slashToken?.takeIf { it != dismissedToken }
    val slash = rememberSlashSuggestions(popoverToken, commands, recentSkills, offer)
    val slashOpen = slashPopoverOpen(popoverToken, slash, commands)
    // The app's shortcuts are read before this field sees a key; while the popover is up, Esc, Ctrl+N and Ctrl+K are
    // its (see `popoverKeys`) and not the shell's.
    val keyboardShortcuts = LocalKeyboardShortcuts.current
    if (slashOpen && keyboardShortcuts != null) {
        DisposableEffect(keyboardShortcuts) {
            val release = keyboardShortcuts.popoverOpened()
            onDispose { release() }
        }
    }
    // Whether a physical keyboard has typed here. Until one has, the popover shows no highlight: the rows are for
    // tapping, and an Enter that picks is only ever a physical keyboard's.
    var physicalKeys by remember { mutableStateOf(false) }
    // The popover's rows keep the click handler they were composed with, so the handler reads the token and catalog
    // as they are when the row is tapped, not as they were when the row first appeared (typing "/", then "g", then
    // "o" composes the row once, under the "/" token; completing that token would leave the "go" in place).
    val currentToken by rememberUpdatedState(slashToken)
    val currentCommands by rememberUpdatedState(commands)
    val currentMenu by rememberUpdatedState(plusMenu)
    val currentPresented by rememberUpdatedState(presented)
    val currentOnMode by rememberUpdatedState(onModePill)
    val currentMode by rememberUpdatedState(modePill)
    val currentExtended by rememberUpdatedState(extendedModes)
    val currentWorn by rememberUpdatedState(wornPill)
    val currentOfferedModes by rememberUpdatedState(offeredModes)
    val currentOnPickModel by rememberUpdatedState(onPickModel)
    val haptics = rememberHaptics()

    /** Hands the owner the field's text in its own shape — `/multitask ` in front while that pill is on. */
    fun publish(text: String, multitask: Boolean = currentPresented.multitask) {
        val next = ModePills.compose(text, multitask)
        if (next != currentValue) currentOnValueChange(next)
    }

    /**
     * Puts [pill] on for the field's [text], and the others off: a mode is asked for and `/multitask` leaves the
     * owner's text, or the text leads with `/multitask` and the mode is put off. The owner keeps them exclusive as
     * well; this is so the composer never depends on it.
     */
    fun turnOn(pill: ModePills.Pill, text: String) {
        haptics.perform(Haptic.ToggleOn)
        when (pill) {
            ModePills.Pill.Multitask -> {
                publish(text, multitask = true)
                if (currentMode != null) currentOnMode?.invoke(null)
            }
            else -> {
                currentOnMode?.invoke(pill)
                publish(text, multitask = false)
            }
        }
    }

    /** Takes off whichever pill is worn, for the field's [text]. */
    fun takeOff(text: String) {
        haptics.perform(Haptic.ToggleOff)
        publish(text, multitask = false)
        if (currentMode != null) currentOnMode?.invoke(null)
    }

    /** The "+" menu's Plan: on, or off while it is worn, and the field handed back once the menu is gone. */
    fun togglePlan() {
        val text = field.text.toString()
        if (currentWorn == ModePills.Pill.Plan) takeOff(text) else turnOn(ModePills.Pill.Plan, text)
        wantsFocus = true
    }

    /** The field without the `/` token under the cursor, handed to the owner, for a pick that leaves no text behind. */
    fun consumeToken(): String? {
        val token = currentToken ?: return null
        val next = ModePills.consumeToken(field.text.toString(), token)
        field.edit {
            replace(0, length, next.text)
            if (next.selection.start >= length) placeCursorAtEnd() else placeCursorBeforeCharAt(next.selection.start)
        }
        return next.text
    }

    fun complete(entry: SlashCommand) {
        val token = currentToken ?: return
        val pill = ModePills.pillFor(entry.name, planEnabled = currentOnMode != null, extended = currentExtended)
        if (pill != null) {
            // Multitask and Plan are pills, not text: the token goes, the mode goes on.
            turnOn(pill, consumeToken() ?: return)
            return
        }
        // A name the catalog does not list — typed, or picked before — is remembered so it is one tap away next time.
        if (currentCommands.byName(entry.name) == null) currentMenu?.onSkillUsed?.invoke(entry.name)
        val next = SlashTokens.complete(field.text.toString(), token, entry.name)
        field.edit {
            replace(0, length, next.text)
            if (next.selection.start >= length) placeCursorAtEnd() else placeCursorBeforeCharAt(next.selection.start)
        }
        // An edit made here does not pass through the input transformation, so the owner is told directly.
        publish(next.text)
        haptics.perform(Haptic.Select)
    }

    /** A row of the popover picked: a command completed, a mode put on (or off, if it was on), a model set, a section opened. */
    fun pick(item: SlashItem) {
        when (item) {
            is SlashItem.Command -> complete(item.command)
            is SlashItem.Mode -> {
                val text = consumeToken() ?: return
                if (item.on) takeOff(text) else turnOn(item.pill, text)
            }
            is SlashItem.Model -> {
                publish(consumeToken() ?: return)
                currentOnPickModel?.invoke(item.choice)
            }
            is SlashItem.ShowMore -> slash.expand(item.section)
        }
    }

    /**
     * Shift+Tab from a physical keyboard steps the mode on, as the desktop's Cycle Mode does: no mode, then each mode
     * this composer can wear in the desktop's order ([ModePills.next]), then no mode again. While the `/` popover is up
     * it has Shift+Tab ([popoverKeys] comes first); plain Tab and every other key are left alone.
     */
    fun cycleMode(event: KeyEvent): Boolean {
        if (!event.isFromHardwareKeyboard || event.key != Key.Tab || !event.isShiftPressed) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || field.composition != null) return false
        val offered = currentOfferedModes
        if (offered.isEmpty()) return false
        if (event.type == KeyEventType.KeyDown) {
            val text = field.text.toString()
            when (val next = ModePills.next(currentWorn) { it in offered }) {
                null -> takeOff(text)
                else -> turnOn(next, text)
            }
        }
        return true
    }

    fun dictate(words: String) {
        val next = insertDictation(field.text.toString(), field.selection, words)
        field.edit {
            replace(0, length, next.text)
            selection = TextRange(next.cursor)
        }
        publish(next.text)
    }

    // A `/multitask `, `/plan ` (or, in Extended mode, `/ask ` or `/debug `) the reader has just closed with a space
    // becomes its pill: the token leaves the text here, before the field ever shows it, and the caret stays on its
    // characters. The last one typed is the one that stays on; the other mode goes off with it. Text with no slash in
    // it holds no token, and is handed on without being copied a second time to look.
    val pillsFromTyping = remember(haptics) {
        InputTransformation {
            val typed = if (asCharSequence().indexOf('/') < 0) null else ModePills.consumeTyped(asCharSequence().toString(), selection, planEnabled = currentOnMode != null, extended = currentExtended)
            val turnedOn = typed?.turnedOn
            if (typed != null && turnedOn != null) {
                replace(0, length, typed.text)
                selection = typed.selection
                turnOn(turnedOn, toString())
            } else {
                publish(toString())
            }
        }
    }

    val micTap = if (voice != null) rememberMicTap(voice, haptics, onTranscript = { dictate(it) }) else null
    // A send takes the composer back down with it: what is left is the next message, begun at the composer's own height.
    val send: () -> Unit = {
        expansion.collapse()
        onSend()
    }

    Column(
        modifier
            .fillMaxWidth()
            .expandableHeight(expansion, expandRoom, collapsed = { collapsedEstimate(expansion, textLayout.get?.invoke()?.size?.height, minFieldPx) })
            // Unclipped: beside the bare mic, the main button's 40dp touch area runs past the box's rounded edge
            // (see FooterSpacing), and a clip would drop those touches. What scrolls inside clips itself.
            .cursorSurface(colors.elevated, border, shape, clip = false)
            .then(if (anchor != null) Modifier.onPlaced { anchor.surface = it } else Modifier)
            .padding(start = pad, end = pad, top = pad, bottom = pad - 2.dp),
    ) {
        // Everything attached, in one row that scrolls sideways past the composer's width; nothing at all when nothing is.
        val shownImages = if (onRemoveAttachment != null) attachments else emptyList()
        val shownFiles = if (onRemoveFile != null) files else emptyList()
        if (shownImages.isNotEmpty() || shownFiles.isNotEmpty()) {
            ComposerAttachments(
                images = shownImages,
                onRemoveImage = { onRemoveAttachment?.invoke(it) },
                files = shownFiles,
                onRemoveFile = { onRemoveFile?.invoke(it) },
                surface = colors.elevated,
                uploads = fileUploads,
                onRetryFile = onRetryFile,
                agentId = mediaAgentId,
                media = media,
                anchor = anchor,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        // The Box is the popover's anchor: it drops from the text, over the footer, like the web's.
        // The extra inset is on the Box so the popover stays under the glyphs, not under the corner,
        // and the top/bottom air matches the left/right. It is also where a pen writes, out to the box's rounded edge,
        // and what gives the field its focus back after a fold, an unfold or a turn moves the chat to the other layout.
        Box(Modifier.then(if (stretched) Modifier.weight(1f) else Modifier).stylusWriting().keepsFocusWhenMoved().padding(CursorDimens.composerTextInset)) {
            if (anchor != null) {
                SideEffect {
                    anchor.layout = { textLayout.get?.invoke() }
                    anchor.scroll = { textScroll.value }
                }
            }
            // What the key handlers below make of a physical Enter, for the newline an IME may type in its place.
            val physicalEnter: (() -> Unit)? = when {
                slashOpen -> { { slash.selection.highlightedItem?.let { pick(it) } } }
                sendsNow -> send
                else -> null
            }
            ComposerTextField(
                field = field,
                placeholder = placeholder,
                minLines = minLines,
                stretched = stretched,
                textScroll = textScroll,
                textLayout = textLayout,
                lineCount = lineCount,
                inputTransformation = pillsFromTyping,
                expansion = expansion,
                expandOffered = expandOffered,
                popoverOpen = slashOpen,
                popover = slash.selection,
                onPick = { pick(it) },
                onDismissPopover = { dismissedToken = slashToken },
                onCycleMode = { cycleMode(it) },
                onEnter = physicalEnter,
                onHardwareSend = send.takeIf { sendsNow },
                onEdited = { publish(it) },
                receiveImages = receiveImages,
                focus = focus,
                onPhysicalKey = { physicalKeys = true },
                onFocusChanged = { focused = it },
                anchor = anchor,
            )
            SlashCommandPopover(
                token = popoverToken,
                suggestions = slash,
                catalog = commands,
                showHighlight = physicalKeys,
                onPick = { pick(it) },
                onDismiss = { dismissedToken = slashToken },
            )
        }
        Spacer(Modifier.height(12.dp))
        // The footer's designed height is a minimum: the model chip and anything [footerExtra] adds are sp-sized, and
        // an exact constraint here would hold them to 28dp however much taller they asked to be.
        Row(Modifier.fillMaxWidth().heightIn(min = CursorDimens.composerFooter), verticalAlignment = Alignment.CenterVertically) {
            if (plusMenu != null) {
                // The Box is the anchor: the menu drops from the "+" like the web's popover.
                Box {
                    ComposerRoundButton(CursorIcons.Plus, "Add to prompt", onClick = { focusedAtMenu = focused; menuOpen = true })
                    ComposerPlusMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        onCancel = {
                            if (focusedAtMenu) {
                                wantsFocus = true
                                wantsKeyboard = true
                            }
                        },
                        prompt = { currentValue },
                        onPromptChange = { next ->
                            currentOnValueChange(next)
                            // The command goes in at the front of the prompt and the caret follows the adopted text
                            // to the end, which is where the reader carries on writing; the field is handed back with it.
                            wantsFocus = true
                        },
                        actions = plusMenu,
                        commands = commands,
                        planOn = wornPill == ModePills.Pill.Plan,
                        onTogglePlan = if (ModePills.Pill.Plan in offeredModes) ({ togglePlan() }) else null,
                    )
                }
            }
            ExpandButton(expansion, offered = expandOffered, afterPlus = plusMenu != null)
            if (plusMenu != null) Spacer(Modifier.width(10.dp))
            // Pills right of "+", the model chip next to send, and the leftover width between them. The children are
            // measured in order, so the pills take what their words need and the chip is left the rest: it ellipsises
            // before a pill would, and send is never pushed out. A dictation under way has the middle to itself.
            val buttons = composerButtons(
                isSending = isSending,
                cancelOffered = cancelOffered && onCancelSend != null,
                canStop = isRunning && onStop != null,
                canSend = canSend,
                hasContent = hasText || shownImages.isNotEmpty() || shownFiles.isNotEmpty(),
                voice = micTap != null,
            )
            val micBeside = buttons.micBeside && voice != null && micTap != null
            val dictating = voice != null && micTap != null && voice.state != VoiceState.Idle
            // The bare mic slides in and out beside the main button rather than popping: its slot widens from nothing
            // while the chip's end padding and the spacer before it give their room up at the same pace.
            val micShown by animateFloatAsState(if (micBeside) 1f else 0f, tween(MicSlideMillis, easing = FastOutSlowInEasing), label = "micShown")
            val chipEnd = lerp(FooterSpacing.ChipEndPadding, FooterSpacing.ChipEndBesideMic, micShown)
            val gapTarget = when {
                micBeside && dictating -> FooterSpacing.CancelToMic
                micBeside -> 0.dp
                // A dictation's status ends in its round cancel button, clear of the main mic's touches.
                dictating -> CursorDimens.roundButtonGap
                else -> FooterSpacing.ChipToMain
            }
            val gap by animateDpAsState(gapTarget, tween(MicSlideMillis, easing = FastOutSlowInEasing), label = "footerGap")
            ComposerFooterMiddle(
                dictating = dictating,
                voice = voice,
                micTap = micTap,
                micBeside = micBeside,
                wornMode = wornMode,
                onClearMode = { haptics.perform(Haptic.ToggleOff); onModePill?.invoke(null) },
                multitask = multitask,
                onClearMultitask = { haptics.perform(Haptic.ToggleOff); publish(field.text.toString(), multitask = false) },
                footerExtra = footerExtra,
                sendHint = sendHint,
                modelLabel = modelLabel,
                onModel = onModel,
                chipEnd = chipEnd,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(gap))
            if (voice != null && micTap != null && micShown > 0f) {
                VoiceMicBeside(
                    voice,
                    glyphStart = FooterSpacing.MicGlyphStart,
                    onClick = micTap,
                    enabled = micBeside,
                    modifier = Modifier
                        .width(CursorDimens.roundButtonTouch * micShown)
                        .wrapContentWidth(Alignment.End, unbounded = true)
                        .graphicsLayer {
                            alpha = micShown
                            scaleX = 0.6f + 0.4f * micShown
                            scaleY = 0.6f + 0.4f * micShown
                        },
                )
            }
            // Beside the mic, the main button takes touches from its disc's left edge rightwards: the room its hit
            // layer would have reached into on the left is the mic's.
            val mainShift = if (micBeside) FooterSpacing.MainTouchShift else 0.dp
            val voiceState = voice?.state
            val face = when (buttons.main) {
                SendSlot.CancelSend -> MainFace(MainGlyph.Stop, "Cancel sending", onClick = { onCancelSend?.invoke() })
                SendSlot.Busy -> MainFace(MainGlyph.Spinner, "Sending", onClick = null)
                SendSlot.Stop -> MainFace(MainGlyph.Stop, "Stop", onClick = { onStop?.invoke() })
                // The tap is felt, not a hardware Enter: a physical keyboard's keys are their own feedback.
                SendSlot.Send -> MainFace(MainGlyph.Send, "Send", onClick = { haptics.perform(Haptic.Confirm); send() }, prominent = canSend, enabled = sendsNow)
                SendSlot.Mic -> when (voiceState) {
                    is VoiceState.Recording -> MainFace(MainGlyph.Mic, "Stop recording", onClick = micTap, recording = true)
                    VoiceState.Transcribing -> MainFace(MainGlyph.Spinner, "Transcribing", onClick = null)
                    else -> MainFace(MainGlyph.Mic, "Voice input", onClick = micTap)
                }
            }
            ComposerMainButton(face, voice = voice, touchShift = mainShift, modifier = Modifier.testTag("composer-main"))
        }
    }
}

/**
 * The composer's text field, its placeholder and its command highlight, with the key handlers that belong to it. Its
 * own composable, taking nothing that changes as the text does, so a keystroke that reruns the composer's body skips
 * it: the field redraws and relays out its text itself.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerTextField(
    field: TextFieldState,
    placeholder: String,
    minLines: Int,
    stretched: Boolean,
    textScroll: ScrollState,
    textLayout: TextLayoutHandle,
    lineCount: MutableIntState,
    inputTransformation: InputTransformation,
    expansion: ComposerExpansion,
    expandOffered: Boolean,
    popoverOpen: Boolean,
    popover: PopoverSelection<SlashItem>,
    onPick: (SlashItem) -> Unit,
    onDismissPopover: () -> Unit,
    onCycleMode: (KeyEvent) -> Boolean,
    onEnter: (() -> Unit)?,
    onHardwareSend: (() -> Unit)?,
    onEdited: (String) -> Unit,
    receiveImages: ReceiveContentListener?,
    focus: FocusRequester,
    onPhysicalKey: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    anchor: ComposerAnchor?,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val sendMotion = LocalSendMotion.current
    val shortcutBindings = LocalShortcutBindings.current
    val commandTints = commandTints()
    ImeEnterFallback(onEnter = onEnter, composing = { field.composition != null }) {
        StylusTextInput {
            BasicTextField(
                state = field,
                textStyle = type.input.copy(color = colors.textPrimary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                cursorBrush = SolidColor(colors.textPrimary),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = if (stretched) Int.MAX_VALUE else CollapsedMaxLines),
                scrollState = textScroll,
                onTextLayout = { provider ->
                    textLayout.get = provider
                    provider()?.lineCount?.let { if (it != lineCount.intValue) lineCount.intValue = it }
                },
                inputTransformation = inputTransformation,
                modifier = Modifier
                    .fillMaxWidth()
                    // One line of `input` at the default font scale, so the box does not shrink under a small system font.
                    .heightIn(min = 22.dp)
                    .then(if (stretched) Modifier.fillMaxHeight() else Modifier)
                    .onSizeChanged { if (expansion.progress.value == 0f) expansion.fieldPx = it.height }
                    // Painted, not dissolved: the field is resized every frame the composer expands or collapses,
                    // and the composer's own fill is flat behind it.
                    .scrollEdgeFade(textScroll, surface = colors.elevated)
                    .onPhysicalKey(onPhysicalKey)
                    .onPreviewKeyEvent { event ->
                        val chord = event.type == KeyEventType.KeyDown && shortcutBindings.matches(
                            Shortcut.ExpandComposer,
                            event.nativeKeyEvent.keyCode,
                            event.isCtrlPressed,
                            event.isShiftPressed,
                            event.isAltPressed,
                            event.isMetaPressed,
                        )
                        if (chord && expandOffered) expansion.toggle()
                        chord && expandOffered
                    }
                    .popoverKeys(popoverOpen, popover, composing = { field.composition != null }, onPick = onPick, onDismiss = onDismissPopover)
                    .modeCycleKeys(onCycleMode)
                    .sendOnHardwareEnter(field, onSend = onHardwareSend, onEdited = onEdited)
                    .then(if (receiveImages != null) Modifier.contentReceiver(receiveImages) else Modifier)
                    .focusRequester(focus)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
                decorator = { inner ->
                    Box {
                        ComposerPlaceholder(field, placeholder, Modifier.sendPlaceholder(sendMotion, anchor))
                        Box(
                            Modifier
                                .then(if (anchor != null) Modifier.onPlaced { anchor.field = it } else Modifier)
                                .sendSource(sendMotion, anchor)
                                .slashCommandHighlight(layout = { textLayout.get?.invoke() }, scroll = textScroll, tints = commandTints),
                        ) {
                            inner()
                        }
                    }
                },
            )
        }
    }
}

/**
 * The footer between the pills' left edge and the mic: the worn pills, [footerExtra], the upload hint and the model
 * chip, or a dictation's status while one is under way. Its own composable so a keystroke, which changes none of it,
 * leaves it alone.
 */
@Composable
private fun ComposerFooterMiddle(
    dictating: Boolean,
    voice: VoiceInput?,
    micTap: (() -> Unit)?,
    micBeside: Boolean,
    wornMode: ModePills.Pill?,
    onClearMode: () -> Unit,
    multitask: Boolean,
    onClearMultitask: () -> Unit,
    footerExtra: (@Composable RowScope.() -> Unit)?,
    sendHint: String?,
    modelLabel: String?,
    onModel: (() -> Unit)?,
    chipEnd: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    AnimatedContent(
        targetState = dictating,
        transitionSpec = { fadeIn(tween(MicMotionMillis, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(MicMotionMillis * 2 / 3)) using SizeTransform(clip = false) },
        contentAlignment = Alignment.CenterStart,
        modifier = modifier,
        label = "footerMiddle",
    ) { showsStatus ->
        if (showsStatus && voice != null && micTap != null) {
            VoiceStatus(voice, onTap = micTap, cancelTouchShift = if (micBeside) FooterSpacing.CancelTouchShift else 0.dp, modifier = Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (wornMode != null) {
                    ModePill(wornMode, onClear = onClearMode)
                    Spacer(Modifier.width(6.dp))
                }
                if (multitask) {
                    ModePill(ModePills.Pill.Multitask, onClear = onClearMultitask)
                    Spacer(Modifier.width(6.dp))
                }
                Spacer(Modifier.weight(1f))
                footerExtra?.invoke(this)
                if (sendHint != null) {
                    Text(
                        sendHint,
                        style = type.small,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 6.dp).testTag("send-hint"),
                    )
                }
                if (modelLabel != null) {
                    SelectorChip(
                        modelLabel,
                        onClick = onModel ?: {},
                        enabled = onModel != null,
                        showChevron = onModel != null,
                        endPadding = chipEnd,
                    )
                }
            }
        }
    }
}

/**
 * The field's placeholder, shown while the field's own text is empty — not the owner's: a placeholder that follows a
 * lagging owner blinks back over the first character typed. Only its emptiness is read, here, so a keystroke into
 * text that was already there recomposes nothing.
 */
@Composable
private fun ComposerPlaceholder(field: TextFieldState, placeholder: String, modifier: Modifier) {
    val empty by remember(field) { derivedStateOf { field.text.isEmpty() } }
    if (empty) {
        val type = CursorTheme.typography
        Text(placeholder, style = type.input, color = CursorTheme.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
}

internal enum class MainGlyph { Send, Stop, Mic, Spinner }

/**
 * What the send slot's disc shows: its glyph, its name, its tap ([onClick] null while a send or a transcription is
 * under way, when it takes none), and its colours — the white disc when [prominent], the soft disabled fill when not
 * [enabled], red while [recording].
 */
internal data class MainFace(
    val glyph: MainGlyph,
    val description: String,
    val onClick: (() -> Unit)?,
    val prominent: Boolean = true,
    val enabled: Boolean = true,
    val recording: Boolean = false,
)

/**
 * The send slot's one disc, whatever it holds: Send, Stop, the microphone, a spinner. It never leaves the layout, so
 * nothing beside it jumps when its role changes; the fill cross-fades and the glyph swaps with a fade and a scale. As
 * the microphone recording, the disc turns red inside [RecordingHalo]. The name is on a glyph-sized node rather than
 * the hit layer, as with [ComposerRoundButton], and says when the disc takes no tap.
 */
@Composable
private fun ComposerMainButton(face: MainFace, voice: VoiceInput?, touchShift: Dp, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val targetFill = when {
        face.recording -> colors.red
        !face.enabled -> colors.fillSoft
        face.prominent -> colors.textPrimary
        else -> colors.fill
    }
    val targetTint = when {
        face.recording -> Color.White
        !face.enabled -> colors.iconQuaternary
        face.prominent -> colors.canvas
        else -> colors.iconSecondary
    }
    val motion = tween<Color>(MicMotionMillis, easing = FastOutSlowInEasing)
    val fill by animateColorAsState(targetFill, motion, label = "mainFill")
    val tint by animateColorAsState(targetTint, motion, label = "mainTint")
    val halo by animateFloatAsState(if (face.recording) 1f else 0f, tween(MicMotionMillis, easing = FastOutSlowInEasing), label = "mainHalo")
    val interaction = remember { MutableInteractionSource() }
    val onClick = face.onClick
    Box(modifier.size(CursorDimens.roundButton), contentAlignment = Alignment.Center) {
        if (voice != null && halo > 0f) RecordingHalo(voice, halo)
        if (onClick != null) {
            Box(
                Modifier
                    .offset(x = touchShift)
                    .requiredSize(CursorDimens.roundButtonTouch)
                    .clickable(interactionSource = interaction, indication = null, enabled = face.enabled, role = Role.Button, onClick = onClick),
            )
        }
        Box(
            Modifier
                .size(CursorDimens.roundButton)
                .clip(CircleShape)
                .indication(interaction, ripple(color = colors.base, bounded = true))
                .background(fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(CursorDimens.roundButtonGlyph)
                    .semantics {
                        contentDescription = face.description
                        if (!face.enabled) disabled()
                    },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(face.glyph, transitionSpec = { glyphSwap() }, contentAlignment = Alignment.Center, label = "mainGlyph") { glyph ->
                    when (glyph) {
                        MainGlyph.Send -> Icon(CursorIcons.ArrowUp, null, tint = tint, modifier = Modifier.size(CursorDimens.roundButtonGlyph))
                        MainGlyph.Stop -> Icon(CursorIcons.Stop, null, tint = tint, modifier = Modifier.size(CursorDimens.roundButtonGlyph))
                        MainGlyph.Mic -> Icon(CursorIcons.Mic, null, tint = tint, modifier = Modifier.size(CursorDimens.roundButtonGlyph))
                        MainGlyph.Spinner -> SpinnerRing(color = tint, size = CursorDimens.roundButtonGlyph, strokeWidth = 1.5.dp)
                    }
                }
            }
        }
    }
}

/**
 * The composer's expand button, right of "+" ([afterPlus]) or first in the footer without one. It slides in and out
 * rather than popping, as the mic beside Send does: its slot widens from nothing while the glyph fades and scales up,
 * and while it is out its disc stands [FooterSpacing.PlusToExpand] from the "+" disc, the footer's one step between
 * controls. The glyph is arrows out while collapsed and arrows in while expanded, named for what a tap does.
 */
@Composable
private fun ExpandButton(expansion: ComposerExpansion, offered: Boolean, afterPlus: Boolean) {
    val shown by animateFloatAsState(if (offered) 1f else 0f, tween(MicSlideMillis, easing = FastOutSlowInEasing), label = "expandShown")
    if (shown <= 0f) return
    val lead = if (afterPlus) FooterSpacing.PlusToExpand else 0.dp
    val trail = if (afterPlus) 0.dp else 10.dp
    Row(
        Modifier
            .width((lead + CursorDimens.roundButton + trail) * shown)
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .graphicsLayer {
                alpha = shown
                scaleX = 0.6f + 0.4f * shown
                scaleY = 0.6f + 0.4f * shown
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(lead))
        ComposerRoundButton(
            if (expansion.expanded) CursorIcons.Collapse else CursorIcons.Expand,
            if (expansion.expanded) "Collapse composer" else "Expand composer",
            onClick = { expansion.toggle() },
            modifier = Modifier.testTag("composer-expand"),
        )
        Spacer(Modifier.width(trail))
    }
}

/** Scrolls the field just far enough that the caret at [selection]'s end is inside its viewport. */
private suspend fun keepCaretInView(layout: TextLayoutResult?, selection: TextRange, scroll: ScrollState) {
    // A text field scrolls its state without ever setting viewportSize (only verticalScroll does), so the viewport is
    // what the text's height leaves once the scroll range is taken out; with no range, all of it is in view.
    if (layout == null || scroll.maxValue <= 0) return
    val viewport = layout.size.height - scroll.maxValue
    if (viewport <= 0) return
    val caret = layout.getCursorRect(selection.end.coerceIn(0, layout.layoutInput.text.length))
    val target = when {
        caret.bottom > scroll.value + viewport -> caret.bottom - viewport
        caret.top < scroll.value -> caret.top
        else -> return
    }
    scroll.scrollTo(target.roundToInt())
}

/** How long a send has to be in flight before the busy ring becomes a cancel button. */
private const val CancelOfferDelayMillis = 2_500L

/**
 * The footer's right-hand group — model chip, the mic beside the main button, the main disc — spaced by what the eye
 * sees rather than by layout boxes, with touch areas that tile the row instead of overlapping.
 *
 * Without a mic, the chip's chevron ink sits [ModelToMain] from the main disc: the chevron's inset in its box, the
 * chip's end padding and [ChipToMain]. With the bare mic beside the main button, the mic's ink sits that same distance
 * from the disc and from the chevron. The mic's 40dp touch area fills the room between the chip and the disc exactly:
 * the chip gives up the end padding nobody sees ([ChipEndBesideMic]) and the main button's hit layer starts at its
 * disc's left edge ([MainTouchShift]), so a tap near the mic is never a send.
 */
internal object FooterSpacing {
    val Chevron = 14.dp
    val ChipEndPadding = 7.dp
    val ChipToMain = 8.dp
    private val ChevronInk = Chevron * (CursorIcons.ChevronInkInset / 24f)
    private val MicInk = CursorDimens.roundButtonGlyph * (CursorIcons.MicInkInset / 24f)

    /** Chevron ink to main disc before the mic existed; now also chevron ink to mic ink and mic ink to main disc. */
    val ModelToMain: Dp = ChevronInk + ChipEndPadding + ChipToMain

    /** Where the mic's glyph box starts inside its 40dp touch box, so its ink ends [ModelToMain] short of the disc. */
    val MicGlyphStart: Dp = CursorDimens.roundButtonTouch - CursorDimens.roundButtonGlyph - (ModelToMain - MicInk)

    /** The chip's end padding beside the mic: its chevron ink [ModelToMain] from the mic's, its tap area ending at the mic's. */
    val ChipEndBesideMic: Dp = ModelToMain - ChevronInk - MicGlyphStart - MicInk

    val MainTouchShift: Dp = (CursorDimens.roundButtonTouch - CursorDimens.roundButton) / 2

    /**
     * "+" disc to the expand disc: the same step as the chevron, mic and main button on the right. It is wider than
     * [CursorDimens.roundButtonGap], so both keep whole 40dp touch squares that never overlap.
     */
    val PlusToExpand: Dp = ModelToMain

    /** Dictating, the status's cancel disc to the mic's box: its edge [ModelToMain] from the mic's ink. */
    val CancelToMic: Dp = ModelToMain - MicGlyphStart - MicInk

    /** The cancel's hit layer moved left so it ends where the mic's begins. */
    val CancelTouchShift: Dp = CancelToMic - MainTouchShift
}

/**
 * Where a text field leaves its layout provider: set from `onTextLayout` during the field's measure, read while the
 * command highlight draws. Not snapshot state on purpose — the provider itself reads the field's layout result, which
 * is, so the draw is invalidated by the layout changing rather than by a write made in the middle of measuring.
 */
private class TextLayoutHandle {
    var get: (() -> TextLayoutResult?)? = null
}

/**
 * Paints the `/command` tokens of a text field in their [tints] — a command in the desktop's command-chip yellow, a
 * mode's own token in its pill's tint — the way cursor.com/agents and the desktop composer set a command apart from
 * the request. Purely a matter of drawing: the field lays its text out once and hands the result over through
 * `onTextLayout` ([layout]); after the field has drawn, the same layout is drawn again in each token's tint, clipped
 * to the box of that token ([SlashCommands.tokenRanges] of the laid-out text), so the tinted glyphs land exactly on
 * the field's own. Nothing about editing, selection or the caret changes; the same rule and tints paint the message
 * once sent (see [highlightSlashCommands]). The field draws its text in the space of its scrolled content (its core
 * node places that content at `-scroll` and draws there), so the repaint follows [scroll] the same way and is clipped
 * to the field's bounds like the field.
 */
private fun Modifier.slashCommandHighlight(layout: () -> TextLayoutResult?, scroll: ScrollState, tints: CommandTints): Modifier {
    val ranges = TokenRangesCache()
    return clipToBounds().drawWithContent {
        drawContent()
        val result = layout() ?: return@drawWithContent
        // The laid-out text rather than the state's: the two differ for the frame between an edit and its layout.
        val text = result.layoutInput.text.text
        val tokens = ranges.of(text)
        if (tokens.isEmpty()) return@drawWithContent
        translate(top = -scroll.value.toFloat()) {
            for (token in tokens) {
                val end = (token.last + 1).coerceAtMost(text.length)
                if (token.first >= end) continue
                clipPath(result.getPathForRange(token.first, end)) { drawText(result, color = tints.forToken(text.substring(token.first + 1, end))) }
            }
        }
    }
}

/**
 * The command highlight's [SlashCommands.tokenRanges], kept for the laid-out text they were found in: the field
 * redraws for every caret blink and every frame of a send's flight, with the same text each time.
 */
internal class TokenRangesCache {
    private var text: String? = null
    private var ranges: List<IntRange> = emptyList()

    fun of(text: String): List<IntRange> {
        if (text !== this.text) {
            ranges = SlashCommands.tokenRanges(text)
            this.text = text
        }
        return ranges
    }
}

/**
 * Saves whether the field had focus, rather than the state it is held in. `rememberSaveable { mutableStateOf(…) }`
 * puts the holder itself into the saved state, and the field is blurred as the composition comes down — after the
 * save has been taken but while the holder is still the live one, which turns a focused composer into an unfocused
 * one on the way back.
 */
private val FocusedSaver = Saver<MutableState<Boolean>, Boolean>(save = { it.value }, restore = { mutableStateOf(it) })

/**
 * The mode cycle's keys, read before the IME is handed them as well as after, as [popoverKeys] and
 * [sendOnHardwareEnter] read theirs: an IME that handles the physical keyboard itself would otherwise take Shift+Tab.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.modeCycleKeys(handle: (KeyEvent) -> Boolean): Modifier =
    onPreInterceptKeyBeforeSoftKeyboard(handle).onPreviewKeyEvent(handle)

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
                // The import off the main thread, the callbacks below back on it (see ioThenMain).
                val imported = ioThenMain { importAttachments(context, uris, taken) }
                if (imported.attachments.isNotEmpty()) add(imported.attachments)
                imported.error?.let { attachmentError.value?.invoke(it) }
            }
            remaining
        }
    }
}

/**
 * Plain-text selector with a small chevron: "codex-poly-bot ⌄", "main ⌄", "Claude Fable 5.1 ⌄". Nothing is
 * painted around it until pressed; a chip that cannot be changed drops the chevron instead of greying out. Squeezed
 * (the label is what gives, never the glyphs) it ellipsises the label and keeps the chevron, so it still reads as a
 * selector; that takes a row with a bounded width to measure in, which is where every chip sits.
 */
@Composable
fun SelectorChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    showChevron: Boolean = enabled,
    endPadding: Dp = FooterSpacing.ChipEndPadding,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .pressable(onClick, CursorTheme.shapes.base, enabled = enabled)
            // The composer footer's height: a fair tap height for a chip that paints nothing until pressed.
            .heightIn(min = CursorDimens.composerFooter)
            .padding(start = 7.dp, end = endPadding),
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
                // Measured after the glyphs, so a squeeze shortens the label rather than dropping the chevron.
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (showChevron) Icon(CursorIcons.ChevronDown, null, tint = colors.iconTertiary, modifier = Modifier.size(FooterSpacing.Chevron))
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
