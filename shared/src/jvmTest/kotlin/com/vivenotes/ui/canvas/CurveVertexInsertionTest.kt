package com.vivenotes.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.model.ink.withAddedVertex
import com.vivenotes.model.ink.withRemovedVertex
import com.vivenotes.ui.theme.ViveNotesTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Android's curve-ghost compatibility cases through the desktop viewport transform. */
@OptIn(ExperimentalTestApi::class)
class CurveVertexInsertionTest {
    private var inserted: String? = null
    private var dismissed = 0
    private val curve: Outline.Shape = run {
        var next = 0
        Outline.Shape(id = "curve", kind = ShapeKind.Curve,
            segments = seedSegments(ShapeKind.Curve, 40f, 100f, 280f, 260f) { "s-${next++}" },
        ).withRecomputedBounds()
    }

    private fun ComposeUiTest.setOverlay(
        transformed: Boolean = false,
        insertOnClick: Boolean = false,
        mode: CurveVertexMode = CurveVertexMode.Add,
        removeOnClick: Boolean = false,
    ) {
        setContent {
            val density = LocalDensity.current.density
            var displayedCurve by remember { mutableStateOf(curve) }
            var visible by remember { mutableStateOf(true) }
            var displayedMode by remember { mutableStateOf(mode) }
            ViveNotesTheme {
                Box(Modifier.size(500.dp, 400.dp).background(Color.White).testTag("curve-ghost-gallery")) {
                    if (!transformed) Canvas(Modifier.fillMaxSize()) {
                        drawDocumentShape(displayedCurve, originX = 0f, originY = 0f)
                    }
                    CurveVertexInsertionOverlay(
                        curve = displayedCurve, visible = visible,
                        mode = displayedMode,
                        pageToView = { point ->
                            val scale = if (transformed) 2f else 1f
                            Offset(point.x * density * scale - if (transformed) 40f * density else 0f,
                                point.y * density * scale - if (transformed) 60f * density else 0f)
                        },
                        onInsert = { segmentId ->
                            inserted = segmentId
                            if (insertOnClick) {
                                displayedCurve = displayedCurve.withAddedVertex(segmentId) { "inserted" }
                                visible = false
                            }
                        },
                        onDismiss = { dismissed++ },
                        onRemove = { segmentId, atEnd ->
                            if (removeOnClick) {
                                displayedCurve = displayedCurve.withRemovedVertex(segmentId, atEnd)
                                visible = false
                                displayedMode = CurveVertexMode.Add
                            }
                        },
                    )
                }
            }
        }
    }

    @Test
    fun ghostsHaveAccessibleLabelsAndFullSizeTouchTargets() = runDesktopComposeUiTest(width = 560, height = 440) {
        setOverlay()
        for (index in 0..1) {
            onNodeWithTag(curveInsertionTag(index)).assertIsDisplayed()
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
                .assertContentDescriptionEquals("Add vertex between points ${index + 1} and ${index + 2}")
        }
        val captured = onNodeWithTag("curve-ghost-gallery").captureToImage()
        val directory = File("build/test-artifacts").apply { mkdirs() }
        Image.makeFromBitmap(captured.asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { File(directory, "curve-ghost-points.png").writeBytes(it.bytes) }
        }
    }

    @Test
    fun ghostTargetsFollowZoomAndScrollWhileKeepingTheirTouchSize() = runDesktopComposeUiTest(width = 560, height = 440) {
        setOverlay(transformed = true)
        val root = onNodeWithTag("curve-ghost-gallery").getUnclippedBoundsInRoot()
        // The path midpoints (100, 140) and (220, 140), zoomed by two then scrolled by (40, 60).
        for ((index, x) in listOf(160f, 400f).withIndex()) {
            val node = onNodeWithTag(curveInsertionTag(index))
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            val bounds = node.getUnclippedBoundsInRoot()
            assertEquals(x, (bounds.left.value + bounds.right.value) / 2f - root.left.value, .5f)
            assertEquals(220f, (bounds.top.value + bounds.bottom.value) / 2f - root.top.value, .5f)
        }
        onNodeWithTag(curveInsertionTag(1)).performTouchInput { click(center) }
        waitForIdle()
        assertEquals(curve.segments.last().id, inserted)
        assertEquals(0, dismissed, "Tapping a ghost was mistaken for a dismissal")
    }

    @Test
    fun insertionFadesTheExistingGhostsWithoutFlashingNewChoices() = runDesktopComposeUiTest(width = 560, height = 440) {
        setOverlay(insertOnClick = true)
        waitForIdle()
        mainClock.autoAdvance = false
        val before = onNodeWithTag(curveInsertionTag(0)).getUnclippedBoundsInRoot()
        onNodeWithTag(curveInsertionTag(0)).performTouchInput { click(center) }
        mainClock.advanceTimeByFrame()
        onNodeWithTag(curveInsertionTag(2)).assertDoesNotExist()
        val fading = onNodeWithTag(curveInsertionTag(0)).assertIsNotEnabled().getUnclippedBoundsInRoot()
        assertEquals(before, fading)
        mainClock.advanceTimeBy(2_000)
        onNodeWithTag(CURVE_INSERTION_OVERLAY_TAG).assertDoesNotExist()
    }

    @Test
    fun removingAPointFadesTheOldTargetsWithoutFlashingAddMode() = runDesktopComposeUiTest(width = 560, height = 440) {
        setOverlay(mode = CurveVertexMode.Remove, removeOnClick = true)
        waitForIdle()
        mainClock.autoAdvance = false
        val before = onNodeWithTag(curveRemovalTag(1)).getUnclippedBoundsInRoot()
        onNodeWithTag(curveRemovalTag(1)).performTouchInput { click(center) }
        mainClock.advanceTimeByFrame()
        onNodeWithTag(CURVE_INSERTION_OVERLAY_TAG).assertDoesNotExist()
        val fading = onNodeWithTag(curveRemovalTag(1)).assertIsNotEnabled().getUnclippedBoundsInRoot()
        assertEquals(before, fading)
        mainClock.advanceTimeBy(2_000)
        onNodeWithTag(CURVE_REMOVAL_OVERLAY_TAG).assertDoesNotExist()
    }
}
