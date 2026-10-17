package com.vivenotes.localization

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.ribbon.settings.AboutTags
import com.vivenotes.ui.ribbon.settings.InterfaceTags
import com.vivenotes.ui.ribbon.view.ViewRibbonTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class LocalizedUiTest {
    @AfterTest
    fun restoreDefaults() = Localization.reset()

    @Test
    fun reloadingResourcesUpdatesMountedTabsDialogsAndAccessibility() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        setContent {
            var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
            ViveNotesTheme {
                WorkspaceScreen(workspace, { workspace = it(workspace) }, appVersion = "0.1.0")
            }
        }
        onNodeWithText("Settings").assertIsDisplayed()
        onNodeWithTag("settings-icon-interface", useUnmergedTree = true).assertIsDisplayed()

        runBlocking { loadFrenchFixtures() }
        waitForIdle()
        onNodeWithText("Paramètres").assertIsDisplayed()
        onNodeWithTag("settings-icon-interface", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("Rechercher dans le carnet").assertExists()
        onNodeWithTag(InterfaceTags.Open).performClick()
        onNode(hasText("Interface utilisateur") and hasAnyAncestor(hasTestTag(InterfaceTags.Dialog)),
            useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Annuler").performClick()
        onNodeWithTag(AboutTags.Open).performClick()
        onNodeWithText("Version de l’application 0.1.0").assertIsDisplayed()
        onNodeWithTag(AboutTags.Close).assertTextEquals("Fermer").performClick()
        onNodeWithTag(AboutTags.Dialog).assertDoesNotExist()
    }

    @Test
    fun translatedPaperChoicesRetainTheirIdentifiersAndStillChangeTheDocument() = runDesktopComposeUiTest(width = 1800, height = 1000) {
        runBlocking { loadFrenchFixtures() }
        setContent {
            var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.View)) }
            ViveNotesTheme { WorkspaceScreen(workspace, { workspace = it(workspace) }) }
        }
        onNodeWithTag(ViewRibbonTags.PaperSize).performClick()
        onNodeWithTag(ToolPaneTags.field("Size")).performClick()
        onNodeWithTag(ToolPaneTags.option("Size", "Custom")).assertTextEquals("Personnalisé").performClick()
        onNodeWithTag(ToolPaneTags.field("Width")).assertIsDisplayed()
        onNodeWithTag(ToolPaneTags.field("Size")).assertTextEquals("Personnalisé")
        onNodeWithTag(ToolPaneTags.Close).assertContentDescriptionEquals("Close Paper Size").performClick()
    }
}
