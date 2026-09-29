package com.cursorforandroid.ui.conversation

import android.animation.ValueAnimator
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.ExpandedTopGap
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.dockedCard
import com.cursorforandroid.ui.components.fadingVerticalScroll
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat
import kotlinx.coroutines.delay

/** [GoalStrip] holding its own open state, saved with the screen per objective. */
@Composable
fun GoalStrip(
    goal: Goal,
    modifier: Modifier = Modifier,
    clock: () -> Long = AppClock::now,
) {
    var expanded by rememberSaveable("goal-strip-${goal.objective.hashCode()}") { mutableStateOf(false) }
    GoalStrip(goal = goal, expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier, clock = clock)
}

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
 * Opened, the strip grows on the queue's spring to the height its parent allows and no further ([GoalDock] allows the
 * page above the queue and the composer); what does not fit scrolls inside it, fading at the edges it scrolls past as
 * the composer's text does.
 *
 * [clock] is the wall clock the count reads; the screenshot tests pin it.
 */
@Composable
fun GoalStrip(
    goal: Goal,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    clock: () -> Long = AppClock::now,
    animate: () -> Boolean = ValueAnimator::areAnimatorsEnabled,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
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
    val animated = animate()
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, if (animated) StackSpring else snap(), label = "goal-chevron")
    val scroll = rememberScrollState()
    LaunchedEffect(expanded) { if (!expanded) scroll.scrollTo(0) }
    Column(
        modifier
            .fillMaxWidth()
            .dockedCard()
            .pressable({ onExpandedChange(!expanded) }, CursorTheme.shapes.xl, role = Role.Button)
            .animateContentSize(if (animated) GoalSizeSpring else snap())
            .semantics { contentDescription = "${goal.label}${elapsed?.let { " for $it" } ?: ""}: ${goal.objective}" }
            .testTag("goal-strip")
            // Inside the animated size, so the viewport is the strip as it stands and the fade rides its edges.
            .fadingVerticalScroll(scroll, surface = colors.elevated),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = RowHeight)
                .padding(start = CursorDimens.composerPadding + CursorDimens.composerTextInset, end = CursorDimens.composerPadding - 2.dp)
                .padding(vertical = 6.dp),
            // Opened, the glyphs stay on the label's line rather than centring on an objective of any length.
            verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.padding(top = if (expanded) 2.dp else 0.dp).size(12.dp))
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
            Icon(CursorIcons.ChevronDown, null, tint = colors.iconQuaternary, modifier = Modifier.padding(top = if (expanded) 1.dp else 0.dp).size(14.dp).rotate(chevron))
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
 * The goal strip over what docks under it — the queue and the composer, in [below] — measured bottom first, so an opened
 * goal takes the height left over rather than pushing the composer off the page. [below] is measured with all the height
 * but the goal's collapsed one, which is what the composer expanding measured before; the goal then gets what [below]
 * left, less [ExpandedTopGap] while [open], the gap an expanded composer keeps under the header. Both are read while
 * laying out, so the goal follows the keyboard and the composer frame by frame. The goal's slot is always composed,
 * empty without a goal, so the composer below keeps its place in the tree (and its text) as a goal comes and goes.
 */
@Composable
fun GoalDock(
    open: Boolean,
    goal: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    below: @Composable ColumnScope.() -> Unit,
) {
    val collapsedGoalPx = remember { mutableIntStateOf(0) }
    val currentOpen by rememberUpdatedState(open)
    Layout(
        content = {
            Box(contentAlignment = Alignment.TopCenter) { goal?.invoke() }
            Column(horizontalAlignment = Alignment.CenterHorizontally, content = below)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val bounded = constraints.hasBoundedHeight
        val full = Constraints(minWidth = width, maxWidth = width)
        val belowMax = if (bounded) (constraints.maxHeight - collapsedGoalPx.intValue).coerceAtLeast(0) else Constraints.Infinity
        val below = measurables[1].measure(full.copy(maxHeight = belowMax))
        val gap = if (currentOpen) ExpandedTopGap.roundToPx() else 0
        val goalMax = if (bounded) (constraints.maxHeight - below.height - gap).coerceAtLeast(0) else Constraints.Infinity
        val goalPlaceable = measurables[0].measure(full.copy(maxHeight = goalMax))
        if (!currentOpen && collapsedGoalPx.intValue != goalPlaceable.height) collapsedGoalPx.intValue = goalPlaceable.height
        val height = (goalPlaceable.height + below.height).coerceIn(constraints.minHeight, if (bounded) constraints.maxHeight else Int.MAX_VALUE)
        layout(width, height) {
            goalPlaceable.place(0, 0)
            below.place(0, goalPlaceable.height)
        }
    }
}

/** The strip's height opening and closing: the queue stack's spring, so the two move as one family. */
private val GoalSizeSpring = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)

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
