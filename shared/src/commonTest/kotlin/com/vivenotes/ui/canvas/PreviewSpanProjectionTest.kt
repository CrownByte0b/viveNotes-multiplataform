package com.vivenotes.ui.canvas

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewSpanProjectionTest {
    private fun reference(source: AnnotatedString, ranges: List<IntRange>): AnnotatedString {
        val display = AnnotatedString(source.text.replace('\n', '\u200B'), source.spanStyles)
        val offsets = PreviewOffsetIndex(ranges)
        return buildAnnotatedString {
            var offset = 0
            ranges.forEachIndexed { index, range ->
                append(display.subSequence(offset, range.first))
                appendInlineContent("preview-$index", "\uFFFC")
                offset = range.last + 1
            }
            append(display.subSequence(offset, source.length))
            source.paragraphStyles.forEach { range ->
                val start = offsets.toDisplay(range.start)
                val end = offsets.toDisplay(range.end) + if (range.end > range.start) offsets.insertionsAt(range.end) else 0
                if (end > start) addStyle(range.item, start, end)
            }
        }
    }

    @Test fun projectsClippedSpansInExactOriginalOrderIncludingEmptyBoundaries() {
        val random = Random(83292)
        repeat(500) {
            val length = random.nextInt(0, 100)
            val text = (0 until length).joinToString("") { if (it % 9 == 0) "\n" else "x" }
            val spans = List(50) { index ->
                val start = random.nextInt(length + 1)
                val end = random.nextInt(start, length + 1)
                AnnotatedString.Range(SpanStyle(color = if (index % 2 == 0) Color.Red else Color.Blue), start, end, "span-$index")
            }
            val source = AnnotatedString(text, spans, listOf(AnnotatedString.Range(ParagraphStyle(), 0, length)))
            var next = 0
            val ranges = buildList {
                repeat(25) {
                    val start = random.nextInt(next, length + 1)
                    val end = random.nextInt(start, length + 1)
                    add(start until end)
                    next = end
                }
            }
            assertEquals(reference(source, ranges), previewProjection(source, ranges), "length=$length ranges=$ranges")
        }
    }
}
