package com.vivenotes.ui.keyboard

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * A key a Compose text field takes for its own undo or redo: Ctrl+Z, Ctrl+Shift+Z and Ctrl+Y, and
 * Alt+Backspace on Windows. That history holds characters only, so a text box uses up whichever of
 * these no shortcut took, rather than let it put text back without its formatting.
 */
internal fun KeyEvent.isTextFieldUndo(): Boolean {
    if (type != KeyEventType.KeyDown || isMetaPressed) return false
    return when (key) {
        Key.Z -> isCtrlPressed && !isAltPressed
        Key.Y -> isCtrlPressed && !isAltPressed && !isShiftPressed
        Key.Backspace -> AltBackspaceUndoesText && isAltPressed && !isCtrlPressed && !isShiftPressed
        else -> false
    }
}

/** Whether this platform's text fields take Alt+Backspace for undo, as Compose's do on Windows. */
internal expect val AltBackspaceUndoesText: Boolean
