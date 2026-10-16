package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.data.InkEdit
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.toByteInk
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.LineType
import com.vivenotes.ui.canvas.INK_LAYER_TAG
import com.vivenotes.ui.canvas.InkLayer
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InkControlsTest {
    @Test
    fun workspaceConnectsRibbonPreferencesToCanvasAuthoringAndTheDesktopSaveCallback() = runDesktopComposeUiTest(width = 1500, height = 900) {
        val state = mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Draw))
        val saved = mutableListOf<InkSettings>()
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, onInkSettingsChange = saved::add) } }
        onNodeWithTag(DrawRibbonTags.penKit(0)).performClick()
        onNodeWithTag(InkControlsTags.line(LineType.Dashed)).performClick()
        onNodeWithTag(InkControlsTags.Width).performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        onNodeWithTag(InkControlsTags.color(0xff2962ff.toInt())).performClick()
        onNodeWithTag(InkControlsTags.Preview).performKeyInput { pressKey(Key.Escape) }
        // Escape first dismisses a visible swatch tooltip; the next closes its owner.
        if (onAllNodesWithTag(InkControlsTags.Preview).fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithTag(InkControlsTags.Preview).performKeyInput { pressKey(Key.Escape) }
        }
        onNodeWithTag(InkControlsTags.Preview).assertDoesNotExist()
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(350f, 350f)); press(); moveTo(Offset(450f, 350f), delayMillis = 80); release()
        }
        runOnIdle {
            val edit = state.value.pendingInkEdits.single().edit as InkEdit.AddStroke
            assertEquals("dashed-line", edit.row.brushFamily)
            assertEquals(6f, edit.row.sizeDp)
            assertEquals(0xff2962ff.toInt(), edit.row.colorArgb)
            assertEquals(false, edit.row.colorFollowsTheme)
            assertNotNull(ViveInkCodec.decode(edit.row.toByteInk()))
            assertEquals(state.value.selectedPageId, edit.row.pageId)
            assertEquals(state.value.inkSettings, saved.last())
        }
    }

    @Test
    fun threePensSelectIndependentlyAndTheirAdjacentDropdownsEditOnlyTheirOwnKit() = runDesktopComposeUiTest(width = 1300, height = 800) {
        val state = mutableStateOf(WorkspaceState.demo())
        val saved = mutableListOf<InkSettings>()
        setContent { DrawRibbon(state.value, { state.value = it(state.value) }, onInkSettingsChange = saved::add) }
        onNodeWithTag(DrawRibbonTags.pen(2)).performClick()
        onNodeWithTag(InkControlsTags.Preview).assertDoesNotExist()
        runOnIdle { assertEquals(2, state.value.activePenIndex) }
        onNodeWithTag(DrawRibbonTags.pen(2)).performClick()
        onNodeWithText("Pen 3").assertExists()
        onNodeWithTag(InkControlsTags.Pressure).assertDoesNotExist()
        onNodeWithTag(InkControlsTags.brush(InkBrush.Calligraphy)).performClick()
        onNodeWithTag(InkControlsTags.Pressure).performClick()
        onNodeWithTag("${InkControlsTags.Pressure}-option-4").performClick()
        onNodeWithTag(InkControlsTags.Stabilization).performClick()
        onNodeWithTag("${InkControlsTags.Stabilization}-option-2").performClick()
        onNodeWithTag(InkControlsTags.Hold).performClick()
        onNodeWithTag(InkControlsTags.Width).performSemanticsAction(SemanticsActions.SetProgress) { it(7.5f) }
        onNodeWithTag(InkControlsTags.WheelButton).performClick()
        onNodeWithTag(InkControlsTags.CustomColor).performScrollTo().performTextReplacement("invalid")
        onNodeWithTag("${InkControlsTags.CustomColor}-apply").assertIsNotEnabled()
        onNodeWithTag(InkControlsTags.CustomColor).performTextReplacement("#123456")
        onNodeWithTag("${InkControlsTags.CustomColor}-apply").performClick()
        runOnIdle {
            assertEquals(InkSettings().pen, state.value.inkSettings.pen)
            assertEquals(InkSettings().pen2, state.value.inkSettings.pen2)
            assertEquals(InkPenSettings(InkBrush.Calligraphy, 7.5f, 0xff123456.toInt(), false,
                pressure = 4, stabilization = 2, holdForStraightLine = false), state.value.inkSettings.pen3)
            assertEquals(state.value.inkSettings, saved.last())
        }
        onNodeWithTag(InkControlsTags.WheelDone).performClick()
        onNodeWithTag(InkControlsTags.Preview).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(DrawRibbonTags.HighlighterKit).performClick()
        onNodeWithTag(InkControlsTags.Pressure).assertDoesNotExist()
        onNodeWithTag(InkControlsTags.Stabilization).assertDoesNotExist()
        onNodeWithTag(InkControlsTags.Hold).assertDoesNotExist()
        onNodeWithTag(InkControlsTags.Automatic).assertDoesNotExist()
        onNodeWithTag(InkControlsTags.Width).performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
        onNodeWithTag(InkControlsTags.color(HighlighterColors[3])).performClick()
        runOnIdle { assertEquals(InkHighlighterSettings(32f, HighlighterColors[3]), state.value.inkSettings.highlighter) }
        onNodeWithTag(InkControlsTags.Preview).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(DrawRibbonTags.penKit(2)).performClick()
        onNodeWithTag(InkControlsTags.WheelButton).performClick()
        onNodeWithTag(InkControlsTags.Automatic).performScrollTo().performClick()
        runOnIdle {
            assertTrue(state.value.inkSettings.pen3.colorFollowsTheme)
            assertEquals(0xff123456.toInt(), state.value.inkSettings.pen3.colorArgb)
            assertEquals(HighlighterColors[3], state.value.inkSettings.highlighter.colorArgb)
        }
    }

    @Test
    fun ribbonChoicesRenderTheConfiguredPenAndTranslucentHighlighterAndReloadIdentically() = runDesktopComposeUiTest(width = 1300, height = 800) {
        val state = mutableStateOf(WorkspaceState.demo())
        val page = mutableStateOf(InkPage("page", emptyList()))
        val edits = mutableListOf<InkEdit.AddStroke>()
        setContent {
            Column {
                DrawRibbon(state.value, { state.value = it(state.value) })
                Box(Modifier.size(128.dp).background(Color.White).testTag("ink-frame")) {
                    InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = state.value.inkTool,
                        settings = state.value.inkSettings, penIndex = state.value.activePenIndex,
                        onEdit = { edit, rendered -> edits += edit as InkEdit.AddStroke; page.value = rendered })
                }
            }
        }
        onNodeWithTag(DrawRibbonTags.penKit(0)).performClick()
        onNodeWithTag(InkControlsTags.Width).performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        onNodeWithTag(InkControlsTags.color(0xffe53935.toInt())).performClick()
        onNodeWithTag(InkControlsTags.Preview).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(10f, 40f)); press(); moveTo(Offset(110f, 40f), delayMillis = 80); release()
        }
        onNodeWithTag(DrawRibbonTags.HighlighterKit).performClick()
        onNodeWithTag(InkControlsTags.Width).performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
        onNodeWithTag(InkControlsTags.color(HighlighterColors[2])).performClick()
        onNodeWithTag(InkControlsTags.Preview).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(10f, 90f)); press(); moveTo(Offset(110f, 90f), delayMillis = 80); release()
        }
        runOnIdle {
            assertEquals(2, edits.size)
            assertEquals(8f, edits[0].row.sizeDp)
            assertEquals(0xffe53935.toInt(), edits[0].row.colorArgb)
            assertEquals(false, edits[0].row.colorFollowsTheme)
            assertEquals(32f, edits[1].row.sizeDp)
            assertEquals(HighlighterColors[2], edits[1].row.colorArgb)
            edits.forEach { assertNotNull(ViveInkCodec.decode(it.row.toByteInk())) }
        }
        val before = onNodeWithTag("ink-frame").captureToImage().toPixelMap()
        assertEquals(Color(0xffe53935), before[60, 40])
        assertEquals(Color.White, before[60, 47], "chosen marker width bounds the stroke")
        assertTrue(before[60, 90].red in .69f.. .72f, "blue highlighter blends at Android's 40% opacity")
        assertEquals(before[60, 90], before[60, 100], "chosen highlighter width covers the band")
        runOnIdle { page.value = InkPageReader.read("page", edits.map { it.row }, emptyList(), emptyList()) }
        val after = onNodeWithTag("ink-frame").captureToImage().toPixelMap()
        for (y in 0..127) for (x in 0..127) assertEquals(before[x, y], after[x, y], "reload pixel $x,$y")
    }

    @Test
    fun changingPreferencesDuringDrawingKeepsTheCapturedBrushAndStylesOnlyTheNextStroke() = runDesktopComposeUiTest(width = 128, height = 128) {
        val settings = mutableStateOf(InkSettings(pen = InkPenSettings(sizeDp = 4f, colorArgb = 0xffe01b24.toInt(), colorFollowsTheme = false)))
        val page = mutableStateOf(InkPage("page", emptyList()))
        val edits = mutableListOf<InkEdit.AddStroke>()
        setContent { InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Pen, settings = settings.value,
            onEdit = { edit, rendered -> edits += edit as InkEdit.AddStroke; page.value = rendered }) }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(10f, 40f)); press(); moveTo(Offset(60f, 40f), delayMillis = 40)
        }
        runOnIdle { settings.value = InkSettings(pen = InkPenSettings(InkBrush.Calligraphy, 8f, 0xff3584e4.toInt(), false, pressure = 5, stabilization = 3)) }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput { moveTo(Offset(110f, 40f), delayMillis = 40); release() }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(10f, 90f)); press(); moveTo(Offset(110f, 90f), delayMillis = 80); release()
        }
        runOnIdle {
            assertEquals("marker", edits[0].row.brushFamily)
            assertEquals(4f, edits[0].row.sizeDp)
            assertEquals(0xffe01b24.toInt(), edits[0].row.colorArgb)
            assertEquals("calligraphy-v1-p5", edits[1].row.brushFamily)
            assertEquals(3, edits[1].row.stabilization)
            assertEquals(8f, edits[1].row.sizeDp)
            assertEquals(0xff3584e4.toInt(), edits[1].row.colorArgb)
        }
    }
}
