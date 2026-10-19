package com.vivenotes.ui.canvas

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextLayoutResult
import com.vivenotes.richtext.TextSelection

/**
 * Up, Down and End in a text box, moved by [caretOnLine] and [visibleLineEnd]. The text field's
 * own read a line's end from the layout, which counts the zero-width separator ending a block as
 * part of its line (see [BlockSeparators]): End went to the start of the next line, Down onto a
 * shorter line went a line too far, and an empty line could be neither reached nor left upwards.
 * As in the field, Up and Down pressed in a row keep aiming at the column they started from.
 */
internal class TextCaretKeys {
    private var column: Float? = null
    private var columnCaret = -1

    /**
     * Where [event] moves [selection] in [text] laid out as [layout] — the moving end only, with
     * Shift — or null for a key left to the text field.
     */
    fun move(event: KeyEvent, layout: TextLayoutResult, text: String, selection: TextSelection): TextSelection? {
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return null
        // A layout of other text is a frame behind; the field's own handling is right for it.
        if (layout.layoutInput.text.length !in text.length..text.length + 1) return null
        val caret = selection.end.coerceIn(0, text.length)
        val target = when (event.key) {
            Key.DirectionUp, Key.DirectionDown -> {
                val x = column?.takeIf { columnCaret == caret } ?: layout.getCursorRect(caret).left
                caretOnLine(layout, text, caret, x, if (event.key == Key.DirectionUp) -1 else 1).also {
                    column = x
                    columnCaret = it
                }
            }
            Key.MoveEnd -> visibleLineEnd(layout, text, layout.getLineForOffset(caret))
            else -> return null
        }
        return if (event.isShiftPressed) TextSelection(selection.start, target) else TextSelection(target)
    }
}
