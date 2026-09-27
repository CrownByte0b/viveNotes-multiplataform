package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkPageOperation
import com.vivenotes.model.ink.InkSample
import com.vivenotes.model.ink.VisibleInkStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InkLayerTest {
    @Test
    fun storedStrokeIsVisibleInItsSavedColor() = runDesktopComposeUiTest(width = 100, height = 100) {
        show(page())
        val pixels = onNodeWithTag("ink-test-frame").captureToImage().toPixelMap()
        assertEquals(Color.Red, pixels[20, 30])
        assertEquals(Color.White, pixels[50, 50])
    }

    @Test
    fun eraseAndMoveReplayChangeTheVisiblePixels() = runDesktopComposeUiTest(width = 100, height = 100) {
        val erase = InkPageOperation.Erase("erase", 2, setOf("s"), false, 10f,
            listOf(InkSample(20f, 25f), InkSample(20f, 35f)))
        val move = InkPageOperation.Move("move", 3, setOf("s"), 30f, 0f, 1f, 1f, 0f, 0f)
        show(page(listOf(erase, move)))
        val pixels = onNodeWithTag("ink-test-frame").captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[20, 30], "the source moved")
        assertEquals(Color.White, pixels[50, 30], "the erased hole moved with the stroke")
        assertTrue(pixels[58, 38] != Color.White, "the surviving stroke moved")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(page: InkPage) {
        setContent {
            Box(Modifier.size(100.dp).background(Color.White).testTag("ink-test-frame")) {
                InkLayer(page, DpSize(100.dp, 100.dp), Color.Black)
            }
        }
    }

    private fun page(operations: List<InkPageOperation> = emptyList()) = InkPage("page", listOf(
        VisibleInkStroke("s", "marker", 1, 10f, 0xFFFF0000.toInt(), false,
            listOf(InkSample(10f, 20f), InkSample(30f, 40f))),
    ), operations)
}
