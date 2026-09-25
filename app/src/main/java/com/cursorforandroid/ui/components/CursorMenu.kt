package com.cursorforandroid.ui.components

import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.max
import kotlin.math.min

/**
 * Every popup menu in the app — the composer's "+" menu and `/` popover, a chat's "More", the long-press menus —
 * on one surface in the composer's language: the docked cards' corner ([CursorDimens.menuRadius], concentric with
 * the composer where the "+" menu drops from its disc), the editor fill, the divider's hairline, and a low, wide
 * shadow in place of a Material elevation, so it reads as a card lifted off the page rather than a slab with a hard
 * edge under it, as the menus on cursor.com and in the iOS app do.
 *
 * It opens below what anchors it, or above when there is no room below, and lines up with the anchor's start where
 * it fits and its end where that does not. It never comes nearer than [CursorDimens.menuEdgeMargin] to the window's
 * edges, the system bars or the keyboard, and moves with the keyboard while it is open. It grows in from the side
 * that anchors it and shrinks back out. Opened by a right-click ([onContextClick]), it takes the pointer for its
 * anchor instead ([at]).
 */
@Composable
fun CursorMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    /** Added to the menu's position, x mirrored right to left, as a Material menu's offset is. */
    offset: DpOffset = DpOffset.Zero,
    /** False for a popover that has to leave the keyboard up and the typing going (the `/` popover). */
    focusable: Boolean = true,
    /** Where a right-click opened the menu, in window coordinates: the menu opens at that point, not beside its anchor. */
    at: IntOffset? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    if (!state.currentState && !state.targetState) return

    val colors = CursorTheme.colors
    val density = LocalDensity.current
    val view = LocalView.current
    val insets = WindowInsets.safeDrawing
    var placement by remember { mutableStateOf(MenuPlacement.Initial) }
    val provider = remember(density, offset, view, insets, at) {
        MenuPositionProvider(density, offset, area = { window -> safeArea(view, insets, density, window) }, onPlaced = { placement = it }, at = at)
    }
    val maxHeight = with(density) {
        val height = view.rootView.height
        if (height <= 0) Dp.Unspecified
        else (height - insets.getTop(this) - insets.getBottom(this)).toDp() - CursorDimens.menuEdgeMargin * 2
    }

    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = focusable)) {
        val transition = rememberTransition(state, label = "menu")
        val progress by transition.animateFloat(
            transitionSpec = { if (targetState) tween(ENTER_MILLIS, easing = EnterEasing) else tween(EXIT_MILLIS, easing = ExitEasing) },
            label = "menu-progress",
        ) { open -> if (open) 1f else 0f }
        val room = CursorDimens.menuShadowRoom
        // The popup is the surface plus room on every side for its shadow; a tap in that room is a tap outside.
        Box(
            Modifier
                .graphicsLayer {
                    val p = progress
                    val scale = ENTER_SCALE + (1f - ENTER_SCALE) * p
                    scaleX = scale
                    scaleY = scale
                    alpha = p
                    transformOrigin = placement.origin
                    translationY = (if (placement.below) -1f else 1f) * CursorDimens.menuGap.toPx() * (1f - p)
                }
                .pointerInput(onDismissRequest) {
                    val inset = room.toPx()
                    detectTapGestures { at ->
                        if (at.x < inset || at.y < inset || at.x > size.width - inset || at.y > size.height - inset) onDismissRequest()
                    }
                }
                .padding(room),
        ) {
            Column(
                modifier
                    .menuShadow(colors.elevated, colors.shadow)
                    .cursorSurface(colors.elevated, colors.strokeSubtle, CursorTheme.shapes.menu)
                    .heightIn(max = maxHeight)
                    .widthIn(min = CursorDimens.menuMinWidth, max = CursorDimens.menuMaxWidth)
                    .width(IntrinsicSize.Max)
                    .fadingVerticalScroll(surface = colors.elevated)
                    .padding(vertical = CursorDimens.menuInset),
                content = content,
            )
        }
    }
}

/**
 * One row of a [CursorMenu]: a [CursorDimens.menuIcon] glyph at the secondary icon tone, a 13sp label with an
 * optional dim [hint] after it (a command's argument) and a second line beneath ([subtitle]), and an optional
 * [trailing] control or mark. The press highlight is inset [CursorDimens.menuInset] from the menu's edges with a
 * corner concentric with the menu's. A [tint] other than the primary text colour (a destructive red) colours the
 * glyph as well; not [enabled], the row dims and takes no taps. [highlighted] is the row a physical keyboard would
 * pick ([PopoverSelection]): it wears the hover fill in the press highlight's place and is scrolled into view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CursorMenuItem(
    label: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    hint: String? = null,
    tint: Color = CursorTheme.colors.textPrimary,
    enabled: Boolean = true,
    subtitleMaxLines: Int = 2,
    highlighted: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val labelColor = if (enabled) tint else colors.textQuaternary
    val iconTint = when {
        !enabled -> colors.iconQuaternary
        tint == colors.textPrimary -> colors.iconSecondary
        else -> tint
    }
    val inView = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted) { if (highlighted) inView.bringIntoView() }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.menuInset)
            .bringIntoViewRequester(inView)
            .then(
                if (highlighted) {
                    Modifier
                        .background(colors.fill, CursorTheme.shapes.menuItem)
                        .semantics { selected = true }
                } else {
                    Modifier
                },
            )
            .pressable(onClick, CursorTheme.shapes.menuItem, enabled = enabled, role = null)
            .heightIn(min = CursorDimens.menuRow)
            .padding(horizontal = CursorDimens.menuItemPadding, vertical = if (subtitle != null) 7.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(CursorDimens.menuIcon))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = type.base, color = labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (hint != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(hint, style = type.base, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (subtitle != null) {
                Text(subtitle, style = type.small, color = if (enabled) colors.textTertiary else colors.textQuaternary, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Between groups of a [CursorMenu]'s rows: the divider's hairline across the whole menu, with [CursorDimens.menuInset] of air either side. */
@Composable
fun CursorMenuSeparator(modifier: Modifier = Modifier) {
    HairlineDivider(modifier.padding(vertical = CursorDimens.menuInset))
}

/**
 * Drawn under the surface: its own shape again, casting a blurred shadow [CursorDimens.menuShadowDrop] down. Hardware
 * canvases draw shadow layers under shapes from API 28; below that there is no shadow, and the hairline alone
 * separates the menu from the page.
 */
private fun Modifier.menuShadow(fill: Color, shadow: Color): Modifier = drawWithCache {
    val corner = CursorDimens.menuRadius.toPx()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = fill.copy(alpha = 1f).toArgb()
        setShadowLayer(CursorDimens.menuShadowBlur.toPx(), 0f, CursorDimens.menuShadowDrop.toPx(), shadow.toArgb())
    }
    onDrawBehind {
        drawContext.canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, corner, corner, paint)
    }
}

/** Where the menu went, for its animation: the point it grows from, as fractions of the popup, and which side of the anchor it is on. */
internal data class MenuPlacement(val origin: TransformOrigin, val below: Boolean) {
    companion object {
        val Initial = MenuPlacement(TransformOrigin(0.5f, 0f), below = true)
    }
}

/**
 * The part of the window a menu may cover: the root view less the system bars, cutouts and keyboard
 * (`safeDrawing`), less [CursorDimens.menuEdgeMargin] on every side. The popup is positioned in the root view's
 * coordinates, so the root view is measured rather than the visible display frame the popup is given, which leaves
 * out the status bar at the top without saying so.
 */
private fun safeArea(view: View, insets: WindowInsets, density: Density, window: IntSize): IntRect {
    val root = view.rootView
    val width = root.width.takeIf { it > 0 } ?: window.width
    val height = root.height.takeIf { it > 0 } ?: window.height
    val margin = with(density) { CursorDimens.menuEdgeMargin.roundToPx() }
    return IntRect(
        left = insets.getLeft(density, LayoutDirection.Ltr) + margin,
        top = insets.getTop(density) + margin,
        right = width - insets.getRight(density, LayoutDirection.Ltr) - margin,
        bottom = height - insets.getBottom(density) - margin,
    )
}

/**
 * Places the surface [CursorDimens.menuGap] below the anchor, or above it when it does not fit below, start-aligned
 * with the anchor where that fits and end-aligned where only that does, else clamped into the [area]; when it fits
 * on neither side it goes to the roomier one. The popup is the surface plus [CursorDimens.menuShadowRoom] on every
 * side, so the offset returned is the surface's less that room. The insets behind [area] are state, read here, so
 * the menu is placed again when the keyboard moves. With [at] the anchor is that point rather than the layout's bounds.
 */
internal class MenuPositionProvider(
    private val density: Density,
    private val offset: DpOffset,
    private val area: (IntSize) -> IntRect,
    private val onPlaced: (MenuPlacement) -> Unit,
    private val at: IntOffset? = null,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val anchor = at?.let { IntRect(it, it) } ?: anchorBounds
        val room = with(density) { CursorDimens.menuShadowRoom.roundToPx() }
        val gap = with(density) { CursorDimens.menuGap.roundToPx() }
        val ltr = layoutDirection == LayoutDirection.Ltr
        val dx = with(density) { offset.x.roundToPx() }.let { if (ltr) it else -it }
        val dy = with(density) { offset.y.roundToPx() }
        val bounds = area(windowSize)
        val width = max(popupContentSize.width - 2 * room, 0)
        val height = max(popupContentSize.height - 2 * room, 0)

        val start = if (ltr) anchor.left + dx else anchor.right - width + dx
        val end = if (ltr) anchor.right - width + dx else anchor.left + dx
        val x = listOf(start, end).firstOrNull { it >= bounds.left && it + width <= bounds.right }
            ?: start.coerceIn(bounds.left, max(bounds.left, bounds.right - width))

        val below = anchor.bottom + gap + dy
        val above = anchor.top - gap - height + dy
        val placeBelow = when {
            below + height <= bounds.bottom -> true
            above >= bounds.top -> false
            else -> bounds.bottom - anchor.bottom >= anchor.top - bounds.top
        }
        val y = (if (placeBelow) below else above).coerceIn(bounds.top, max(bounds.top, bounds.bottom - height))

        val pivotX = when {
            x >= anchor.right -> 0f
            x + width <= anchor.left -> width.toFloat()
            else -> (max(anchor.left, x) + min(anchor.right, x + width)) / 2f - x
        }
        val pivotY = if (placeBelow) 0f else height.toFloat()
        val origin = if (popupContentSize.width > 0 && popupContentSize.height > 0) {
            TransformOrigin((room + pivotX) / popupContentSize.width, (room + pivotY) / popupContentSize.height)
        } else {
            MenuPlacement.Initial.origin
        }
        onPlaced(MenuPlacement(origin, placeBelow))
        return IntOffset(x - room, y - room)
    }
}

private const val ENTER_MILLIS = 180
private const val EXIT_MILLIS = 120
private const val ENTER_SCALE = 0.95f
private val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
