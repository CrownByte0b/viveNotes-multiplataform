package com.vivenotes.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider

/** Centers the tooltip on its button using the popup's actual window bounds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberHoverTooltipPositionProvider(position: TooltipAnchorPosition): PopupPositionProvider {
    // Match Material 3's default anchor gap while using the popup's actual windowSize argument.
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    return remember(position, gap) { HoverTooltipPositionProvider(position, gap) }
}

@OptIn(ExperimentalMaterial3Api::class)
internal class HoverTooltipPositionProvider(
    private val preferred: TooltipAnchorPosition,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val centeredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val centeredY = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val above = anchorBounds.top - gap - popupContentSize.height
        val below = anchorBounds.bottom + gap
        val left = anchorBounds.left - gap - popupContentSize.width
        val right = anchorBounds.right + gap

        val side = when (preferred) {
            TooltipAnchorPosition.Left -> -1
            TooltipAnchorPosition.Right -> 1
            TooltipAnchorPosition.Start -> if (layoutDirection == LayoutDirection.Ltr) -1 else 1
            TooltipAnchorPosition.End -> if (layoutDirection == LayoutDirection.Ltr) 1 else -1
            else -> 0
        }
        if (side != 0) {
            val primaryX = if (side < 0) left else right
            val alternateX = if (side < 0) right else left
            val x = if (primaryX in 0..maxX) primaryX else alternateX
            return IntOffset(x.coerceIn(0, maxX), centeredY.coerceIn(0, maxY))
        }

        val preferAbove = preferred == TooltipAnchorPosition.Above
        val primaryY = if (preferAbove) above else below
        val alternateY = if (preferAbove) below else above
        val y = if (primaryY in 0..maxY) primaryY else alternateY
        return IntOffset(centeredX.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }
}
