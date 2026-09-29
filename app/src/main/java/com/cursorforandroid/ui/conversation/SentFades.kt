package com.cursorforandroid.ui.conversation

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.LocalSendMotion
import com.cursorforandroid.ui.components.SendMotion
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A sent message's bubble coming up from its sending fade to full strength, as one motion over whichever bubble shows
 * the message by then. The bubble the composer shows ahead of the request is the transcript's item under a `local-`
 * id; the server's copy that replaces it once the transcript has caught up is another item, under the server's id, and
 * so another row of the list, composed afresh. A fade kept by the bubble itself restarted at full strength with that
 * row — a snap from the sending look to the sent one whenever the copy came in with the filing or during its fade.
 *
 * So the fade is the screen's, told the transcript's user messages as they are shown ([look]): a bubble that was
 * sending at the last look and is not now starts one, from where the sending look left it; a bubble that leaves with
 * its fade under way (or still sending) hands it to the new bubble saying the same words, which carries on from the
 * value it had. The fade runs here ([run], in the screen's scope), so neither a row scrolled off and back nor a row
 * recomposed starts it again, and a bubble with none is at full strength. With animations off there is no fade.
 *
 * A bubble the send's copy is still flying into is not drawn, so a fade begun under it would be spent unseen: the
 * account often files a message within the flight's third of a second, and the copy then landed on a bubble at full
 * strength. A fade waits at the sending look until no copy is on its way into its bubble (see [run]).
 */
@Stable
class SentFades(private val animatorsEnabled: () -> Boolean = ValueAnimator::areAnimatorsEnabled) {
    /** [id] is the bubble drawing the fade now: the sending bubble's, then the server's copy's once it has taken over. */
    private class Fade(var id: String, val words: String) {
        val alpha = Animatable(PendingMessageAlpha)
    }

    /** The fades under way, by the bubble drawing each. Plain: written as the transcript is looked at, before its rows compose. */
    private val fades = HashMap<String, Fade>()
    private val started = Channel<Fade>(Channel.UNLIMITED)
    private var sending: Map<String, String> = emptyMap()
    private var known: Set<String> = emptySet()
    private var seen: List<UserMessage>? = null

    /**
     * [message]'s bubble's opacity: its sending look, under its fade, or whole. Read in the bubble's composition, so
     * the fade's frames reach it. A list recomposes a row whose item changed before the screen looks at the new
     * transcript, so the bubble claims its fade here when it is first to know — by what the last [look] saw.
     */
    fun alpha(message: UserMessage): Float {
        if (message.isPending) return PendingMessageAlpha
        return (fades[message.id] ?: claim(message))?.alpha?.value ?: 1f
    }

    private fun claim(message: UserMessage): Fade? {
        val id = message.id
        // Filed where it stands.
        sending[id]?.let { words -> return start(id, words) }
        if (id in known) return null
        // New since the last look, and saying what a sending bubble said, or one whose fade is under way: its copy.
        val words = words(message.text)
        sending.entries.firstOrNull { it.value == words }?.let { (was, _) -> sending = sending - was; return start(id, words) }
        return fades.values.firstOrNull { it.words == words }?.also { fades[id] = it; it.id = id }
    }

    private fun start(id: String, words: String): Fade? {
        sending = sending - id
        if (!animatorsEnabled()) return null
        return Fade(id, words).also {
            fades[id] = it
            started.trySend(it)
        }
    }

    /** Whether the bubble [id] is coming up from its sending fade. */
    fun fading(id: String): Boolean = id in fades

    /**
     * The transcript's user messages as they are shown now (see [PresentedTranscript.userMessages]). Called in the
     * composition that shows them, before its rows compose, so a bubble composed in that frame already has its fade; a
     * list looked at before is let be.
     */
    fun look(messages: List<UserMessage>) {
        if (messages === seen) return
        seen = messages
        val byId = messages.associateBy { it.id }
        // Bubbles new to this look, sent: where a message that left under one id reappears under the server's.
        val fresh = messages.filterTo(ArrayList()) { !it.isPending && it.id !in known }
        fun successor(id: String, words: String): UserMessage? {
            byId[id]?.let { return it.takeUnless { m -> m.isPending } }
            return fresh.firstOrNull { words(it.text) == words }?.also { fresh.remove(it) }
        }
        // A fade whose bubble left: the bubble now saying its words carries it on; none, and it is dropped.
        for ((id, fade) in fades.entries.toList()) {
            if (id in byId) continue
            fades.remove(id)
            successor(id, fade.words)?.let { fades[it.id] = fade; fade.id = it.id }
        }
        // A bubble that was sending and is not: its fade starts, on it or on the copy that took its place — unless that
        // bubble, composed first, has started it already.
        for ((id, words) in sending) {
            if (byId[id]?.isPending == true) continue
            val next = successor(id, words) ?: continue
            if (next.id !in fades) start(next.id, words)
        }
        sending = messages.filter { it.isPending }.associate { it.id to words(it.text) }
        known = byId.keys
    }

    /**
     * Runs each fade [look] starts, in the caller's scope: the screen's, which outlives any one row. Each waits at the
     * sending look while one of [motion]'s copies is still on its way into its bubble.
     */
    suspend fun run(motion: () -> SendMotion? = { null }) = coroutineScope {
        for (fade in started) {
            launch {
                snapshotFlow { motion()?.landingOn(fade.id, fade.words) == true }.first { !it }
                fade.alpha.animateTo(1f, tween(SentFadeMillis))
                fades.values.removeAll { it === fade }
            }
        }
    }

    companion object {
        /** How long a sent bubble takes to come up to full strength: the app's short fades are 150–240 ms. */
        const val SentFadeMillis = 240
    }
}

@Composable
fun rememberSentFades(
    key: Any,
    animatorsEnabled: () -> Boolean = ValueAnimator::areAnimatorsEnabled,
    motion: SendMotion? = LocalSendMotion.current,
): SentFades {
    val fades = remember(key) { SentFades(animatorsEnabled) }
    val currentMotion = rememberUpdatedState(motion)
    LaunchedEffect(fades) { fades.run { currentMotion.value } }
    return fades
}

/** The screen's [SentFades]; null where rows are drawn without one, each bubble then fading on its own. */
val LocalSentFades = staticCompositionLocalOf<SentFades?> { null }

/** How faded a message still being sent is drawn. */
internal const val PendingMessageAlpha = 0.5f

private fun words(text: String): String = text.replace(Whitespace, " ").trim()

private val Whitespace = Regex("\\s+")
