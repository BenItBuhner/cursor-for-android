package com.cursorforandroid.ui.conversation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.dockedCard
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat
import kotlinx.coroutines.delay

/**
 * The chat's goal, above the composer while there is one: the strip Cursor's own clients keep there (the desktop's
 * goal tray, above its queue), in the surface the queue's follow-ups sit in ([dockedCard], concentric with the
 * composer's corners) so the two stack as one family. A small
 * line says where the goal stands — "Goal active", "Goal paused", "Goal updated", "Goal completed" — with the active
 * time counting up beside it every second while the goal is active (`1h 2m 3s`, as the desktop formats it), and
 * under that the objective, verbatim, on one line. A tap opens the strip onto the whole objective and a line on what
 * the status means, how many turns Cursor has started for the goal, and where the goal was read from; a second tap
 * closes it. Read-only: a goal is set with `/goal`, and the agent's `UpdateGoal` moves it along.
 *
 * [clock] is the wall clock the count reads; the screenshot tests pin it.
 */
@Composable
fun GoalStrip(
    goal: Goal,
    modifier: Modifier = Modifier,
    clock: () -> Long = AppClock::now,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var expanded by rememberSaveable("goal-strip-${goal.objective.hashCode()}") { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(clock()) }
    // The count ticks only while the goal is active and a source said from when; a paused or completed goal shows
    // what it accrued and holds still.
    LaunchedEffect(goal.isTicking, goal.accruingSinceMillis) {
        now = clock()
        if (!goal.isTicking) return@LaunchedEffect
        while (true) {
            delay(TICK_MS)
            now = clock()
        }
    }
    val elapsed = if (goal.hasElapsed) TimeFormat.elapsed(goal.elapsedMillis(now)) else null
    val (icon, tint) = when (goal.status) {
        GoalStatus.COMPLETE -> CursorIcons.Check to colors.green.copy(alpha = 0.8f)
        GoalStatus.PAUSED -> CursorIcons.Pause to colors.iconQuaternary
        else -> CursorIcons.Target to colors.iconTertiary
    }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "goal-chevron")
    Column(
        modifier
            .fillMaxWidth()
            .dockedCard()
            .pressable({ expanded = !expanded }, CursorTheme.shapes.xl, role = Role.Button)
            .animateContentSize()
            .semantics { contentDescription = "${goal.label}${elapsed?.let { " for $it" } ?: ""}: ${goal.objective}" }
            .testTag("goal-strip"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = RowHeight)
                .padding(start = CursorDimens.composerPadding + CursorDimens.composerTextInset, end = CursorDimens.composerPadding - 2.dp)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(goal.label, style = type.small, color = colors.textTertiary, maxLines = 1, modifier = Modifier.testTag("goal-label"))
                    if (elapsed != null) {
                        Text(" · $elapsed", style = type.small, color = colors.textQuaternary, maxLines = 1, modifier = Modifier.testTag("goal-elapsed"))
                    }
                }
                Text(
                    goal.objective,
                    style = type.input,
                    color = colors.textPrimary,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    softWrap = expanded,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("goal-objective"),
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(CursorIcons.ChevronDown, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp).rotate(chevron))
        }
        if (expanded) {
            HairlineDivider(Modifier.padding(horizontal = CursorDimens.composerPadding))
            Column(
                Modifier.padding(start = CursorDimens.composerPadding + CursorDimens.composerTextInset, end = CursorDimens.composerPadding, top = 8.dp, bottom = 10.dp).testTag("goal-details"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                details(goal, elapsed).forEach { line -> Text(line, style = type.small, color = colors.textTertiary) }
            }
        }
    }
}

/**
 * The lines the open strip adds under the objective: what the status means for the chat, the turns Cursor has
 * started for the goal, the time worked when the count no longer moves, and which record the goal was read from.
 */
internal fun details(goal: Goal, elapsed: String?): List<String> = buildList {
    add(
        when (goal.status) {
            GoalStatus.ACTIVE -> "Cursor keeps working toward this goal between turns until it is complete."
            GoalStatus.PAUSED -> "Held: Cursor starts no new turn for this goal until it is resumed."
            GoalStatus.COMPLETE -> if (elapsed != null) "Complete after $elapsed of work." else "Complete."
            GoalStatus.CLEARED -> "Cleared."
        },
    )
    if (goal.continuationCount > 0) add(if (goal.continuationCount == 1) "1 turn started by Cursor for it." else "${goal.continuationCount} turns started by Cursor for it.")
    add(
        when (goal.source) {
            Goal.Source.Account -> "From the goal Cursor keeps on the chat."
            Goal.Source.Transcript -> "From the goal calls and continuations in this chat's transcript."
        },
    )
}

/** One line of composer text plus the composer's vertical padding: the queue's rows are this tall, and this one is two lines of it. */
private val RowHeight = 40.dp
private const val TICK_MS = 1_000L
