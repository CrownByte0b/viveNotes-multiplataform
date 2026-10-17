package com.vivenotes.workspace

import com.vivenotes.data.PageLoad
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.pdf.PdfExportException
import com.vivenotes.richtext.TextSelection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PdfExportSessionTest {
    @Test
    fun preparingPdfSavesTheEditBeforeAutosaveAndRefusesAFailedSave() = runTest {
        val store = FakeNotesStore()
        val sections = store.notebook("Book", "Section" to listOf("Page" to PageDoc(outlines = listOf(
            Outline.Text(id = "text", blocks = listOf(Block.of("stored")))))))
        val pageId = store.pageIdsIn(sections.single()).single()
        val logs = mutableListOf<String>()
        val session = WorkspaceSession(store, backgroundScope, { "date" }, log = DebugLog(true, logs::add))
        session.start()
        runCurrent()
        val actions = session.fileActions(FakeNotebookFiles(store))
        session.update { it.focusBody().editSelectedText("latest edit", TextSelection(11)) }
        val prepare = async { actions.preparePdfExport() }
        runCurrent()
        prepare.await()
        assertEquals("latest edit", (store.body(pageId) as PageLoad.Loaded).doc.outlines
            .filterIsInstance<Outline.Text>().first().blocks.first().text)

        store.saveFailure = IllegalStateException("Disk full")
        session.update { it.focusBody().editSelectedText("unsaved", TextSelection(7)) }
        val failure = async { runCatching { actions.preparePdfExport() } }
        runCurrent()
        assertIs<PdfExportException>(failure.await().exceptionOrNull())
        assertEquals("unsaved", session.state.value!!.selectedPage!!.body)
        assertTrue(logs.any { it.contains("[pdf] export stopped") })
        assertFalse(logs.any { it.contains("latest edit") || it.contains("unsaved") })
    }
}
