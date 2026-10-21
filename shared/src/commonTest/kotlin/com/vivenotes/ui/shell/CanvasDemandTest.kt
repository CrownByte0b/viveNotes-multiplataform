package com.vivenotes.ui.shell

import androidx.compose.ui.geometry.Rect
import com.vivenotes.model.Block
import com.vivenotes.model.Run
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasDemandTest {
    @Test fun viewportUsesConservativeQuantizedOverscan() {
        val first = canvasDemandWindow(Rect(20f, 20f, 900f, 700f))
        assertEquals(first, canvasDemandWindow(Rect(30f, 30f, 910f, 710f)))
        assertTrue(first.contains(Rect(0f, 0f, 1100f, 900f).topLeft))
        assertEquals(Rect(-256f, -256f, 1280f, 1024f), first)
        val next = canvasDemandWindow(Rect(256f, 1024f, 1156f, 1724f))
        assertTrue(next.left <= 256f && next.top <= 1024f && next.right >= 1156f && next.bottom >= 1724f)
    }

    @Test fun unknownOrPinnedEditorsStayAndMeasuredOffscreenEditorsCanLeave() {
        val window = Rect(0f, 0f, 1000f, 700f)
        assertTrue(shouldComposeCanvasItem(20f, 5000f, 600f, null, window, false))
        assertFalse(shouldComposeCanvasItem(20f, 5000f, 600f, 200f, window, false))
        assertTrue(shouldComposeCanvasItem(20f, 5000f, 600f, 200f, window, true))
        assertTrue(shouldComposeCanvasItem(20f, -4000f, 600f, 4500f, window, false))
        assertFalse(shouldComposeCanvasItem(2000f, 20f, 600f, 200f, window, false))
    }

    @Test fun textMeasurementsInvalidateForContentWidthAndFontScale() {
        val key = TextMeasurementKey(listOf(Block("b", runs = listOf(Run("old")))), 600f, 80f, 1f, 1f)
        assertEquals(key, key.copy())
        assertTrue(key != key.copy(blocks = listOf(Block("b", runs = listOf(Run("new"))))))
        assertTrue(key != key.copy(width = 200f))
        assertTrue(key != key.copy(minHeight = 500f))
        assertTrue(key != key.copy(density = 2f))
        assertTrue(key != key.copy(fontScale = 1.5f))
        assertTrue(key != key.copy(editing = true))
        assertTrue(key != key.copy(thumbnails = object : com.vivenotes.data.VideoThumbnailSource {
            override suspend fun load(videoId: String): ByteArray? = null
        }))
    }
}
