package com.vivenotes.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.model.search.FuzzyMatcher
import com.vivenotes.model.search.MatchSpan
import com.vivenotes.ui.components.BoundTextField
import com.vivenotes.ui.search.LocalPageSearchQuery
import com.vivenotes.ui.search.textSearchSpans
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class TextSearchHighlightTest {
    @Test fun textMatchesUseOriginalOffsetsAndTheSameBlockRulesAsSearch() {
        assertEquals(listOf(MatchSpan(9, 15)), FuzzyMatcher.occurrences("needle", "İstanbul needle"))
        assertEquals(listOf(MatchSpan(0, 8)), FuzzyMatcher.occurrences("istanbul", "İstanbul"))
        assertEquals(listOf(MatchSpan(0, 3), MatchSpan(8, 11), MatchSpan(12, 15)), textSearchSpans("cat", "cat dog cat\ncat"))
        assertTrue(textSearchSpans("cat dog", "cat\ndog").isEmpty())
        assertTrue(textSearchSpans("", "cat").isEmpty())
    }

    @Test fun typedMatchesHaveBlueBoxesAndFollowQueryChangesWithoutAnEditOrSelectionChange() = runDesktopComposeUiTest {
        var query by mutableStateOf("cat")
        var layout: TextLayoutResult? = null
        setContent {
            ViveNotesTheme {
                CompositionLocalProvider(LocalPageSearchQuery provides query) {
                    Box(Modifier.size(300.dp, 80.dp).background(Color.White).testTag("text")) {
                        BoundTextField(TextFieldValue("cat dog cat"), {},
                            textStyle = TextStyle(fontSize = 24.sp, fontFamily = FontFamily.Monospace, color = Color.Black),
                            onTextLayout = { layout = it })
                    }
                }
            }
        }
        waitForIdle()
        fun bluePixels(start: Int, end: Int): Int {
            val result = layout!!
            val first = result.getBoundingBox(start)
            val last = result.getBoundingBox(end - 1)
            val pixels = onNodeWithTag("text").captureToImage().toPixelMap()
            var count = 0
            for (x in first.left.toInt().coerceAtLeast(0) until last.right.toInt().coerceAtMost(pixels.width)) {
                for (y in first.top.toInt().coerceAtLeast(0) until last.bottom.toInt().coerceAtMost(pixels.height)) {
                    val color = pixels[x, y]
                    if (color.blue > color.red + .05f) count++
                }
            }
            return count
        }
        assertTrue(bluePixels(0, 3) > 20)
        assertEquals(0, bluePixels(4, 7))
        runOnIdle { query = "dog" }
        assertEquals(0, bluePixels(0, 3))
        assertTrue(bluePixels(4, 7) > 20)
        runOnIdle { query = "" }
        assertEquals(0, bluePixels(4, 7))
    }
}
