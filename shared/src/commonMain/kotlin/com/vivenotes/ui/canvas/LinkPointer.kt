package com.vivenotes.ui.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput

/** Whether Ctrl is down, from the keys a focused editor sees and from the mouse's own events. */
internal class CtrlState {
    var held by mutableStateOf(false)
}

/**
 * The CSS `pointer` cursor while the mouse is over a hyperlink, [otherwise] elsewhere. [onLink]
 * says whether a point is on one; the icon changes only as the mouse crosses a link's edge. Given
 * [ctrl], the pointer waits for Ctrl as well, as an editor whose plain click places the caret
 * opens a link on Ctrl+click.
 */
internal fun Modifier.linkPointer(
    otherwise: PointerIcon,
    ctrl: CtrlState? = null,
    onLink: (Offset) -> Boolean,
): Modifier = composed {
    val currentOnLink by rememberUpdatedState(onLink)
    var overLink by remember { mutableStateOf(false) }
    pointerHoverIcon(if (overLink && (ctrl == null || ctrl.held)) PointerIcon.Hand else otherwise)
        .pointerInput(ctrl) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.type == PointerType.Touch) continue
                    if (event.type == PointerEventType.Exit) {
                        overLink = false
                        continue
                    }
                    // Desktop reports a modifier change at a still mouse as an Unknown event.
                    ctrl?.held = event.keyboardModifiers.isCtrlPressed
                    if (event.type == PointerEventType.Enter || event.type == PointerEventType.Move ||
                        event.type == PointerEventType.Unknown) {
                        overLink = !change.pressed && currentOnLink(change.position)
                    }
                }
            }
        }
}
