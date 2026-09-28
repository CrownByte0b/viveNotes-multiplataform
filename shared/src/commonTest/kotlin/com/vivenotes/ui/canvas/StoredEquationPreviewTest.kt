package com.vivenotes.ui.canvas

import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.OBJECT_REPLACEMENT_CHARACTER
import com.vivenotes.model.Run
import com.vivenotes.richtext.RichTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class StoredEquationPreviewTest {
    @Test fun markSourceUsesTheEditorCharacterRangeAcrossParagraphs() {
        val buffer = RichTextBuffer(listOf(
            Block(id = "first", runs = listOf(Run("A "),
                Run(OBJECT_REPLACEMENT_CHARACTER.toString(), setOf(Mark.Equation("x^2"))),
                Run(" then"))),
            Block(id = "second", runs = listOf(Run("more "),
                Run(OBJECT_REPLACEMENT_CHARACTER.toString(), setOf(Mark.Equation("\\frac{1}{2}"))))),
        ))

        assertEquals(listOf(
            EquationPreview(2, 3, "x^2"),
            EquationPreview(14, 15, "\\frac{1}{2}"),
        ), buffer.storedEquationPreviews())
    }
}
