package com.vivenotes.workspace

import com.vivenotes.richtext.TextSelection
import kotlin.test.Test
import kotlin.test.assertEquals

/** The text highlighter's ink is a device preference beside the default font, size and paper. */
class EditorDefaultsTest {
    @Test
    fun theHighlightInkStartsYellowAndKeepsOnlyOfferedInks() {
        assertEquals(0x66FFEB3B, EditorDefaults().highlight)
        for (ink in EditorDefaults.SUPPORTED_HIGHLIGHTS) {
            assertEquals(ink, EditorDefaults(highlight = ink).normalized().highlight)
        }
        // An opaque colour is a block over the text, not a highlight.
        assertEquals(0x66FFEB3B, EditorDefaults(highlight = 0xFF000000.toInt()).normalized().highlight)
    }

    @Test
    fun aNewInkLeavesTheTextBeingWrittenAlone() {
        val demo = WorkspaceState.demo()
        val box = demo.focusTextBox(demo.bodyTextOutline!!.id).selectText(TextSelection(3))
        val inked = box.setEditorDefaults(box.editorDefaults.copy(highlight = 0x66FF4081))
        assertEquals(0x66FF4081, inked.editorDefaults.highlight)
        assertEquals(box.typingMarks, inked.typingMarks)
        assertEquals(box.selectedPage!!.document, inked.selectedPage!!.document)
    }
}
