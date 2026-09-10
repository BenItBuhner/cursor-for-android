package com.cursorforandroid.share

import android.content.Context
import android.content.Intent
import com.cursorforandroid.ui.components.PendingAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a share should land after the user picks: a fresh composer, or an existing chat's follow-up. */
sealed interface ShareTarget {
    data object NewChat : ShareTarget
    data class Chat(val agentId: String) : ShareTarget
}

/**
 * Text and images taken from another app's share sheet, ready to drop into a composer. [target] is null while the
 * destination picker is up; once it is set the matching composer consumes this draft and [ShareInbox.consume]s it.
 */
data class ShareDraft(
    val generation: Long,
    val text: String,
    val attachments: List<PendingAttachment>,
    val target: ShareTarget? = null,
    val warning: String? = null,
) {
    val isEmpty: Boolean get() = text.isBlank() && attachments.isEmpty()

    /** One line for the picker's subtitle: the first line of text and/or how many images came along. */
    fun summary(): String {
        val line = text.trim().lineSequence().firstOrNull().orEmpty()
        val clipped = if (line.length > 48) line.take(47) + "…" else line
        val images = when (attachments.size) {
            0 -> null
            1 -> "1 image"
            else -> "${attachments.size} images"
        }
        return listOfNotNull(clipped.takeIf { it.isNotEmpty() }, images).joinToString(" · ")
    }

    companion object {
        /** Incoming share text is appended under whatever the composer already has, so a half-written prompt is kept. */
        fun mergeText(existing: String, incoming: String): String {
            val add = incoming.trim()
            if (add.isEmpty()) return existing
            val have = existing.trimEnd()
            return if (have.isEmpty()) add else "$have\n\n$add"
        }
    }
}

/**
 * Holds the share that is waiting to be drafted. Lives on [com.cursorforandroid.AppGraph] so it survives rotation
 * and sign-in: the picker (or the composer that consumes it) reads [offer], and image bytes are loaded here rather
 * than on the activity so a recreate mid-read cannot drop them.
 */
class ShareInbox(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _offer = MutableStateFlow<ShareDraft?>(null)
    val offer: StateFlow<ShareDraft?> = _offer.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var nextGeneration = 1L
    private var lastToken: String? = null
    /** Bumped when a new load starts or the inbox is cleared, so a stale read cannot re-offer a dismissed share. */
    private var loadId = 0

    /** Told once the share has been drafted, dismissed or found to hold nothing; see [receive]. */
    private var onHandled: (() -> Unit)? = null

    /**
     * Reads [intent] if it is a share. The same payload already being offered or still loading is ignored (rotation
     * redelivers the intent); an identical share after this one was consumed or dismissed is accepted again.
     *
     * [onHandled] runs once this share has been dealt with, for the caller to strike it off the intent it came on:
     * the in-memory guard here dies with the process, so nothing else stops a redelivered intent being offered
     * again (see [ShareIntent.clear]).
     */
    fun receive(intent: Intent?, onHandled: () -> Unit = {}) {
        if (intent == null || !ShareIntent.isShare(intent)) return
        // Set before the guard below: what is worth clearing is the intent the activity is answering with now.
        this.onHandled = onHandled
        val token = ShareIntent.token(intent)
        if (token == lastToken && (_offer.value != null || _loading.value)) return
        lastToken = token
        val id = ++loadId
        scope.launch {
            _loading.value = true
            val loaded = withContext(Dispatchers.IO) { ShareIntent.load(appContext, intent) }
            if (id != loadId) return@launch
            if (loaded == null) {
                _loading.value = false
                handled()
                return@launch
            }
            _offer.value = loaded.copy(generation = nextGeneration++)
            _loading.value = false
        }
    }

    fun setTarget(target: ShareTarget) {
        _offer.update { it?.copy(target = target) }
    }

    fun consume(generation: Long) {
        var took = false
        _offer.update { current ->
            took = current?.generation == generation
            if (took) null else current
        }
        if (took) handled()
    }

    fun clear() {
        loadId++
        _offer.value = null
        _loading.value = false
        handled()
    }

    private fun handled() {
        val done = onHandled ?: return
        onHandled = null
        done()
    }
}
