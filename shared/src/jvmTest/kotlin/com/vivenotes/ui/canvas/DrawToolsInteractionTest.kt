package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.data.EraserMode
import com.vivenotes.data.InkEdit
import com.vivenotes.ink.*
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.*
import com.vivenotes.ui.ribbon.draw.*
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DrawToolsInteractionTest {
    @Test
    fun rulerDropdownShowsTheRulerWithoutChangingTheActivePenAndPersistsItsKit() = runDesktopComposeUiTest(width = 1300, height = 800) {
        val state = mutableStateOf(WorkspaceState.demo().selectInkTool(InkTool.Pen, 1))
        val saves = mutableListOf<InkSettings>()
        setContent { DrawRibbon(state.value, { state.value = it(state.value) }, onInkSettingsChange = saves::add) }
        onNodeWithTag(DrawRibbonTags.RulerKit).performClick()
        onNodeWithTag("draw-ruler-Protractor").performClick()
        onNodeWithTag("draw-ruler-size").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(800f) }
        runOnIdle {
            assertTrue(state.value.rulerVisible)
            assertEquals(InkTool.Pen, state.value.inkTool)
            assertEquals(1, state.value.activePenIndex)
            assertEquals(InkRulerSettings(RulerKind.Protractor, 800), saves.last().ruler)
        }
        onNodeWithText("Ruler").performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(DrawRibbonTags.RulerTool).performClick()
        runOnIdle { assertFalse(state.value.rulerVisible); assertEquals(InkTool.Pen, state.value.inkTool) }
    }

    @Test
    fun rulerPaintDoesNotBlockInkAndMouseDialAndTouchBodyControlPlacement() = runDesktopComposeUiTest(width = 256, height = 256) {
        val ruler = mutableStateOf(Ruler(128f, 128f, 0f, RulerKind.Straight, 320f))
        val edits = mutableListOf<InkEdit>()
        setContent {
            Box(Modifier.size(256.dp).background(Color.White).testTag("ruler-frame")
                .pointerInput(Unit) { detectRulerGestures({ ruler.value }, { it }) { ruler.value = it } }) {
                InkLayer(InkPage("page", emptyList()), DpSize(256.dp, 256.dp), Color.Black,
                    tool = InkTool.Pen, ruler = ruler.value, onEdit = { edit, _ -> edits += edit })
                RulerLayer(ruler.value, DpSize(256.dp, 256.dp), Color.Black, 1f) {}
            }
        }
        onNodeWithTag("ruler-frame").performMouseInput {
            updatePointerTo(Offset(20f, 60f)); press(); moveTo(Offset(200f, 140f), delayMillis = 80); release()
        }
        runOnIdle {
            val row = (edits.single() as InkEdit.AddStroke).row
            val stroke = assertNotNull(ViveInkCodec.decode(row.toByteInk()))
            assertTrue((0 until stroke.inputs.size).all { stroke.inputs[it].y == 72f }, "the starting upper edge remains captured")
        }
        onNodeWithTag("ruler-frame").performMouseInput { click(Offset(128f, 128f)) }
        runOnIdle { assertEquals(45, ruler.value.degrees()); assertEquals(1, edits.size) }
        onNodeWithTag("ruler-frame").performTouchInput {
            down(Offset(140f, 128f)); moveBy(Offset(20f, 15f), delayMillis = 80); up()
        }
        runOnIdle {
            assertEquals(148f, ruler.value.centerX, .1f); assertEquals(143f, ruler.value.centerY, .1f)
            assertEquals(1, edits.size, "moving the ruler never authors ink")
        }
    }

    @Test
    fun holdingALineReplacesWetInkOnceAndLiftingDoesNotCommitTheOriginalStroke() = runDesktopComposeUiTest(width = 256, height = 256) {
        val lines = mutableListOf<StraightLine>()
        val edits = mutableListOf<InkEdit>()
        setContent { InkLayer(InkPage("page", emptyList()), DpSize(256.dp, 256.dp), Color.Black,
            tool = InkTool.Pen, onStraighten = { line, _ -> lines += line }, onEdit = { edit, _ -> edits += edit }) }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(20f, 100f)); press(); moveTo(Offset(200f, 102f), delayMillis = 80)
        }
        mainClock.advanceTimeBy(1100)
        waitUntil(timeoutMillis = 5000) { lines.isNotEmpty() }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput { release() }
        runOnIdle {
            assertEquals(listOf(StraightLine(20f, 100f, 20f + kotlin.math.hypot(180f, 2f), 100f)), lines)
            assertTrue(edits.isEmpty())
        }
    }

    @Test
    fun objectEraserDeletesOnlyTheTouchedFragmentAndUsesTheConfiguredSize() = runDesktopComposeUiTest(width = 160, height = 260) {
        val page = mutableStateOf(splitSelectionPage().second)
        val edits = mutableListOf<InkEdit>()
        setContent { InkLayer(page.value, DpSize(160.dp, 260.dp), Color.Black,
            tool = InkTool.Eraser, eraserMode = EraserMode.Object,
            settings = InkSettings(eraser = InkEraserSettings(EraserMode.Object, 4f)),
            onEdit = { edit, rendered -> edits += edit; page.value = rendered }) }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput { click(Offset(30f, 200f)) }
        waitUntil(timeoutMillis = 5000) { edits.isNotEmpty() }
        runOnIdle {
            val edit = edits.single() as InkEdit.PartialErase
            assertEquals(EraserMode.Object, edit.row.mode)
            assertEquals(4f, edit.row.sizeDp)
            val remaining = page.value.desktopGeometry().projections.single().pageBounds!!
            assertTrue(remaining.left > 60f)
        }
    }

    @Test
    fun openLassoSelectsNothingAndAClosedLoopChoosesOnlyEnclosedInk() = runDesktopComposeUiTest(width = 1500, height = 900) {
        val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        val ink = InkPageReader.read(initial.selectedPageId, listOf(selectionLine("inside", initial.selectedPageId),
            selectionLine("outside", initial.selectedPageId, y = 280f)), emptyList(), emptyList())
        val state = mutableStateOf(initial.updatePage(initial.selectedPageId) { it.copy(document = PageDoc.empty(), ink = ink) })
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(DrawRibbonTags.ObjectLasso).performClick()
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(0f, 175f)); press(); moveTo(Offset(130f, 175f), delayMillis = 20)
            moveTo(Offset(130f, 230f), delayMillis = 20); moveTo(Offset(0f, 230f), delayMillis = 20); release()
        }
        runOnIdle { assertNull(state.value.selectedInk) }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(0f, 175f)); press(); moveTo(Offset(130f, 175f), delayMillis = 20)
            moveTo(Offset(130f, 230f), delayMillis = 20); moveTo(Offset(0f, 230f), delayMillis = 20)
            moveTo(Offset(0f, 175f), delayMillis = 20); release()
        }
        runOnIdle { assertEquals(setOf("inside"), state.value.selectedInk!!.targetIds) }
    }
}
