package com.vivenotes.ui.canvas

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import com.vivenotes.data.LocalTextClipboard
import com.vivenotes.data.TextClipboard
import com.vivenotes.model.GraphBackground
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.ui.ribbon.draw.TableTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.PageContent
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.model.newId
import com.vivenotes.model.newTable
import com.vivenotes.workspace.withLockGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The prime object right-click menu (`memory/diagram.md`, Prime Object Class: copy, cut, delete),
 * driven through the workspace as a mouse, a held finger and the keyboard would.
 */
@OptIn(ExperimentalTestApi::class)
class ObjectContextMenuTest {

    @Test
    fun rightClickingAnObjectSelectsItAndOffersCutCopyAndDeleteWithTheirKeys() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = workspace(listOf(shape(SHAPE, 300f), shape(OTHER, 600f))).selectObject(OTHER)
            setWorkspace(observed) { observed = it }

            onNodeWithTag(WorkspaceTestTags.primeObject(SHAPE)).performMouseInput { rightClick(center) }

            runOnIdle { assertEquals(setOf(SHAPE), observed.selectedObjectIds, "the right-clicked object is held") }
            val rows = listOf(
                ObjectMenuTags.Cut to listOf("Cut", "Ctrl+X"),
                ObjectMenuTags.Copy to listOf("Copy", "Ctrl+C"),
                ObjectMenuTags.Delete to listOf("Delete", "Delete"),
            )
            rows.forEach { (tag, words) -> onNodeWithTag(tag).assertIsEnabled().assertTextEquals(*words.toTypedArray()) }
            // Each command on one line: a shortcut pushed onto a second makes its row taller.
            val heights = rows.map { (tag) -> onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top } }
            assertEquals(1, heights.toSet().size, "rows differ in height: $heights")

            onNodeWithTag(ObjectMenuTags.Copy).performClick()

            onNodeWithTag(ObjectMenuTags.Copy).assertDoesNotExist()
            runOnIdle {
                assertEquals(listOf(SHAPE), observed.canvasClipboard.objects.map { it.id })
                assertEquals(listOf(SHAPE, OTHER), shapes(observed).map { it.id }, "Copy leaves the page alone")
                assertEquals(setOf(SHAPE), observed.selectedObjectIds)
            }
        }

    @Test
    fun cutIsOneUndoStepAndWhatItTookPastesBackFromThePagesMenu() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = workspace(listOf(shape(SHAPE, 300f)))
            setWorkspace(observed) { observed = it }

            onNodeWithTag(WorkspaceTestTags.primeObject(SHAPE)).performMouseInput { rightClick(center) }
            onNodeWithTag(ObjectMenuTags.Cut).performClick()

            runOnIdle {
                assertTrue(shapes(observed).isEmpty())
                assertEquals(listOf(SHAPE), observed.canvasClipboard.objects.map { it.id })
                assertEquals(1, observed.structuralUndo.size)
            }
            onNodeWithTag(WorkspaceTestTags.CanvasExtent).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            runOnIdle { assertEquals(listOf(SHAPE), shapes(observed).map { it.id }) }

            // The bare page's own menu is still Paste, not the object's.
            onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { rightClick(Offset(800f, 600f)) }
            onNodeWithTag(ObjectMenuTags.Cut).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.CanvasPaste).performClick()
            runOnIdle { assertEquals(2, shapes(observed).size) }
        }

    @Test
    fun deleteRemovesTheObjectAndLeavesTheClipboardAlone() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = workspace(listOf(shape(SHAPE, 300f)))
        setWorkspace(observed) { observed = it }

        onNodeWithTag(WorkspaceTestTags.primeObject(SHAPE)).performMouseInput { rightClick(center) }
        onNodeWithTag(ObjectMenuTags.Delete).performClick()

        onNodeWithTag(ObjectMenuTags.Delete).assertDoesNotExist()
        runOnIdle {
            assertTrue(shapes(observed).isEmpty())
            assertTrue(observed.canvasClipboard.isEmpty)
            assertTrue(observed.selectedObjectIds.isEmpty())
        }
    }

    /** Every kind that implements the Prime Object Class, a locked table and an ink-only one among them. */
    @Test
    fun everyPrimeObjectKindOpensTheMenu() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val kinds = listOf(
            shape(SHAPE, 100f),
            Outline.Equation("equation", 300f, 220f, 80f, 60f, "x^2"),
            Outline.Image("image", 450f, 220f, 80f, attachmentId = "attachment", height = 60f),
            Outline.Graph(id = "graph", x = 600f, y = 220f, width = 200f, height = 160f,
                expression = "((x^2)+(-4))", latex = "x^{2} - 4", xMin = -5.0, xMax = 5.0, yMin = -5.0, yMax = 5.0,
                showEquation = false, background = GraphBackground.None),
            newTable(2, 2, 100f, 450f, inkOnly = true).copy(id = "ink-table"),
            newTable(2, 2, 500f, 450f, inkOnly = false).copy(id = "locked-table").withLockGroup("pinned"),
        )
        var observed = workspace(kinds)
        setWorkspace(observed) { observed = it }

        for (outline in kinds) {
            onNodeWithTag(WorkspaceTestTags.primeObject(outline.id)).performMouseInput { rightClick(Offset(12f, 12f)) }
            runOnIdle { assertEquals(setOf(outline.id), observed.selectedObjectIds, outline.id) }
            onNodeWithTag(ObjectMenuTags.Copy).performClick()
            runOnIdle { assertEquals(listOf(outline.id), observed.canvasClipboard.objects.map { it.id }, outline.id) }
        }
    }

    /** A right-click inside a selection of several keeps them all, and the command takes them all. */
    @Test
    fun aRightClickInASelectionActsOnAllOfIt() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = workspace(listOf(shape(SHAPE, 300f), shape(OTHER, 500f))).selectObjectsInRect(250f, 300f, 700f, 500f)
        assertEquals(setOf(SHAPE, OTHER), observed.selectedObjectIds)
        setWorkspace(observed) { observed = it }

        onNodeWithTag(WorkspaceTestTags.GroupSelectionFrame).performMouseInput { rightClick(Offset(40f, 40f)) }

        runOnIdle { assertEquals(setOf(SHAPE, OTHER), observed.selectedObjectIds) }
        onNodeWithTag(ObjectMenuTags.Delete).performClick()
        runOnIdle { assertTrue(shapes(observed).isEmpty()) }
    }

    /** Lock means stays put, not protected: a locked object is cut like any other. */
    @Test
    fun aLockedObjectIsCutFromTheMenu() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = workspace(listOf(shape(SHAPE, 300f).withLockGroup("pinned")))
        setWorkspace(observed) { observed = it }

        onNodeWithTag(WorkspaceTestTags.primeObject(SHAPE)).performMouseInput { rightClick(center) }
        runOnIdle { assertTrue(observed.selectedObjectsLocked) }
        onNodeWithTag(ObjectMenuTags.Cut).assertIsEnabled().performClick()

        runOnIdle {
            assertTrue(shapes(observed).isEmpty())
            assertEquals(listOf(SHAPE), observed.canvasClipboard.objects.map { it.id })
        }
    }

    @Test
    fun aPageThatCannotBeEditedOffersOnlyCopy() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = workspace(listOf(shape(SHAPE, 300f))).let { state ->
            state.updatePage(state.selectedPageId) { it.copy(content = PageContent.Unreadable) }
        }
        setWorkspace(observed) { observed = it }

        onNodeWithTag(WorkspaceTestTags.primeObject(SHAPE)).performMouseInput { rightClick(center) }

        onNodeWithTag(ObjectMenuTags.Cut).assertIsNotEnabled()
        onNodeWithTag(ObjectMenuTags.Delete).assertIsNotEnabled()
        onNodeWithTag(ObjectMenuTags.Copy).assertIsEnabled().performClick()
        runOnIdle {
            assertEquals(listOf(SHAPE), observed.canvasClipboard.objects.map { it.id })
            assertEquals(1, shapes(observed).size)
        }
    }

    /** The touch screen's right-click: a finger held still on the object. */
    @Test
    fun aHeldFingerOpensTheMenu() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = workspace(listOf(shape(SHAPE, 300f)))
        setWorkspace(observed) { observed = it }

        onNodeWithTag(WorkspaceTestTags.primeObject(SHAPE)).performTouchInput { longClick(center) }

        runOnIdle { assertEquals(setOf(SHAPE), observed.selectedObjectIds) }
        onNodeWithTag(ObjectMenuTags.Delete).performClick()
        runOnIdle { assertTrue(shapes(observed).isEmpty()) }
    }

    /** A drag with the right button held is the menu's, not a move. */
    @Test
    fun aRightButtonDragDoesNotMoveTheObject() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = workspace(listOf(shape(SHAPE, 300f)))
        setWorkspace(observed) { observed = it }

        onNodeWithTag(WorkspaceTestTags.primeObject(SHAPE)).performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            moveBy(Offset(80f, 60f))
            moveBy(Offset(80f, 60f))
            release(MouseButton.Secondary)
        }

        onNodeWithTag(ObjectMenuTags.Cut).assertExists()
        runOnIdle {
            val moved = shapes(observed).single()
            assertEquals(300f, moved.x)
            assertEquals(350f, moved.y)
        }
    }

    /**
     * A typed table's cells keep their text menu, which brings the table's own commands: Copy table
     * leaves the cell being edited, and Delete table takes the table. The cell's padding opens the
     * same menu as its text.
     */
    @Test
    fun aTypedTablesCellMenuCarriesTheTablesCommands() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val table = newTable(2, 2, 300f, 300f, inkOnly = false).copy(id = "table")
        val cell = table.rows.first().cells.first()
        var observed = workspace(listOf(table))
        setWorkspace(observed) { observed = it }

        onNodeWithTag(TableTags.cell(cell.id), useUnmergedTree = true).performMouseInput { rightClick(center) }

        runOnIdle { assertEquals(cell.id, observed.focusedTextOutlineId) }
        onNodeWithTag(ObjectMenuTags.Cut).assertDoesNotExist()
        onNodeWithTag(ObjectMenuTags.CutTable).assertIsEnabled().assertTextEquals("Cut table")
        onNodeWithTag(ObjectMenuTags.DeleteTable).assertIsEnabled().assertTextEquals("Delete table")
        onNodeWithTag(ObjectMenuTags.CopyTable).assertTextEquals("Copy table").performClick()
        runOnIdle {
            assertEquals(listOf(table.id), observed.canvasClipboard.objects.map { it.id })
            assertEquals(cell.id, observed.focusedTextOutlineId, "copying the table keeps the cell being edited")
        }

        // Inside the cell but outside its text: the corner of its 8 dp padding.
        onNodeWithTag(WorkspaceTestTags.primeObject(table.id)).performMouseInput { rightClick(Offset(3f, 3f)) }
        onNodeWithTag(ObjectMenuTags.Cut).assertDoesNotExist()
        onNodeWithTag(ObjectMenuTags.DeleteTable).performClick()
        runOnIdle {
            assertTrue(observed.selectedPage!!.document.outlines.none { it.id == table.id })
            assertNull(observed.focusedTextOutlineId)
        }
    }

    private fun shape(id: String, x: Float) = Outline.Shape(id = id, kind = ShapeKind.Rectangle,
        segments = seedSegments(ShapeKind.Rectangle, x, 350f, x + 120f, 430f, ::newId)).withRecomputedBounds()

    private fun workspace(outlines: List<Outline>): WorkspaceState {
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        return base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = outlines)) }.clearCanvasFocus()
    }

    private fun shapes(state: WorkspaceState): List<Outline.Shape> =
        state.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>()

    private fun ComposeUiTest.setWorkspace(initial: WorkspaceState, onStateChange: (WorkspaceState) -> Unit) {
        val clipboard = object : TextClipboard {
            var value: AnnotatedString? = null
            override fun getText(): AnnotatedString? = value
            override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
        }
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme {
                CompositionLocalProvider(LocalTextClipboard provides clipboard) {
                    WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
                }
            }
        }
    }

    private companion object {
        const val SHAPE = "menu-shape"
        const val OTHER = "menu-other"
    }
}
