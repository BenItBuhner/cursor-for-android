package com.cursorforandroid.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.cursorforandroid.ui.theme.CursorDimens

/**
 * How every screen other than a chat fits a pane wider than a phone: [gutter] in from the pane's sides, then a column
 * no wider than [maxWidth], centred in what is left. Where the pane less its gutters is narrower than the column —
 * every phone held upright — this is exactly `padding(horizontal = gutter)`, so a phone lays out as it always has;
 * on an unfolded foldable or a tablet the rows, cards and footers stop at the column's edges instead of running out
 * to the pane's.
 *
 * Place it after a scroll (`fadingVerticalScroll`), not before: the viewport, its drag and its edge fades then still
 * span the whole pane, and only what scrolls is set in the column. In a lazy list, give it to each item. A block that
 * draws its own insets (a sidebar-style row) takes `gutter = 0.dp`.
 */
fun Modifier.contentColumn(maxWidth: Dp = CursorDimens.contentMaxWidth, gutter: Dp = CursorDimens.pageGutter): Modifier =
    padding(horizontal = gutter)
        .fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = maxWidth)
        .fillMaxWidth()
