package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vivenotes.model.*
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.ui.ribbon.draw.TableTags
import com.vivenotes.ui.ribbon.draw.DrawRibbonTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PrimeObjectInteractionTest {
    private fun workspace(outlines: List<Outline>): WorkspaceState {
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        return base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = outlines)) }
    }

    @Test
    fun aSingleLockedObjectKeepsOneUnlockButtonAndHidesAllResizeHandles() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val objects = listOf(
                Outline.Shape(id = "shape", kind = ShapeKind.Rectangle,
                    segments = seedSegments(ShapeKind.Rectangle, 100f, 220f, 180f, 280f, ::newId)).withRecomputedBounds(),
                newTable(2, 2, 100f, 220f).copy(id = "table"),
                Outline.Equation("equation", 100f, 220f, 80f, 60f, "x^2"),
                Outline.Image("image", 100f, 220f, 80f, attachmentId = "attachment", height = 60f),
            )
            val state = mutableStateOf(workspace(objects.take(1)).selectObject("shape"))
            setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            for (outline in objects) {
                runOnIdle { state.value = workspace(listOf(outline)).selectObject(outline.id) }
                onNodeWithTag(WorkspaceTestTags.objectCorner(outline.id, 3)).assertExists()
                onNodeWithTag(WorkspaceTestTags.ObjectLock).performClick()
                onAllNodesWithTag(WorkspaceTestTags.ObjectLock).assertCountEquals(1)
                onNodeWithContentDescription("Unlock selection").assertIsDisplayed()
                repeat(4) { onNodeWithTag(WorkspaceTestTags.objectCorner(outline.id, it)).assertDoesNotExist() }
                onNodeWithTag(WorkspaceTestTags.primeObject(outline.id)).performMouseInput {
                    moveTo(Offset(20f, 20f)); press(); moveTo(Offset(55f, 45f)); release()
                }
                runOnIdle { assertEquals(outline.x, state.value.selectedPage!!.document.outlines.single().x) }
                onNodeWithTag(WorkspaceTestTags.ObjectLock).performClick()
                onNodeWithContentDescription("Lock selection").assertIsDisplayed()
                onNodeWithTag(WorkspaceTestTags.objectCorner(outline.id, 3)).assertExists()
            }
        }

    @Test
    fun lockingALineHidesItsEndpointsAndPickingAPenDropsTheSelection() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val arrow = Outline.Shape(id = "arrow", kind = ShapeKind.Arrow,
                segments = seedSegments(ShapeKind.Arrow, 100f, 220f, 220f, 300f, ::newId)).withRecomputedBounds()
            val state = mutableStateOf(workspace(listOf(arrow)).selectObject(arrow.id))
            setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            val ends = listOf("workspace-shape-end-arrow-false", "workspace-shape-end-arrow-true")
            ends.forEach { onNodeWithTag(it).assertIsDisplayed() }
            onNodeWithTag(WorkspaceTestTags.ObjectLock).performClick()
            ends.forEach { onNodeWithTag(it).assertDoesNotExist() }
            onNodeWithTag(WorkspaceTestTags.ObjectCopy).assertIsEnabled()
            onNodeWithTag(WorkspaceTestTags.ObjectDelete).assertIsEnabled()
            onNodeWithTag(DrawRibbonTags.PenTool).performClick()
            onNodeWithTag(WorkspaceTestTags.ObjectToolkit).assertDoesNotExist()
            runOnIdle { assertTrue(state.value.selectedObjectIds.isEmpty()) }
        }

    @Test
    fun tappingALockedTypedCellSelectsItsWholeGroupAndUnlockRestoresEditing() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val table = newTable(2, 2, 100f, 220f, inkOnly = false).copy(lockGroup = "group")
            val equation = Outline.Equation("equation", 500f, 220f, 80f, 60f, "x^2", lockGroup = "group")
            val state = mutableStateOf(workspace(listOf(table, equation)))
            setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            val cell = table.rows.first().cells.first()
            onNodeWithTag(TableTags.cell(cell.id), useUnmergedTree = true).performMouseInput { click(center) }
            runOnIdle {
                assertNull(state.value.focusedTextOutlineId)
                assertEquals(setOf(table.id, equation.id), state.value.selectedObjectIds)
            }
            onNodeWithTag(WorkspaceTestTags.ObjectToolkit).assertIsDisplayed()
            save(onRoot(), "prime-locked-table.png")
            onNodeWithContentDescription("Unlock selection").performClick()
            onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { click(Offset(800f, 500f)) }
            onNodeWithTag(TableTags.cell(cell.id), useUnmergedTree = true).performMouseInput { click(center) }
            onNodeWithTag(TableTags.cell(cell.id)).performTextInput("Editable again")
            runOnIdle {
                assertEquals("Editable again", state.value.selectedPage!!.document.outlines
                    .filterIsInstance<Outline.Table>().single().rows.first().cells.first().plainText)
            }
        }

    @Test
    fun aTypedTableFirstClickSelectsTheObjectAndTheNextCellClickEditsItsText() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val table = newTable(2, 2, 100f, 220f, inkOnly = false)
            val state = mutableStateOf(workspace(listOf(table)))
            setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            val tag = TableTags.cell(table.rows.first().cells.first().id)
            onNodeWithTag(tag, useUnmergedTree = true).performMouseInput { click(center) }
            runOnIdle {
                assertEquals(setOf(table.id), state.value.selectedObjectIds)
                assertNull(state.value.focusedTextOutlineId)
            }
            onNodeWithTag(TableTags.RowMenu).assertIsDisplayed()
            onNodeWithTag(tag, useUnmergedTree = true).performMouseInput { click(center) }
            onNodeWithTag(tag).performTextInput("Typed through the cell")
            runOnIdle {
                assertEquals(table.rows.first().cells.first().id, state.value.focusedTextOutlineId)
                assertEquals("Typed through the cell", state.value.selectedPage!!.document.outlines
                    .filterIsInstance<Outline.Table>().single().rows.first().cells.first().plainText)
            }
        }

    @Test
    fun aPictureFirstInAMixedSelectionLeavesTheOtherObjectsColorControlAvailable() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val image = Outline.Image("image", 100f, 220f, 80f, attachmentId = "attachment", height = 60f)
            val equation = Outline.Equation("equation", 250f, 220f, 80f, 60f, "x^2")
            val state = mutableStateOf(workspace(listOf(image, equation)).selectObjectsInRect(80f, 200f, 400f, 320f))
            setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            onNodeWithTag(WorkspaceTestTags.ObjectColor).assertIsDisplayed().performClick()
            onNodeWithContentDescription("Red").performClick()
            runOnIdle {
                val outlines = state.value.selectedPage!!.document.outlines
                assertEquals(image, outlines.first())
                assertNotNull((outlines.last() as Outline.Equation).colorArgb)
            }
            save(onRoot(), "prime-image-equation.png")
            runOnIdle { state.value = state.value.selectObject(image.id) }
            onNodeWithTag(WorkspaceTestTags.ObjectColor).assertDoesNotExist()
        }

    @Test
    fun fingerDoubleTapOverAnObjectWithAPenArmedDoesNotOfferCanvasPaste() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val image = Outline.Image("image", 100f, 220f, 120f, attachmentId = "attachment", height = 80f)
            val state = mutableStateOf(workspace(listOf(image)).selectObject(image.id)
                .copySelectedObjects().selectInkTool(InkTool.Pen))
            setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            onNodeWithTag(WorkspaceTestTags.primeObject(image.id)).performTouchInput {
                click(center); advanceEventTime(120); click(center)
            }
            onNodeWithTag(WorkspaceTestTags.CanvasPaste).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
                click(Offset(700f, 600f)); advanceEventTime(120); click(Offset(702f, 601f))
            }
            onNodeWithTag(WorkspaceTestTags.CanvasPaste).assertIsDisplayed()
        }

    @Test
    fun hoveringAndPressingAnyObjectLeavesItsPixelsUntinted() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val objects = listOf(
                Outline.Image("image", 100f, 220f, 120f, attachmentId = "attachment", height = 80f),
                Outline.Shape(id = "shape", kind = ShapeKind.Rectangle,
                    segments = seedSegments(ShapeKind.Rectangle, 300f, 220f, 420f, 300f, ::newId)).withRecomputedBounds(),
                newTable(2, 2, 500f, 220f).copy(id = "table"),
                Outline.Equation("equation", 700f, 220f, 120f, 80f, "x^2"),
            )
            val state = mutableStateOf(workspace(objects))
            setContent {
                ViveNotesTheme {
                    Box(Modifier.fillMaxSize()) {
                        WorkspaceScreen(state.value, { state.value = it(state.value) })
                        Box(Modifier.align(Alignment.BottomEnd).size(80.dp).background(Color.White)
                            .testTag(RippleControl).clickable {})
                    }
                }
            }
            // The theme's ripple shows on an ordinary clickable, so the probe can see one.
            val probes = objects.map { WorkspaceTestTags.primeObject(it.id) to false } + (RippleControl to true)
            for ((tag, tinted) in probes) {
                val node = onNodeWithTag(tag)
                val idle = node.captureToImage().toPixelMap()
                node.performMouseInput { moveTo(Offset(20f, 20f)); press() }
                val pressed = node.captureToImage().toPixelMap()
                node.performMouseInput { release(); moveTo(Offset(-200f, -100f)) }
                assertEquals(tinted, !idle.buffer.contentEquals(pressed.buffer), tag)
            }
            runOnIdle { assertEquals(setOf("equation"), state.value.selectedObjectIds) }
        }

    private companion object { const val RippleControl = "ripple-control" }

    private fun save(node: SemanticsNodeInteraction, name: String) {
        val directory = File("build/test-artifacts").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { File(directory, name).writeBytes(it.bytes) }
        }
    }
}
