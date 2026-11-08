package com.vivenotes.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.byteink.kit.InkBrushCatalog
import com.vivenotes.data.InkEdit
import com.vivenotes.ink.WebInkGeometry
import com.vivenotes.ink.loadedWebInk
import com.vivenotes.ink.strokeRow
import com.vivenotes.data.EraserMode
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.workspace.InkHighlighterSettings
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InkTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The browser preview's ink layer over the real engine, driven by mouse input. */
@OptIn(ExperimentalTestApi::class)
class WebInkLayerTest {
    private val ink = Color(0xff1e1e1e)

    /** Shows the layer on a 400 x 300 dp page with a 40 dp title floor; returns what it committed. */
    private suspend fun ComposeUiTest.showLayer(tool: InkTool?, settings: InkSettings = InkSettings(),
                                               page: InkPage? = null, eraserMode: EraserMode = EraserMode.Normal,
                                               selection: InkSelection? = null,
                                               selectionOffset: Offset = Offset.Zero): List<Pair<InkEdit, InkPage>> {
        val pages = loadedWebInk()
        val edits = mutableListOf<Pair<InkEdit, InkPage>>()
        setContent {
            var current by remember { mutableStateOf(page) }
            Box {
                WebInkLayer(pages, current, DpSize(400.dp, 300.dp), ink, "page", tool, 40f,
                    { edit, rendered -> edits += edit to rendered; current = rendered }, settings, 0,
                    eraserMode, selection, selectionOffset)
            }
        }
        waitForIdle()
        return edits
    }

    private fun ComposeUiTest.drag(from: Offset, to: Offset, button: MouseButton = MouseButton.Primary) {
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            moveTo(from)
            press(button)
            for (step in 1..12) moveTo(Offset(from.x + (to.x - from.x) * step / 12f,
                from.y + (to.y - from.y) * step / 12f + if (step % 2 == 0) 6f else 0f))
            release(button)
        }
        waitForIdle()
    }

    @Test
    fun aMouseDragCommitsOneAutomaticPenStrokeAndDrawsIt() = runComposeUiTest {
        val edits = showLayer(InkTool.Pen)
        val floor = 40f * density.density
        drag(Offset(40f, floor + 60f), Offset(300f, floor + 90f))
        val (edit, rendered) = edits.single()
        val row = assertIs<InkEdit.AddStroke>(edit).row
        assertEquals(listOf<Any?>("page", InkBrushCatalog.MARKER, ink.toArgb(), true, 1.5f, 1, "ink/androidx1"),
            listOf(row.pageId, row.brushFamily, row.colorArgb, row.colorFollowsTheme, row.sizeDp, row.stabilization, row.enc))
        assertEquals(listOf(row.id), rendered.strokes.map { it.id })
        val geometry = assertIs<WebInkGeometry>(rendered.geometry)
        assertEquals(listOf(row.id), geometry.rows.strokes.map { it.id })
        assertTrue(geometry.rightDp > 250f / density.density, "${geometry.rightDp}")

        val pixels = onNodeWithTag(INK_LAYER_TAG).captureToImage().toPixelMap()
        val along = (60..280 step 20).count { x -> (0 until pixels.height).any { y -> pixels[x, y].alpha > 0.5f } }
        assertTrue(along >= 10, "the committed stroke is drawn along its path ($along of 12 columns)")

        drag(Offset(40f, floor + 160f), Offset(300f, floor + 160f))
        assertEquals(2, edits.size)
        assertEquals(listOf(row.id, (edits.last().first as InkEdit.AddStroke).row.id), edits.last().second.strokes.map { it.id })
    }

    @Test
    fun inkStartsOnlyBelowTheTitleFloorAndWithThePrimaryButton() = runComposeUiTest {
        val edits = showLayer(InkTool.Pen)
        val floor = 40f * density.density
        drag(Offset(40f, floor - 20f), Offset(300f, floor + 80f))
        drag(Offset(40f, floor + 60f), Offset(300f, floor + 90f), MouseButton.Secondary)
        assertEquals(emptyList(), edits)
    }

    @Test
    fun theHighlighterStoresItsFamilyAndFixedColour() = runComposeUiTest {
        val edits = showLayer(InkTool.Highlighter, InkSettings(highlighter = InkHighlighterSettings(20f, 0x6644aa88)))
        drag(Offset(40f, 40f * density.density + 60f), Offset(300f, 40f * density.density + 60f))
        val row = assertIs<InkEdit.AddStroke>(edits.single().first).row
        assertEquals(listOf<Any?>(InkBrushCatalog.HIGHLIGHTER, 0x6644aa88, false, 20f, 0),
            listOf(row.brushFamily, row.colorArgb, row.colorFollowsTheme, row.sizeDp, row.stabilization))
    }

    /** A page with one stored 6 dp marker stroke from (60, 120) to (300, 140) dp. */
    private suspend fun storedStroke(): InkPage {
        val pages = loadedWebInk()
        return pages.withEdits(InkPage("page", emptyList()),
            listOf(InkEdit.AddStroke(pages.strokeRow("stored", 60f to 120f, 300f to 140f))))
    }

    /** Whether any drawn pixel lies in the band of page rows [top]..[bottom] dp. */
    private fun ComposeUiTest.inkBetween(top: Float, bottom: Float): Boolean {
        val pixels = onNodeWithTag(INK_LAYER_TAG).captureToImage().toPixelMap()
        val from = (top * density.density).toInt().coerceIn(0, pixels.height - 1)
        val to = (bottom * density.density).toInt().coerceIn(0, pixels.height - 1)
        return (from..to).any { y -> (0 until pixels.width).any { x -> pixels[x, y].alpha > 0.5f } }
    }

    @Test
    fun aNormalEraserDragCutsTheStrokeItCrosses() = runComposeUiTest {
        val pages = loadedWebInk()
        val edits = showLayer(InkTool.Eraser, page = storedStroke())
        val d = density.density
        drag(Offset(180f * d, 60f * d), Offset(180f * d, 220f * d))
        val (edit, erased) = edits.single()
        val erase = assertIs<InkEdit.PartialErase>(edit)
        assertEquals(setOf("stored"), erase.targetIds)
        assertEquals(EraserMode.Normal, erase.row.mode)
        assertEquals(2, pages.pieces(erased).size, "the stroke is cut in two")
        assertTrue(inkBetween(115f, 145f), "both pieces are still drawn")
    }

    @Test
    fun anObjectEraserDragRemovesTheStrokeItTouches() = runComposeUiTest {
        val pages = loadedWebInk()
        val edits = showLayer(InkTool.Eraser, page = storedStroke(), eraserMode = EraserMode.Object)
        val d = density.density
        drag(Offset(180f * d, 60f * d), Offset(180f * d, 220f * d))
        val (edit, erased) = edits.single()
        assertEquals(EraserMode.Object, assertIs<InkEdit.PartialErase>(edit).row.mode)
        assertEquals(emptyList(), pages.pieces(erased))
        assertTrue(!inkBetween(0f, 299f), "nothing is drawn")
    }

    @Test
    fun aSelectionBeingDraggedIsDrawnAtItsOffset() = runComposeUiTest {
        val page = storedStroke()
        val selection = assertNotNull(page.geometry!!.selectAt(page, 180f, 130f))
        showLayer(null, page = page, selection = selection, selectionOffset = Offset(0f, 100f))
        assertTrue(!inkBetween(110f, 150f), "the held ink has left its place")
        assertTrue(inkBetween(210f, 250f), "and is drawn 100 dp lower")
    }

    @Test
    fun withoutAnInkToolThePageOnlyDraws() = runComposeUiTest {
        val pages = loadedWebInk()
        val page = pages.withEdits(InkPage("page", emptyList()),
            listOf(InkEdit.AddStroke(pages.strokeRow("stored", 60f to 120f, 300f to 140f))))
        val edits = showLayer(null, page = page)
        drag(Offset(40f, 40f * density.density + 60f), Offset(300f, 40f * density.density + 90f))
        assertEquals(emptyList(), edits)
        val pixels = onNodeWithTag(INK_LAYER_TAG).captureToImage().toPixelMap()
        assertTrue((0 until pixels.width).any { x -> (0 until pixels.height).any { y -> pixels[x, y].alpha > 0.5f } },
            "the page's stored stroke is drawn")
    }
}
