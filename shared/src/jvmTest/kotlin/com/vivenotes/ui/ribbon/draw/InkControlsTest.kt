package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.data.InkEdit
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.toByteInk
import com.vivenotes.model.ink.InkPage
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
        setContent {
            ViveNotesTheme {
                WorkspaceScreen(state.value, { state.value = it(state.value) }, onInkSettingsChange = saved::add)
            }
        }
        onNodeWithTag(DrawRibbonTags.PenTool).performClick()
        onNodeWithTag(InkControlsTags.Brush).performClick()
        onNodeWithTag(InkControlsTags.brush(InkBrush.DashedLine)).performClick()
        onNodeWithTag(InkControlsTags.Width).performClick()
        onNodeWithTag(InkControlsTags.width(6f)).performClick()
        onNodeWithTag(InkControlsTags.Color).performClick()
        onNodeWithTag(InkControlsTags.color(0xff3584e4.toInt())).performClick()
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(350f, 350f)); press(); moveTo(Offset(450f, 350f), delayMillis = 80); release()
        }
        runOnIdle {
            val edit = state.value.pendingInkEdits.single().edit as InkEdit.AddStroke
            assertEquals("dashed-line", edit.row.brushFamily)
            assertEquals(6f, edit.row.sizeDp)
            assertEquals(0xff3584e4.toInt(), edit.row.colorArgb)
            assertEquals(false, edit.row.colorFollowsTheme)
            assertNotNull(ViveInkCodec.decode(edit.row.toByteInk()))
            assertEquals(state.value.selectedPageId, edit.row.pageId)
            assertEquals(state.value.inkSettings, saved.last())
        }
    }

    @Test
    fun brushWidthPaletteAndCustomColorUpdateIndependentPersistablePreferences() = runDesktopComposeUiTest(width = 1300, height = 700) {
        val state = mutableStateOf(WorkspaceState.demo().toggleInkTool(InkTool.Pen))
        val saved = mutableListOf<InkSettings>()
        setContent { DrawRibbon(state.value, { state.value = it(state.value) }, onInkSettingsChange = saved::add) }
        onNodeWithTag(InkControlsTags.Brush).performClick()
        onNodeWithTag(InkControlsTags.brush(InkBrush.Calligraphy)).performClick()
        onNodeWithTag(InkControlsTags.Width).performClick()
        onNodeWithTag(InkControlsTags.CustomWidth).performTextReplacement("NaN")
        onNodeWithTag("${InkControlsTags.CustomWidth}-apply").assertIsNotEnabled()
        onNodeWithTag(InkControlsTags.CustomWidth).performTextReplacement("7.5")
        onNodeWithTag("${InkControlsTags.CustomWidth}-apply").performClick()
        onNodeWithTag(InkControlsTags.Color).performClick()
        onNodeWithTag(InkControlsTags.color(0xff3584e4.toInt())).performClick()
        onNodeWithTag(InkControlsTags.Color).performClick()
        onNodeWithTag(InkControlsTags.CustomColor).performTextReplacement("invalid")
        onNodeWithTag("${InkControlsTags.CustomColor}-apply").assertIsNotEnabled()
        onNodeWithTag(InkControlsTags.CustomColor).performTextReplacement("#123456")
        onNodeWithTag("${InkControlsTags.CustomColor}-apply").performClick()
        runOnIdle {
            assertEquals(InkPenSettings(InkBrush.Calligraphy, 7.5f, 0xff123456.toInt(), false), state.value.inkSettings.pen)
            assertEquals(state.value.inkSettings, saved.last())
            assertEquals(4, saved.size)
        }
        onNodeWithTag(DrawRibbonTags.HighlighterTool).performClick()
        onNodeWithTag(InkControlsTags.Brush).assertDoesNotExist()
        onNodeWithTag(InkControlsTags.Width).performClick()
        onNodeWithTag(InkControlsTags.width(32f)).performClick()
        onNodeWithTag(InkControlsTags.Color).performClick()
        onNodeWithTag(InkControlsTags.Automatic).assertDoesNotExist()
        onNodeWithTag(InkControlsTags.color(0xffd81b60.toInt())).performClick()
        runOnIdle { assertEquals(InkHighlighterSettings(32f, 0x80d81b60.toInt()), state.value.inkSettings.highlighter) }
        onNodeWithTag(DrawRibbonTags.PenTool).performClick()
        onNodeWithTag(InkControlsTags.Brush).assertTextContains("Calligraphy")
        onNodeWithTag(InkControlsTags.Width).assertTextContains("7.5 dp")
        onNodeWithTag(InkControlsTags.Color).performClick()
        onNodeWithTag(InkControlsTags.Automatic).performClick()
        runOnIdle {
            assertTrue(state.value.inkSettings.pen.colorFollowsTheme)
            assertEquals(0xff123456.toInt(), state.value.inkSettings.pen.colorArgb)
            assertEquals(0x80d81b60.toInt(), state.value.inkSettings.highlighter.colorArgb)
            assertEquals(state.value.inkSettings, saved.last())
        }
    }

    @Test
    fun ribbonChoicesRenderTheConfiguredPenAndTranslucentHighlighterAndReloadIdentically() = runDesktopComposeUiTest(width = 1300, height = 700) {
        val state = mutableStateOf(WorkspaceState.demo().toggleInkTool(InkTool.Pen))
        val page = mutableStateOf(InkPage("page", emptyList()))
        val edits = mutableListOf<InkEdit.AddStroke>()
        setContent {
            Column {
                DrawRibbon(state.value, { state.value = it(state.value) })
                Box(Modifier.size(128.dp).background(Color.White).testTag("ink-frame")) {
                    InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = state.value.inkTool,
                        settings = state.value.inkSettings, onEdit = { edit, rendered ->
                            edits += edit as InkEdit.AddStroke; page.value = rendered
                        })
                }
            }
        }
        onNodeWithTag(InkControlsTags.Width).performClick()
        onNodeWithTag(InkControlsTags.width(8f)).performClick()
        onNodeWithTag(InkControlsTags.Color).performClick()
        onNodeWithTag(InkControlsTags.color(0xffe01b24.toInt())).performClick()
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(10f, 40f)); press(); moveTo(Offset(110f, 40f), delayMillis = 80); release()
        }
        onNodeWithTag(DrawRibbonTags.HighlighterTool).performClick()
        onNodeWithTag(InkControlsTags.Width).performClick()
        onNodeWithTag(InkControlsTags.width(32f)).performClick()
        onNodeWithTag(InkControlsTags.Color).performClick()
        onNodeWithTag(InkControlsTags.color(0xff3584e4.toInt())).performClick()
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(10f, 90f)); press(); moveTo(Offset(110f, 90f), delayMillis = 80); release()
        }
        runOnIdle {
            assertEquals(2, edits.size)
            assertEquals(8f, edits[0].row.sizeDp)
            assertEquals(0xffe01b24.toInt(), edits[0].row.colorArgb)
            assertEquals(false, edits[0].row.colorFollowsTheme)
            assertEquals(32f, edits[1].row.sizeDp)
            assertEquals(0x803584e4.toInt(), edits[1].row.colorArgb)
            edits.forEach { assertNotNull(ViveInkCodec.decode(it.row.toByteInk())) }
        }
        val before = onNodeWithTag("ink-frame").captureToImage().toPixelMap()
        assertEquals(Color(0xffe01b24), before[60, 40])
        assertEquals(Color.White, before[60, 47], "chosen marker width bounds the stroke")
        assertTrue(before[60, 90].red in 0.59f..0.62f, "blue highlighter blends at half opacity")
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
        setContent {
            InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Pen, settings = settings.value,
                onEdit = { edit, rendered -> edits += edit as InkEdit.AddStroke; page.value = rendered })
        }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(10f, 40f)); press(); moveTo(Offset(60f, 40f), delayMillis = 40)
        }
        runOnIdle { settings.value = InkSettings(pen = InkPenSettings(InkBrush.Calligraphy, 12f, 0xff3584e4.toInt(), false)) }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput { moveTo(Offset(110f, 40f), delayMillis = 40); release() }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(10f, 90f)); press(); moveTo(Offset(110f, 90f), delayMillis = 80); release()
        }
        runOnIdle {
            assertEquals("marker", edits[0].row.brushFamily)
            assertEquals(4f, edits[0].row.sizeDp)
            assertEquals(0xffe01b24.toInt(), edits[0].row.colorArgb)
            assertEquals("calligraphy-v1-p3", edits[1].row.brushFamily)
            assertEquals(12f, edits[1].row.sizeDp)
            assertEquals(0xff3584e4.toInt(), edits[1].row.colorArgb)
        }
    }
}
