package com.vivenotes.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.InMemoryNavigation
import com.vivenotes.workspace.NEW_NOTEBOOK_NAME
import com.vivenotes.workspace.NEW_SECTION_NAME
import com.vivenotes.workspace.NavigationItem
import com.vivenotes.workspace.NotebookSummary
import com.vivenotes.workspace.SectionSummary
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The notebook pane: Android's `NotebookRailTest` as compatibility specification — right-click in
 * place of a long press, a mouse in place of a finger — and the desktop's folding and New rows.
 *
 * The drag tests are the ones that matter. Reordering is worked out against the live layout, row
 * heights and offsets read back from the lazy list as it reflows, so it can only be checked by
 * moving a pointer across a composed list and seeing where the row lands.
 */
@OptIn(ExperimentalTestApi::class)
class NotebookRailTest {

    private val notebook = NotebookSummary(
        id = "nb",
        name = "Field notes",
        colorArgb = 0xFF4CAF50.toInt(),
        sections = listOf(section("a", "Alpha"), section("b", "Bravo"), section("c", "Charlie")),
    )

    private fun section(id: String, name: String) = SectionSummary(id, name, 0xFF2196F3.toInt(), emptyList())

    private val navigation = RecordingNavigation()
    private val requests = NavigationRequests()
    private var selected: String? = null

    private fun ComposeUiTest.setRail(state: WorkspaceState = WorkspaceState(listOf(notebook), "nb", "a", "")) {
        setContent {
            ViveNotesTheme(darkTheme = true) {
                Box(Modifier.width(232.dp).height(500.dp)) {
                    NotebookPane(state, requests, navigation, onSelectSection = { selected = it })
                }
            }
        }
    }

    /** The commands are behind a right-click, not docked beside the name. */
    @Test
    fun sectionCommandsStayHiddenUntilTheRowIsRightClicked() = runDesktopComposeUiTest(width = 400, height = 600) {
        setRail()

        onNodeWithTag(NavigationTestTags.Rename).assertDoesNotExist()
        onNodeWithTag(NavigationTestTags.Delete).assertDoesNotExist()
    }

    @Test
    fun rightClickingASectionOffersRenameForThatSection() = runDesktopComposeUiTest(width = 400, height = 600) {
        setRail()

        onNodeWithTag(WorkspaceTestTags.section("b")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Rename).performClick()
        waitForIdle()

        assertEquals(NavigationItem.Section("b"), requests.renaming)
    }

    @Test
    fun rightClickingASectionOffersDeleteForThatSection() = runDesktopComposeUiTest(width = 400, height = 600) {
        setRail()

        onNodeWithTag(WorkspaceTestTags.section("c")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Delete).performClick()
        waitForIdle()

        assertEquals(NavigationItem.Section("c"), requests.deleting)
    }

    /** Opening a section is a click, so right-clicking one must not also open it. */
    @Test
    fun rightClickingASectionDoesNotOpenIt() = runDesktopComposeUiTest(width = 400, height = 600) {
        setRail()

        onNodeWithTag(WorkspaceTestTags.section("b")).performMouseInput { rightClick(center) }
        waitForIdle()

        assertNull(selected)
    }

    /**
     * The header's own click folds the notebook. Its menu has to be reachable without that click
     * firing underneath, or renaming would always fold the notebook shut on the way.
     */
    @Test
    fun rightClickingANotebookOffersRenameWithoutFoldingIt() = runDesktopComposeUiTest(width = 400, height = 600) {
        setRail()

        onNodeWithTag(NavigationTestTags.notebook("nb")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Rename).performClick()
        waitForIdle()

        assertEquals(NavigationItem.Notebook("nb"), requests.renaming)
        assertTrue(navigation.expansions.isEmpty(), "the header's own click fired underneath the right-click")
    }

    @Test
    fun draggingASectionDownMovesItPastTheOneBelow() = runDesktopComposeUiTest(width = 400, height = 600) {
        setRail()

        dragSection("a", rows = 1.4f)

        assertEquals(listOf("nb" to listOf("b", "a", "c")), navigation.sectionOrders)
    }

    @Test
    fun draggingASectionUpMovesItPastEverythingAbove() = runDesktopComposeUiTest(width = 400, height = 600) {
        setRail()

        dragSection("c", rows = -2.4f)

        assertEquals(listOf("nb" to listOf("c", "a", "b")), navigation.sectionOrders)
    }

    /** A press that never travels is not a reorder, and must not store one. */
    @Test
    fun clickingTheHandleReordersNothing() = runDesktopComposeUiTest(width = 400, height = 600) {
        setRail()

        onNodeWithTag(NavigationTestTags.sectionDrag("a"), useUnmergedTree = true).performMouseInput {
            moveTo(center)
            press()
            release()
        }
        waitForIdle()

        assertTrue(navigation.sectionOrders.isEmpty())
    }

    /** The store's write is not instant: letting go of the dragged order at once would snap it back. */
    @Test
    fun theDroppedOrderIsHeldUntilTheNotebooksAgree() = runDesktopComposeUiTest(width = 400, height = 600) {
        setRail()

        dragSection("a", rows = 1.4f)

        assertTrue(rowTop("b") < rowTop("a"), "Bravo should stay above Alpha after the drop")
        assertTrue(rowTop("a") < rowTop("c"))
    }

    @Test
    fun clickingANotebookFoldsItsSectionsAwayAndBack() = runDesktopComposeUiTest(width = 400, height = 600) {
        setContent {
            var state by remember { mutableStateOf(WorkspaceState(listOf(notebook), "nb", "a", "")) }
            val folding = InMemoryNavigation { state = it(state) }
            ViveNotesTheme(darkTheme = true) {
                Box(Modifier.width(232.dp).height(500.dp)) {
                    NotebookPane(state, requests, folding, onSelectSection = { selected = it })
                }
            }
        }
        onNodeWithTag(WorkspaceTestTags.section("b")).assertIsDisplayed()
        onNodeWithTag(NavigationTestTags.addSection("nb")).assertIsDisplayed()

        onNodeWithTag(NavigationTestTags.notebook("nb")).performClick()

        onNodeWithTag(WorkspaceTestTags.section("b")).assertDoesNotExist()
        onNodeWithTag(NavigationTestTags.addSection("nb")).assertDoesNotExist()
        onNodeWithTag(NavigationTestTags.notebook("nb")).assertIsDisplayed()
        assertNull(selected, "folding a notebook opens nothing")

        onNodeWithTag(NavigationTestTags.notebook("nb")).performClick()
        onNodeWithTag(WorkspaceTestTags.section("b")).assertIsDisplayed()
    }

    /** Android's rail: no heading, a New Section row under each open notebook, New Notebook last. */
    @Test
    fun theRailListsNotebooksWithTheirNewRowsAndNoHeading() = runDesktopComposeUiTest(width = 400, height = 600) {
        val folded = NotebookSummary("shut", "Archive", 0xFF2196F3.toInt(), listOf(section("z", "Old")), expanded = false)
        setRail(WorkspaceState(listOf(notebook, folded), "nb", "a", ""))

        onNodeWithText("Notebooks").assertDoesNotExist()
        onNodeWithTag(NavigationTestTags.notebook("nb")).assertTextContains("Field notes")
        onNodeWithTag(NavigationTestTags.notebook("shut")).assertTextContains("Archive")
        onNodeWithTag(WorkspaceTestTags.section("z")).assertDoesNotExist()
        onNodeWithTag(NavigationTestTags.addSection("nb")).assertTextContains("New Section")
        onNodeWithTag(NavigationTestTags.addSection("shut")).assertDoesNotExist()
        onNodeWithTag(NavigationTestTags.AddNotebook).assertTextContains("New Notebook")
        val headerTop = onNodeWithTag(NavigationTestTags.notebook("shut")).fetchSemanticsNode().boundsInRoot.top
        assertTrue(onNodeWithTag(NavigationTestTags.addSection("nb")).fetchSemanticsNode().boundsInRoot.top < headerTop,
            "New Section belongs to the notebook above the next one")

        onNodeWithTag(NavigationTestTags.addSection("nb")).performClick()
        assertEquals(NewItem.Section("nb"), requests.creating)
        onNodeWithTag(NavigationTestTags.AddNotebook).performClick()
        assertEquals(NewItem.Notebook, requests.creating)
    }

    @Test
    fun newSectionAsksForANameAndOpensTheSectionOnANewPage() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }

        onNodeWithTag(NavigationTestTags.addSection("calculus")).performClick()
        onNodeWithText("New section").assertIsDisplayed()
        onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("Chapter 3")
        onNodeWithTag(NavigationTestTags.ConfirmCreate).performClick()

        onNodeWithTag(NavigationTestTags.Dialog).assertDoesNotExist()
        val section = observed.notebooks.first().sections.last()
        assertEquals("Chapter 3", section.name)
        assertEquals(section.id, observed.selectedSectionId)
        assertEquals(section.pages.single().id, observed.selectedPageId)
        onNodeWithTag(WorkspaceTestTags.section(section.id)).assertTextContains("Chapter 3")
        onNodeWithTag(WorkspaceTestTags.page(section.pages.single().id)).assertIsDisplayed()
    }

    /** Creating, unlike renaming, has a default: a blank name is allowed and means it. */
    @Test
    fun aNotebookLeftUnnamedIsCalledNewNotebook() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }

        onNodeWithTag(NavigationTestTags.AddNotebook).performClick()
        onNodeWithText("New notebook").assertIsDisplayed()
        onNodeWithTag(NavigationTestTags.NameField).performKeyInput { pressKey(Key.Enter) }

        onNodeWithTag(NavigationTestTags.Dialog).assertDoesNotExist()
        val notebook = observed.notebooks.last()
        assertEquals(NEW_NOTEBOOK_NAME, notebook.name)
        assertEquals(listOf(NEW_SECTION_NAME), notebook.sections.map { it.name })
        assertEquals(notebook.sections.single().id, observed.selectedSectionId)
        onNodeWithTag(NavigationTestTags.notebook(notebook.id)).assertTextContains(NEW_NOTEBOOK_NAME)
    }

    @Test
    fun cancellingANewNotebookCreatesNothing() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }

        onNodeWithTag(NavigationTestTags.AddNotebook).performClick()
        onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("Physics")
        onNodeWithTag(NavigationTestTags.Cancel).performClick()

        onNodeWithTag(NavigationTestTags.Dialog).assertDoesNotExist()
        assertEquals(listOf("calculus", "biology"), observed.notebooks.map { it.id })
    }

    /**
     * Drags [sectionId] by [rows] row pitches, positive downwards. The pitch is measured off two
     * composed rows, and the drag moves in steps: a swap is decided from where the dragged row's
     * centre sits each time it moves, so one jump would leap over the row it should trade places
     * with. Android's test adds touch slop back on; a mouse's drag slop is a fraction of a pixel.
     */
    private fun ComposeUiTest.dragSection(sectionId: String, rows: Float) {
        val pitch = rowTop("b") - rowTop("a")
        onNodeWithTag(NavigationTestTags.sectionDrag(sectionId), useUnmergedTree = true).performMouseInput {
            val distance = pitch * rows
            moveTo(center)
            press()
            repeat(Steps) { moveBy(Offset(0f, distance / Steps)) }
            release()
        }
        waitForIdle()
    }

    private fun ComposeUiTest.rowTop(sectionId: String) =
        onNodeWithTag(WorkspaceTestTags.section(sectionId)).fetchSemanticsNode().boundsInRoot.top

    private fun ComposeUiTest.setWorkspace(onStateChange: (WorkspaceState) -> Unit) {
        setContent {
            var state by remember { mutableStateOf(WorkspaceState.demo()) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) },
                    interfaceSettings = InterfaceSettings(displayScale = 1f))
            }
        }
    }

    private companion object {
        const val Steps = 12
    }
}
