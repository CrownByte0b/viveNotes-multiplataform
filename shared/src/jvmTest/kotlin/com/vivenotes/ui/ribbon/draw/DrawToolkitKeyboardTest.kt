package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.components.HoverTooltipDelayMillis
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class DrawToolkitKeyboardTest {
    private data class Picker(val tool: String, val picker: String, val field: String, val alpha: Boolean = false)
    private val pickers = listOf(
        Picker(DrawRibbonTags.penKit(0), InkControlsTags.WheelButton, InkControlsTags.CustomColor),
        Picker(TableTags.Kit, "draw-table-border-wheel", "draw-table-border-custom"),
        Picker(TableTags.Kit, TableTags.Fill, "draw-table-fill-custom", alpha = true),
        Picker(DrawRibbonTags.ShapeKit, ShapeMenuTags.BorderWheel, "draw-shape-custom-border"),
        Picker(DrawRibbonTags.ShapeKit, ShapeMenuTags.Fill, "draw-shape-custom-fill", alpha = true),
    )

    @Test
    fun clickingEveryCustomHexFieldAcceptsKeyboardCharactersAndAppliesOnlyTheChosenColor() {
        for (picker in pickers) runDesktopComposeUiTest(width = 1600, height = 1100) {
            val state = mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Draw))
            val original = state.value.selectedPage!!.document
            val saved = mutableListOf<InkSettings>()
            val shapes = mutableListOf<ShapeToolSettings>()
            setContent {
                ViveNotesTheme(darkTheme = true) {
                    WorkspaceScreen(state.value, { state.value = it(state.value) },
                        interfaceSettings = InterfaceSettings(displayScale = 1f),
                        onInkSettingsChange = saved::add, onShapeSettingsChange = shapes::add)
                }
            }
            onNodeWithTag(picker.tool).performClick()
            onNodeWithTag(picker.picker).performScrollTo().performClick()
            val field = onNodeWithTag(picker.field).performScrollTo()
            field.performMouseInput { click(center) }
            mainClock.advanceTimeBy(100)
            field.assertIsFocused()
            field.typeCharacters("x")
            field.assertTextContains("x")
            onNodeWithTag("${picker.field}-apply").assertIsNotEnabled()
            field.performKeyInput { pressKey(Key.Backspace) }
            val hex = if (picker.alpha) "#801a2B3c" else "#1a2B3c"
            field.typeCharacters(hex)
            field.assertTextContains(hex)
            onNodeWithTag("${picker.field}-apply").assertIsEnabled().performClick()
            runOnIdle {
                val argb = if (picker.alpha) 0x801A2B3C.toInt() else 0xFF1A2B3C.toInt()
                val actual = when (picker.field) {
                    InkControlsTags.CustomColor -> state.value.inkSettings.pen.colorArgb
                    "draw-table-border-custom" -> state.value.inkSettings.table.borderArgb
                    "draw-table-fill-custom" -> state.value.inkSettings.table.fillArgb
                    "draw-shape-custom-border" -> state.value.shapeSettings.borderArgb
                    else -> state.value.shapeSettings.fillArgb
                }
                assertEquals(argb, actual, picker.field)
                assertTrue(saved.isNotEmpty() || shapes.isNotEmpty())
                assertSame(original, state.value.selectedPage!!.document, "typing a color must not edit the page")
            }
        }
    }

    @Test
    fun escapeImmediatelyDismissesEveryDrawToolkitWithoutChangingTheActiveTool() {
        val kits = listOf(
            DrawRibbonTags.penKit(0) to "Pen 1", DrawRibbonTags.penKit(1) to "Pen 2", DrawRibbonTags.penKit(2) to "Pen 3",
            DrawRibbonTags.HighlighterKit to "Highlighter", DrawRibbonTags.EraserModePicker to "Eraser",
            DrawRibbonTags.ShapeKit to "Shape", TableTags.Kit to "Table", EquationTags.Kit to "Insert equation",
            DrawRibbonTags.RulerKit to "Ruler",
        )
        for ((button, title) in kits) runDesktopComposeUiTest(width = 1600, height = 1100) {
            val state = mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Draw))
            setContent {
                ViveNotesTheme(darkTheme = true) {
                    WorkspaceScreen(state.value, { state.value = it(state.value) },
                        keyBindings = KeyBindings.Default.rebind(ShortcutAction.SelectTool, null))
                }
            }
            onNodeWithTag(button).performClick()
            val before = runOnIdle { state.value }
            onNodeWithText(title).assertIsDisplayed().performKeyInput { pressKey(Key.Escape) }
            onNodeWithText(title).assertDoesNotExist()
            runOnIdle { assertEquals(before, state.value, "Escape closes $title without selecting another tool") }
        }
    }

    @Test
    fun oneEscapeClosesTheToolkitAndItsVisibleSwatchTooltip() = runDesktopComposeUiTest(width = 1600, height = 1100) {
        val state = mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Draw))
        setContent { ViveNotesTheme(darkTheme = true) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(DrawRibbonTags.penKit(0)).performClick()
        val swatch = onNodeWithTag(InkControlsTags.color(0xFFE53935.toInt()))
        swatch.performClick()
        mainClock.autoAdvance = false
        swatch.performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(HoverTooltipDelayMillis + 20)
        onNodeWithText("Color #e53935").assertIsDisplayed()
        swatch.performKeyInput { pressKey(Key.Escape) }
        mainClock.advanceTimeByFrame()
        onNodeWithText("Color #e53935").assertDoesNotExist()
        onNodeWithTag(InkControlsTags.Preview).assertDoesNotExist()
        runOnIdle { assertEquals(InkTool.Pen, state.value.inkTool) }
    }

    @Test
    fun escapeClosesTheFocusedColorPickerThenItsToolkitAndKeyboardReturnsToTheWorkspace() =
        runDesktopComposeUiTest(width = 1600, height = 1100) {
            val state = mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Draw))
            setContent { ViveNotesTheme(darkTheme = true) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            onNodeWithTag(DrawRibbonTags.penKit(0)).performClick()
            onNodeWithTag(InkControlsTags.WheelButton).performClick()
            val field = onNodeWithTag(InkControlsTags.CustomColor).performScrollTo()
            field.performClick()
            field.typeCharacters("#123456")
            field.assertTextContains("#123456")
            field.performKeyInput { pressKey(Key.Escape) }
            field.assertDoesNotExist()
            onNodeWithTag(InkControlsTags.Preview).assertIsDisplayed().performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag(InkControlsTags.Preview).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.HeaderBar).performKeyInput {
                keyDown(Key.CtrlLeft); pressKey(Key.Equals); keyUp(Key.CtrlLeft)
            }
            onNodeWithTag(WorkspaceTestTags.ZoomIndicator).assertTextContains("125%")
            runOnIdle { assertEquals(InkTool.Pen, state.value.inkTool) }
        }

    // Send the typed event through the focused UI owner; semantic SetText/InsertText would bypass focus.
    private fun SemanticsNodeInteraction.typeCharacters(value: String) {
        value.forEach { char ->
            performKeyPress(KeyEvent(Key.Unknown, KeyEventType.KeyDown, codePoint = char.code))
            val typed = java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED,
                System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_UNDEFINED, char)
            performKeyPress(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = typed))
            performKeyPress(KeyEvent(Key.Unknown, KeyEventType.KeyUp, codePoint = char.code))
        }
    }
}
