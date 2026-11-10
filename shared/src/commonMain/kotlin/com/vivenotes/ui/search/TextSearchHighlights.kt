package com.vivenotes.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.vivenotes.model.search.FuzzyMatcher
import com.vivenotes.model.search.MatchSpan

internal val LocalPageSearchQuery = staticCompositionLocalOf { "" }

internal fun textSearchSpans(query: String, text: String): List<MatchSpan> {
    if (query.isBlank()) return emptyList()
    var offset = 0
    return buildList {
        text.split('\n').forEach { line ->
            FuzzyMatcher.occurrences(query, line).forEach { add(MatchSpan(it.start + offset, it.end + offset)) }
            offset += line.length + 1
        }
    }
}

internal class TextSearchHighlight {
    private var spans: List<MatchSpan> = emptyList()
    private var result: TextLayoutResult? = null
    private var paths by mutableStateOf<List<Path>>(emptyList())

    fun spans(value: List<MatchSpan>) {
        if (value == spans) return
        spans = value
        result?.let(::layout)
    }

    fun layout(result: TextLayoutResult) {
        this.result = result
        val length = result.layoutInput.text.length
        paths = spans.mapNotNull { span ->
            val start = span.start.coerceIn(0, length)
            val end = span.end.coerceIn(0, length)
            if (start < end) result.getPathForRange(start, end) else null
        }
    }

    val modifier: Modifier = Modifier.drawWithContent {
        drawContent()
        paths.forEach { path ->
            drawPath(path, SearchHighlightColor.copy(alpha = MATCH_FILL_ALPHA))
            drawPath(path, SearchHighlightColor.copy(alpha = MATCH_BORDER_ALPHA),
                style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun rememberTextSearchHighlight(text: String): TextSearchHighlight {
    val query = LocalPageSearchQuery.current
    val spans = remember(query, text) { textSearchSpans(query, text) }
    val highlight = remember { TextSearchHighlight() }
    SideEffect { highlight.spans(spans) }
    return highlight
}
