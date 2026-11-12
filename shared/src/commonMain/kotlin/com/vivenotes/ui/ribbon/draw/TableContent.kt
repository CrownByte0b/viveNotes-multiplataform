package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.*
import com.vivenotes.ui.components.BoundTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.TextLayoutResult
import com.vivenotes.model.TableCell
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.canvas.LocalWordSelectionActions
import com.vivenotes.ui.canvas.TableObjectMenuItems
import com.vivenotes.ui.canvas.WordSelectionActions
import com.vivenotes.ui.canvas.selectAdditionalWords
import com.vivenotes.ui.canvas.wordAt
import com.vivenotes.ui.components.ContextMenu
import com.vivenotes.ui.components.ContextMenuItem
import com.vivenotes.ui.components.onSecondaryPress
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.ContextSymbols
import com.vivenotes.localization.Strings
import com.vivenotes.ui.keyboard.LocalKeyBindings
import com.vivenotes.workspace.ObjectCommand
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.textSelectionsFor
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
 * Whether [table]'s cells open their own right-click menus — text commands, then the table's Cut,
 * Copy and Delete. A locked table's cells are not edited, and a table without typed cells has none;
 * a right-click on those opens the prime object menu instead.
 */
internal fun tableCellMenus(table: Outline.Table, actions: WordSelectionActions?): Boolean =
    actions != null && table.lockGroup == null && !table.inkOnly

/**
 * The grid: its cells, with the fill, header tint, [held] band and rules drawn once behind them
 * (Android `TableGrid`), so every rule is the same width wherever two cells meet. [onObjectCommand]
 * runs the table's own commands from a cell's right-click menu.
 */
@Composable
internal fun TableContent(table: Outline.Table, rows: List<Float>, canvasInk: Color, state: WorkspaceState,
    colors: RichTextColors, focusRequester: FocusRequester, onFocus: (String) -> Unit,
    onChange: (String, TextFieldValue) -> Unit, onShortcut: (KeyEvent) -> Boolean,
    held: TableAxis? = null, heldTint: Color = Color.Transparent,
    onObjectCommand: (ObjectCommand) -> Unit = {},
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
                        if (!table.inkOnly) TableCellText(table, cell, header, editing, canvasInk,
                            state, colors, focusRequester, onFocus, onChange, onShortcut, onObjectCommand)

                    }
                }
            }
        }
    }
}


@Composable
private fun TableCellText(
    table: Outline.Table, cell: TableCell, header: Boolean, editing: Boolean, canvasInk: Color,
    state: WorkspaceState, colors: RichTextColors, focusRequester: FocusRequester,
    onFocus: (String) -> Unit, onChange: (String, TextFieldValue) -> Unit, onShortcut: (KeyEvent) -> Boolean,
    onObjectCommand: (ObjectCommand) -> Unit,
) {
    val focused = table.lockGroup == null && state.focusedTextOutlineId == cell.id
    val buffer = RichTextBuffer(cell.blocks, if (focused) state.editorSelection else TextSelection(0),
        if (focused) state.typingMarks else emptySet())
    val cellStyle = TextStyle(color = canvasInk, fontSize = 14.sp,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
    var layout by remember(cell.id) { mutableStateOf<TextLayoutResult?>(null) }
    var keyboardFocus by remember(cell.id) { mutableStateOf(false) }
    var menu by remember(cell.id) { mutableStateOf<Offset?>(null) }
    val actions = LocalWordSelectionActions.current
    val density = LocalDensity.current
    val selectionTint = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = .35f)
    val padding = 8.dp
    // Over the whole cell, its padding too: the cells tile the table, so every right-click on a typed
    // table reaches one of their menus rather than some reaching the object's and some a cell's.
    val modifier = Modifier.fillMaxSize()
        .then(if (actions == null || !tableCellMenus(table, actions)) Modifier else Modifier.onSecondaryPress(cell.id) { point ->
            val inText = point - with(density) { Offset(padding.toPx(), padding.toPx()) }
            val clicked = layout?.getOffsetForPosition(inText)?.coerceIn(0, buffer.text.length) ?: 0
            val range = state.textSelectionsFor(cell.id).firstOrNull { clicked in it.min..it.max }
                ?: if (focused && clicked in state.editorSelection.min..state.editorSelection.max)
                    state.editorSelection else TextSelection(clicked)
            actions.activate(cell.id, range)
            menu = point
        })
        .padding(padding)
        .testTag(TableTags.cell(cell.id))
        .selectAdditionalWords(cell.id, rangeAt = { point ->
            wordAt(layout, buffer.text, layout?.getOffsetForPosition(point) ?: 0)
        })
        .drawBehind {
            layout?.let { result -> state.textSelectionsFor(cell.id).forEach { range ->
                if (!keyboardFocus || !focused || range != state.editorSelection) {
                    drawPath(result.getPathForRange(range.min.coerceIn(0, result.layoutInput.text.length),
                        range.max.coerceIn(0, result.layoutInput.text.length)), selectionTint)
                }
            } }
        }
    if (!editing) {
        Text(buffer.asAnnotatedString(colors), style = cellStyle, modifier = modifier, onTextLayout = { layout = it })
    } else {
        BoundTextField(TextFieldValue(buffer.asAnnotatedString(colors), TextRange(buffer.selection.start, buffer.selection.end)),
            { onChange(cell.id, it) }, readOnly = state.selectedPage?.editable != true,
            cursorBrush = SolidColor(canvasInk), textStyle = cellStyle, onTextLayout = { layout = it },
            modifier = modifier.then(if (focused) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged {
                    keyboardFocus = it.isFocused
                    if (it.isFocused && !focused) onFocus(cell.id)
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Tab && !event.isCtrlPressed && !event.isAltPressed) {
                        val next = if (event.isShiftPressed) table.cellBefore(cell.id) else table.cellAfter(cell.id)
                        if (next != null) { onFocus(next); true } else onShortcut(event)
                    } else onShortcut(event)
                })
    }
    if (actions != null) ContextMenu(menu, onDismiss = { menu = null }) {
        val bindings = LocalKeyBindings.current
        val selected = actions.state().selectedText.isNotEmpty()
        ContextMenuItem(Strings.cut, DocumentSymbols.ContentCut, shortcut = bindings.primary(ShortcutAction.Cut)?.label,
            enabled = selected, onClick = { menu = null; actions.clipboard.cut(actions.state(), null) })
        ContextMenuItem(Strings.copy, DocumentSymbols.ContentCopy, shortcut = bindings.primary(ShortcutAction.Copy)?.label,
            enabled = selected, onClick = { menu = null; actions.clipboard.copy(actions.state(), null) })
        ContextMenuItem(Strings.paste, DocumentSymbols.ContentPaste, shortcut = bindings.primary(ShortcutAction.Paste)?.label,
            enabled = actions.clipboard.canPaste(), onClick = { menu = null; actions.clipboard.paste(null, true) })
        ContextMenuItem(Strings.paste_as_plain_text, ContextSymbols.PasteAsText,
            shortcut = bindings.primary(ShortcutAction.PastePlainText)?.label,
            enabled = actions.clipboard.canPaste(), onClick = { menu = null; actions.clipboard.paste(null, false) })
        ContextMenuItem(Strings.select_all, ContextSymbols.SelectAll, shortcut = bindings.primary(ShortcutAction.SelectAll)?.label,
            onClick = { menu = null; actions.edit { it.selectAllTextBox(cell.id) } })
        ContextMenuItem(Strings.clear_formatting, DocumentSymbols.FormatClear, enabled = selected,
            onClick = { menu = null; actions.edit { it.clearSelectedFormatting() } })
        TableObjectMenuItems(editable = state.selectedPage?.editable == true) { command ->
            menu = null
            onObjectCommand(command)
        }
    }
}
