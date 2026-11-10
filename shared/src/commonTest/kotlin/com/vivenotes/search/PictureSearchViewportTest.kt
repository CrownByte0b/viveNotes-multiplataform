package com.vivenotes.search

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vivenotes.model.Outline
import com.vivenotes.model.ocr.PictureHighlight
import com.vivenotes.ui.search.pictureSearchScrollTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class PictureSearchViewportTest {
    private val image = Outline.Image(id = "image", attachmentId = "sha", x = 100f, y = 200f,
        width = 2000f, height = 4000f)

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = PictureHighlight(floatArrayOf(
        left, top, right, top, right, bottom, left, bottom))

    @Test fun aDistantMatchCentersItsWholeBoxInTheViewport() {
        assertEquals(Offset(750f, 3000f), pictureSearchScrollTarget(image, box(.4f, .75f, .5f, .8f),
            Rect(0f, 0f, 500f, 600f)))
    }

    @Test fun adjacentWordsInTheMiddleOfTheViewDoNotMoveEitherAxis() {
        val viewport = Rect(750f, 3000f, 1250f, 3600f)
        assertEquals(viewport.topLeft, pictureSearchScrollTarget(image, box(.42f, .76f, .48f, .78f), viewport))
        assertEquals(viewport.topLeft, pictureSearchScrollTarget(image, box(.49f, .77f, .55f, .8f), viewport))
    }

    @Test fun aVisibleWordNearTheEdgeIsCenteredBeforeItGetsClipped() {
        assertEquals(Offset(490f, 820f), pictureSearchScrollTarget(image, box(.3f, .22f, .34f, .24f),
            Rect(300f, 600f, 800f, 1200f)))
    }

    @Test fun aWideWordIsCenteredEvenWhenItsCenterIsAlreadyNearTheMiddle() {
        assertEquals(Offset(350f, 2900f), pictureSearchScrollTarget(image, box(.12f, .73f, .38f, .77f),
            Rect(300f, 2900f, 800f, 3500f)))
    }

    @Test fun pageEdgesClampTheCenterAndNearbyWordsStillDoNotYankTheView() {
        val viewport = Rect(0f, 0f, 500f, 600f)
        assertEquals(Offset.Zero, pictureSearchScrollTarget(image, box(0f, 0f, .05f, .025f), viewport))
        val nearEnd = Rect(600f, 2400f, 1100f, 3000f)
        assertEquals(nearEnd.topLeft, pictureSearchScrollTarget(image, box(.4f, .65f, .5f, .68f), nearEnd,
            maxScroll = Offset(600f, 2400f)))
    }

    @Test fun rotatedAndStretchedBoxesUseAllFourCornersForTheirCenter() {
        val highlight = PictureHighlight(floatArrayOf(.1f, .4f, .3f, .5f, .28f, .52f, .08f, .42f))
        assertEquals(Offset(230f, 1940f), pictureSearchScrollTarget(image, highlight, Rect(0f, 0f, 500f, 200f)))
    }

    @Test fun aResultWithSeveralMatchedTermsCentersTheirCombinedBounds() {
        val highlight = PictureHighlight(box(.1f, .5f, .2f, .55f).quads + box(.3f, .6f, .4f, .65f).quads)
        assertEquals(Offset(350f, 2200f), pictureSearchScrollTarget(image, highlight, Rect(0f, 0f, 500f, 600f)))
    }

    @Test fun changingDisplayDensityOrZoomKeepsTheSamePageLocation() {
        val highlight = box(.4f, .75f, .5f, .8f)
        for (scale in listOf(.5f, .75f, 1f, 1.5f, 2f, 4f)) {
            val viewport = Rect(0f, 0f, 800f / scale, 600f / scale)
            val scroll = pictureSearchScrollTarget(image.copy(x = 2000f), highlight, viewport)
            assertEquals(2900f * scale, scroll.x * scale + 400f, .001f)
            assertEquals(3300f * scale, scroll.y * scale + 300f, .001f)
        }
    }

    @Test fun anUnmeasuredViewportDoesNotScroll() {
        assertEquals(Offset(100f, 200f), pictureSearchScrollTarget(image, box(.4f, .75f, .5f, .8f),
            Rect(100f, 200f, 100f, 200f)))
    }
}
