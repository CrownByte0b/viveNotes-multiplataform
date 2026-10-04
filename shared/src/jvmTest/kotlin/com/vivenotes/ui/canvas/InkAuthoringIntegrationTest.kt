package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.*
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.data.InkEdit
import com.vivenotes.ink.toByteInk
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.model.ink.InkPage
import com.vivenotes.ui.ribbon.draw.DrawRibbon
import com.vivenotes.ui.ribbon.draw.DrawRibbonTags
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InkAuthoringIntegrationTest {
    @Test
    fun ribbonChoosesEachDrawingToolAndSelectDisarmsIt() = runDesktopComposeUiTest(width = 1000, height = 100) {
        val state = mutableStateOf(WorkspaceState.demo())
        setContent { DrawRibbon(state.value, { state.value = it(state.value) }) }
        for ((tag, tool) in listOf(DrawRibbonTags.PenTool to InkTool.Pen,
            DrawRibbonTags.HighlighterTool to InkTool.Highlighter, DrawRibbonTags.EraserTool to InkTool.Eraser)) {
            onNodeWithTag(tag).performClick().assertIsSelected()
            runOnIdle { assertEquals(tool, state.value.inkTool) }
        }
        onNodeWithTag(DrawRibbonTags.PointerTool).performClick().assertIsSelected()
        runOnIdle { assertNull(state.value.inkTool) }
    }

    @Test
    fun mousePenHighlighterAndEraserProducePersistableRowsAndVisibleInk() = runDesktopComposeUiTest(width = 128, height = 128) {
        val page = mutableStateOf(InkPage("page", emptyList()))
        val tool = mutableStateOf<InkTool?>(InkTool.Pen)
        val edits = mutableListOf<InkEdit>()
        setContent {
            Box(Modifier.size(128.dp).background(Color.White).testTag("frame")) {
                InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = tool.value,
                    onEdit = { edit, rendered -> edits += edit; page.value = rendered })
            }
        }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(10f, 50f)); press(); moveTo(Offset(110f, 50f), delayMillis = 80)
        }
        assertEquals(Color.Black, onNodeWithTag("frame").captureToImage().toPixelMap()[60, 50], "wet pen")
        onNodeWithTag(INK_LAYER_TAG).performMouseInput { release() }
        runOnIdle {
            val row = (edits.single() as InkEdit.AddStroke).row
            assertEquals("marker", row.brushFamily)
            assertEquals(true, row.colorFollowsTheme)
            assertNotNull(ViveInkCodec.decode(row.toByteInk()))
            tool.value = InkTool.Highlighter
        }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(20f, 80f)); press(); moveTo(Offset(110f, 80f), delayMillis = 80); release()
        }
        runOnIdle {
            val row = (edits.last() as InkEdit.AddStroke).row
            assertEquals("highlighter", row.brushFamily)
            assertEquals(false, row.colorFollowsTheme)
            assertEquals(0, row.stabilization)
            assertEquals(0x80ffe000.toInt(), row.colorArgb)
            assertNotNull(ViveInkCodec.decode(row.toByteInk()))
            tool.value = InkTool.Eraser
        }
        val pixels = onNodeWithTag("frame").captureToImage().toPixelMap()
        assertTrue(pixels[60, 80].blue in 0.49f..0.51f, "highlighter remains translucent")
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(60f, 35f)); press(); moveTo(Offset(60f, 57f), delayMillis = 40); release()
        }
        runOnIdle {
            assertEquals(setOf((edits.first() as InkEdit.AddStroke).row.id), (edits.last() as InkEdit.EraseStrokes).ids)
            assertEquals(1, page.value.desktopGeometry().projections.size)
        }
        assertEquals(Color.White, onNodeWithTag("frame").captureToImage().toPixelMap()[60, 50])
    }

    @Test
    fun densityMapsMousePixelsToPageDpAndTitlePressDoesNotAuthor() = runDesktopComposeUiTest(width = 256, height = 256) {
        val edits = mutableListOf<InkEdit>()
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                InkLayer(InkPage("page", emptyList()), DpSize(128.dp, 128.dp), Color.Black,
                    tool = InkTool.Pen, titleFloorDp = 30f, onEdit = { edit, _ -> edits += edit })
            }
        }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(20f, 20f)); press(); moveTo(Offset(200f, 20f), delayMillis = 60); release()
        }
        runOnIdle { assertTrue(edits.isEmpty()) }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(20f, 100f)); press(); moveTo(Offset(200f, 100f), delayMillis = 60); release()
        }
        runOnIdle {
            val decoded = assertNotNull(ViveInkCodec.decode((edits.single() as InkEdit.AddStroke).row.toByteInk()))
            assertEquals(10f, decoded.inputs[0].x)
            assertEquals(50f, decoded.inputs[0].y)
            assertEquals(100f, decoded.inputs[decoded.inputs.size - 1].x)
        }
    }
    @Test
    fun touchAndMouseDotKeepTheirToolTypesAndNoSyntheticPressure() = runDesktopComposeUiTest(width = 128, height = 128) {
        val edits = mutableListOf<InkEdit>()
        setContent {
            InkLayer(InkPage("page", emptyList()), DpSize(128.dp, 128.dp), Color.Black,
                tool = InkTool.Pen, onEdit = { edit, _ -> edits += edit })
        }
        onNodeWithTag(INK_LAYER_TAG).performTouchInput {
            down(Offset(10f, 50f)); moveTo(Offset(100f, 50f), delayMillis = 80); up()
        }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(60f, 80f)); press(); release()
        }
        runOnIdle {
            assertEquals(2, edits.size)
            val touch = assertNotNull(ViveInkCodec.decode((edits[0] as InkEdit.AddStroke).row.toByteInk()))
            val dot = assertNotNull(ViveInkCodec.decode((edits[1] as InkEdit.AddStroke).row.toByteInk()))
            assertEquals(androidx.ink.brush.InputToolType.TOUCH, touch.inputs[0].toolType)
            assertEquals(androidx.ink.brush.InputToolType.MOUSE, dot.inputs[0].toolType)
            assertEquals(1, dot.inputs.size)
            assertNotNull(dot.shape.computeBoundingBox())
            assertTrue((0 until touch.inputs.size).all { touch.inputs[it].pressure == androidx.ink.strokes.StrokeInput.NO_PRESSURE })
        }
    }

    @Test
    fun changingToolsCancelsWetInkAndSecondaryMouseButtonsDoNotAuthor() = runDesktopComposeUiTest(width = 128, height = 128) {
        val tool = mutableStateOf<InkTool?>(InkTool.Pen)
        val edits = mutableListOf<InkEdit>()
        setContent {
            Box(Modifier.size(128.dp).testTag("cancel-frame")) {
                InkLayer(InkPage("page", emptyList()), DpSize(128.dp, 128.dp), Color.Black,
                    tool = tool.value, onEdit = { edit, _ -> edits += edit })
            }
        }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(10f, 50f)); press(); moveTo(Offset(100f, 50f), delayMillis = 80)
        }
        runOnIdle { tool.value = null }
        onNodeWithTag("cancel-frame").performMouseInput { release() }
        runOnIdle { tool.value = InkTool.Pen }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(10f, 50f)); press(MouseButton.Secondary)
            moveTo(Offset(100f, 50f), delayMillis = 80); release(MouseButton.Secondary)
        }
        runOnIdle { assertTrue(edits.isEmpty()) }
    }

    @Test
    fun zoomedParentMapsScreenPixelsBackIntoUnzoomedPageCoordinates() = runDesktopComposeUiTest(width = 256, height = 256) {
        val edits = mutableListOf<InkEdit>()
        setContent {
            Box(Modifier.size(256.dp).testTag("zoom-frame")) {
                androidx.compose.ui.layout.Layout(content = {
                    InkLayer(InkPage("page", emptyList()), DpSize(128.dp, 128.dp), Color.Black,
                        tool = InkTool.Pen, zoom = 2f, onEdit = { edit, _ -> edits += edit })
                }) { measurables, _ ->
                    val child = measurables.single().measure(Constraints())
                    layout(child.width * 2, child.height * 2) {
                        child.placeWithLayer(0, 0) {
                            scaleX = 2f; scaleY = 2f
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        }
                    }
                }
            }
        }
        onNodeWithTag("zoom-frame").performMouseInput {
            updatePointerTo(Offset(20f, 100f)); press(); moveTo(Offset(200f, 100f), delayMillis = 60); release()
        }
        runOnIdle {
            val decoded = assertNotNull(ViveInkCodec.decode((edits.single() as InkEdit.AddStroke).row.toByteInk()))
            assertEquals(10f, decoded.inputs[0].x)
            assertEquals(50f, decoded.inputs[0].y)
            assertEquals(100f, decoded.inputs[decoded.inputs.size - 1].x)
        }
    }

}
