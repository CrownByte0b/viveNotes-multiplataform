package com.vivenotes.ui.keyboard

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The keys a text box keeps from the Compose text field's own, formatting-blind undo. */
@OptIn(InternalComposeUiApi::class)
class TextFieldUndoKeysTest {
    private fun down(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false) =
        KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl, isShiftPressed = shift, isAltPressed = alt,
            isMetaPressed = meta)

    @Test
    fun theTextFieldsUndoAndRedoKeysAreKept() {
        assertTrue(down(Key.Z, ctrl = true).isTextFieldUndo())
        assertTrue(down(Key.Z, ctrl = true, shift = true).isTextFieldUndo())
        assertTrue(down(Key.Y, ctrl = true).isTextFieldUndo())
    }

    @Test
    fun otherKeysAreLeftToTheField() {
        assertFalse(down(Key.Z).isTextFieldUndo(), "a plain z is typed")
        assertFalse(down(Key.Y, ctrl = true, shift = true).isTextFieldUndo())
        assertFalse(down(Key.Z, ctrl = true, alt = true).isTextFieldUndo())
        assertFalse(down(Key.Z, ctrl = true, meta = true).isTextFieldUndo())
        assertFalse(down(Key.Backspace).isTextFieldUndo())
        assertFalse(down(Key.Backspace, ctrl = true).isTextFieldUndo(), "Ctrl+Backspace deletes a word")
        assertFalse(KeyEvent(Key.Z, KeyEventType.KeyUp, isCtrlPressed = true).isTextFieldUndo())
    }

    /** Compose's text fields undo on Alt+Backspace only on Windows; elsewhere it deletes a word. */
    @Test
    fun altBackspaceIsKeptOnlyWhereItUndoes() {
        val windows = System.getProperty("os.name").startsWith("Windows")
        assertEquals(windows, AltBackspaceUndoesText)
        assertEquals(windows, down(Key.Backspace, alt = true).isTextFieldUndo())
        assertFalse(down(Key.Backspace, alt = true, shift = true).isTextFieldUndo())
    }
}
