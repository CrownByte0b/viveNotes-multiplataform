package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.model.Outline
import com.vivenotes.data.automaticColorOr
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.ui.canvas.RichTextColors
import com.vivenotes.ui.canvas.asAnnotatedString
import com.vivenotes.workspace.WorkspaceState

@Composable
internal fun measuredTableRows(table: Outline.Table, colors: RichTextColors): List<Float> {
    if (table.inkOnly) return table.rows.map { it.minHeight }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val styled = table.rows.map { row -> row.cells.map { RichTextBuffer(it.blocks).asAnnotatedString(colors) } }
    return remember(table, density, colors, styled) {
        table.rows.mapIndexed { r, row ->
            maxOf(row.minHeight, row.cells.mapIndexed { c, cell ->
                val header = (r == 0 && table.headerRow) || (c == 0 && table.headerColumn)
                val measured = measurer.measure(styled[r][c], TextStyle(fontSize = 14.sp,
                    fontWeight = if (header) FontWeight.Bold else FontWeight.Normal),
                    constraints = Constraints(maxWidth = with(density) { (table.columns[c] - 16f).dp.roundToPx().coerceAtLeast(1) }))
                measured.size.height / density.density + 16f
            }.maxOrNull() ?: row.minHeight)
        }
    }
}

@Composable
internal fun TableContent(table: Outline.Table, rows: List<Float>, canvasInk: Color, state: WorkspaceState,
    colors: RichTextColors, focusRequester: FocusRequester, onFocus: (String) -> Unit,
    onChange: (String, TextFieldValue) -> Unit, onShortcut: (KeyEvent) -> Boolean,
) {
    val border = Color(automaticColorOr(table.borderArgb, table.borderFollowsTheme, canvasInk.toArgb()))
    val editing = table.lockGroup == null && (
        state.selectedObjectIds == setOf(table.id) && state.selectedTextOutlineIds.isEmpty() && state.selectedInk == null ||
            table.locate(state.focusedTextOutlineId.orEmpty()) != null)
    Column(Modifier.fillMaxSize().then(table.fillArgb?.let { Modifier.background(Color(it)) } ?: Modifier)) {
        table.rows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().height(rows[r].dp)) {
                row.cells.forEachIndexed { c, cell ->
                    val header = (r == 0 && table.headerRow) || (c == 0 && table.headerColumn)
                    Box(Modifier.width(table.columns.getOrElse(c) { 160f }.dp).fillMaxHeight()
                        .background(if (header) border.copy(alpha = .10f) else Color.Transparent)
                        .border(table.borderWidth.dp, border)) {
                        if (!table.inkOnly) {
                            val focused = state.focusedTextOutlineId == cell.id
                            val buffer = state.richTextFor(cell.id) ?: RichTextBuffer(cell.blocks)
                            val cellStyle = TextStyle(color = canvasInk, fontSize = 14.sp,
                                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
                            if (!editing) {
                                Text(buffer.asAnnotatedString(colors), style = cellStyle,
                                    modifier = Modifier.fillMaxSize().padding(8.dp).testTag(TableTags.cell(cell.id)))
                            } else {
                                BasicTextField(TextFieldValue(buffer.asAnnotatedString(colors), TextRange(buffer.selection.start, buffer.selection.end)),
                                    { onChange(cell.id, it) }, readOnly = state.selectedPage?.editable != true,
                                    cursorBrush = SolidColor(canvasInk),
                                    textStyle = cellStyle,
                                    modifier = Modifier.fillMaxSize().padding(8.dp).testTag(TableTags.cell(cell.id))
                                        .then(if (focused) Modifier.focusRequester(focusRequester) else Modifier)
                                        .onFocusChanged { if (it.isFocused && !focused) onFocus(cell.id) }
                                        .onPreviewKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && event.key == Key.Tab && !event.isCtrlPressed && !event.isAltPressed) {
                                                val next = if (event.isShiftPressed) table.cellBefore(cell.id) else table.cellAfter(cell.id)
                                                if (next != null) { onFocus(next); true } else onShortcut(event)
                                            } else onShortcut(event)
                                        })
                            }
                        }
                    }
                }
            }
        }
    }
}
