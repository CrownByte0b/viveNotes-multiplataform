package com.vivenotes.desktop

import com.vivenotes.data.NotesLibrary
import com.vivenotes.data.PageLoad
import com.vivenotes.model.plainText
import com.vivenotes.richtext.TextSelection
import com.vivenotes.workspace.PageContent
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The process's notes as the windows use them: opened at launch, written and closed at exit. */
class DesktopNotesTest {

    private val directory: File = Files.createTempDirectory("desktop-notes").toFile()

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    /** Closing inside the autosave delay is the case the delay would otherwise lose. */
    @Test
    fun closingWritesAnEditMadeJustBeforeIt() = runBlocking<Unit> {
        // This test's thread stands in for the UI thread the session is confined to.
        val ui = CoroutineScope(coroutineContext + SupervisorJob())
        val notes = DesktopNotes(NotesLibrary.open(directory), ui)
        notes.start()
        val open = openPage(notes)

        notes.session.update { it.editSelectedText(LAST_WORDS, TextSelection(LAST_WORDS.length)) }
        close(notes)
        ui.cancel()

        NotesLibrary.open(directory).use { library ->
            val load = library.repository.loadDoc(open.selectedPageId)
            assertIs<PageLoad.Loaded>(load)
            assertEquals(LAST_WORDS, load.doc.plainText())
        }
    }

    @Test
    fun theFirstUpkeepPassRunsAtLaunch() = runBlocking<Unit> {
        val ui = CoroutineScope(coroutineContext + SupervisorJob())
        val notes = DesktopNotes(NotesLibrary.open(directory), ui)

        notes.start()
        withTimeout(10_000) {
            while (File(directory, "database_backups").listFiles().orEmpty().none { it.extension == "db" }) delay(20)
        }
        close(notes)
        ui.cancel()
    }

    @Test
    fun askingToCloseAgainWhileClosingClosesOnce() = runBlocking<Unit> {
        val ui = CoroutineScope(coroutineContext + SupervisorJob())
        val notes = DesktopNotes(NotesLibrary.open(directory), ui)
        notes.start()
        openPage(notes)
        var closes = 0
        val closed = CompletableDeferred<Unit>()

        notes.close { closes++; closed.complete(Unit) }
        notes.close { closes++ }
        closed.await()
        ui.cancel()

        assertEquals(1, closes)
    }

    private suspend fun openPage(notes: DesktopNotes): WorkspaceState = withTimeout(10_000) {
        notes.session.state.first { it?.selectedPage?.content == PageContent.Loaded }!!
    }

    private suspend fun close(notes: DesktopNotes) {
        val closed = CompletableDeferred<Unit>()
        notes.close { closed.complete(Unit) }
        withTimeout(10_000) { closed.await() }
    }

    private companion object {
        const val LAST_WORDS = "typed just before closing"
    }
}
