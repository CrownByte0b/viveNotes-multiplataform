package com.vivenotes.search

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.selectionLine
import com.vivenotes.ai.InkTextRegion
import com.vivenotes.localization.Strings
import com.vivenotes.model.*
import com.vivenotes.model.search.*
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.components.assertIsIndeterminateWavyBar
import com.vivenotes.ui.search.SearchPane
import com.vivenotes.ui.search.SearchTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import java.util.Collections
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SearchPaneTest {
    private fun typed() = WorkspaceState.demo().let { state -> state.updatePage(state.selectedPageId) {
        it.copy(document = PageDoc(outlines = listOf(Outline.Text(id = "box", x = 24f, y = 1500f,
            blocks = listOf(Block.of("first line"), Block.of(""), Block.of("unsaved needle"))))))
    } }

    @Test fun ctrlFFromATextBoxOpensSearchAndAResultSelectsTheMatchingWordAndScrollsToIt() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = typed().focusTextBox("box")
            val requests = Collections.synchronizedList(mutableListOf<SearchRequest>())
            val service = object : ContentSearchService {
                override fun search(request: SearchRequest): Flow<SearchResults> {
                    requests.add(request)
                    val hits = searchContent(request.liveDocument!!.contentUnits(request.livePageId!!,
                        request.liveSectionId, request.liveTitle), request.query)
                    return flowOf(SearchResults(pages = listOf(SearchPageResults(request.livePageId, request.liveTitle, "Section", hits))))
                }
            }
            setContent {
                var state by remember { mutableStateOf(observed) }
                ViveNotesTheme {
                    WorkspaceScreen(state, { state = it(state); observed = state }, search = service)
                }
            }
            // The field is offscreen initially; Ctrl+F must still open at workspace scope.
            onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.F); keyUp(Key.CtrlLeft) }
            onNodeWithTag(SearchTags.Query).performTextInput("needle")
            waitUntil(timeoutMillis = 10_000) { requests.isNotEmpty() }
            val hit = searchContent(observed.selectedPage!!.document.contentUnits(observed.selectedPageId,
                observed.selectedSectionId, observed.selectedPage!!.title), "needle").single()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(SearchTags.hit(hit)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(SearchTags.hit(hit)).performClick()
            waitForIdle()
            runOnIdle {
                assertEquals("box", observed.focusedTextOutlineId)
                assertEquals(TextSelection(20, 26), observed.editorSelection)
            }
            onNodeWithTag(WorkspaceTestTags.BodyEditor).assertIsDisplayed()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).assert(SemanticsMatcher.expectValue(
                SemanticsProperties.TextSelectionRange, androidx.compose.ui.text.TextRange(20, 26)))
            onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.F); keyUp(Key.CtrlLeft) }
            onNodeWithTag(SearchTags.Query).assertIsFocused()
            onNodeWithTag(SearchTags.Close).performClick()
            onNodeWithTag(SearchTags.Pane).assertDoesNotExist()
        }

    /** Reported: the pane's close control read "Close" instead of showing an X. */
    @Test fun closeIsAnXIconButtonNamedForThePane() = runDesktopComposeUiTest(width = 800, height = 800) {
        var closes = 0
        val service = object : ContentSearchService {
            override fun search(request: SearchRequest) = emptyFlow<SearchResults>()
        }
        setContent {
            ViveNotesTheme { SearchPane(service, SearchRequest("n", "", null, "s", "", null), "", {}, {}, { closes++ }) }
        }
        onNodeWithTag(SearchTags.Close)
            .assertContentDescriptionEquals(Strings.close_value(Strings.search))
            .assertWidthIsEqualTo(32.dp)
            .assertHeightIsEqualTo(32.dp)
        onAllNodesWithText(Strings.close).assertCountEquals(0)
        onNodeWithTag(SearchTags.Close).performClick()
        runOnIdle { assertEquals(1, closes) }
    }

    @Test fun titleHitSelectsItsWordAndConsecutiveTitleEditsKeepTheCaret() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo().selectPage("lecture-notes")
            val hit = searchContent(listOfNotNull(titleUnit(observed.selectedPageId, observed.selectedSectionId,
                observed.selectedPage!!.title)), "Lecture").single()
            val service = object : ContentSearchService {
                override fun search(request: SearchRequest) = flowOf(SearchResults(pages = listOf(
                    SearchPageResults(observed.selectedPageId, observed.selectedPage!!.title, "Section", listOf(hit)))))
            }
            setContent {
                var state by remember { mutableStateOf(observed) }
                WorkspaceScreen(state, { state = it(state); observed = state }, search = service)
            }
            onNodeWithTag(SearchTags.Open).performClick()
            onNodeWithTag(SearchTags.Query).performTextInput("Lecture")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(SearchTags.hit(hit)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(SearchTags.hit(hit)).performClick()
            onNodeWithTag(WorkspaceTestTags.TitleEditor).assert(SemanticsMatcher.expectValue(
                SemanticsProperties.TextSelectionRange, androidx.compose.ui.text.TextRange(0, 7)))
            onNodeWithTag(WorkspaceTestTags.TitleEditor).performTextInput("Class")
            onNodeWithTag(WorkspaceTestTags.TitleEditor).performTextInput("room")
            runOnIdle { assertEquals("Classroom notes", observed.selectedPage!!.title) }
        }

    @Test fun pictureResultSelectsTheSourcePicture() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo().let { state -> state.updatePage(state.selectedPageId) {
            it.copy(document = PageDoc(outlines = listOf(Outline.Image(id = "picture", attachmentId = "a".repeat(64),
                x = 40f, y = 200f, height = 200f))))
        } }
        val hit = searchContent(imageUnits(ImagePlacement(observed.selectedPageId, observed.selectedSectionId,
            "picture", "a".repeat(64)), listOf("Quarterly revenue")), "revenue").single()
        val service = object : ContentSearchService {
            override fun search(request: SearchRequest) = flowOf(SearchResults(pages = listOf(
                SearchPageResults(observed.selectedPageId, "Slides", "Section", listOf(hit))), imagesDone = 1, imagesTotal = 1))
        }
        setContent {
            var state by remember { mutableStateOf(observed) }
            WorkspaceScreen(state, { state = it(state); observed = state }, search = service)
        }
        onNodeWithTag(SearchTags.Open).performClick()
        onNodeWithTag(SearchTags.Query).performTextInput("revenue")
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(SearchTags.hit(hit)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(SearchTags.hit(hit)).performClick()
        runOnIdle { assertEquals(setOf("picture"), observed.selectedObjectIds) }
        onNodeWithText("Picture text").assertIsDisplayed()
    }

    @Test fun openingAHandwritingResultHighlightsAndScrollsWithoutAnEditableInkSelection() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo()
            val ink = InkPageReader.read(initial.selectedPageId,
                listOf(selectionLine(pageId = initial.selectedPageId, y = 1500f)), emptyList(), emptyList())
            var observed = initial.updatePage(initial.selectedPageId) { it.copy(document = PageDoc.empty(), ink = ink) }
            val hit = searchContent(inkUnits(observed.selectedPageId, observed.selectedSectionId,
                listOf(InkTextRegion("line-1", "handwritten needle", .95f, left = 7f, top = 1497f,
                    right = 113f, bottom = 1503f, strokeIds = listOf("source")))), "needle").single()
            val service = object : ContentSearchService {
                override fun search(request: SearchRequest) = flowOf(SearchResults(pages = listOf(
                    SearchPageResults(observed.selectedPageId, "Page", "Section", listOf(hit))), inkDone = 1, inkTotal = 1))
            }
            setContent {
                var state by remember { mutableStateOf(observed) }
                WorkspaceScreen(state, { state = it(state); observed = state }, search = service)
            }
            onNodeWithTag(SearchTags.Open).performClick()
            onNodeWithTag(SearchTags.Query).performTextInput("needle")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(SearchTags.hit(hit)).fetchSemanticsNodes().isNotEmpty() }
            mainClock.autoAdvance = false
            onNodeWithTag(SearchTags.hit(hit)).performClick()
            mainClock.advanceTimeBy(150)
            onNodeWithTag(SearchTags.InkHighlight).assertIsDisplayed()
            onNodeWithText("Handwriting").assertIsDisplayed()
            runOnIdle {
                assertNull(observed.selectedInk)
                assertTrue(observed.selectedObjectIds.isEmpty())
            }
            mainClock.advanceTimeBy(1600)
            onNodeWithTag(SearchTags.InkHighlight).assertDoesNotExist()
            mainClock.autoAdvance = true
        }

    @Test fun handwritingProgressAndFailureHaveAWorkingRetryControl() = runDesktopComposeUiTest(width = 800, height = 800) {
        val progress = MutableStateFlow(SearchResults(searching = true, inkDone = 1, inkTotal = 3))
        var retries = 0
        val service = object : ContentSearchService {
            override fun search(request: SearchRequest) = progress
            override suspend fun retryFailedHandwriting() { retries++ }
        }
        setContent { ViveNotesTheme {
            SearchPane(service, SearchRequest("n", "needle", null, "s", "", null), "needle", {}, {}, {})
        } }
        onNodeWithTag(SearchTags.Status).assertTextContains("Handwriting pages 1/3", substring = true)
        onNodeWithTag(SearchTags.Progress).assertIsIndeterminateWavyBar()
        progress.value = SearchResults(inkDone = 3, inkTotal = 3, failedInk = 1)
        waitForIdle()
        onNodeWithTag(SearchTags.Status).assertTextContains("1 handwriting pages could not be read", substring = true)
        onNodeWithText("Retry handwriting").performClick()
        waitUntil(timeoutMillis = 5_000) { retries == 1 }
    }

    @Test fun newerQueriesCancelOldOnesAndShowProgressFailuresRetryAndEmptyState() =
        runDesktopComposeUiTest(width = 800, height = 800) {
            val requests = Collections.synchronizedList(mutableListOf<String>())
            val cancellations = Collections.synchronizedList(mutableListOf<String>())
            val progress = MutableStateFlow(SearchResults(searching = true, imagesTotal = 4, imagesDone = 2))
            var retries = 0
            val service = object : ContentSearchService {
                override fun search(request: SearchRequest) = if (request.query == "old") flow<SearchResults> {
                    requests.add(request.query)
                    try { awaitCancellation() } finally { cancellations.add(request.query) }
                } else flow { requests.add(request.query); emitAll(progress) }
                override suspend fun retryFailedImages() {
                    retries++
                    if (retries == 1) error("private storage error")
                }
            }
            setContent {
                var query by remember { mutableStateOf("") }
                ViveNotesTheme { SearchPane(service, SearchRequest("n", query, null, "s", "", null), query,
                    { query = it }, {}, {}) }
            }
            onNodeWithTag(SearchTags.Query).performTextInput("old")
            waitUntil(timeoutMillis = 10_000) { "old" in requests }
            onNodeWithTag(SearchTags.Query).performTextReplacement("new")
            waitUntil(timeoutMillis = 10_000) { "old" in cancellations && "new" in requests }
            onNodeWithTag(SearchTags.Status).assertTextContains("Pictures 2/4", substring = true)
            onNodeWithTag(SearchTags.Progress).assertIsIndeterminateWavyBar()
            progress.value = SearchResults(imagesTotal = 4, imagesDone = 4, failedImages = 1)
            waitForIdle()
            onNodeWithTag(SearchTags.Progress).assertDoesNotExist()
            onNodeWithText("No matches").assertIsDisplayed()
            onNodeWithText("Retry pictures").performClick()
            waitUntil(timeoutMillis = 10_000) { retries == 1 }
            onNodeWithTag(SearchTags.Status).assertTextEquals("Pictures could not be queued. Try again.")
            onNodeWithText("Retry pictures").performClick()
            waitUntil(timeoutMillis = 10_000) { retries == 2 }
            onNodeWithTag(SearchTags.Query).performTextClearance()
            onNodeWithTag(SearchTags.Status).assertTextContains("Search page titles", substring = true)
        }
}
