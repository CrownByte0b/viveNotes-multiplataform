package com.vivenotes.ui.ribbon.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.navigation.NavigationTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InterfaceDialogTest {
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
}
