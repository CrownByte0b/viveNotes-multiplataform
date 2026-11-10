package com.vivenotes.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.model.ocr.ImageLineLayout
import com.vivenotes.model.ocr.PictureHighlights
import com.vivenotes.model.search.MatchSpan
import com.vivenotes.ui.search.*
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ImageSearchHighlightTest {
    private val box = PictureHighlights.forSpans("abcd", ImageLineLayout(
        floatArrayOf(.1f, .2f, .9f, .2f, .9f, .4f, .1f, .4f),
        floatArrayOf(0f, .25f, .5f, .75f, 1f)), listOf(MatchSpan(0, 2)))!!
    private val image = Outline.Image(id = "frame", width = 400f, height = 200f, attachmentId = "picture")

    @Test fun aMatchIsPaintedOnItsWordsWithALightBlueFillAndBlueBox() = runDesktopComposeUiTest {
        setContent {
            ViveNotesTheme {
                val state = rememberImageSearchHighlights({ mapOf("picture" to box) }, null)
                Box(Modifier.size(400.dp, 200.dp).background(Color.White).testTag("picture")) {
                    PictureSearchOverlay(image, state) { Rect(0f, 0f, 400f, 200f) }
                }
            }
        }
        val pixels = onNodeWithTag("picture").captureToImage().toPixelMap()
        val inside = pixels[(pixels.width * .3f).toInt(), (pixels.height * .3f).toInt()]
        val border = pixels[(pixels.width * .1f).toInt(), (pixels.height * .3f).toInt()]
        assertTrue(inside.blue > inside.red && inside.red > .65f, "light-blue translucent fill: $inside")
        assertTrue(border.red < inside.red, "the box has a stronger border")
        assertEquals(Color.White, pixels[(pixels.width * .7f).toInt(), (pixels.height * .3f).toInt()])
        assertEquals(Color.White, pixels[(pixels.width * .3f).toInt(), (pixels.height * .7f).toInt()])
    }

    @Test fun aBoxStaysOnItsWordsWhenTheFrameIsStretched() = runDesktopComposeUiTest {
        val stretched = image.copy(width = 200f, height = 400f)
        setContent {
            ViveNotesTheme {
                val state = rememberImageSearchHighlights({ mapOf("picture" to box) }, null)
                Box(Modifier.size(200.dp, 400.dp).background(Color.White).testTag("picture")) {
                    PictureSearchOverlay(stretched, state) { Rect(0f, 0f, 200f, 400f) }
                }
            }
        }
        val pixels = onNodeWithTag("picture").captureToImage().toPixelMap()
        assertNotEquals(Color.White, pixels[(pixels.width * .3f).toInt(), (pixels.height * .3f).toInt()])
        assertEquals(Color.White, pixels[(pixels.width * .7f).toInt(), (pixels.height * .3f).toInt()])
    }

    @Test fun aNewQueryRepaintsAndAClearedOneRemovesTheBoxesWithoutRecomposingThePage() = runDesktopComposeUiTest {
        val matches = mutableStateOf(emptyMap<String, com.vivenotes.model.ocr.PictureHighlight>())
        var compositions = 0
        setContent {
            ViveNotesTheme {
                val state = rememberImageSearchHighlights({ matches.value }, null)
                SideEffect { compositions++ }
                Box(Modifier.size(400.dp, 200.dp).background(Color.White).testTag("picture")) {
                    PictureSearchOverlay(image, state) { Rect(0f, 0f, 400f, 200f) }
                }
            }
        }
        waitForIdle()
        val before = compositions
        runOnIdle { matches.value = mapOf("picture" to box) }
        var pixels = onNodeWithTag("picture").captureToImage().toPixelMap()
        assertNotEquals(Color.White, pixels[pixels.width * 3 / 10, pixels.height * 3 / 10])
        onNodeWithTag(SearchTags.pictureHighlight("frame")).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "1 search match"))
        runOnIdle { matches.value = emptyMap() }
        pixels = onNodeWithTag("picture").captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[pixels.width * 3 / 10, pixels.height * 3 / 10])
        onNodeWithTag(SearchTags.pictureHighlight("frame")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        runOnIdle { assertEquals(before, compositions) }
    }

    @Test fun offscreenWordBoxesAreCulledEvenWhenThePictureIsVisible() = runDesktopComposeUiTest {
        setContent {
            ViveNotesTheme {
                val state = rememberImageSearchHighlights({ mapOf("picture" to box) }, null)
                Box(Modifier.size(400.dp, 200.dp).background(Color.White).testTag("picture")) {
                    PictureSearchOverlay(image, state) { Rect(240f, 100f, 400f, 200f) }
                }
            }
        }
        val pixels = onNodeWithTag("picture").captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[pixels.width * 3 / 10, pixels.height * 3 / 10])
    }

    @Test fun focusUsesOnlyTheOpenedPlacementAndFadesAway() = runDesktopComposeUiTest {
        var focus by mutableStateOf<ImageSearchFocus?>(ImageSearchFocus("frame", box))
        setContent {
            ViveNotesTheme {
                val state = rememberImageSearchHighlights({ emptyMap() }, focus)
                Box(Modifier.size(400.dp, 200.dp).background(Color.White).testTag("picture")) {
                    PictureSearchOverlay(image, state) { Rect(0f, 0f, 400f, 200f) }
                }
            }
        }
        var pixels = onNodeWithTag("picture").captureToImage().toPixelMap()
        assertNotEquals(Color.White, pixels[pixels.width * 3 / 10, pixels.height * 3 / 10])
        runOnIdle { focus = ImageSearchFocus("other-frame", box) }
        pixels = onNodeWithTag("picture").captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[pixels.width * 3 / 10, pixels.height * 3 / 10])
        runOnIdle { focus = null }
        pixels = onNodeWithTag("picture").captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[pixels.width * 3 / 10, pixels.height * 3 / 10])
    }
}
