package com.vivenotes

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.data.NotesLibrary
import com.vivenotes.data.NotesStore
import com.vivenotes.data.PageLoad
import com.vivenotes.data.execute
import com.vivenotes.model.plainText
import com.vivenotes.ui.shell.UnreadablePageMessage
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.workspace.FakeNotesStore
import com.vivenotes.workspace.WorkspaceSession
import com.vivenotes.workspace.formatCreated
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The application over real storage: what is typed survives closing, through the actual window
 * content, a real SQLite file and a fresh session reading it back.
 */
@OptIn(ExperimentalTestApi::class)
class AppTest {

    private val directory: File = Files.createTempDirectory("app-test").toFile()

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun whatIsTypedIsThereWhenTheAppIsOpenedAgain() {
        NotesLibrary.open(directory).use { library ->
            runDesktopComposeUiTest(width = 1400, height = 900) {
                val app = showApp(library.repository)
                awaitOpenPage()

                onNodeWithTag(WorkspaceTestTags.TitleEditor).performTextReplacement("Groceries")
                onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextReplacement("Milk and eggs")
                app.close()
            }
            val welcome = runBlocking { firstPage(library) }
            assertEquals("Groceries" to "Milk and eggs", welcome.title to welcome.preview)
        }

        NotesLibrary.open(directory).use { library ->
            runDesktopComposeUiTest(width = 1400, height = 900) {
                showApp(library.repository)
                awaitOpenPage()

                onNodeWithTag(WorkspaceTestTags.TitleEditor).assertTextContains("Groceries")
                onNodeWithTag(WorkspaceTestTags.BodyEditor).assertTextContains("Milk and eggs")
            }
        }
    }

    @Test
    fun aPageAddedInTheAppIsStoredWithWhatWasWrittenOnIt() {
        NotesLibrary.open(directory).use { library ->
            runDesktopComposeUiTest(width = 1400, height = 900) {
                val app = showApp(library.repository)
                awaitOpenPage()

                onNodeWithTag(WorkspaceTestTags.AddPage).performClick()
                waitUntil(timeoutMillis = 10_000) {
                    app.session.state.value?.selectedSection?.pages?.size == 2 &&
                        app.session.state.value?.selectedPage?.title == "" &&
                        onAllNodesWithTag(WorkspaceTestTags.BodyEditor).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextReplacement("Second page")
                app.close()
            }
            runBlocking {
                val section = library.repository.observeTree().first().single().liveSections.first()
                val pages = library.repository.observePages(section.id).first()
                assertEquals(listOf("Welcome", ""), pages.map { it.title })
                val load = library.repository.loadDoc(pages.last().id)
                assertIs<PageLoad.Loaded>(load)
                assertEquals("Second page", load.doc.plainText())
            }
        }
    }

    /** The Android promise: a body that will not decode is never replaced by the blank shown for it. */
    @Test
    fun anUnreadablePageIsShownReadOnlyAndItsStoredBodyIsKept() {
        NotesLibrary.open(directory).use { library ->
            val pageId = runBlocking {
                library.repository.seedIfEmpty()
                firstPage(library).id.also { id ->
                    library.database.execute("UPDATE page_content SET docJson = ? WHERE pageId = ?", "{broken", id)
                }
            }
            runDesktopComposeUiTest(width = 1400, height = 900) {
                val app = showApp(library.repository)
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag(WorkspaceTestTags.UnreadablePage).fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithTag(WorkspaceTestTags.UnreadablePage).assertTextContains(UnreadablePageMessage)
                onNodeWithTag(WorkspaceTestTags.BodyEditor).assertDoesNotExist()
                app.close()
            }
            assertEquals("{broken", runBlocking { library.database.pageContentDao().byId(pageId)!!.docJson })
        }
    }

    @Test
    fun theWorkspaceWaitsForStorageBehindALoadingIndicator() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val store = FakeNotesStore().apply { seedGate = CompletableDeferred() }
        showApp(store)

        onNodeWithTag(AppTestTags.Opening).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.PageCanvas).assertDoesNotExist()

        store.seedGate!!.complete(Unit)
        awaitOpenPage()
        onNodeWithTag(AppTestTags.Opening).assertDoesNotExist()
    }

    @Test
    fun aFailedSaveIsShownUntilASaveSucceeds() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val store = FakeNotesStore().apply { saveFailure = IllegalStateException("The disk is full") }
        val app = showApp(store)
        awaitOpenPage()

        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextReplacement("Unsaved")
        app.close()

        onNodeWithTag(WorkspaceTestTags.StorageError).assertTextContains("The disk is full", substring = true)
        store.saveFailure = null
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextReplacement("Saved now")
        app.close()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithTag(WorkspaceTestTags.StorageError).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(store.saves.single().second.plainText() == "Saved now")
    }

    private class ShownApp(val test: ComposeUiTest, val scope: CoroutineScope, val session: WorkspaceSession) {
        /** What closing the window does first: every pending edit reaches storage. */
        fun close() {
            var flushed = false
            scope.launch {
                session.flush()
                flushed = true
            }
            test.waitUntil(timeoutMillis = 10_000) { flushed }
        }
    }

    /** The app as a window shows it, with the session confined to the composition's thread. */
    private fun ComposeUiTest.showApp(store: NotesStore): ShownApp {
        lateinit var shown: ShownApp
        setContent {
            val scope = rememberCoroutineScope()
            val session = remember { WorkspaceSession(store, scope, ::formatCreated) }
            shown = remember { ShownApp(this, scope, session) }
            LaunchedEffect(session) { session.start() }
            App(session)
        }
        waitForIdle()
        return shown
    }

    private fun ComposeUiTest.awaitOpenPage() = waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithTag(WorkspaceTestTags.BodyEditor).fetchSemanticsNodes().isNotEmpty()
    }

    private suspend fun firstPage(library: NotesLibrary) = library.repository.observePages(
        library.repository.observeTree().first().first().liveSections.first().id,
    ).first().first()
}
