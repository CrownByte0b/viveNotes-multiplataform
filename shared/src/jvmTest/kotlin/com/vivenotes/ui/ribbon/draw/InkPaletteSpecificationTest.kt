package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.workspace.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InkPaletteSpecificationTest {
    @Test
    fun everyPenPinsWhiteAndBlackAcrossCustomColorsAndTracksTheAutomaticSwatch() = runDesktopComposeUiTest(width = 1300, height = 900) {
        val state = mutableStateOf(WorkspaceState.demo())
        val canvas = mutableStateOf(Color.White)
        val saved = mutableListOf<InkSettings>()
        setContent { DrawRibbon(state.value, { state.value = it(state.value) }, canvasInk = canvas.value, onInkSettingsChange = saved::add) }
        repeat(3) { index ->
            runOnIdle { canvas.value = Color.White }
            onNodeWithTag(DrawRibbonTags.penKit(index)).performClick()
            val before = PenColors.take(2).map { onNodeWithTag(InkControlsTags.color(it)).fetchSemanticsNode().boundsInRoot }
            onNodeWithTag(InkControlsTags.WheelButton).performScrollTo().performClick()
            onNodeWithTag(InkControlsTags.CustomColor).performScrollTo().performTextReplacement("#12345$index")
            onNodeWithTag("${InkControlsTags.CustomColor}-apply").performClick()
            onNodeWithTag(InkControlsTags.WheelDone).performClick()
            val after = state.value.inkSettings.palette.map { onNodeWithTag(InkControlsTags.color(it)).fetchSemanticsNode().boundsInRoot }
            assertEquals(before, after.take(2))
            assertTrue(after.zipWithNext().all { (a, b) -> a.left < b.left && a.top == b.top })
            runOnIdle {
                assertEquals(PenColors.take(2), state.value.inkSettings.palette.take(2))
                assertEquals(0xff123450.toInt() + index, state.value.inkSettings.palette[2])
            }
            onNodeWithTag(InkControlsTags.color(-1)).performClick()
            runOnIdle { assertTrue(state.value.inkSettings.pen(index).colorFollowsTheme) }
            onNodeWithTag(InkControlsTags.color(0xff000000.toInt())).performClick()
            runOnIdle { assertFalse(state.value.inkSettings.pen(index).colorFollowsTheme); canvas.value = Color.Black }
            onNodeWithTag(InkControlsTags.color(-1)).performClick()
            runOnIdle { assertFalse(state.value.inkSettings.pen(index).colorFollowsTheme) }
            onNodeWithTag(InkControlsTags.color(0xff000000.toInt())).performClick()
            runOnIdle { assertTrue(state.value.inkSettings.pen(index).colorFollowsTheme) }
            onNodeWithTag(InkControlsTags.Preview).performKeyInput { pressKey(Key.Escape) }
        }
        runOnIdle { assertEquals(state.value.inkSettings, saved.last()) }
    }

    @Test
    fun highlighterStartsWithWhiteAndBlackAndKeepsTheirTranslucency() = runDesktopComposeUiTest(width = 1300, height = 850) {
        val state = mutableStateOf(WorkspaceState.demo())
        setContent { DrawRibbon(state.value, { state.value = it(state.value) }) }
        onNodeWithTag(DrawRibbonTags.HighlighterKit).performClick()
        val bounds = HighlighterColors.map { onNodeWithTag(InkControlsTags.color(it)).fetchSemanticsNode().boundsInRoot }
        assertEquals(listOf(0x66ffffff, 0x66000000), HighlighterColors.take(2))
        assertTrue(bounds.take(3).zipWithNext().all { (a, b) -> a.top == b.top && a.left < b.left })
        for (argb in HighlighterColors.take(2)) {
            onNodeWithTag(InkControlsTags.color(argb)).performClick()
            runOnIdle { assertEquals(argb, state.value.inkSettings.highlighter.colorArgb) }
        }
    }
}
