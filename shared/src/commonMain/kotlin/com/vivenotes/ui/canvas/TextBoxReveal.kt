package com.vivenotes.ui.canvas

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView

/**
 * Lets a text box scroll the canvas only to follow its caret while it is typed in.
 *
 * Compose brings a text field into view as it gains the focus — the whole field, and the caret the
 * field had before the click, which for a box nobody was editing is its first character. A click in
 * a box taller than the window, or in one whose top was scrolled away, moved the page from under
 * the pointer. The box was in view where it was clicked, and a box focused from the ribbon or a
 * menu is the one being looked at, so those requests are dropped: until [text] differs from what
 * the box held when it gained the focus, nothing below this modifier scrolls the canvas.
 */
internal fun Modifier.scrollOnlyForTyping(text: () -> String?): Modifier = this then ScrollOnlyForTypingElement(text)

private data class ScrollOnlyForTypingElement(private val text: () -> String?) :
    ModifierNodeElement<ScrollOnlyForTypingNode>() {
    override fun create() = ScrollOnlyForTypingNode(text)

    override fun update(node: ScrollOnlyForTypingNode) {
        node.text = text
    }
}

private class ScrollOnlyForTypingNode(var text: () -> String?) :
    Modifier.Node(), BringIntoViewModifierNode, FocusEventModifierNode {
    private var focused = false
    private var textWhenFocused: String? = null

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.hasFocus && !focused) textWhenFocused = text()
        focused = focusState.hasFocus
    }

    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        // A request arriving before the focus event was seen is the focus's own.
        if (!focused || text() == textWhenFocused) return
        val own = requireLayoutCoordinates()
        bringIntoView {
            val bounds = boundsProvider()
            if (bounds == null || !childCoordinates.isAttached || !own.isAttached) null
            else Rect(own.localPositionOf(childCoordinates, bounds.topLeft), bounds.size)
        }
    }
}
