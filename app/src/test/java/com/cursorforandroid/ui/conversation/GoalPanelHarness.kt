package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerExpansion
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.rememberComposerExpansion
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The chat's dock as `ConversationScreen` lays it out, for the goal panel's tests and goldens: a header band, the
 * transcript taking what is left, then the goal, one queued follow-up and the composer in a [GoalDock], over a
 * [keyboard] band standing in for the on-screen keyboard (the dock sits on it as it sits on the IME's inset).
 */
class GoalPanelHarness(open: Boolean = false, keyboard: Dp = 0.dp) {
    var open by mutableStateOf(open)
    var keyboard by mutableStateOf(keyboard)
    lateinit var expansion: ComposerExpansion
        private set

    @Composable
    fun Content(goal: Goal = LongGoal, clock: () -> Long = { T0 + 4_512_000L }, animate: () -> Boolean = { true }) {
        val expansion = rememberComposerExpansion().also { expansion = it }
        LaunchedEffect(expansion.expanded) { if (expansion.expanded) open = false }
        Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
            Column(Modifier.fillMaxSize().padding(bottom = keyboard)) {
                Box(Modifier.fillMaxWidth().height(CursorDimens.chatHeaderHeight).testTag(HEADER))
                Box(Modifier.weight(1f).fillMaxWidth().testTag(TRANSCRIPT))
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).testTag(DOCK), horizontalAlignment = Alignment.CenterHorizontally) {
                    GoalDock(
                        open = open,
                        goal = {
                            GoalStrip(
                                goal = goal,
                                expanded = open,
                                onExpandedChange = { open = it; if (it) expansion.collapse() },
                                clock = clock,
                                animate = animate,
                                modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp),
                            )
                        },
                    ) {
                        AccountQueueRows(
                            queue = listOf(PendingFollowup("fu-1", "Then add a test for the light theme", 1_000L, AgentSource.GLASS)),
                            inFlightIds = emptySet(),
                            onSteer = {},
                            onRemove = {},
                            onUpdate = { _, _ -> },
                            onEditing = { _, _ -> },
                            steers = true,
                            modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp),
                        )
                        ComposerBox(
                            value = "",
                            onValueChange = {},
                            placeholder = "Follow up (queues on your account)…",
                            onSend = {},
                            isRunning = true,
                            onStop = {},
                            plusMenu = ComposerMenuActions(onPickMedia = {}),
                            modelLabel = "Claude Fable 5.1",
                            onModel = {},
                            expansion = expansion,
                            modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).testTag(COMPOSER),
                        )
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(keyboard).background(KeyboardColor).testTag(KEYBOARD))
        }
    }

    companion object {
        const val HEADER = "harness-header"
        const val TRANSCRIPT = "harness-transcript"
        const val DOCK = "harness-dock"
        const val COMPOSER = "harness-composer"
        const val KEYBOARD = "harness-keyboard"
        const val T0 = 1_789_380_000_000L
        private val KeyboardColor = Color(0xFF202124)

        /** A goal whose objective runs to several screens' worth of lines on a phone. */
        val LongObjective = buildString {
            append("Ship the Verity photoreal limbs end to end, without cutting a single corner anywhere along the way. ")
            listOf(
                "Build hands, forearms, upper arms, thighs, shanks and feet from anthropometric tables, every landmark placed where the literature puts it.",
                "Rig them with natural pose models, analytic weights and contact deformation of the finger pads and soles against whatever they touch.",
                "Shade the skin hyper-real: pores, creases, veins, nails and body hair, subsurface scattering tuned against reference photographs.",
                "Iterate visually, render after render, until the limbs read as genuinely real to someone who was not told they are procedural.",
                "Render stills and clips of the limbs gripping a mug, a pen, a keyboard and a railing, and walking barefoot over sand, tile and grass.",
                "Publish every render into demo/, commit, push, and open a pull request with the renders embedded so they can be reviewed in place.",
                "Keep the frame budget under four seconds per still on the CI runner and document every parameter that trades quality for time.",
                "Write the regression suite: silhouettes, joint limits, contact penetration depth and skin tone histograms, all checked on every push.",
                "Report back with a summary of what changed, what is still weak, and which references were used for each part of the body.",
            ).forEachIndexed { index, line -> append("(${index + 1}) ").append(line).append(' ') }
            append("Nothing is done until every requirement above is demonstrably met and the pull request is green.")
        }

        val LongGoal = Goal(LongObjective, GoalStatus.ACTIVE, accruingSinceMillis = T0, continuationCount = 4, source = Goal.Source.Account)
    }
}
