package com.cursorforandroid.screenshots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp

/**
 * Settings' list scrolled until the label of [group] sits just under its top edge, so the group's rows are in view
 * rather than dimmed under the list's bottom fade, as a bare scroll-to would leave them. The list is told from a
 * tablet's sidebar beside it by the label it holds.
 */
internal fun ComposeTestRule.scrollSettingsGroupToTop(group: String) {
    onNodeWithText(group).performScrollTo()
    waitForIdle()
    val list = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(hasText(group)))
    val top = list.fetchSemanticsNode().boundsInRoot.top
    val label = onNodeWithText(group).fetchSemanticsNode().boundsInRoot.top
    val margin = with(density) { 24.dp.toPx() }
    list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, label - top - margin) }
    waitForIdle()
}
