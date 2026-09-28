package com.vivenotes.ui.canvas

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewProjectionTest {
    @Test fun multilineReplacementLeavesNoParagraphBoundaryInsideAPlaceholder() {
        val sourceText = "Before\n\$\$\nx^2\n\$\$\nAfter"
        val source = AnnotatedString(sourceText,
            spanStyles = listOf(AnnotatedString.Range(SpanStyle(), 0, sourceText.length)),
            paragraphStyles = listOf(
                AnnotatedString.Range(ParagraphStyle(textAlign = TextAlign.Start), 0, 7),
                AnnotatedString.Range(ParagraphStyle(textAlign = TextAlign.Center), 7, 17),
                AnnotatedString.Range(ParagraphStyle(textAlign = TextAlign.End), 17, sourceText.length),
                AnnotatedString.Range(ParagraphStyle(textAlign = TextAlign.Start), 7, 7),
            ))

        val projected = previewProjection(source, listOf(7 until 16))

        assertEquals("Before\u200B\uFFFC\u200BAfter", projected.text)
        assertEquals(listOf(0 to 7, 7 to 9, 9 to 14),
            projected.paragraphStyles.map { it.start to it.end })
        assertTrue(projected.paragraphStyles.all { it.end > it.start })
        assertEquals(sourceText, source.text)
    }
}
