package com.vivenotes.ui.keyboard

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.KeyChord
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.ShortcutKey
import com.vivenotes.workspace.ShortcutScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dispatcher below the workspace's key handlers, fed events one at a time. The Compose UI tests
 * cannot cover the typed half of a key press: their key input sends presses and releases only.
 */
@OptIn(InternalComposeUiApi::class)
class ShortcutKeysTest {
    private val keys = ShortcutKeys()
    private val ran = mutableListOf<ShortcutAction>()

    /** One event through the workspace's order: the outermost preview first, then [scope]. */
    private fun send(event: KeyEvent, scope: ShortcutScope = ShortcutScope.Workspace): Boolean =
        keys.observe(event) || keys.dispatch(event, KeyBindings.Default, scope) { ran += it; true }

    // Compose's own factory is the only way to build the typed half outside a window; it is marked
    // unstable, which is acceptable in a test that pins this Compose version's key events.
    private fun down(key: Key, ctrl: Boolean = false) = KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl)
    private fun up(key: Key, ctrl: Boolean = false) = KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl)
    private fun typed(char: Char, ctrl: Boolean = false) =
        KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, isCtrlPressed = ctrl)

    /** Regression guard: Ctrl+= in a text box zoomed and then typed "=" into it. */
    @Test
    fun theCharacterOfAUsedChordIsNotTyped() {
        assertTrue(send(down(Key.Equals, ctrl = true)))
        assertEquals(listOf(ShortcutAction.ZoomIn), ran)
        assertTrue(send(typed('=', ctrl = true)), "the typed \"=\" reached the text field")
        assertFalse(send(up(Key.Equals, ctrl = true)))

        // Only that one character: ordinary typing afterwards is untouched.
        assertFalse(send(down(Key.A)))
        assertFalse(send(typed('a')))
    }

    @Test
    fun theCharacterOfAKeyNoShortcutUsedIsTyped() {
        assertFalse(send(down(Key.Equals)))
        assertFalse(send(typed('=')))
        assertTrue(ran.isEmpty())
    }

    @Test
    fun aHeldKeyRepeatsOnlyARepeatableCommand() {
        assertTrue(send(down(Key.N, ctrl = true)))
        assertTrue(send(down(Key.N, ctrl = true)), "a repeat of Ctrl+N went on to the focused control")
        assertEquals(listOf(ShortcutAction.NewPage), ran)
        send(up(Key.N, ctrl = true))
        assertTrue(send(down(Key.N, ctrl = true)))
        assertEquals(listOf(ShortcutAction.NewPage, ShortcutAction.NewPage), ran)

        ran.clear()
        send(down(Key.Minus, ctrl = true))
        send(down(Key.Minus, ctrl = true))
        assertEquals(listOf(ShortcutAction.ZoomOut, ShortcutAction.ZoomOut), ran)
    }

    @Test
    fun aCommandThatDoesNothingLeavesTheKey() {
        val event = down(Key.Z, ctrl = true)
        keys.observe(event)
        assertFalse(keys.dispatch(event, KeyBindings.Default, ShortcutScope.Workspace) { false })
        assertFalse(keys.observe(typed('z', ctrl = true)))
    }

    @Test
    fun eventsBecomeChords() {
        assertEquals(KeyChord(ShortcutKey.NumPadAdd, ctrl = true), down(Key.NumPadAdd, ctrl = true).toChord())
        assertEquals(KeyChord(ShortcutKey.Escape), down(Key.Escape).toChord())
        assertNull(down(Key.Unknown).toChord())
        assertTrue(Key.CtrlLeft.isModifier() && Key.ShiftRight.isModifier() && Key.MetaLeft.isModifier())
        assertFalse(Key.A.isModifier())
    }
}
