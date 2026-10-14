package com.vivenotes.workspace

import com.vivenotes.model.*
import com.vivenotes.model.search.*
import com.vivenotes.richtext.TextSelection
import com.vivenotes.search.revealSearchHit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchRevealTest {
    @Test fun searchOpensAnUnloadedSectionAndPreservesThePageBeingLeft() = runTest {
        val store = FakeNotesStore()
        val sections = store.notebook("Notebook", "First" to listOf("Open" to PageDoc.empty()),
            "Other" to listOf("Target" to PageDoc(outlines = listOf(Outline.Text(id = "target",
                blocks = listOf(Block.of("needle")))))))
        val target = store.pageIdsIn(sections[1]).single()
        val session = WorkspaceSession(store, backgroundScope, { "" })
        session.start()
        runCurrent()
        val original = session.state.value!!.selectedPageId
        session.update { it.updateSelectedPage(title = "Unsaved title") }
        assertTrue(session.state.value!!.notebooks.single().sections[1].pages.isEmpty())
        session.openSearchPage(sections[1], target)
        runCurrent()
        val state = session.state.value!!
        assertEquals(target, state.selectedPageId)
        assertEquals(PageContent.Loaded, state.selectedPage!!.content)
        assertEquals("Unsaved title", store.pageById(original)!!.title)
        val hit = searchContent(state.selectedPage!!.document.contentUnits(target, sections[1], "Target"), "needle").single()
        val revealed = state.revealSearchHit(hit)
        assertEquals("target", revealed.focusedTextOutlineId)
        assertEquals(TextSelection(0, 6), revealed.editorSelection)
    }

    @Test fun staleTextHitsCannotSelectOutsideTheCurrentDocument() {
        val state = WorkspaceState.demo()
        val hit = ContentHit(ContentUnit(state.selectedPageId, state.selectedSectionId, ContentKind.Text,
            state.bodyTextOutline!!.id, text = "old"), 1, listOf(MatchSpan(10000, 10006)))
        assertEquals(state, state.revealSearchHit(hit))
        assertEquals(state, state.revealSearchHit(hit.copy(unit = hit.unit.copy(pageId = "other"))))
    }

    @Test fun unicodeCaseFoldingKeepsEditorOffsets() {
        val match = FuzzyMatcher.match("istanbul", "İstanbul")!!
        assertEquals(listOf(MatchSpan(0, 8)), match.spans)
        assertEquals(listOf(MatchSpan(9, 15)), FuzzyMatcher.match("needle", "İstanbul needle")!!.spans)
    }
}
