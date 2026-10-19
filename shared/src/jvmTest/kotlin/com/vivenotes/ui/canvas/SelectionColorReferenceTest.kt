package com.vivenotes.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.LocalPopupLayerDensity
import com.vivenotes.ui.ribbon.draw.InkControlsTags
import com.vivenotes.ui.theme.ViveNotesTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SelectionColorReferenceTest {
    @Test
    fun panelMatchesTheCompactReferenceWithOneRowAndTheCurrentColorRing() = runDesktopComposeUiTest(width = 800, height = 600) {
        showPicker { }
        onNodeWithTag("open-colors").performClick()
        val menu = onNodeWithTag(SelectionColorTags.Menu).assertWidthIsEqualTo(320.dp)
        assertTrue(menu.fetchSemanticsNode().boundsInRoot.height <= 160f, "the compact panel must be at most 80 dp tall")
        onNodeWithText("Color").assertIsDisplayed()
        onNodeWithTag(SelectionColorTags.Add).assertIsDisplayed()
        onNodeWithTag(SelectionColorTags.Custom).assertDoesNotExist()
        onNodeWithTag(InkControlsTags.Wheel).assertDoesNotExist()
        val expectedColors = listOf(0xffffffff, 0xff000000, 0xffe53935, 0xff00bcd4, 0xff00c853,
            0xffffd600, 0xffff9100, 0xff9c27b0, 0xff2962ff).map { it.toInt() }
        val swatches = (expectedColors.map(SelectionColorTags::color) + SelectionColorTags.Rainbow)
            .map { onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertTrue(swatches.zipWithNext().all { (a, b) -> a.top == b.top && a.right < b.left })
        assertTrue(swatches.all { it.width == 52f && it.height == 52f })
        expectedColors.forEach { argb ->
            val rendered = onNodeWithTag(SelectionColorTags.color(argb)).captureToImage().toPixelMap()
            assertEquals(Color(argb), rendered[rendered.width / 2, rendered.height / 2])
        }
        val white = onNodeWithTag(SelectionColorTags.color(-1)).assertIsSelected().captureToImage().toPixelMap()
        assertEquals(Color(0xff007fff), white[1, white.height / 2])
        onNodeWithTag(SelectionColorTags.color(0xff000000.toInt())).assertIsNotSelected()
        assertEquals(Color(0xff262626), menu.captureToImage().toPixelMap()[320, 10])
        Image.makeFromBitmap(menu.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                File("build/test-artifacts").apply { mkdirs() }.resolve("selection-color-reference.png").writeBytes(data.bytes)
            }
        }
    }

    @Test
    fun plusOpensTheCustomWheelAndDoneCommitsOneColor() = runDesktopComposeUiTest(width = 800, height = 600) {
        val picked = mutableListOf<Int>()
        showPicker(picked::add)
        onNodeWithTag("open-colors").performClick()
        onNodeWithTag(SelectionColorTags.Add).performMouseInput { click(center) }
        onNodeWithTag(InkControlsTags.WheelDone).performScrollTo().performClick()
        onNodeWithTag(SelectionColorTags.Menu).assertDoesNotExist()
        runOnIdle { assertTrue(picked.isEmpty(), "untouched Done must not recolor the selection") }
        onNodeWithTag("open-colors").performClick()
        onNodeWithTag(SelectionColorTags.Add).performMouseInput { click(center) }
        onNodeWithTag(InkControlsTags.Wheel).performScrollTo().performTouchInput { click(centerRight - Offset(5f, 0f)) }
        runOnIdle { assertTrue(picked.isEmpty(), "mixing a custom color waits for Done") }
        onNodeWithTag(SelectionColorTags.Custom).performScrollTo().assertIsDisplayed()
        onNodeWithTag(InkControlsTags.WheelDone).performScrollTo().performClick()
        onNodeWithTag(SelectionColorTags.Menu).assertDoesNotExist()
        runOnIdle { assertEquals(1, picked.size); assertNotEquals(-1, picked.single()) }
    }

    private fun ComposeUiTest.showPicker(onPick: (Int) -> Unit) {
        setContent { CompositionLocalProvider(LocalDensity provides Density(2f), LocalPopupLayerDensity provides Density(1f)) {
            ViveNotesTheme(darkTheme = true) {
                Box(Modifier.size(400.dp, 300.dp)) {
                    Box(Modifier.offset(20.dp, 260.dp)) {
                        SelectionColorPicker(onColor = onPick, selectedColor = -1) { open ->
                            Button(onClick = open, modifier = Modifier.testTag("open-colors")) { Text("Open") }
                        }
                    }
                }
            }
        } }
    }
}
