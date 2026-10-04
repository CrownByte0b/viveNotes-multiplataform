package com.vivenotes.desktop

import com.vivenotes.data.NotesLibrary
import com.vivenotes.data.InkEdit
import com.vivenotes.data.InkWriter
import com.vivenotes.data.PageLoad
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.plainText
import com.vivenotes.richtext.TextSelection
import com.vivenotes.workspace.InputSettings
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.KeyChord
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.PageContent
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.ShortcutKey
import com.vivenotes.workspace.TabsLayout
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.ShapeToolSettings
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.LineType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The process's notes as the windows use them: opened at launch, written and closed at exit. */
class DesktopNotesTest {

    @Test
    fun shapeChoicesSurviveRestartAndSeedTheNextWorkspace() = runBlocking<Unit> {
        val file = File(directory, "config/shape.properties")
        val ui = CoroutineScope(coroutineContext + SupervisorJob())
        val notes = DesktopNotes(NotesLibrary.open(directory), ui, shapeStore = ShapeSettingsFile(file))
        notes.start()
        openPage(notes)
        val chosen = ShapeToolSettings(ShapeKind.Cone, LineType.Dashed, 6,
            0xFF3584E4.toInt(), 0xFFE01B24.toInt(), colorFollowsTheme = false)
        notes.updateShapeSettings(chosen)
        assertEquals(chosen, ShapeSettingsFile(file).load())
        close(notes)
        ui.cancel()

        val restartedUi = CoroutineScope(coroutineContext + SupervisorJob())
        val restarted = DesktopNotes(NotesLibrary.open(directory), restartedUi,
            shapeStore = ShapeSettingsFile(file))
        restarted.start()
        assertEquals(chosen, openPage(restarted).shapeSettings)
        close(restarted)
        restartedUi.cancel()
    }

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

        notes.session.update { state ->
            state.focusTextBox(state.bodyTextOutline!!.id).editSelectedText(LAST_WORDS, TextSelection(LAST_WORDS.length))
        }
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

    @Test
    fun failedInkSaveKeepsTheWindowAndDatabaseAliveUntilCloseCanRetry() = runBlocking<Unit> {
        val ui = CoroutineScope(coroutineContext + SupervisorJob())
        val library = NotesLibrary.open(directory)
        val writer = RetryingInkWriter(library)
        val deferredClose = CompletableDeferred<Unit>()
        val logs = mutableListOf<String>()
        val notes = DesktopNotes(library, ui, inkSource = writer, log = DebugLog(true) { event ->
            logs += event
            if ("close deferred" in event) deferredClose.complete(Unit)
        })
        notes.start()
        val opened = withTimeout(10_000) {
            notes.session.state.first { it?.selectedPage?.inkReady == true }!!
        }
        val row = inkRow(opened.selectedPageId)
        notes.session.update { state ->
            state.toggleInkTool(InkTool.Pen).applyInkEdit(InkEdit.AddStroke(row),
                checkNotNull(state.selectedPage!!.ink))
        }
        var closes = 0
        notes.close { closes++ }
        withTimeout(10_000) { deferredClose.await() }

        assertEquals(0, closes, "a failed stroke save must retain the window")
        assertEquals(row, (notes.session.state.value!!.pendingInkEdits.single().edit as InkEdit.AddStroke).row)
        assertNotNull(notes.session.state.value!!.storageError)
        assertTrue(library.repository.inkFor(row.pageId).none { it.id == row.id }, "failed ink must remain pending")
        assertTrue(logs.none { row.id in it }, "close diagnostics must not log document row identifiers")

        // The retained session still accepts edits, and a later close request retries its pending
        // ink before shutting down. Keep the existing document autosave/close behavior covered too.
        notes.session.update { state ->
            state.focusTextBox(state.bodyTextOutline!!.id).editSelectedText(LAST_WORDS, TextSelection(LAST_WORDS.length))
        }
        writer.failWrites = false
        val closed = CompletableDeferred<Unit>()
        notes.close { closes++; closed.complete(Unit) }
        withTimeout(10_000) { closed.await() }
        assertEquals(1, closes)
        assertTrue(notes.session.state.value!!.pendingInkEdits.isEmpty())
        ui.cancel()

        NotesLibrary.open(directory).use { reopened ->
            assertContentEquals(row.points, reopened.repository.inkFor(row.pageId).single { it.id == row.id }.points)
            assertEquals(LAST_WORDS, assertIs<PageLoad.Loaded>(reopened.repository.loadDoc(row.pageId)).doc.plainText())
        }
    }

    @Test
    fun aPreferencesWriteFailingBeforeFlushAlsoRetainsPendingInkAndAllowsRetry() = runBlocking<Unit> {
        val earlyFailure = CompletableDeferred<Throwable>()
        val ui = CoroutineScope(coroutineContext + SupervisorJob() +
            CoroutineExceptionHandler { _, failure -> earlyFailure.complete(failure) })
        val library = NotesLibrary.open(directory)
        val writer = RetryingInkWriter(library)
        val blockedParent = File(directory, "blocked-config").apply { writeText("a file occupies the directory path") }
        val settings = ViewSettingsFile(File(blockedParent, "view.properties"))
        val notes = DesktopNotes(library, ui, inkSource = writer, viewStore = settings)
        notes.start()
        val opened = withTimeout(10_000) {
            notes.session.state.first { it?.selectedPage?.inkReady == true }!!
        }
        val row = inkRow(opened.selectedPageId)
        notes.session.update { state ->
            state.toggleInkTool(InkTool.Pen).applyInkEdit(InkEdit.AddStroke(row),
                checkNotNull(state.selectedPage!!.ink))
        }
        notes.updateViewSettings(ViewSettings(zoom = 1.5f))
        var closes = 0
        notes.close { closes++ }
        assertIs<IOException>(withTimeout(10_000) { earlyFailure.await() })

        assertEquals(0, closes, "preferences failure must not discard waiting ink")
        assertEquals(1, notes.session.state.value!!.pendingInkEdits.size)
        assertIs<PageLoad.Loaded>(library.repository.loadDoc(row.pageId), "database must remain available")
        assertTrue(blockedParent.delete())
        writer.failWrites = false
        close(notes)
        ui.cancel()

        NotesLibrary.open(directory).use { reopened ->
            assertContentEquals(row.points, reopened.repository.inkFor(row.pageId).single { it.id == row.id }.points)
        }
    }

    /** Ctrl+wheel reports a zoom per notch; the file is written once the gesture settles. */
    @Test
    fun viewSettingsAreShownAtOnceAndWrittenOnceTheyStopChanging() = runBlocking<Unit> {
        val ui = CoroutineScope(coroutineContext + SupervisorJob())
        val file = File(directory, "config/view.properties")
        val notes = DesktopNotes(NotesLibrary.open(directory), ui, viewStore = ViewSettingsFile(file))
        notes.start()

        listOf(1.1f, 1.21f, 1.33f).forEach { notes.updateViewSettings(ViewSettings(zoom = it)) }
        assertEquals(1.33f, notes.viewSettings.zoom)
        assertFalse(file.exists(), "a zoom still changing was written")
        delay(DesktopNotes.VIEW_SAVE_DELAY + 300.milliseconds)
        assertEquals(1.33f, ViewSettingsFile(file).load().zoom)

        close(notes)
        ui.cancel()
    }

    @Test
    fun closingWritesViewSettingsStillWaitingToBeSaved() = runBlocking<Unit> {
        val ui = CoroutineScope(coroutineContext + SupervisorJob())
        val file = File(directory, "config/view.properties")
        val notes = DesktopNotes(NotesLibrary.open(directory), ui, viewStore = ViewSettingsFile(file))
        notes.start()
        openPage(notes)

        notes.updateViewSettings(ViewSettings(tabsLayout = TabsLayout.Horizontal))
        close(notes)
        ui.cancel()

        assertEquals(TabsLayout.Horizontal, ViewSettingsFile(file).load().tabsLayout)
    }

    /** A changed shortcut is written at once and is in force the next time the app opens. */
    @Test
    fun changedShortcutsAreWrittenAndComeBackAtTheNextLaunch() = runBlocking<Unit> {
        val ui = CoroutineScope(coroutineContext + SupervisorJob())
        val file = File(directory, "config/keyboard.properties")
        val notes = DesktopNotes(NotesLibrary.open(directory), ui, keyStore = KeyBindingsFile(file))
        notes.start()
        assertEquals(KeyBindings.Default, notes.keyBindings)

        val changed = KeyBindings.Default.rebind(ShortcutAction.NewPage, KeyChord(ShortcutKey.T, ctrl = true))
        notes.updateKeyBindings(changed)
        assertEquals(changed, notes.keyBindings)
        assertEquals(changed, KeyBindingsFile(file).load())
        close(notes)
        ui.cancel()

        val reopenedUi = CoroutineScope(coroutineContext + SupervisorJob())
        val reopened = DesktopNotes(NotesLibrary.open(directory), reopenedUi, keyStore = KeyBindingsFile(file))
        assertEquals(changed, reopened.keyBindings)
        reopened.start()
        close(reopened)
        reopenedUi.cancel()
    }

    /** Settings → Hardware's finger choice is written at once and is in force at the next launch. */
    @Test
    fun fingerDrawingChoiceIsWrittenAndComesBackAtTheNextLaunch() = runBlocking<Unit> {
        val ui = CoroutineScope(coroutineContext + SupervisorJob())
        val file = File(directory, "config/input.properties")
        val notes = DesktopNotes(NotesLibrary.open(directory), ui, inputStore = InputSettingsFile(file))
        notes.start()
        assertEquals(InputSettings(drawWithFinger = false), notes.inputSettings)

        notes.updateInputSettings(InputSettings(drawWithFinger = true))
        assertEquals(InputSettings(drawWithFinger = true), notes.inputSettings)
        assertEquals(InputSettings(drawWithFinger = true), InputSettingsFile(file).load())
        close(notes)
        ui.cancel()

        val reopenedUi = CoroutineScope(coroutineContext + SupervisorJob())
        val reopened = DesktopNotes(NotesLibrary.open(directory), reopenedUi, inputStore = InputSettingsFile(file))
        assertEquals(InputSettings(drawWithFinger = true), reopened.inputSettings)
        reopened.start()
        close(reopened)
        reopenedUi.cancel()
    }

    private suspend fun openPage(notes: DesktopNotes): WorkspaceState = withTimeout(10_000) {
        notes.session.state.first { it?.selectedPage?.content == PageContent.Loaded }!!
    }

    private suspend fun close(notes: DesktopNotes) {
        val closed = CompletableDeferred<Unit>()
        notes.close { closed.complete(Unit) }
        withTimeout(10_000) { closed.await() }
    }

    private class RetryingInkWriter(private val library: NotesLibrary) : InkWriter {
        var failWrites = true
        override suspend fun loadInk(pageId: String) = library.repository.loadInk(pageId)
        override fun overlayPendingInk(page: InkPage, edits: List<InkEdit>) =
            library.repository.overlayPendingInk(page, edits)
        override suspend fun applyInkEdit(pageId: String, edit: InkEdit) {
            if (failWrites) error("disk unavailable")
            library.repository.applyInkEdit(pageId, edit)
        }
    }

    private fun inkRow(pageId: String) = InkStrokeEntity("private-pending-stroke", pageId, 0, "marker", 1,
        3f, 0xff000000.toInt(), true, 0.25f, 0, 8.5f, 18.5f, 31.5f, 41.5f,
        Base64.getDecoder().decode(ANDROID_TWO_POINT_INPUTS), "ink/androidx1", 1L)

    private companion object {
        const val LAST_WORDS = "typed just before closing"
        // Existing AndroidX Ink alpha06 fixture: unknown-tool (10,20) -> (30,40), 0 -> 10 ms.
        const val ANDROID_TWO_POINT_INPUTS = "H4sIAAAAAAAA/+Pi52JmaHAQZWBYYC3LwKDgKIQqsMBRipuLhWHBHEbRveZtphYMvgwMDAyhIAIA9usmejsAAAA="
    }
}
