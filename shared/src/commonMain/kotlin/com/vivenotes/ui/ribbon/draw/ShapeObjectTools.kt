package com.vivenotes.ui.ribbon.draw

import com.vivenotes.localization.Strings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.vivenotes.ui.canvas.CurveVertexMode
import com.vivenotes.model.ink.ShapeKind
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.canFill
import com.vivenotes.ui.canvas.SelectionColorPicker
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.workspace.ShapeToolSettings

internal const val OBJECT_ADD_VERTEX_TAG = "object-add-vertex"
internal const val OBJECT_REMOVE_VERTEX_TAG = "object-remove-vertex"

internal object ShapeObjectTags {
    const val LineType = "object-shape-line-type"
    const val Width = "object-shape-width"
    const val Fill = "object-shape-fill"
    fun lineType(type: LineType) = "$LineType-${type.name}"
}

/**
 * Shape-specific extension of the Prime Object toolkit: the line style, the border width slider and,
 * for closed shapes, the fill, whose bucket sits on a bar of the current fill.
 */
@Composable
internal fun ShapeObjectTools(shape: Outline.Shape, onLineType: (LineType) -> Unit,
                              onWidth: (Int) -> Unit, onFill: (Int?) -> Unit,
                              vertexMode: CurveVertexMode? = null,
                              onVertexMode: ((CurveVertexMode) -> Unit)? = null) {
    if (shape.kind == ShapeKind.Curve && shape.lockGroup == null && onVertexMode != null) {
        CurveVertexAction(Strings.shape_add_vertex, OBJECT_ADD_VERTEX_TAG, ObjectSymbols.Keep,
            vertexMode == CurveVertexMode.Add) { onVertexMode(CurveVertexMode.Add) }
        if (shape.segments.size > 1) CurveVertexAction(Strings.shape_remove_vertex, OBJECT_REMOVE_VERTEX_TAG,
            ObjectSymbols.KeepOff, vertexMode == CurveVertexMode.Remove) { onVertexMode(CurveVertexMode.Remove) }
    }
    var lineMenu by remember(shape.id) { mutableStateOf(false) }
    Box {
        TooltipIconButton(Strings.change_line_style, onClick = { lineMenu = true }, tooltipPosition = TooltipAnchorPosition.Above,
            modifier = Modifier.size(32.dp).testTag(ShapeObjectTags.LineType)) {
            Icon(ObjectSymbols.LineStyle, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        ScaledDropdownMenu(lineMenu, { lineMenu = false }) {
            Box(Modifier.width(220.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
                ToolkitLineTypes(shape.lineType, ShapeObjectTags::lineType) { lineMenu = false; onLineType(it) }
            }
        }
    }
    BorderWidthAction(shape.borderWidth.toInt(), ShapeToolSettings.MIN_BORDER_WIDTH..ShapeToolSettings.MAX_BORDER_WIDTH,
        ShapeObjectTags.Width, LocalContentColor.current, onWidth)
    if (shape.canFill) {
        SelectionColorPicker(onColor = onFill, onNoFill = { onFill(null) }, selectedColor = shape.fillArgb) { open ->
            TooltipIconButton(Strings.change_fill_color, onClick = open, tooltipPosition = TooltipAnchorPosition.Above,
                modifier = Modifier.size(32.dp).testTag(ShapeObjectTags.Fill)) {
                Box {
                    Icon(ObjectSymbols.FormatColorFillBucket, contentDescription = null, modifier = Modifier.size(18.dp))
                    Icon(ObjectSymbols.FormatColorFillBar, contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = shape.fillArgb?.let(::Color) ?: LocalContentColor.current.copy(alpha = .38f))
                }
            }
        }
    }
}

@Composable
private fun CurveVertexAction(label: String, tag: String, glyph: androidx.compose.ui.graphics.vector.ImageVector,
                              active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) Color(0xFFE8EAED).copy(alpha = .14f) else Color.Transparent),
        modifier = Modifier.size(48.dp).testTag(tag).semantics { selected = active }) {
        Icon(glyph, contentDescription = label, tint = Color(0xFFE8EAED), modifier = Modifier.size(20.dp))
    }
}
