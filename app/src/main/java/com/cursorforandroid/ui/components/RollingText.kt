package com.cursorforandroid.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/**
 * A one-line status that rolls from one wording to the next, the desktop's `TextRoll`: the new words rise half a line
 * into place as they fade in while the old ones rise out and fade, and each wording holds for at least [RollingText.HoldMillis]
 * before the next rolls up, so a status racing through its steps reads as a steady line rather than a flicker. It
 * shimmers while [shimmer] is on.
 *
 * A subagent's row says where its child stands with it ("Working" → "Reading file" → "Thinking"), and a chat's run
 * status caption says the run's state with it ("Starting…" → "Working…" → "Reconnecting…").
 */
@Composable
fun RollingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = CursorTheme.typography.base,
    color: Color = CursorTheme.colors.textTertiary,
    shimmer: Boolean = true,
    label: String = "rolling-text",
) {
    val shown = heldText(text)
    AnimatedContent(
        targetState = shown,
        transitionSpec = {
            (slideInVertically(tween(RollingText.RollMillis)) { it / 2 } + fadeIn(tween(RollingText.RollMillis))) togetherWith
                (slideOutVertically(tween(RollingText.RollMillis)) { -it / 2 } + fadeOut(tween(RollingText.RollMillis))) using SizeTransform(clip = true)
        },
        label = label,
        modifier = modifier,
    ) { words ->
        ShimmerText(words, style = style, color = color, active = shimmer, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** [text], but no value shown for less than [RollingText.HoldMillis]: the newest one waiting its turn is what shows next. */
@Composable
private fun heldText(text: String): String {
    val latest by rememberUpdatedState(text)
    var shown by remember { mutableStateOf(text) }
    LaunchedEffect(Unit) {
        snapshotFlow { latest }.conflate().collect { next ->
            if (next != shown) shown = next
            delay(RollingText.HoldMillis)
        }
    }
    return shown
}

object RollingText {
    /** How long one wording stays before the next may roll in. */
    const val HoldMillis = 1_200L

    /** How long a roll takes. */
    const val RollMillis = 220
}
