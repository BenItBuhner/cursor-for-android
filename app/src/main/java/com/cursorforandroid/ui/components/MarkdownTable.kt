package com.cursorforandroid.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.roundToInt

/** What a table is fitted to when nothing bounds it (a preview, an unconstrained measure). */
private val TableFallbackWidth = 360.dp

/**
 * A GFM table in a hairline card, laid out the way a browser lays out `table-layout: auto`: every column starts at
 * the width of its widest cell; when that does not fit the message, columns give up width in proportion to how much
 * their text can wrap, down to their longest word; and when even that overflows — many columns, a long hash — the
 * table scrolls sideways inside the card rather than breaking words. The header row is bold on a faint wash.
 */
@Composable
fun TableBlock(table: MdBlock.Table, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else with(LocalDensity.current) { TableFallbackWidth.roundToPx() }
        val scroll = rememberScrollState()
        Box(
            Modifier
                .cursorSurface(Color.Transparent, colors.strokeSubtle, CursorTheme.shapes.lg)
                .horizontalScrollEdgeFade(clippedAtStart = scroll.canScrollBackward, clippedAtEnd = scroll.canScrollForward)
                .horizontalScroll(scroll),
        ) {
            TableGrid(table, style, color, available)
        }
    }
}

@Composable
private fun TableGrid(table: MdBlock.Table, style: TextStyle, color: Color, availableWidth: Int) {
    val columns = table.header.size
    val rows = 1 + table.rows.size
    Layout(
        content = {
            table.header.forEachIndexed { c, cell ->
                TableCell(cell, style, color, table.alignments[c], header = true, lastColumn = c == columns - 1, lastRow = rows == 1)
            }
            table.rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, cell ->
                    TableCell(cell, style, color, table.alignments[c], header = false, lastColumn = c == columns - 1, lastRow = r == table.rows.size - 1)
                }
            }
        },
    ) { measurables, constraints ->
        if (columns == 0 || measurables.isEmpty()) return@Layout layout(0, 0) {}
        val widest = IntArray(columns)
        val narrowest = IntArray(columns)
        measurables.forEachIndexed { index, cell ->
            val c = index % columns
            widest[c] = maxOf(widest[c], cell.maxIntrinsicWidth(Constraints.Infinity))
            narrowest[c] = maxOf(narrowest[c], cell.minIntrinsicWidth(Constraints.Infinity))
        }
        val widths = fitColumns(narrowest, widest, availableWidth)
        val heights = IntArray(rows)
        measurables.forEachIndexed { index, cell ->
            val r = index / columns
            heights[r] = maxOf(heights[r], cell.maxIntrinsicHeight(widths[index % columns]))
        }
        val placeables = measurables.mapIndexed { index, cell -> cell.measure(Constraints.fixed(widths[index % columns], heights[index / columns])) }
        layout(constraints.constrainWidth(widths.sum()), constraints.constrainHeight(heights.sum())) {
            var y = 0
            for (r in 0 until rows) {
                var x = 0
                for (c in 0 until columns) {
                    placeables[r * columns + c].placeRelative(x, y)
                    x += widths[c]
                }
                y += heights[r]
            }
        }
    }
}

/**
 * Column widths for cells whose longest words need [narrowest] and whose unwrapped text needs [widest]: the natural
 * widths when they fit in [available]; otherwise each column shrinks from its natural width in proportion to how much
 * it can wrap, so single-word columns (numbers, hashes) keep their size and prose absorbs the difference; and when
 * even the longest words overflow, prose columns are capped at 60 % of the width so the table scrolls at a readable
 * width instead of putting one word per line.
 */
internal fun fitColumns(narrowest: IntArray, widest: IntArray, available: Int): IntArray {
    val naturalTotal = widest.sum()
    val minimumTotal = narrowest.sum()
    return when {
        naturalTotal <= available -> widest.copyOf()
        minimumTotal <= available -> {
            val slack = available - minimumTotal
            val wrappable = naturalTotal - minimumTotal
            IntArray(widest.size) { c -> narrowest[c] + (slack.toLong() * (widest[c] - narrowest[c]) / wrappable).toInt() }
        }
        else -> {
            val cap = (available * 0.6f).roundToInt()
            IntArray(widest.size) { c -> maxOf(narrowest[c], minOf(widest[c], cap)) }
        }
    }
}

/** One cell: text at the column's alignment inside 10 x 6 padding; draws its own row and column hairlines. */
@Composable
private fun TableCell(
    text: String,
    style: TextStyle,
    color: Color,
    align: TableAlign,
    header: Boolean,
    lastColumn: Boolean,
    lastRow: Boolean,
) {
    val colors = CursorTheme.colors
    val line = colors.strokeSubtle
    val wash = colors.fillFaint
    val hairline = CursorDimens.hairline
    val cellStyle = style.copy(
        fontWeight = if (header) FontWeight.SemiBold else style.fontWeight,
        textAlign = when (align) {
            TableAlign.Start -> TextAlign.Start
            TableAlign.Center -> TextAlign.Center
            TableAlign.End -> TextAlign.End
        },
    )
    Box(
        Modifier
            .drawBehind {
                if (header) drawRect(wash)
                val stroke = hairline.toPx()
                if (!lastRow) drawRect(line, Offset(0f, size.height - stroke), Size(size.width, stroke))
                if (!lastColumn) drawRect(line, Offset(size.width - stroke, 0f), Size(stroke, size.height))
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = when (align) {
            TableAlign.Start -> Alignment.TopStart
            TableAlign.Center -> Alignment.TopCenter
            TableAlign.End -> Alignment.TopEnd
        },
    ) {
        InlineText(text, cellStyle, color)
    }
}
