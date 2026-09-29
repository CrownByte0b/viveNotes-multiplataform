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
import com.vivenotes.ui.shell.WorkspaceScreen
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
            onNodeWithText("About Vive Notes").assertIsDisplayed()
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

            onNodeWithTag(AboutTags.Close).performClick()
            onNodeWithTag(AboutTags.Dialog).assertDoesNotExist()
            onNodeWithTag(AboutTags.Open).performClick()
            onNodeWithTag(AboutTags.Dialog).assertIsDisplayed()
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
