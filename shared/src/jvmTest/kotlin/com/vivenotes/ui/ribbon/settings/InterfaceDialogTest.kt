package com.vivenotes.ui.ribbon.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.navigation.NavigationTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InterfaceDialogTest {
    @Test
    fun sunMoonSwitchPreviewsAndAppliesLightThemeThenResetFollowsSystem() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var saved = InterfaceSettings()
            var viewSaved: ViewSettings? = null
            setContent {
                var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
                var settings by remember { mutableStateOf(saved) }
                ViveNotesTheme(darkTheme = true) {
                    WorkspaceScreen(workspace, { workspace = it(workspace) }, interfaceSettings = settings,
                        onInterfaceSettingsChange = { settings = it; saved = it },
                        viewSettings = ViewSettings(canvasDark = true, canvasThemeDark = true),
                        onViewSettingsChange = { viewSaved = it })
                }
            }
            val original = onNodeWithTag(WorkspaceTestTags.HeaderBar).captureToImage().toPixelMap().let {
                it[it.width - 8, 8].luminance()
            }
            onNodeWithTag(InterfaceTags.Open).performClick()
            onNodeWithTag(InterfaceTags.ThemeSwitch).assertIsOn().performClick()
            onNodeWithTag(InterfaceTags.ThemeSwitch).assertIsOff()
            onNodeWithTag(InterfaceTags.Apply).performClick()
            runOnIdle {
                assertEquals(false, saved.darkTheme)
                assertEquals(null, viewSaved?.canvasDark)
            }
            val light = onNodeWithTag(WorkspaceTestTags.HeaderBar).captureToImage().toPixelMap().let {
                it[it.width - 8, 8].luminance()
            }
            assertTrue(light > original + 0.4f, "light theme should brighten the header: $original -> $light")

            onNodeWithTag(InterfaceTags.Open).performClick()
            onNodeWithTag(InterfaceTags.ThemeSwitch).assertIsOff()
            onNodeWithTag(InterfaceTags.Reset).performClick()
            onNodeWithTag(InterfaceTags.Apply).performClick()
            runOnIdle { assertEquals(null, saved.darkTheme) }
        }

    @Test
    fun cancelDiscardsThemeChoice() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var saved = InterfaceSettings()
        setContent {
            var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(workspace, { workspace = it(workspace) }, interfaceSettings = saved,
                    onInterfaceSettingsChange = { saved = it })
            }
        }
        fun headerBrightness(): Float = onNodeWithTag(WorkspaceTestTags.HeaderBar)
            .captureToImage().toPixelMap().let { it[it.width - 8, 8].luminance() }
        val dark = headerBrightness()
        onNodeWithTag(InterfaceTags.Open).performClick()
        onNodeWithTag(InterfaceTags.ThemeSwitch).performClick().assertIsOff()
        val preview = headerBrightness()
        assertTrue(preview > dark + 0.05f, "theme change should preview before Apply: $dark -> $preview")
        onNodeWithTag(InterfaceTags.Cancel).performClick()
        assertTrue(kotlin.math.abs(headerBrightness() - dark) < 0.05f, "Cancel should restore the theme")
        onNodeWithTag(InterfaceTags.Open).performClick()
        onNodeWithTag(InterfaceTags.ThemeSwitch).assertIsOn()
        runOnIdle { assertEquals(null, saved.darkTheme) }
    }

    @Test
    fun switchCanApplyDarkThemeFromLight() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var saved = InterfaceSettings(darkTheme = false)
        setContent {
            var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
            var settings by remember { mutableStateOf(saved) }
            ViveNotesTheme(darkTheme = false) {
                WorkspaceScreen(workspace, { workspace = it(workspace) }, interfaceSettings = settings,
                    onInterfaceSettingsChange = { settings = it; saved = it })
            }
        }
        onNodeWithTag(InterfaceTags.Open).performClick()
        onNodeWithTag(InterfaceTags.ThemeSwitch).assertIsOff().performClick().assertIsOn()
        onNodeWithTag(InterfaceTags.Apply).performClick()
        runOnIdle { assertEquals(true, saved.darkTheme) }
    }

    @Test
    fun settingsTabOpensDialogAndApplyCommitsScale() = runDesktopComposeUiTest(width = 2200, height = 900) {
        var saved = InterfaceSettings()
        var observed = WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)
        setContent {
            var workspace by remember { mutableStateOf(observed) }
            var settings by remember { mutableStateOf(saved) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(workspace, { workspace = it(workspace); observed = workspace }, interfaceSettings = settings,
                    onInterfaceSettingsChange = { settings = it; saved = it })
            }
        }

        onNodeWithTag(InterfaceTags.Open).performClick()
        onNodeWithTag(InterfaceTags.Dialog).assertIsDisplayed()
        val backdrop = onNodeWithTag(InterfaceTags.Backdrop).fetchSemanticsNode().boundsInRoot
        val dialog = onNodeWithTag(InterfaceTags.Dialog).fetchSemanticsNode().boundsInRoot
        assertTrue(backdrop.width >= 2199f && backdrop.height >= 899f)
        assertTrue(dialog.width <= 540f && dialog.height < 700f,
            "Interface dialog should stay compact inside the desktop window: $dialog")
        onNodeWithText("Display scale (DPI): 75%").assertIsDisplayed()
        onNodeWithContentDescription("Display scale (DPI)").assertIsDisplayed()
        onNodeWithTag(InterfaceTags.DisplayScale).performTouchInput {
            click(center + Offset(80f, 0f))
        }
        onNodeWithTag(InterfaceTags.Apply).performClick()
        onNodeWithTag(InterfaceTags.Dialog).assertDoesNotExist()
        runOnIdle { assertTrue(saved.displayScale > 0.75f) }

        onNodeWithTag(NavigationTestTags.notebook("calculus")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Rename).performClick()
        onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("Analysis")
        onNodeWithTag(NavigationTestTags.ConfirmRename).performClick()
        runOnIdle { assertEquals("Analysis", observed.notebooks.first().name) }

        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Settings)).assertIsDisplayed()
        onNodeWithTag(InterfaceTags.Open).performClick()
        onNodeWithTag(InterfaceTags.Reset).performClick()
        onNodeWithTag(InterfaceTags.Apply).performClick()
        runOnIdle { assertEquals(InterfaceSettings(), saved) }
    }

    @Test
    fun cancelDiscardsPreview() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var saved = InterfaceSettings()
        setContent {
            var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(workspace, { workspace = it(workspace) },
                    interfaceSettings = InterfaceSettings(), onInterfaceSettingsChange = { saved = it })
            }
        }
        onNodeWithTag(InterfaceTags.Open).performClick()
        onNodeWithTag(InterfaceTags.FontScale).performTouchInput {
            click(center + Offset(80f, 0f))
        }
        onNodeWithTag(InterfaceTags.Cancel).performClick()
        runOnIdle { assertEquals(InterfaceSettings(), saved) }
    }

    @Test
    fun allThreeSlidersCanGoBelowTheirDefaults() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var saved = InterfaceSettings()
        setContent {
            var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(workspace, { workspace = it(workspace) },
                    interfaceSettings = InterfaceSettings(), onInterfaceSettingsChange = { saved = it })
            }
        }
        onNodeWithTag(InterfaceTags.Open).performClick()
        for (slider in listOf(InterfaceTags.DisplayScale, InterfaceTags.UiScale, InterfaceTags.FontScale)) {
            onNodeWithTag(slider).performTouchInput { click(Offset(16f, center.y)) }
        }
        onNodeWithTag(InterfaceTags.Apply).performClick()
        runOnIdle {
            assertTrue(saved.displayScale < 0.75f)
            assertTrue(saved.uiScale < 1f)
            assertTrue(saved.fontScale < 1f)
        }
    }

    @Test
    fun largeDpiAndFontPreviewKeepTheActionsInsideAShortWindow() =
        runDesktopComposeUiTest(width = 1200, height = 600) {
            var saved = InterfaceSettings()
            setContent {
                var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
                ViveNotesTheme(darkTheme = true) {
                    WorkspaceScreen(workspace, { workspace = it(workspace) },
                        onInterfaceSettingsChange = { saved = it })
                }
            }
            onNodeWithTag(InterfaceTags.Open).performClick()
            onNodeWithTag(InterfaceTags.FontScale).performTouchInput { click(center + Offset(170f, 0f)) }
            onNodeWithTag(InterfaceTags.DisplayScale).performTouchInput { click(center + Offset(170f, 0f)) }

            val dialog = onNodeWithTag(InterfaceTags.Dialog).fetchSemanticsNode().boundsInRoot
            val apply = onNodeWithTag(InterfaceTags.Apply).fetchSemanticsNode().boundsInRoot
            assertTrue(dialog.top >= 0f && dialog.bottom <= 600f, "dialog outside short window: $dialog")
            assertTrue(apply.bottom <= 600f, "Apply was pushed out of the window: $apply")
            onNodeWithTag(InterfaceTags.Apply).performClick()
            runOnIdle {
                assertTrue(saved.displayScale > 2f)
                assertTrue(saved.fontScale > 1.5f)
            }
        }
}
