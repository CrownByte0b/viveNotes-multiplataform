package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.*
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.*
import com.vivenotes.data.EraserMode
import com.vivenotes.data.InkEdit
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.ink.*
import com.vivenotes.model.ink.InkPage
import com.vivenotes.ui.ribbon.draw.DrawRibbon
import com.vivenotes.ui.ribbon.draw.DrawRibbonTags
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InkEraserSettings
import androidx.compose.ui.input.key.Key
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PartialEraseInteractionTest {
    @Test
    fun zoomAndScrollAtFractionalDensityKeepTheMaskInPageCoordinates() = runDesktopComposeUiTest(width = 256, height = 256) {
        val density = 1.5f; val zoom = 1.25f; val scroll = Offset(17.25f, 29.75f)
        val page = mutableStateOf(InkPageReader.read("page", listOf(line("pen")), emptyList(), emptyList()))
        val edits = mutableListOf<InkEdit>()
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                Box(Modifier.size((256f / density).dp).clipToBounds().background(Color.White).testTag(FRAME)) {
                    Layout(content = {
                        InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Eraser, settings = InkSettings(eraser = InkEraserSettings(sizeDp = 24f)), zoom = zoom,
                            visibleWindow = { Rect(scroll.x / zoom, scroll.y / zoom,
                                (scroll.x + 256f) / zoom, (scroll.y + 256f) / zoom) },
                            onEdit = { edit, updated -> edits += edit; page.value = updated })
                    }) { children, _ ->
                        val child = children.single().measure(Constraints())
                        layout(256, 256) { child.placeWithLayer(0, 0) {
                            scaleX = zoom; scaleY = zoom; translationX = -scroll.x; translationY = -scroll.y
                            transformOrigin = TransformOrigin(0f, 0f)
                        } }
                    }
                }
            }
        }
        fun screen(x: Float, y: Float) = Offset(x * density * zoom - scroll.x, y * density * zoom - scroll.y)
        onNodeWithTag(FRAME).performMouseInput {
            updatePointerTo(screen(60f, 35f)); press(); moveTo(screen(60f, 75f), delayMillis = 40); release()
        }
        waitUntil(timeoutMillis = 5000) { edits.size == 1 }
        val edit = edits.single() as InkEdit.PartialErase
        val mask = assertNotNull(ViveInkCodec.decodeErase(StoredInkErase(edit.row.id, "page", "Normal", 24f,
            edit.row.points, edit.row.enc, edit.row.createdAt, targetIds = edit.targetIds.toList())))
        assertEquals(60f, mask.inputs[0].x, 0.02f); assertEquals(35f, mask.inputs[0].y, 0.02f)
        val pixels = onNodeWithTag(FRAME).captureToImage().toPixelMap()
        val gap = screen(60f, 50f); val end = screen(20f, 50f)
        assertEquals(Color.White, pixels[gap.x.toInt(), gap.y.toInt()])
        assertEquals(Color.Black, pixels[end.x.toInt(), end.y.toInt()])
    }

    @Test
    fun pageToolAndZoomChangesCancelUnfinishedPartialMasks() {
        for (change in listOf("page", "tool", "zoom")) runDesktopComposeUiTest(width = 128, height = 128) {
            val rows = listOf(line("pen"))
            val page = mutableStateOf(InkPageReader.read("page", rows, emptyList(), emptyList()))
            val tool = mutableStateOf<InkTool?>(InkTool.Eraser)
            val zoom = mutableStateOf(1f)
            val edits = mutableListOf<InkEdit>()
            setContent {
                Box(Modifier.size(128.dp).background(Color.White).testTag(FRAME)) {
                    InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = tool.value, zoom = zoom.value,
                        onEdit = { edit, _ -> edits += edit })
                }
            }
            val frame = onNodeWithTag(FRAME)
            frame.performMouseInput { updatePointerTo(Offset(60f, 35f)); press(); moveTo(Offset(60f, 75f), delayMillis = 40) }
            waitUntil(timeoutMillis = 5000) { frame.captureToImage().toPixelMap()[60, 50] == Color.White }
            runOnIdle { when (change) {
                "page" -> page.value = InkPageReader.read("other", rows.map { it.copy(pageId = "other") }, emptyList(), emptyList())
                "tool" -> tool.value = null
                "zoom" -> zoom.value = 1.25f
            } }
            frame.performMouseInput { release() }
            runOnIdle { assertTrue(edits.isEmpty(), "$change cancels the held mask") }
            assertEquals(Color.Black, frame.captureToImage().toPixelMap()[60, 50])
        }
    }

    @Test
    fun rapidGesturesAreCapturedWhileEarlierMasksAreModeledAndCommitInOrder() = runDesktopComposeUiTest(width = 128, height = 128) {
        val page = mutableStateOf(InkPageReader.read("page", listOf(line("pen")), emptyList(), emptyList()))
        val edits = mutableListOf<InkEdit>()
        setContent {
            Box(Modifier.size(128.dp).background(Color.White).testTag(FRAME)) {
                InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Eraser, settings = InkSettings(eraser = InkEraserSettings(sizeDp = 24f)),
                    onEdit = { edit, updated -> edits += edit; page.value = updated })
            }
        }
        val frame = onNodeWithTag(FRAME)
        frame.performMouseInput {
            updatePointerTo(Offset(40f, 50f)); press(); release()
            updatePointerTo(Offset(80f, 50f)); press(); release()
        }
        waitUntil(timeoutMillis = 5000) { edits.size == 2 }
        val erases = edits.filterIsInstance<InkEdit.PartialErase>()
        assertTrue(erases[1].row.createdAt > erases[0].row.createdAt)
        assertEquals(3, page.value.desktopGeometry().projections.size)
        val pixels = frame.captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[40, 50]); assertEquals(Color.White, pixels[80, 50])
        assertTrue(pixels[60, 50].red < 0.05f, "the middle fragment remains visible after both cuts")
    }

    @Test
    fun aHeldSecondPreviewIncludesTheEarlierCompletedMask() = runDesktopComposeUiTest(width = 128, height = 128) {
        val page = mutableStateOf(InkPageReader.read("page", listOf(line("pen")), emptyList(), emptyList()))
        val edits = mutableListOf<InkEdit>()
        setContent {
            Box(Modifier.size(128.dp).background(Color.White).testTag(FRAME)) {
                InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Eraser, settings = InkSettings(eraser = InkEraserSettings(sizeDp = 24f)),
                    onEdit = { edit, updated -> edits += edit; page.value = updated })
            }
        }
        val frame = onNodeWithTag(FRAME)
        frame.performMouseInput {
            updatePointerTo(Offset(40f, 50f)); press(); release()
            updatePointerTo(Offset(80f, 35f)); press(); moveTo(Offset(80f, 75f), delayMillis = 32)
        }
        waitUntil(timeoutMillis = 5000) {
            val pixels = frame.captureToImage().toPixelMap()
            edits.size == 1 && pixels[40, 50] == Color.White && pixels[80, 50] == Color.White
        }
        frame.performMouseInput { release() }
        waitUntil(timeoutMillis = 5000) { edits.size == 2 }
        assertEquals(3, page.value.desktopGeometry().projections.size)
    }

    @Test
    fun normalPreviewCutsBothOverlappingBrushesAndItsPixelsMatchTheReloadedMask() = runDesktopComposeUiTest(width = 128, height = 128) {
        val rows = listOf(line("pen"), line("highlight", highlight = true))
        val page = mutableStateOf(InkPageReader.read("page", rows, emptyList(), emptyList()))
        val edits = mutableListOf<InkEdit>()
        setContent {
            Box(Modifier.size(128.dp).background(Color.White).testTag(FRAME)) {
                InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Eraser, settings = InkSettings(eraser = InkEraserSettings(sizeDp = 24f)),
                    onEdit = { edit, updated -> edits += edit; page.value = updated })
            }
        }
        val frame = onNodeWithTag(FRAME)
        val original = frame.captureToImage().toPixelMap()[20, 50]
        frame.performMouseInput { updatePointerTo(Offset(60f, 35f)); press(); moveTo(Offset(60f, 75f), delayMillis = 40) }
        waitUntil(timeoutMillis = 5000) { frame.captureToImage().toPixelMap()[60, 50] == Color.White }
        val preview = frame.captureToImage().toPixelMap()
        assertEquals(original, preview[20, 50], "left end and translucent drawing order survive")
        assertEquals(original, preview[100, 50], "right end survives while held")
        runOnIdle { assertTrue(edits.isEmpty(), "preview is not a stored operation") }
        frame.performMouseInput { release() }
        waitUntil(timeoutMillis = 5000) { edits.size == 1 }
        val edit = edits.single() as InkEdit.PartialErase
        assertEquals(EraserMode.Normal, edit.row.mode)
        assertEquals(setOf("pen", "highlight"), edit.targetIds)
        assertEquals(2, page.value.desktopGeometry().projections.count { it.id == "pen" })
        assertEquals(1, page.value.desktopGeometry().projections.count { it.id == "highlight" })
        val committed = frame.captureToImage().toPixelMap()
        runOnIdle { page.value = InkPageReader.read("page", rows,
            listOf(InkEraseWithTargets(edit.row, edit.targetIds.map { InkEraseTargetEntity(edit.row.id, it) })), emptyList()) }
        val reloaded = frame.captureToImage().toPixelMap()
        for (y in 0 until 128) for (x in 0 until 128) {
            val a = committed[x, y]; val b = reloaded[x, y]
            assertTrue(kotlin.math.abs(a.red - b.red) <= 2.01f / 255f &&
                kotlin.math.abs(a.green - b.green) <= 2.01f / 255f &&
                kotlin.math.abs(a.blue - b.blue) <= 2.01f / 255f && a.alpha == b.alpha, "reload differs at $x,$y")
        }
    }

    @Test
    fun dotEraseUsesPageDpAndRealTouchInputsAtDoubleDensity() = runDesktopComposeUiTest(width = 256, height = 256) {
        val edits = mutableListOf<InkEdit>()
        val page = mutableStateOf(InkPageReader.read("page", listOf(line("pen")), emptyList(), emptyList()))
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Box(Modifier.size(128.dp).background(Color.White).testTag(FRAME)) {
                    InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Eraser, settings = InkSettings(eraser = InkEraserSettings(sizeDp = 24f)),
                        onEdit = { edit, updated -> edits += edit; page.value = updated })
                }
            }
        }
        onNodeWithTag(FRAME).performTouchInput { down(Offset(120f, 100f)); up() }
        waitUntil(timeoutMillis = 5000) { edits.size == 1 }
        val edit = edits.single() as InkEdit.PartialErase
        val stored = StoredInkErase(edit.row.id, "page", "Normal", edit.row.sizeDp, edit.row.points,
            edit.row.enc, edit.row.createdAt, targetIds = edit.targetIds.toList())
        val mask = assertNotNull(ViveInkCodec.decodeErase(stored))
        assertEquals(24f, mask.brush.size)
        assertEquals(InputToolType.TOUCH, mask.inputs[0].toolType)
        assertFalse(mask.inputs.hasPressure())
        assertEquals(60f, mask.inputs[0].x, 0.01f)
        assertEquals(50f, mask.inputs[0].y, 0.01f)
        val pixels = onNodeWithTag(FRAME).captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[120, 100])
        assertEquals(Color.Black, pixels[40, 100])
        assertEquals(Color.Black, pixels[200, 100])
    }

    @Test
    fun modeChangeCancelsAPartialPreviewAndObjectErasesTheTouchedProjection() = runDesktopComposeUiTest(width = 128, height = 128) {
        val page = mutableStateOf(InkPageReader.read("page", listOf(line("pen")), emptyList(), emptyList()))
        val mode = mutableStateOf(EraserMode.Normal)
        val edits = mutableListOf<InkEdit>()
        setContent {
            Box(Modifier.size(128.dp).background(Color.White).testTag(FRAME)) {
                InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Eraser, settings = InkSettings(eraser = InkEraserSettings(sizeDp = 24f)),
                    eraserMode = mode.value, onEdit = { edit, updated -> edits += edit; page.value = updated })
            }
        }
        val frame = onNodeWithTag(FRAME)
        frame.performMouseInput { updatePointerTo(Offset(60f, 35f)); press(); moveTo(Offset(60f, 75f), delayMillis = 40) }
        waitUntil(timeoutMillis = 5000) { frame.captureToImage().toPixelMap()[60, 50] == Color.White }
        runOnIdle { mode.value = EraserMode.Object }
        frame.performMouseInput { release() }
        assertEquals(Color.Black, frame.captureToImage().toPixelMap()[60, 50])
        runOnIdle { assertTrue(edits.isEmpty()) }
        frame.performMouseInput { updatePointerTo(Offset(20f, 50f)); press(); release() }
        waitUntil(timeoutMillis = 5000) { edits.size == 1 }
        runOnIdle {
            val edit = edits.single() as InkEdit.PartialErase
            assertEquals(setOf("pen"), edit.targetIds)
            assertEquals(EraserMode.Object, edit.row.mode)
        }
        assertEquals(Color.White, frame.captureToImage().toPixelMap()[100, 50])
    }

    @Test
    fun emptySpaceSecondaryClicksAndTitlePressesProduceNoErase() = runDesktopComposeUiTest(width = 128, height = 128) {
        val edits = mutableListOf<InkEdit>()
        setContent {
            Box(Modifier.size(128.dp).background(Color.White).testTag(FRAME)) {
                InkLayer(InkPageReader.read("page", listOf(line("pen")), emptyList(), emptyList()),
                    DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Eraser, settings = InkSettings(eraser = InkEraserSettings(sizeDp = 24f)), titleFloorDp = 30f,
                    onEdit = { edit, _ -> edits += edit })
            }
        }
        val frame = onNodeWithTag(FRAME)
        frame.performMouseInput { updatePointerTo(Offset(20f, 100f)); press(); release() }
        frame.performMouseInput { updatePointerTo(Offset(60f, 50f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        frame.performMouseInput { updatePointerTo(Offset(60f, 20f)); press(); moveTo(Offset(60f, 70f), delayMillis = 40); release() }
        runOnIdle { assertTrue(edits.isEmpty()) }
        assertEquals(Color.Black, frame.captureToImage().toPixelMap()[60, 50])
    }

    @Test
    fun drawRibbonSelectsNormalAndObjectModesWithoutDisarmingTheEraser() = runDesktopComposeUiTest(width = 1000, height = 120) {
        val state = mutableStateOf(WorkspaceState.demo().toggleInkTool(InkTool.Eraser))
        setContent { DrawRibbon(state.value, { state.value = it(state.value) }) }
        assertEquals(EraserMode.Normal, state.value.eraserMode)
        for (mode in listOf(EraserMode.Object, EraserMode.Normal)) {
            onNodeWithTag(DrawRibbonTags.EraserModePicker).performClick()
            onNodeWithTag(DrawRibbonTags.eraserMode(mode)).performClick()
            onNodeWithTag(DrawRibbonTags.eraserMode(mode)).performKeyInput { pressKey(Key.Escape) }
            runOnIdle { assertEquals(mode, state.value.eraserMode); assertEquals(InkTool.Eraser, state.value.inkTool) }
        }
    }

    private fun line(id: String, highlight: Boolean = false): com.vivenotes.data.db.InkStrokeEntity {
        val tool = ViveInkTool(if (highlight) ViveBrushes.HIGHLIGHTER else ViveBrushes.MARKER,
            sizeDp = if (highlight) 12f else 6f, colorArgb = if (highlight) 0x80ffe000.toInt() else 0xff000000.toInt())
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 10f, 50f, 0L); add(InputToolType.MOUSE, 110f, 50f, 100L)
        }
        return tool.complete(Stroke(tool.brush, inputs), id, "page", if (highlight) 1 else 0, 1L).row.toEntity()
    }

    companion object { private const val FRAME = "partial-erase-frame" }
}
