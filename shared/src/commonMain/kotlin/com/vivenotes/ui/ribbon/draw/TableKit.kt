package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.workspace.*
import kotlin.math.roundToInt

internal object TableTags {
    const val Tool = "draw-table-tool"
    const val Kit = "draw-table-kit"
    const val Preview = "draw-table-preview"
    const val Fill = "draw-table-fill"
    const val Columns = "draw-table-columns"
    const val Rows = "draw-table-rows"
    fun cell(id: String) = "draw-table-cell-$id"
}

@Composable
internal fun TableKit(settings: InkSettings, canvasInk: Color = Color.Black, onChange: (InkSettings) -> Unit) {
    val table = settings.table
    val color = if (table.colorFollowsTheme) canvasInk else Color(table.borderArgb)
    fun update(next: TableToolSettings) = onChange(settings.copy(table = next.normalized()))
    ReferenceToolKit("Table") {
        TablePreview(table, color)
        Spacer(Modifier.height(10.dp))
        ToolkitToggle("Write in with a pen", table.inkOnly, "draw-table-ink-only", "Off, each cell is a text field you type into.") {
            update(table.copy(inkOnly = it))
        }
        ToolkitToggle("Header column", table.headerColumn, "draw-table-header-column") { update(table.copy(headerColumn = it)) }
        ToolkitToggle("Header row", table.headerRow, "draw-table-header-row") { update(table.copy(headerRow = it)) }
        ToolkitNumberPicker("Column count", table.columns, 1..Outline.Table.MAX_COLUMNS, TableTags.Columns) { update(table.copy(columns = it)) }
        ToolkitNumberPicker("Row count", table.rows, 1..minOf(Outline.Table.MAX_ROWS, Outline.Table.MAX_CELLS / table.columns), TableTags.Rows) {
            update(table.copy(rows = it))
        }
        Spacer(Modifier.height(4.dp))
        ToolkitSlider("Border width", table.borderWidth.toFloat(), 1f..8f, 1f, "draw-table-border-width", color) {
            update(table.copy(borderWidth = it.roundToInt()))
        }
        Spacer(Modifier.height(2.dp))
        Text("Border color", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 2.dp))
        ToolkitPalette(settings.palette, color.toArgb(), InkControlsTags::color, "draw-table-border-wheel", "draw-table-border-custom",
            onPick = { update(table.copy(borderArgb = it, colorFollowsTheme = false)) },
            onPromote = { onChange(settings.copy(palette = settings.palette.withColorInFront(it))) },
            onAutomatic = { update(table.copy(colorFollowsTheme = true)) })
        Spacer(Modifier.height(6.dp))
        ToolkitFill(table.fillArgb, color.toArgb(), TableTags.Fill, "draw-table-fill-none", "draw-table-fill-custom",
            onChange = { update(table.copy(fillArgb = it)) },
            onPromote = { onChange(settings.copy(palette = settings.palette.withColorInFront(it))) })
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun TablePreview(table: TableToolSettings, color: Color) {
    val header = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)
    Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest).testTag(TableTags.Preview)) {
        Canvas(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 36.dp).align(androidx.compose.ui.Alignment.Center)) {
            val columns = table.columns.coerceIn(1, 6)
            val rows = table.rows.coerceIn(1, 6)
            val width = size.width / columns
            val height = size.height / rows
            table.fillArgb?.let { drawRect(Color(it)) }
            if (table.headerRow) drawRect(header, Offset.Zero, Size(size.width, height))
            if (table.headerColumn) drawRect(header, Offset.Zero, Size(width, size.height))
            val stroke = (table.borderWidth.dp.toPx() * .7f).coerceAtLeast(1f)
            for (c in 0..columns) {
                val x = (c * width).coerceAtMost(size.width - stroke / 2)
                drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            }
            for (r in 0..rows) {
                val y = (r * height).coerceAtMost(size.height - stroke / 2)
                drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
            }
        }
    }
}
