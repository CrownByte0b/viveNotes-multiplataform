package com.vivenotes.search

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ai.imageOf
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import com.vivenotes.model.*
import com.vivenotes.model.ocr.*
import com.vivenotes.model.search.*
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.search.SearchTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PictureSearchNavigationTest {
    @Test fun openingAHitScrollsToItsWordsAndHighlightsOnlyWhileSearchIsOpen() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val original = WorkspaceState.demo()
        var observed = original.updatePage(original.selectedPageId) {
            it.copy(document = PageDoc(style = PageStyle(hideTitle = true), outlines = listOf(
                Outline.Image(id = "picture", attachmentId = "sha", width = 400f, height = 4000f))))
        }.selectObject("picture")
        val hit = searchContent(imageUnits(ImagePlacement(observed.selectedPageId, observed.selectedSectionId, "picture", "sha"),
            listOf("Grand total")), "total").single()
        val highlight = PictureHighlights.forSpans("Grand total", ImageLineLayout(
            floatArrayOf(.1f, .75f, .9f, .75f, .9f, .78f, .1f, .78f),
            FloatArray(12) { it / 11f }), hit.spans)!!
        val highlights = MutableStateFlow(mapOf("sha" to highlight))
        val service = object : ContentSearchService {
            override fun search(request: SearchRequest) = flowOf(SearchResults(pages = listOf(
                SearchPageResults(observed.selectedPageId, "Page", "Section", listOf(hit)))))
            override fun pictureHighlights(attachmentIds: Set<String>, query: String) = highlights
            override suspend fun pictureHighlight(hit: ContentHit) = highlight
        }
        val bytes = imageOf(80, 80) { _, _ -> -1 }.use { it.encodeToData()!!.use { data -> data.bytes } }
        val pictures = object : PictureLibrary {
            override suspend fun choose(): ImportedPicture? = null
            override suspend fun import(bytes: ByteArray): ImportedPicture? = null
            override suspend fun bytes(attachmentId: String) = bytes
        }
        setContent {
            var state by remember { mutableStateOf(observed) }
            ViveNotesTheme {
                WorkspaceScreen(state, { state = it(state); observed = state }, search = service, pictures = pictures)
            }
        }
        onNodeWithTag(SearchTags.Open).performClick()
        onNodeWithTag(SearchTags.Query).performTextInput("total")
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(SearchTags.hit(hit)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(SearchTags.hit(hit)).performClick()
        waitUntil(timeoutMillis = 10_000) { observed.selectedObjectIds.isEmpty() }
        waitForIdle()
        onNodeWithTag(SearchTags.pictureHighlight("picture"), useUnmergedTree = true).assertExists().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "1 search match"))
        val frame = onNodeWithTag(WorkspaceTestTags.primeObject("picture")).getUnclippedBoundsInRoot()
        val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas).getUnclippedBoundsInRoot()
        val lineCenter = frame.top.value + (frame.bottom.value - frame.top.value) * .765f
        assertEquals((canvas.top.value + canvas.bottom.value) / 2f, lineCenter, 1f,
            "the matched line should be centered vertically")
        runOnIdle { assertNull(observed.selectedInk); assertNull(observed.focusedTextOutlineId) }
        onNodeWithTag(SearchTags.Query).performTextClearance()
        waitForIdle()
        onNodeWithTag(SearchTags.pictureHighlight("picture"), useUnmergedTree = true).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        onNodeWithTag(SearchTags.Query).performTextInput("total")
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithTag(SearchTags.pictureHighlight("picture"), useUnmergedTree = true).fetchSemanticsNodes()
                .any { it.config.getOrElse(SemanticsProperties.StateDescription) { "" } == "1 search match" }
        }
        onNodeWithTag(SearchTags.pictureHighlight("picture"), useUnmergedTree = true).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "1 search match"))
        onNodeWithTag(SearchTags.Close).performClick()
        waitForIdle()
        onNodeWithTag(SearchTags.pictureHighlight("picture"), useUnmergedTree = true).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test fun nearbyResultsKeepTheViewStillAndDistantResultsCenterAtScaledZoom() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val original = WorkspaceState.demo()
        var observed = original.updatePage(original.selectedPageId) {
            it.copy(document = PageDoc(style = PageStyle(hideTitle = true), outlines = listOf(
                Outline.Image(id = "picture", attachmentId = "sha", width = 4000f, height = 4000f))))
        }
        val hits = searchContent(imageUnits(ImagePlacement(observed.selectedPageId, observed.selectedSectionId, "picture", "sha"),
            listOf("total first", "total neighbor", "total distant")), "total").sortedBy { it.unit.blockIndex }
        fun box(x: Float, y: Float) = PictureHighlight(floatArrayOf(x - .01f, y - .01f, x + .01f, y - .01f,
            x + .01f, y + .01f, x - .01f, y + .01f))
        val boxes = listOf(box(.5f, .6f), box(.54f, .605f), box(.75f, .8f))
        val opened = java.util.concurrent.atomic.AtomicInteger()
        val service = object : ContentSearchService {
            override fun search(request: SearchRequest) = flowOf(SearchResults(pages = listOf(
                SearchPageResults(observed.selectedPageId, "Page", "Section", hits))))
            override suspend fun pictureHighlight(hit: ContentHit): PictureHighlight {
                opened.incrementAndGet()
                return boxes[hit.unit.blockIndex]
            }
        }
        setContent {
            var state by remember { mutableStateOf(observed) }
            ViveNotesTheme {
                WorkspaceScreen(state, { state = it(state); observed = state }, search = service,
                    interfaceSettings = InterfaceSettings(displayScale = .75f), viewSettings = ViewSettings(zoom = 1.5f))
            }
        }
        onNodeWithTag(SearchTags.Open).performClick()
        onNodeWithTag(SearchTags.Query).performTextInput("total")
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(SearchTags.hit(hits[0])).fetchSemanticsNodes().isNotEmpty() }
        fun open(index: Int, count: Int) {
            onNodeWithTag(SearchTags.hit(hits[index])).performClick()
            waitUntil(timeoutMillis = 10_000) { opened.get() == count }
            waitForIdle()
        }
        fun assertCentered(x: Float, y: Float) {
            val frame = onNodeWithTag(WorkspaceTestTags.primeObject("picture")).fetchSemanticsNode()
            val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas).fetchSemanticsNode().boundsInRoot
            assertEquals(canvas.center.x, frame.positionInRoot.x + frame.size.width * 1.5f * x, 1f)
            assertEquals(canvas.center.y, frame.positionInRoot.y + frame.size.height * 1.5f * y, 1f)
        }
        open(0, 1)
        assertCentered(.5f, .6f)
        val before = onNodeWithTag(WorkspaceTestTags.primeObject("picture")).getUnclippedBoundsInRoot()
        open(1, 2)
        val after = onNodeWithTag(WorkspaceTestTags.primeObject("picture")).getUnclippedBoundsInRoot()
        assertEquals(before.left, after.left, "an adjacent word must not pan horizontally")
        assertEquals(before.top, after.top, "an adjacent word must not pan vertically")
        open(1, 3)
        assertEquals(after, onNodeWithTag(WorkspaceTestTags.primeObject("picture")).getUnclippedBoundsInRoot(),
            "reopening the same result must keep the viewport still")
        open(2, 4)
        assertCentered(.75f, .8f)
        runOnIdle { assertTrue(observed.selectedObjectIds.isEmpty()); assertNull(observed.selectedInk) }
    }
}
