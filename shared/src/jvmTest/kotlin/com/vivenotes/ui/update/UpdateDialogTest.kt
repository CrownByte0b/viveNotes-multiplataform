package com.vivenotes.ui.update

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The update dialog as each state shows it, and where each of its buttons goes. */
@OptIn(ExperimentalTestApi::class)
class UpdateDialogTest {
    private val update = AvailableUpdate("0.2.0", "0.1.0", "• Line spacing for paragraphs\n• Drag pictures onto a page", 229_876_426)

    @Test
    fun anAvailableUpdateNamesBothVersionsItsNotesAndWhatInstallingDoes() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val updates = FakeUpdates(UpdateState.Available(update))
        setContent { ViveNotesTheme { UpdateDialog(updates) } }

        onNodeWithTag(UpdateDialogTags.Dialog).assertIsDisplayed()
        onNodeWithText("Update available").assertIsDisplayed()
        onNodeWithText("Vive Notes 0.2.0 is ready to download. You have version 0.1.0.").assertIsDisplayed()
        onNodeWithText("What’s new").assertIsDisplayed()
        onNodeWithTag(UpdateDialogTags.Notes, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("• Line spacing for paragraphs\n• Drag pictures onto a page", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Vive Notes saves your notes, closes to install the update and opens again when it is done.")
            .assertIsDisplayed()

        onNodeWithTag(UpdateDialogTags.Install).assertIsDisplayed().performClick()
        onNodeWithTag(UpdateDialogTags.Later).performClick()
        onNodeWithTag(UpdateDialogTags.Skip).performClick()
        runOnIdle { assertEquals(listOf("install", "later", "skip"), updates.calls) }
    }

    @Test
    fun notesAreLeftOutWhenTheReleaseHasNone() = runDesktopComposeUiTest(width = 1200, height = 800) {
        setContent { ViveNotesTheme { UpdateDialog(FakeUpdates(UpdateState.Available(update.copy(notes = " ")))) } }

        onNodeWithTag(UpdateDialogTags.Dialog).assertIsDisplayed()
        onNodeWithText("What’s new").assertDoesNotExist()
        onNodeWithTag(UpdateDialogTags.Notes, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun escapePutsTheOfferOffButNotADownloadUnderWay() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val updates = FakeUpdates(UpdateState.Available(update))
        setContent { ViveNotesTheme { UpdateDialog(updates) } }

        onNodeWithTag(UpdateDialogTags.Install).performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag(UpdateDialogTags.Install).performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(listOf("later"), updates.calls) }

        updates.state.value = UpdateState.Downloading(update, 1_000_000)
        onNodeWithTag(UpdateDialogTags.Cancel).performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag(UpdateDialogTags.Cancel).performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(listOf("later"), updates.calls, "Escape must not throw a download away") }
        onNodeWithTag(UpdateDialogTags.Dialog).assertIsDisplayed()
    }

    @Test
    fun theDownloadShowsItsProgressAndCanBeCancelled() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val updates = FakeUpdates(UpdateState.Downloading(update, 12_345_678))
        setContent { ViveNotesTheme { UpdateDialog(updates) } }

        onNodeWithText("Downloading update").assertIsDisplayed()
        onNodeWithText("Downloading Vive Notes 0.2.0…").assertIsDisplayed()
        onNodeWithText("12.3 MB of 230 MB").assertIsDisplayed()
        onNodeWithTag(UpdateDialogTags.Progress).assertIsDisplayed()
        onNodeWithTag(UpdateDialogTags.Install).assertDoesNotExist()

        onNodeWithTag(UpdateDialogTags.Cancel).performClick()
        runOnIdle { assertEquals(listOf("cancel"), updates.calls) }
    }

    @Test
    fun installingSaysTheAppIsClosingAndOffersNothingToClick() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val updates = FakeUpdates(UpdateState.Installing(update))
        setContent { ViveNotesTheme { UpdateDialog(updates) } }

        onNodeWithText("Installing update").assertIsDisplayed()
        onNodeWithText("Saving your notes and closing Vive Notes. The installer starts once it has closed.").assertIsDisplayed()
        listOf(UpdateDialogTags.Install, UpdateDialogTags.Later, UpdateDialogTags.Skip, UpdateDialogTags.Cancel,
            UpdateDialogTags.Retry).forEach { onNodeWithTag(it).assertDoesNotExist() }
    }

    @Test
    fun aFailureSaysWhyAndOffersRetry() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val updates = FakeUpdates(UpdateState.Failed(update, "ViveNotes-0.2.0-x64.msi failed verification"))
        setContent { ViveNotesTheme { UpdateDialog(updates) } }

        onNodeWithText("Update failed").assertIsDisplayed()
        onNodeWithText("Vive Notes 0.2.0 was not installed. You can keep using this version and try again.").assertIsDisplayed()
        onNodeWithText("ViveNotes-0.2.0-x64.msi failed verification").assertIsDisplayed()

        onNodeWithTag(UpdateDialogTags.Retry).performClick()
        onNodeWithTag(UpdateDialogTags.Later).performClick()
        runOnIdle { assertEquals(listOf("install", "later"), updates.calls) }
    }

    @Test
    fun idleShowsNothingAndTheDialogFollowsTheState() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val updates = FakeUpdates(UpdateState.Idle)
        setContent { ViveNotesTheme { UpdateDialog(updates) } }

        onNodeWithTag(UpdateDialogTags.Dialog).assertDoesNotExist()
        updates.state.value = UpdateState.Available(update)
        onNodeWithTag(UpdateDialogTags.Dialog).assertIsDisplayed()
        updates.state.value = UpdateState.Idle
        onNodeWithTag(UpdateDialogTags.Dialog).assertDoesNotExist()
    }

    @Test
    fun everyButtonStaysInsideAShortWindow() = runDesktopComposeUiTest(width = 760, height = 480) {
        setContent { ViveNotesTheme { UpdateDialog(FakeUpdates(UpdateState.Available(update.copy(notes = "• change\n".repeat(60))))) } }

        val dialog = onNodeWithTag(UpdateDialogTags.Dialog).fetchSemanticsNode().boundsInRoot
        assertTrue(dialog.top >= 0f && dialog.bottom <= 480f, "Update dialog outside the window: $dialog")
        listOf(UpdateDialogTags.Skip, UpdateDialogTags.Later, UpdateDialogTags.Install).forEach {
            onNodeWithTag(it).assertIsDisplayed()
        }
    }

    private class FakeUpdates(initial: UpdateState) : AppUpdates {
        override val state = MutableStateFlow(initial)
        val calls = mutableListOf<String>()
        override fun install() { calls += "install" }
        override fun cancel() { calls += "cancel" }
        override fun later() { calls += "later" }
        override fun skip() { calls += "skip" }
    }
}
