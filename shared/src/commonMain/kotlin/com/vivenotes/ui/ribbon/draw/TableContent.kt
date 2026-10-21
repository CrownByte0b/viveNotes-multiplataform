package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.canvas.RichTextColors
import com.vivenotes.ui.canvas.asAnnotatedString
import com.vivenotes.workspace.WorkspaceState

@Composable
internal fun measuredTableRows(table: Outline.Table, colors: RichTextColors): List<Float> {
    if (table.inkOnly) return table.rows.map { it.minHeight }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val styled = table.rows.map { row -> row.cells.map { RichTextBuffer(it.blocks).asAnnotatedString(colors) } }
    val minimumHeights = table.rows.map { it.minHeight }
    return remember(table.columns, minimumHeights, table.headerRow, table.headerColumn, density, styled) {
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

/**
 * The grid: its cells, with the fill, header tint, [held] band and rules drawn once behind them
 * (Android `TableGrid`), so every rule is the same width wherever two cells meet.
 */
@Composable
internal fun TableContent(table: Outline.Table, rows: List<Float>, canvasInk: Color, state: WorkspaceState,
    colors: RichTextColors, focusRequester: FocusRequester, onFocus: (String) -> Unit,
    onChange: (String, TextFieldValue) -> Unit, onShortcut: (KeyEvent) -> Boolean,
    held: TableAxis? = null, heldTint: Color = Color.Transparent,
) {
    val border = Color(automaticColorOr(table.borderArgb, table.borderFollowsTheme, canvasInk.toArgb()))
    val editing = table.lockGroup == null && (
        state.selectedObjectIds == setOf(table.id) && state.selectedTextOutlineIds.isEmpty() && state.selectedInk == null ||
            table.locate(state.focusedTextOutlineId.orEmpty()) != null)
    Column(Modifier.fillMaxSize().drawBehind {
        table.fillArgb?.let { drawRect(Color(it)) }
        val tops = rows.runningFold(0f) { top, height -> top + height.dp.toPx() }
        val lefts = table.columns.runningFold(0f) { left, width -> left + width.dp.toPx() }
        val headerTint = border.copy(alpha = .10f)
        if (table.headerRow && rows.isNotEmpty()) drawRect(headerTint, Offset.Zero, Size(size.width, tops[1]))
        if (table.headerColumn && table.columns.isNotEmpty()) drawRect(headerTint, Offset.Zero, Size(lefts[1], size.height))
        // Over the header tint and under the rules, so the band never greys out the text it marks.
        when (held) {
            is TableAxis.Row -> if (held.index in rows.indices)
                drawRect(heldTint, Offset(0f, tops[held.index]), Size(size.width, tops[held.index + 1] - tops[held.index]))
            is TableAxis.Column -> if (held.index in table.columns.indices)
                drawRect(heldTint, Offset(lefts[held.index], 0f), Size(lefts[held.index + 1] - lefts[held.index], size.height))
            null -> Unit
        }
        val stroke = table.borderWidth.dp.toPx().coerceAtLeast(1f)
        // Half a stroke in at each edge, so the outer rules are not clipped in half by the box.
        lefts.forEach { x ->
            val at = x.coerceIn(stroke / 2f, (size.width - stroke / 2f).coerceAtLeast(stroke / 2f))
            drawLine(border, Offset(at, 0f), Offset(at, size.height), stroke)
        }
        tops.forEach { y ->
            val at = y.coerceIn(stroke / 2f, (size.height - stroke / 2f).coerceAtLeast(stroke / 2f))
            drawLine(border, Offset(0f, at), Offset(size.width, at), stroke)
        }
    }) {
        table.rows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().height(rows[r].dp)) {
                row.cells.forEachIndexed { c, cell ->
                    val header = (r == 0 && table.headerRow) || (c == 0 && table.headerColumn)
                    Box(Modifier.width(table.columns.getOrElse(c) { 160f }.dp).fillMaxHeight()) {
                        if (!table.inkOnly) {
                            val focused = table.lockGroup == null && state.focusedTextOutlineId == cell.id
                            val buffer = RichTextBuffer(cell.blocks,
                                if (focused) state.editorSelection else TextSelection(0),
                                if (focused) state.typingMarks else emptySet())
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
