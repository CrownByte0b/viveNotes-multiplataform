package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.workspace.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InkColorWheelTest {
    @Test
    fun untouchedWheelDoneKeepsThePaletteAndReturningToThePenKitDoesNotSave() = runDesktopComposeUiTest(width = 1300, height = 850) {
        val state = mutableStateOf(WorkspaceState.demo())
        val saved = mutableListOf<InkSettings>()
        setContent { DrawRibbon(state.value, { state.value = it(state.value) }, onInkSettingsChange = saved::add) }
        onNodeWithTag(DrawRibbonTags.penKit(1)).performClick()
        onNodeWithTag(InkControlsTags.WheelButton).performScrollTo().performClick()
        onNodeWithTag(InkControlsTags.WheelDone).performClick()
        onNodeWithTag(InkControlsTags.Preview).assertExists()
        runOnIdle { assertTrue(saved.isEmpty()); assertEquals(PenColors, state.value.inkSettings.palette) }
    }

    @Test
    fun wheelCommitsOnLiftAndPromotesOnlyTheLastMixedColorWhenDone() = runDesktopComposeUiTest(width = 1300, height = 850) {
        val state = mutableStateOf(WorkspaceState.demo())
        val saved = mutableListOf<InkSettings>()
        setContent { DrawRibbon(state.value, { state.value = it(state.value) }, onInkSettingsChange = saved::add) }
        onNodeWithTag(DrawRibbonTags.penKit(2)).performClick()
        onNodeWithTag(InkControlsTags.WheelButton).performScrollTo().performClick()
        val original = state.value.inkSettings.pen3
        onNodeWithTag(InkControlsTags.Wheel).performTouchInput { down(centerRight - Offset(5f, 0f)); moveTo(center) }
        runOnIdle { assertEquals(original, state.value.inkSettings.pen3); assertTrue(saved.isEmpty()) }
        onNodeWithTag(InkControlsTags.Wheel).performTouchInput { up() }
        runOnIdle { assertEquals(0xffe5e5e5.toInt(), state.value.inkSettings.pen3.colorArgb); assertEquals(PenColors, state.value.inkSettings.palette) }
        onNodeWithTag(InkControlsTags.Brightness).performTouchInput { click(centerLeft) }
        runOnIdle { assertEquals(0xff000000.toInt(), state.value.inkSettings.pen3.colorArgb); assertEquals(PenColors, state.value.inkSettings.palette) }
        onNodeWithTag(InkControlsTags.WheelDone).performClick()
        runOnIdle {
            assertEquals(PenColors.withColorInFront(0xff000000.toInt()), state.value.inkSettings.palette)
            assertEquals(InkSettings().pen, state.value.inkSettings.pen)
            assertEquals(InkSettings().pen2, state.value.inkSettings.pen2)
            assertEquals(3, saved.size)
        }
    }
}
