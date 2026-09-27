package com.vivenotes.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vivenotes.model.PageDoc
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.PageSummary
import com.vivenotes.workspace.SectionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The page pane: Android's `PageListReorderTest` as compatibility specification — dragging, and the
 * sorts where dragging is not on offer — with a mouse, plus the rows and header as Android draws them.
 */
@OptIn(ExperimentalTestApi::class)
class PageListPaneTest {

    private fun page(id: String, title: String, updatedAt: Long, preview: String = "") = PageSummary(
        id = id,
        title = title,
        preview = preview,
        createdLabel = "",
        document = PageDoc(outlines = emptyList()),
        updatedAt = updatedAt,
        updatedLabel = "label $id",
    )

    private val section = SectionSummary(
        id = "sec",
        name = "Section",
        colorArgb = 0,
        pages = listOf(
            page("a", "Alpha", updatedAt = 100),
            page("b", "Bravo", updatedAt = 300),
            page("c", "Charlie", updatedAt = 200),
        ),
    )

    private val navigation = RecordingNavigation()
    private var opened: String? = null

    private fun ComposeUiTest.setList(shown: SectionSummary? = section) {
        setContent {
            ViveNotesTheme(darkTheme = true) {
                Box(Modifier.width(260.dp).height(600.dp)) {
                    PageListPane(shown, selectedPageId = "a", requests = NavigationRequests(),
                        navigation = navigation, onSelectPage = { opened = it })
                }
            }
        }
    }

    @Test
    fun draggingAPageDownMovesItPastTheOneBelow() = runDesktopComposeUiTest(width = 400, height = 700) {
        setList()

        dragPage("a", rows = 1.4f)

        assertEquals(listOf("sec" to listOf("b", "a", "c")), navigation.pageOrders)
    }

    @Test
    fun draggingAPageUpMovesItPastEverythingAbove() = runDesktopComposeUiTest(width = 400, height = 700) {
        setList()

        dragPage("c", rows = -2.4f)

        assertEquals(listOf("sec" to listOf("c", "a", "b")), navigation.pageOrders)
    }

    /**
     * The other two sorts come from the pages themselves, so a dropped row would be sorted straight
     * back out of where it was put. The handle is absent rather than inert.
     */
    @Test
    fun theHandleIsOnlyOfferedUnderSectionOrder() = runDesktopComposeUiTest(width = 400, height = 700) {
        setList()
        onNodeWithTag(NavigationTestTags.pageDrag("a"), useUnmergedTree = true).assertIsDisplayed()

        onNodeWithTag(NavigationTestTags.SortPages).performClick()
        onNodeWithText(PageSort.Alphabetical.label).performClick()
        waitForIdle()

        onNodeWithTag(NavigationTestTags.pageDrag("a"), useUnmergedTree = true).assertDoesNotExist()
        assertTrue(navigation.pageOrders.isEmpty(), "switching sorts is not itself a reorder")
    }

    @Test
    fun byDateModifiedListsTheLatestFirstAndTheMenuTicksIt() = runDesktopComposeUiTest(width = 400, height = 700) {
        setList()

        onNodeWithTag(NavigationTestTags.SortPages).performClick()
        onNodeWithTag(NavigationTestTags.sort(PageSort.Manual)).assertIsSelected()
        onNodeWithTag(NavigationTestTags.sort(PageSort.Recent)).assertIsNotSelected().performClick()
        waitForIdle()

        assertTrue(rowTop("b") < rowTop("c") && rowTop("c") < rowTop("a"), "expected Bravo, Charlie, Alpha")
        onNodeWithTag(NavigationTestTags.SortPages).performClick()
        onNodeWithTag(NavigationTestTags.sort(PageSort.Recent)).assertIsSelected()
    }

    /** Android's header: Add Page on the left and the sort menu on the right, no section name. */
    @Test
    fun theHeaderAddsPagesAndNamesNoSection() = runDesktopComposeUiTest(width = 400, height = 700) {
        setList()

        onNodeWithText("Section").assertDoesNotExist()
        onNodeWithText("3 pages").assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.AddPage).assertTextContains("Add Page").performClick()
        assertEquals(1, navigation.pagesAdded)
        val add = onNodeWithTag(WorkspaceTestTags.AddPage).fetchSemanticsNode().boundsInRoot
        val sort = onNodeWithTag(NavigationTestTags.SortPages).fetchSemanticsNode().boundsInRoot
        assertTrue(add.center.x < sort.left && kotlin.math.abs(add.center.y - sort.center.y) < 4f,
            "Add Page $add and the sort button $sort should share one row, sort on the right")
    }

    @Test
    fun withNoSectionOpenThereIsNothingToAddTo() = runDesktopComposeUiTest(width = 400, height = 700) {
        setList(shown = null)

        onNodeWithTag(WorkspaceTestTags.AddPage).assertDoesNotExist()
        onNodeWithTag(NavigationTestTags.SortPages).assertDoesNotExist()
    }

    /** A row: title — or "Untitled page" — the first line written when there is one, and its date. */
    @Test
    fun aRowShowsTitlePreviewAndWhenItChanged() = runDesktopComposeUiTest(width = 400, height = 700) {
        setList(section.copy(pages = listOf(
            page("a", "Alpha", updatedAt = 1, preview = "First line"),
            page("u", "", updatedAt = 2),
        )))

        onNodeWithTag(WorkspaceTestTags.page("a")).assertTextContains("Alpha")
            .assertTextContains("First line").assertTextContains("label a")
        onNodeWithTag(WorkspaceTestTags.page("u")).assertTextContains(UntitledPage).assertTextContains("label u")
        val withPreview = onNodeWithTag(WorkspaceTestTags.page("a")).fetchSemanticsNode().boundsInRoot.height
        val without = onNodeWithTag(WorkspaceTestTags.page("u")).fetchSemanticsNode().boundsInRoot.height
        assertTrue(without < withPreview, "a row without a preview is a line shorter")

        onNodeWithTag(WorkspaceTestTags.page("u")).performClick()
        assertEquals("u", opened)
    }

    private fun ComposeUiTest.dragPage(pageId: String, rows: Float) {
        val pitch = rowTop("b") - rowTop("a")
        onNodeWithTag(NavigationTestTags.pageDrag(pageId), useUnmergedTree = true).performMouseInput {
            // No slop added back, unlike Android's touch test: a mouse's is a fraction of a pixel.
            val distance = pitch * rows
            moveTo(center)
            press()
            // Stepwise: a swap is decided from where the row's centre sits each time it moves.
            repeat(Steps) { moveBy(Offset(0f, distance / Steps)) }
            release()
        }
        waitForIdle()
    }

    private fun ComposeUiTest.rowTop(pageId: String) =
        onNodeWithTag(WorkspaceTestTags.page(pageId)).fetchSemanticsNode().boundsInRoot.top

    private companion object {
        const val Steps = 12
    }
}
