package com.vivenotes

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.data.NotebookFiles
import com.vivenotes.data.NotebookTransferManager
import com.vivenotes.data.NotesLibrary
import com.vivenotes.data.NotesStore
import com.vivenotes.data.PageLoad
import com.vivenotes.data.execute
import com.vivenotes.model.plainText
import com.vivenotes.ui.navigation.NavigationTestTags
import com.vivenotes.ui.shell.UnreadablePageMessage
import com.vivenotes.ui.ribbon.file.FileRibbonTags
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.FakeNotesStore
import com.vivenotes.workspace.NEW_SECTION_NAME
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

    @Test
    fun renamesAndDeletesMadeFromTheMenusAreStored() {
        NotesLibrary.open(directory).use { library ->
            val (sectionId, scratch) = runBlocking {
                library.repository.seedIfEmpty()
                val section = library.repository.observeTree().first().single().liveSections.first()
                section.id to library.repository.createPage(section.id, "Scratch")
            }
            runDesktopComposeUiTest(width = 1400, height = 900) {
                val app = showApp(library.repository)
                awaitOpenPage()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag(WorkspaceTestTags.page(scratch)).fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithTag(WorkspaceTestTags.section(sectionId)).performMouseInput { rightClick(center) }
                onNodeWithTag(NavigationTestTags.Rename).performClick()
                onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("Inbox")
                onNodeWithTag(NavigationTestTags.ConfirmRename).performClick()

                onNodeWithTag(WorkspaceTestTags.page(scratch)).performMouseInput { rightClick(center) }
                onNodeWithTag(NavigationTestTags.Delete).performClick()
                onNodeWithTag(NavigationTestTags.ConfirmDelete).performClick()
                onNodeWithTag(WorkspaceTestTags.page(scratch)).assertDoesNotExist()
                app.close()
            }
            runBlocking {
                val section = library.repository.observeTree().first().single().liveSections.first { it.id == sectionId }
                assertEquals("Inbox", section.name)
                assertEquals(listOf("Welcome"), library.repository.observePages(section.id).first().map { it.title })
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

    /**
     * The notebook pane over real storage: a section created, a page dragged, a notebook folded and
     * another created are all in the database when the app closes.
     */
    @Test
    fun notebooksAndSectionsMadeFoldedAndReorderedInThePaneAreStored() {
        NotesLibrary.open(directory).use { library ->
            val notebookId = runBlocking {
                library.repository.seedIfEmpty()
                library.repository.observeTree().first().single().notebook.id
            }
            var first = ""
            var second = ""
            runDesktopComposeUiTest(width = 1400, height = 900) {
                val app = showApp(library.repository)
                awaitOpenPage()

                onNodeWithTag(NavigationTestTags.addSection(notebookId)).performClick()
                onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("Reading")
                onNodeWithTag(NavigationTestTags.ConfirmCreate).performClick()
                waitUntil(timeoutMillis = 10_000) {
                    val state = app.session.state.value
                    state?.selectedSection?.name == "Reading" && state.selectedPage != null
                }
                first = app.session.state.value!!.selectedPageId
                onNodeWithTag(WorkspaceTestTags.AddPage).performClick()
                waitUntil(timeoutMillis = 10_000) { app.session.state.value?.selectedSection?.pages?.size == 2 }
                second = app.session.state.value!!.selectedSection!!.pages.last().id

                val pitch = onNodeWithTag(WorkspaceTestTags.page(second)).fetchSemanticsNode().boundsInRoot.top -
                    onNodeWithTag(WorkspaceTestTags.page(first)).fetchSemanticsNode().boundsInRoot.top
                onNodeWithTag(NavigationTestTags.pageDrag(first), useUnmergedTree = true).performMouseInput {
                    val distance = pitch * 1.4f
                    moveTo(center)
                    press()
                    repeat(12) { moveBy(Offset(0f, distance / 12)) }
                    release()
                }
                waitUntil(timeoutMillis = 10_000) {
                    app.session.state.value?.selectedSection?.pages?.map { it.id } == listOf(second, first)
                }

                onNodeWithTag(NavigationTestTags.notebook(notebookId)).performClick()
                onNodeWithTag(NavigationTestTags.AddNotebook).performClick()
                onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("Travel")
                onNodeWithTag(NavigationTestTags.ConfirmCreate).performClick()
                waitUntil(timeoutMillis = 10_000) { app.session.state.value?.selectedNotebook?.name == "Travel" }
                app.close()
            }
            runBlocking {
                val tree = library.repository.observeTree().first()
                assertEquals(listOf(false, true), tree.map { it.notebook.expanded })
                assertEquals(listOf(NEW_SECTION_NAME), tree.last().liveSections.map { it.name })
                val reading = tree.first().liveSections.single { it.name == "Reading" }
                val pages = library.repository.observePages(reading.id).first()
                assertEquals(listOf(second, first), pages.map { it.id }, "the dragged order")
                assertEquals("Travel", tree.last().notebook.name)
            }
        }
    }

    /**
     * A notebook moves between two installations through the File tab: exported to a file from one
     * real library, imported into a fresh one, where it replaces the untouched starter notebook and
     * opens on the page that was written.
     */
    @Test
    fun aNotebookExportedFromTheFileTabImportsIntoAnotherLibrary() {
        val file = File(directory, "exports/Field notes.vive").apply { parentFile.mkdirs() }
        NotesLibrary.open(File(directory, "first")).use { library ->
            val files = FileNotebookFiles(library.transfers, file)
            runDesktopComposeUiTest(width = 1400, height = 900) {
                val app = showApp(library.repository, files)
                awaitOpenPage()
                onNodeWithTag(WorkspaceTestTags.TitleEditor).performTextReplacement("Herons")
                onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextReplacement("Two at dawn by the reeds")

                onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.File)).performClick()
                onNodeWithTag(FileRibbonTags.ExportNotebook).performClick()
                waitUntil(timeoutMillis = 10_000) { app.session.state.value?.notebookTransfer?.message != null }
                onNodeWithTag(FileRibbonTags.TransferDialog)
                    .assertTextContains("My Notebook was exported as a .vive notebook.")
                onNodeWithTag(FileRibbonTags.TransferOk).performClick()
                app.close()
            }
            assertEquals(listOf("My Notebook.vive"), files.suggestedNames)
        }
        assertTrue(file.length() > 0)

        NotesLibrary.open(File(directory, "second")).use { library ->
            runDesktopComposeUiTest(width = 1400, height = 900) {
                val app = showApp(library.repository, FileNotebookFiles(library.transfers, file))
                awaitOpenPage()
                onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.File)).performClick()
                onNodeWithTag(FileRibbonTags.ImportNotebook).performClick()
                waitUntil(timeoutMillis = 10_000) {
                    app.session.state.value?.let { it.notebookTransfer.message != null && it.selectedPage?.title == "Herons" } == true
                }
                onNodeWithTag(FileRibbonTags.TransferDialog).assertTextContains("My Notebook was imported.")
                onNodeWithTag(FileRibbonTags.TransferOk).performClick()
                awaitOpenPage()

                onNodeWithTag(WorkspaceTestTags.TitleEditor).assertTextContains("Herons")
                onNodeWithTag(WorkspaceTestTags.BodyEditor).assertTextContains("Two at dawn by the reeds")
                assertEquals(1, app.session.state.value!!.notebooks.size, "the untouched starter was replaced")
            }
        }
    }

    /** `.vive` files at one fixed path: the dialogs answered, the real transfer behind them. */
    private class FileNotebookFiles(private val transfers: NotebookTransferManager, private val file: File) : NotebookFiles {
        val suggestedNames = mutableListOf<String>()
        override suspend fun chooseExportDestination(suggestedName: String): String {
            suggestedNames += suggestedName
            return file.path
        }
        override suspend fun chooseImportSource(): String = file.path
        override suspend fun export(notebookId: String, destination: String) =
            transfers.exportNotebook(notebookId, File(destination))
        override suspend fun import(source: String) = transfers.importNotebook(File(source))
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
    private fun ComposeUiTest.showApp(store: NotesStore, notebookFiles: NotebookFiles? = null): ShownApp {
        lateinit var shown: ShownApp
        setContent {
            val scope = rememberCoroutineScope()
            val session = remember { WorkspaceSession(store, scope, ::formatCreated) }
            shown = remember { ShownApp(this, scope, session) }
            LaunchedEffect(session) { session.start() }
            App(session, notebookFiles = notebookFiles)
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
