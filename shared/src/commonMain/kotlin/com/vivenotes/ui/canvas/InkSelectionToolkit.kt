package com.vivenotes.ui.canvas

import com.vivenotes.localization.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.ui.shell.WorkspaceTestTags
import kotlin.math.roundToInt

internal fun selectionToolkitPosition(bounds: Rect, viewport: IntSize, toolkit: IntSize, gap: Float): IntOffset {
    val maxX = (viewport.width - toolkit.width - gap).coerceAtLeast(0f)
    val maxY = (viewport.height - toolkit.height - gap).coerceAtLeast(0f)
    val above = bounds.top - toolkit.height - gap
    val below = bounds.bottom + gap
    val y = if (above >= gap) above else if (below <= maxY) below else gap
    return IntOffset(bounds.left.coerceIn(gap.coerceAtMost(maxX), maxX).roundToInt(),
        y.coerceIn(gap.coerceAtMost(maxY), maxY).roundToInt())
}

@Composable
internal fun InkSelectionToolkit(selection: InkSelection, boundsInView: Rect, viewport: IntSize,
    canvasInk: Color, enabled: Boolean, showColor: Boolean,
    onBoundsChange: (Rect) -> Unit, onCopy: () -> Unit, onDelete: () -> Unit, onColor: (Int, Boolean) -> Unit,
    showGroup: Boolean, showMath: Boolean, onGroup: () -> Unit, onMath: () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(LocalDensity.current) { 6.dp.toPx() }
    val position = selectionToolkitPosition(boundsInView, viewport, size, gap)
    SideEffect { onBoundsChange(Rect(Offset(position.x.toFloat(), position.y.toFloat()),
        androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))) }
    val foreground = Color(0xffe8eaed)
    val buttonColors = IconButtonDefaults.iconButtonColors(contentColor = foreground,
        disabledContentColor = foreground.copy(alpha = .38f))
    Surface(color = Color(0xff202124), contentColor = foreground, shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp,
        modifier = Modifier.offset { position }.onSizeChanged { size = it }.testTag(WorkspaceTestTags.ObjectToolkit)) {
        Row(Modifier.padding(horizontal = 5.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (showColor) {
                val swatch = if (selection.colorFollowsTheme) canvasInk else selection.colorArgb?.let(::Color) ?: canvasInk
                SelectionColorButton(swatch, canvasInk, onColor, enabled, buttonColors)
            }
            if (showColor) Spacer(Modifier.padding(horizontal = 4.dp).width(1.dp).height(22.dp)
                .background(Color(0xff5f6368)).testTag(WorkspaceTestTags.InkColorDivider))
            TooltipIconButton(Strings.copy_selection, onClick = onCopy, enabled = enabled,
                tooltipPosition = TooltipAnchorPosition.Above, colors = buttonColors,
                modifier = Modifier.size(32.dp).testTag(WorkspaceTestTags.ObjectCopy)) {
                Icon(DocumentSymbols.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            if (showMath) HoverTooltip(Strings.math, position = TooltipAnchorPosition.Above) {
                TextButton(onClick = onMath, enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = foreground,
                        disabledContentColor = foreground.copy(alpha = .38f)),
                    contentPadding = PaddingValues(horizontal = 7.dp),
                    modifier = Modifier.height(32.dp).testTag(WorkspaceTestTags.InkMath)) {
                    Icon(ObjectSymbols.Functions, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(Strings.math, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (showGroup) {
                val label = if (selection.isGrouped) Strings.ungroup else Strings.group
                HoverTooltip(label, position = TooltipAnchorPosition.Above) {
                    TextButton(onClick = onGroup, enabled = enabled,
                        colors = ButtonDefaults.textButtonColors(contentColor = foreground,
                            disabledContentColor = foreground.copy(alpha = .38f)),
                        contentPadding = PaddingValues(horizontal = 7.dp),
                        modifier = Modifier.height(32.dp).testTag(WorkspaceTestTags.InkGroup)) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            TooltipIconButton(Strings.delete_selection, onClick = onDelete, enabled = enabled,
                tooltipPosition = TooltipAnchorPosition.Above, colors = buttonColors,
                modifier = Modifier.size(32.dp).testTag(WorkspaceTestTags.ObjectDelete)) {
                Icon(ObjectSymbols.Delete, contentDescription = null, tint = Color(0xffffa8a8),
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}
