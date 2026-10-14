package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.vivenotes.model.Outline
import com.vivenotes.workspace.*

internal object TableTags {
    const val Tool = "draw-table-tool"
    const val Kit = "draw-table-kit"
    fun cell(id: String) = "draw-table-cell-$id"
}

@Composable
internal fun TableKit(settings: InkSettings, onChange: (InkSettings) -> Unit) {
    val table = settings.table
    fun update(next: TableToolSettings) = onChange(settings.copy(table = next.normalized()))
    ToolKit("Table") {
        KitToggle("Write in with a pen", table.inkOnly, "draw-table-ink-only") { update(table.copy(inkOnly = it)) }
        KitToggle("Header column", table.headerColumn, "draw-table-header-column") { update(table.copy(headerColumn = it)) }
        KitToggle("Header row", table.headerRow, "draw-table-header-row") { update(table.copy(headerRow = it)) }
        TableCount("Columns", table.columns, 1..Outline.Table.MAX_COLUMNS) { update(table.copy(columns = it)) }
        TableCount("Rows", table.rows, 1..minOf(Outline.Table.MAX_ROWS, Outline.Table.MAX_CELLS / table.columns)) { update(table.copy(rows = it)) }
        KitSlider("Border width", table.borderWidth.toFloat(), 1f..8f, 1f, "draw-table-border-width") { update(table.copy(borderWidth = it.toInt())) }
        Text("Border color", style = MaterialTheme.typography.labelLarge)
        ColorSwatches(settings.palette, table.borderArgb) { update(table.copy(borderArgb = it, colorFollowsTheme = false)) }
        TextButton({ update(table.copy(colorFollowsTheme = true)) }, Modifier.testTag("draw-table-border-auto")) { Text("Automatic") }
        CustomShapeColorField("Border", "draw-table-border-custom") { update(table.copy(borderArgb = it, colorFollowsTheme = false)) }
        Text("Fill", style = MaterialTheme.typography.labelLarge)
        TextButton({ update(table.copy(fillArgb = null)) }, Modifier.testTag("draw-table-fill-none")) { Text("None") }
        CustomShapeColorField("Fill", "draw-table-fill-custom") { update(table.copy(fillArgb = it)) }
    }
}

@Composable
private fun TableCount(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(label)
        Row {
            TextButton({ onChange(value - 1) }, enabled = value > range.first, modifier = Modifier.testTag("draw-table-${label.lowercase()}-minus")) { Text("−") }
            Text("$value", Modifier.testTag("draw-table-${label.lowercase()}"))
            TextButton({ onChange(value + 1) }, enabled = value < range.last, modifier = Modifier.testTag("draw-table-${label.lowercase()}-plus")) { Text("+") }
        }
    }
}
