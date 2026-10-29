package com.vivenotes.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import com.vivenotes.data.TextClipboard
import com.vivenotes.data.LocalTextClipboard
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageStyle
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ctrl+C, Ctrl+X and Ctrl+V on the canvas use the shared object clipboard the toolkit's Copy fills;
 * in a text box they stay the text's own.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasClipboardKeysTest {

    /** Regression: with an object selected, Ctrl+C did nothing — the keys only reached a text box. */
    @Test
    fun ctrlCCopiesTheSelectedObjectAndCtrlVPastesItUnderTheMouse() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = withShape()
        setWorkspace(observed) { observed = it }
        onNodeWithTag(WorkspaceTestTags.primeObject(SHAPE)).performClick()
        runOnIdle { assertEquals(setOf(SHAPE), observed.selectedObjectIds) }

        ctrl(Key.C)
        runOnIdle {
            assertEquals(listOf(SHAPE), observed.canvasClipboard.objects.map { it.id })
            assertEquals(1, shapes(observed).size, "Copy is copy: nothing lands on the page")
        }
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { moveTo(Offset(700f, 600f)) }
        ctrl(Key.V)
        runOnIdle {
            val pasted = shapes(observed).single { it.id != SHAPE }
            assertEquals(700f, pasted.x, 0.5f)
            assertEquals(600f, pasted.y, 0.5f)
            assertEquals(setOf(pasted.id), observed.selectedObjectIds)
        }
    }

    @Test
    fun ctrlXCutsAsOneUndoStepAndCtrlVAwayFromThePagePastesInsideTheVisibleCorner() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            // Selected before the window opens, and everything after from the keyboard: the mouse
            // never comes over the page.
            var observed = withShape().selectObject(SHAPE)
            setWorkspace(observed) { observed = it }

            ctrl(Key.X)
            runOnIdle {
                assertTrue(shapes(observed).isEmpty())
                assertEquals(listOf(SHAPE), observed.canvasClipboard.objects.map { it.id })
                assertEquals(1, observed.structuralUndo.size)
            }
            ctrl(Key.Z)
            runOnIdle { assertEquals(listOf(SHAPE), shapes(observed).map { it.id }) }

            ctrl(Key.V)
            runOnIdle {
                val pasted = shapes(observed).single { it.id != SHAPE }
                assertEquals(WorkspaceState.PICTURE_INSERT_MARGIN, pasted.x, 0.5f)
                assertEquals(PageStyle.TITLE_BAND_DP, pasted.y, 0.5f)
            }
        }

    @Test
    fun withNothingSelectedTheCanvasKeysChangeNothing() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = withShape()
        val initial = observed
        setWorkspace(observed) { observed = it }
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { moveTo(Offset(700f, 600f)) }
        ctrl(Key.C)
        ctrl(Key.X)
        ctrl(Key.V)
        runOnIdle {
            assertTrue(observed.canvasClipboard.isEmpty)
            assertEquals(initial.selectedPage!!.document, observed.selectedPage!!.document)
        }
    }

    /** In a text box the keys stay the text's own, with objects waiting on the canvas clipboard. */
    @Test
    fun aTextBoxKeepsItsOwnCopyAndPasteWhileTheCanvasClipboardHoldsObjects() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val clipboard = FakeClipboard()
            var observed = withShape().selectObject(SHAPE).copySelectedObjects().clearCanvasFocus()
            setWorkspace(observed, clipboard) { observed = it }
            val editor = onNodeWithTag(WorkspaceTestTags.BodyEditor)
            editor.performClick()
            runOnIdle { assertEquals(observed.bodyTextOutline!!.id, observed.focusedTextOutlineId) }

            editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
            editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
            runOnIdle {
                assertEquals("start", clipboard.value?.text)
                assertEquals("start", observed.textClipboard?.text)
                assertEquals(listOf(SHAPE), observed.canvasClipboard.objects.map { it.id })
            }
            editor.performKeyInput { pressKey(Key.MoveEnd) }
            editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
            runOnIdle {
                assertEquals("startstart", observed.bodyTextOutline!!.blocks.joinToString("\n") { it.text })
                assertEquals(1, shapes(observed).size)
            }
        }

    private fun withShape(): WorkspaceState {
        val demo = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
        val body = demo.bodyTextOutline!!.id
        return demo.updatePage(demo.selectedPageId) { page ->
            page.copy(document = page.document.copy(outlines = page.document.outlines.map { outline ->
                if (outline is Outline.Text && outline.id == body) outline.copy(blocks = listOf(Block.of("start")))
                else outline
            } + Outline.Shape(id = SHAPE, x = 300f, y = 350f)))
        }.clearCanvasFocus()
    }

    private fun shapes(state: WorkspaceState): List<Outline.Shape> =
        state.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>()

    private fun ComposeUiTest.ctrl(key: Key) =
        onRoot().performKeyInput { keyDown(Key.CtrlLeft); keyDown(key); keyUp(key); keyUp(Key.CtrlLeft) }

    private fun ComposeUiTest.setWorkspace(
        initial: WorkspaceState,
        clipboard: TextClipboard = FakeClipboard(),
        onStateChange: (WorkspaceState) -> Unit = {},
    ) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                CompositionLocalProvider(LocalTextClipboard provides clipboard) {
                    WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
                }
            }
        }
    }

    private class FakeClipboard(var value: AnnotatedString? = null) : TextClipboard {
        override fun getText(): AnnotatedString? = value
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
    }

    private companion object {
        const val SHAPE = "keys-shape"
    }
}
