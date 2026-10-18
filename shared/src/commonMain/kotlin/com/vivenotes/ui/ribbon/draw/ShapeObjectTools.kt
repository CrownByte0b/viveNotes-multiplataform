package com.vivenotes.ui.ribbon.draw

import com.vivenotes.localization.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.canFill
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.canvas.SelectionColorPicker
import com.vivenotes.workspace.ShapeToolSettings

internal object ShapeObjectTags {
    const val LineType = "object-shape-line-type"
    const val Width = "object-shape-width"
    const val Fill = "object-shape-fill"
}

/** Shape-specific extension of the Prime Object toolkit. */
@Composable
internal fun ShapeObjectTools(shape: Outline.Shape, onLineType: (LineType) -> Unit,
                              onWidth: (Int) -> Unit, onFill: (Int?) -> Unit) {
    var lineMenu by remember(shape.id) { mutableStateOf(false) }
    var widthMenu by remember(shape.id) { mutableStateOf(false) }
    Box {
        TextButton(onClick = { lineMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.height(32.dp).testTag(ShapeObjectTags.LineType)) {
            Text(shape.lineType.label)
        }
        ScaledDropdownMenu(lineMenu, { lineMenu = false }) {
            LineType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.label) }, onClick = { lineMenu = false; onLineType(type) })
            }
        }
    }
    Box {
        TextButton(onClick = { widthMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.height(32.dp).testTag(ShapeObjectTags.Width)) {
            Text(Strings.value_pt(shape.borderWidth.toInt()))
        }
        ScaledDropdownMenu(widthMenu, { widthMenu = false }) {
            (ShapeToolSettings.MIN_BORDER_WIDTH..ShapeToolSettings.MAX_BORDER_WIDTH).forEach { width ->
                DropdownMenuItem(text = { Text(Strings.value_pt(width)) }, onClick = { widthMenu = false; onWidth(width) })
            }
        }
    }
    if (shape.canFill) {
        SelectionColorPicker(onColor = onFill, onNoFill = { onFill(null) }, selectedColor = shape.fillArgb) { open ->
            TextButton(onClick = open, contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(32.dp).testTag(ShapeObjectTags.Fill)) {
                Text(Strings.fill)
                Box(Modifier.padding(start = 4.dp).size(14.dp).background(shape.fillArgb?.let(::Color)
                    ?: Color.Transparent))
            }
        }
    }
}
