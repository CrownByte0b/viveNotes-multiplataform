package com.vivenotes.ui.keyboard

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.KeyChord
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.ShortcutDecision
import com.vivenotes.workspace.ShortcutKey
import com.vivenotes.workspace.ShortcutScope

/** The shortcuts in force, for anything that names one — a menu row's "Ctrl+C". */
internal val LocalKeyBindings = compositionLocalOf { KeyBindings.Default }

/** The chord [event]'s key makes with the modifiers held; null for a key no shortcut can use. */
internal fun KeyEvent.toChord(): KeyChord? {
    val shortcutKey = ComposeKeys[key] ?: return null
    return KeyChord(shortcutKey, ctrl = isCtrlPressed, shift = isShiftPressed, alt = isAltPressed, meta = isMetaPressed)
}

/** Ctrl, Shift, Alt and Super on their own: pressed on the way to a chord, never one themselves. */
internal fun Key.isModifier(): Boolean = this in ModifierKeys

/**
 * Key handling shared by every place the workspace dispatches a shortcut from. [observe] must see
 * each key event first — the workspace's outermost preview handler — to tell a held key's repeats
 * from new presses and to eat the character a used chord would also type: Compose delivers Ctrl+=
 * as a key press and then a typed "=", which a text field would insert after the zoom.
 */
internal class ShortcutKeys {
    private val held = mutableSetOf<Key>()
    private var repeat = false
    private var swallowTyped = false

    /** True when [event] is to be used up here: the typed character of a chord already handled. */
    fun observe(event: KeyEvent): Boolean {
        when (event.type) {
            KeyEventType.KeyDown -> {
                repeat = !held.add(event.key)
                swallowTyped = false
            }
            KeyEventType.KeyUp -> held.remove(event.key)
            else -> if (swallowTyped) {
                swallowTyped = false
                return true
            }
        }
        return false
    }

    /**
     * Runs the command [event] names where [scope] dispatches, if it names one there. [run] returns
     * whether the command did anything; true means the key is used up.
     */
    fun dispatch(
        event: KeyEvent,
        bindings: KeyBindings,
        scope: ShortcutScope,
        run: (ShortcutAction) -> Boolean,
    ): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val chord = event.toChord() ?: return false
        val used = when (val decision = bindings.decide(chord, scope, repeat)) {
            ShortcutDecision.Pass -> false
            ShortcutDecision.Swallow -> true
            is ShortcutDecision.Run -> run(decision.action)
        }
        if (used) swallowTyped = true
        return used
    }
}

private val ModifierKeys = setOf(
    Key.CtrlLeft, Key.CtrlRight, Key.ShiftLeft, Key.ShiftRight,
    Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight,
)

private val ComposeKeys: Map<Key, ShortcutKey> = mapOf(
    Key.A to ShortcutKey.A, Key.B to ShortcutKey.B, Key.C to ShortcutKey.C, Key.D to ShortcutKey.D,
    Key.E to ShortcutKey.E, Key.F to ShortcutKey.F, Key.G to ShortcutKey.G, Key.H to ShortcutKey.H,
    Key.I to ShortcutKey.I, Key.J to ShortcutKey.J, Key.K to ShortcutKey.K, Key.L to ShortcutKey.L,
    Key.M to ShortcutKey.M, Key.N to ShortcutKey.N, Key.O to ShortcutKey.O, Key.P to ShortcutKey.P,
    Key.Q to ShortcutKey.Q, Key.R to ShortcutKey.R, Key.S to ShortcutKey.S, Key.T to ShortcutKey.T,
    Key.U to ShortcutKey.U, Key.V to ShortcutKey.V, Key.W to ShortcutKey.W, Key.X to ShortcutKey.X,
    Key.Y to ShortcutKey.Y, Key.Z to ShortcutKey.Z,
    Key.Zero to ShortcutKey.Digit0, Key.One to ShortcutKey.Digit1, Key.Two to ShortcutKey.Digit2,
    Key.Three to ShortcutKey.Digit3, Key.Four to ShortcutKey.Digit4, Key.Five to ShortcutKey.Digit5,
    Key.Six to ShortcutKey.Digit6, Key.Seven to ShortcutKey.Digit7, Key.Eight to ShortcutKey.Digit8,
    Key.Nine to ShortcutKey.Digit9,
    Key.F1 to ShortcutKey.F1, Key.F2 to ShortcutKey.F2, Key.F3 to ShortcutKey.F3, Key.F4 to ShortcutKey.F4,
    Key.F5 to ShortcutKey.F5, Key.F6 to ShortcutKey.F6, Key.F7 to ShortcutKey.F7, Key.F8 to ShortcutKey.F8,
    Key.F9 to ShortcutKey.F9, Key.F10 to ShortcutKey.F10, Key.F11 to ShortcutKey.F11, Key.F12 to ShortcutKey.F12,
    Key.Tab to ShortcutKey.Tab, Key.Spacebar to ShortcutKey.Space, Key.Enter to ShortcutKey.Enter,
    Key.Backspace to ShortcutKey.Backspace, Key.Delete to ShortcutKey.Delete, Key.Insert to ShortcutKey.Insert,
    Key.MoveHome to ShortcutKey.Home, Key.MoveEnd to ShortcutKey.End,
    Key.PageUp to ShortcutKey.PageUp, Key.PageDown to ShortcutKey.PageDown,
    Key.DirectionUp to ShortcutKey.Up, Key.DirectionDown to ShortcutKey.Down,
    Key.DirectionLeft to ShortcutKey.Left, Key.DirectionRight to ShortcutKey.Right,
    Key.Escape to ShortcutKey.Escape,
    Key.Equals to ShortcutKey.Equals, Key.Minus to ShortcutKey.Minus, Key.Plus to ShortcutKey.Plus,
    Key.Comma to ShortcutKey.Comma, Key.Period to ShortcutKey.Period, Key.Slash to ShortcutKey.Slash,
    Key.Backslash to ShortcutKey.Backslash, Key.Semicolon to ShortcutKey.Semicolon,
    Key.Apostrophe to ShortcutKey.Apostrophe, Key.Grave to ShortcutKey.Grave,
    Key.LeftBracket to ShortcutKey.LeftBracket, Key.RightBracket to ShortcutKey.RightBracket,
    Key.NumPad0 to ShortcutKey.NumPad0, Key.NumPad1 to ShortcutKey.NumPad1, Key.NumPad2 to ShortcutKey.NumPad2,
    Key.NumPad3 to ShortcutKey.NumPad3, Key.NumPad4 to ShortcutKey.NumPad4, Key.NumPad5 to ShortcutKey.NumPad5,
    Key.NumPad6 to ShortcutKey.NumPad6, Key.NumPad7 to ShortcutKey.NumPad7, Key.NumPad8 to ShortcutKey.NumPad8,
    Key.NumPad9 to ShortcutKey.NumPad9,
    Key.NumPadAdd to ShortcutKey.NumPadAdd, Key.NumPadSubtract to ShortcutKey.NumPadSubtract,
    Key.NumPadMultiply to ShortcutKey.NumPadMultiply, Key.NumPadDivide to ShortcutKey.NumPadDivide,
    Key.NumPadDot to ShortcutKey.NumPadDot, Key.NumPadEnter to ShortcutKey.NumPadEnter,
)
