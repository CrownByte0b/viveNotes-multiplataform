package com.vivenotes.ui.canvas

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewProjectionTest {
    @Test fun linkGlyphAtParagraphEndStaysOnTheLinksLine() {
        val source = AnnotatedString("Review\nNext", paragraphStyles = listOf(
            AnnotatedString.Range(ParagraphStyle(), 0, 6),
            AnnotatedString.Range(ParagraphStyle(), 7, 11),
        ))

        val projected = previewProjection(source, listOf(6 until 6))

        assertEquals("Review\uFFFC\u200BNext", projected.text)
        assertEquals(listOf(0 to 7, 8 to 12), projected.paragraphStyles.map { it.start to it.end })
        assertEquals(8, projectedOffset(7, listOf(6 until 6)))
    }

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

    /** A click on the preview starts editing at the source character it shows. */
    @Test fun aPreviewOffsetLeadsBackToItsSource() {
        // "See $x$ here." with the formula drawn, then a link icon after "here".
        val ranges = listOf(4 until 7, 12 until 12)
        listOf(0, 3, 4, 7, 8, 11, 12, 13).forEach { source ->
            assertEquals(source, sourceOffset(projectedOffset(source, ranges), ranges), "source $source")
        }
        // Shown: "See " ▢ " here" ▢ "." — the formula at 4, the icon at 10.
        assertEquals(4, sourceOffset(4, ranges), "before the formula is before its source")
        assertEquals(7, sourceOffset(5, ranges), "after the formula is after its source")
        assertEquals(12, sourceOffset(10, ranges))
        assertEquals(12, sourceOffset(11, ranges), "after the link icon is the link's end")
        assertEquals(13, sourceOffset(12, ranges))
    }
}
