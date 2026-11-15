package com.vivenotes.ui.ribbon.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.update.AppUpdates
import com.vivenotes.ui.update.AvailableUpdate
import com.vivenotes.ui.update.UpdateCheck
import com.vivenotes.ui.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AboutDialogTest {
    @Test
    fun aboutShowsBuildVersionAndLicenseAndCanCloseAndReopen() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val opened = mutableListOf<String>()
            setContent {
                var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                }) {
                    WorkspaceScreen(workspace, { workspace = it(workspace) }, appVersion = "0.1.0")
                }
            }

            onNodeWithTag(AboutTags.Open).performClick()
            onNodeWithTag(AboutTags.Dialog).assertIsDisplayed()
            onNodeWithText("About Vive Notes [KMP]").assertIsDisplayed()
            onNodeWithText("Vive Notes [KMP]").assertIsDisplayed()
            onNodeWithText("Version 0.1.0").assertIsDisplayed()
            onNodeWithText("CROWNBYTE LLC · Source First License 1.1").assertIsDisplayed()
            onNodeWithTag(AboutTags.Icon, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(AboutTags.GitHubIcon, useUnmergedTree = true).assertIsDisplayed()
            val heart = onNodeWithTag(AboutTags.SupportIcon, useUnmergedTree = true)
                .assertIsDisplayed().captureToImage().toPixelMap()
            val redPixels = (0 until heart.height).sumOf { y ->
                (0 until heart.width).count { x ->
                    val pixel = heart[x, y]
                    pixel.alpha > 0.5f && pixel.red > 0.7f && pixel.green < 0.5f && pixel.blue < 0.5f
                }
            }
            assertTrue(redPixels > 20, "The support icon should render as a red heart")
            onNodeWithTag(AboutTags.GitHub).assertIsDisplayed().performClick()
            onNodeWithTag(AboutTags.Website).assertIsDisplayed().performClick()
            onNodeWithTag(AboutTags.Support).assertIsDisplayed().performClick()
            runOnIdle {
                assertEquals(listOf(AboutLinks.GitHub, AboutLinks.Website, AboutLinks.Support), opened)
            }

            onNodeWithTag(AboutTags.Updates, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(AboutTags.Close).performClick()
            onNodeWithTag(AboutTags.Dialog).assertDoesNotExist()
            onNodeWithTag(AboutTags.Open).performClick()
            onNodeWithTag(AboutTags.Dialog).assertIsDisplayed()
        }

    /** The installations that update themselves show what the last check found and can check again. */
    @Test
    fun updatesShowWhatTheLastCheckFoundAndCheckAgain() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val updates = FakeUpdates()
        setContent {
            var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
            WorkspaceScreen(workspace, { workspace = it(workspace) }, appVersion = "0.1.0", updates = updates)
        }
        onNodeWithTag(AboutTags.Open).performClick()
        onNodeWithTag(AboutTags.Updates, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Updates").assertIsDisplayed()

        onNodeWithText("Vive Notes looks for a newer version each time it opens.").assertIsDisplayed()
        onNodeWithTag(AboutTags.UpdateButton).assertTextEquals("Check for updates").assertIsEnabled().performClick()
        runOnIdle { assertEquals(1, updates.checks) }

        updates.check.value = UpdateCheck.Checking
        onNodeWithText("Checking for updates…").assertIsDisplayed()
        onNodeWithTag(AboutTags.UpdateButton).assertIsNotEnabled()

        updates.check.value = UpdateCheck.UpToDate
        onNodeWithText("Vive Notes is up to date").assertIsDisplayed()
        onNodeWithText("Version 0.1.0 is the newest release.").assertIsDisplayed()
        onNodeWithTag(AboutTags.UpdateButton).assertTextEquals("Check again")

        updates.check.value = UpdateCheck.Failed("The download server could not be reached. Check the connection and try again.")
        onNodeWithText("Could not check for updates").assertIsDisplayed()
        onNodeWithText("The download server could not be reached. Check the connection and try again.").assertIsDisplayed()
        onNodeWithTag(AboutTags.UpdateButton).assertTextEquals("Check again").performClick()
        runOnIdle { assertEquals(2, updates.checks) }

        // A version the user skipped at launch is still named here, and can be looked at again.
        updates.check.value = UpdateCheck.Found("0.2.0")
        onNodeWithText("Vive Notes 0.2.0 is available").assertIsDisplayed()
        onNodeWithText("You have version 0.1.0.").assertIsDisplayed()
        onNodeWithTag(AboutTags.UpdateButton).assertTextEquals("View update").performClick()
        runOnIdle { assertEquals(3, updates.checks) }
    }

    /** Keys and focus belong to the update dialog once it opens, so About steps aside. */
    @Test
    fun aboutClosesWhenTheUpdateDialogTakesOver() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val updates = FakeUpdates()
        setContent {
            var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
            WorkspaceScreen(workspace, { workspace = it(workspace) }, appVersion = "0.1.0", updates = updates)
        }
        onNodeWithTag(AboutTags.Open).performClick()
        onNodeWithTag(AboutTags.Dialog).assertIsDisplayed()

        updates.state.value = UpdateState.Available(AvailableUpdate("0.2.0", "0.1.0"))
        onNodeWithTag(AboutTags.Dialog).assertDoesNotExist()
    }

    @Test
    fun theUpdatesRowKeepsCloseVisibleInAShortWindow() = runDesktopComposeUiTest(width = 800, height = 520) {
        val updates = FakeUpdates().apply { check.value = UpdateCheck.Failed("Server returned HTTP 403") }
        setContent {
            var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
            WorkspaceScreen(workspace, { workspace = it(workspace) }, appVersion = "0.1.0", updates = updates)
        }
        onNodeWithTag(AboutTags.Open).performClick()
        onNodeWithTag(AboutTags.Close).assertIsDisplayed()
        val dialog = onNodeWithTag(AboutTags.Dialog).fetchSemanticsNode().boundsInRoot
        assertTrue(dialog.top >= 0f && dialog.bottom <= 520f, "About dialog outside the window: $dialog")
    }

    private class FakeUpdates : AppUpdates {
        override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
        override val check = MutableStateFlow<UpdateCheck>(UpdateCheck.NotChecked)
        var checks = 0
        override fun checkNow() { checks++ }
        override fun install() = Unit
        override fun cancel() = Unit
        override fun later() = Unit
        override fun skip() = Unit
    }

    @Test
    fun closeRemainsVisibleInAShortWindow() = runDesktopComposeUiTest(width = 800, height = 520) {
        setContent {
            var workspace by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
            WorkspaceScreen(workspace, { workspace = it(workspace) }, appVersion = "0.1.0")
        }
        onNodeWithTag(AboutTags.Open).performClick()
        onNodeWithTag(AboutTags.Close).assertIsDisplayed()
        val dialog = onNodeWithTag(AboutTags.Dialog).fetchSemanticsNode().boundsInRoot
        assertTrue(dialog.top >= 0f && dialog.bottom <= 520f, "About dialog outside the window: $dialog")
        onNodeWithTag(AboutTags.Close).performClick()
        onNodeWithTag(AboutTags.Dialog).assertDoesNotExist()
    }
}
