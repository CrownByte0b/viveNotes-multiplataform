package com.vivenotes.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.ai.*
import com.vivenotes.ink.*
import com.vivenotes.math.*
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.InkPage
import com.vivenotes.ui.ribbon.draw.DrawMath
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InkToolkitInteractionTest {
    private class Models(ready: Boolean = true) : AiModels {
        override val state = MutableStateFlow(AiModelsState(formulaEngine = FormulaEngine.FormulaNetS,
            formulaLatex = if (ready) AiModelInstallState.Installed else AiModelInstallState.NotInstalled))
        override fun download(engine: FormulaEngine) {}
        override fun delete(engine: FormulaEngine) {}
        override fun selectFormulaEngine(engine: FormulaEngine) {}
    }

    private class Math(private val read: suspend (Int) -> String = { "x^2-4=0=" }) : DrawMath {
        val reads = mutableListOf<InkPage>()
        val calls = mutableListOf<Pair<String, String>>()
        override val engine = object : MathEngine {
            override suspend fun analyze(latex: String) = MathAnalysis(actions = listOf(
                MathAction("solve", "Solve"), MathAction("simplify", "Simplify")))
            override suspend fun execute(latex: String, actionId: String): MathOperationResult {
                calls += latex to actionId
                return MathOperationResult(latex = "x = -2, 2")
            }
        }
        override suspend fun readFormula(ink: InkPage): String {
            val index = reads.size
            reads += ink
            return read(index)
        }
    }

    private fun workspace(): WorkspaceState {
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        val ink = InkPageReader.read(base.selectedPageId, listOf(selectionLine("one", base.selectedPageId),
            selectionLine("two", base.selectedPageId, 240f)), emptyList(), emptyList())
        return base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = emptyList()), ink = ink) }
            .selectObjectsInRect(0f, 180f, 120f, 260f)
    }

    @Test
    fun groupButtonMakesInkSelectableAndMovableAsOneObjectAndCanUngroupIt() = runDesktopComposeUiTest(width = 1500, height = 900) {
        val state = mutableStateOf(workspace())
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertTextEquals("Group").performMouseInput { click(center) }
        waitUntil(timeoutMillis = 5000) { state.value.selectedInk?.isGrouped == true }
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertTextEquals("Ungroup")
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { click(Offset(400f, 400f)) }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput { click(Offset(40f, 200f)) }
        runOnIdle { assertEquals(setOf("one", "two"), state.value.selectedInk!!.targetIds) }
        onNodeWithTag(INK_SELECTION_FRAME_TAG).performMouseInput {
            updatePointerTo(center); press(); moveBy(Offset(40f, 30f), delayMillis = 80); release()
        }
        waitUntil(timeoutMillis = 5000) { state.value.structuralUndo.size == 2 }
        runOnIdle {
            assertTrue(state.value.selectedPage!!.ink!!.desktopGeometry().projections.all { it.pageBounds!!.left > 40f })
        }
        onNodeWithTag(WorkspaceTestTags.InkGroup).performMouseInput { click(center) }
        waitUntil(timeoutMillis = 5000) { state.value.selectedInk?.isGrouped == false }
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { click(Offset(400f, 400f)) }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput { click(Offset(80f, 230f)) }
        runOnIdle {
            assertEquals(setOf("one"), state.value.selectedInk!!.targetIds)
            assertEquals(3, state.value.structuralUndo.size)
        }
        onNodeWithTag(WorkspaceTestTags.ObjectLock).assertDoesNotExist()
    }

    @Test
    fun mathAndGroupBelongToInkOnlySelectionsAndMathFollowsModelAvailability() = runDesktopComposeUiTest(width = 1500, height = 900) {
        val state = mutableStateOf(workspace())
        val models = Models(false)
        val math = Math()
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, aiModels = models, drawMath = math) } }
        onNodeWithTag(WorkspaceTestTags.InkMath).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertIsDisplayed()
        runOnIdle { models.state.value = models.state.value.copy(formulaLatex = AiModelInstallState.Installed) }
        onNodeWithTag(WorkspaceTestTags.InkMath).assertIsDisplayed()
        runOnIdle {
            val equation = Outline.Equation("equation", 200f, 200f, 80f, 40f, "x")
            state.value = state.value.updatePage(state.value.selectedPageId) {
                it.copy(document = it.document.copy(outlines = listOf(equation)))
            }.copy(selectedObjectIds = setOf(equation.id))
        }
        onNodeWithTag(WorkspaceTestTags.InkMath).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.ObjectCopy).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.ObjectDelete).assertIsDisplayed()
        runOnIdle { assertTrue(math.reads.isEmpty()) }
    }

    @Test
    fun mathReadsOnlyTheSelectedVisibleFragmentAndOpensFunctionsWithoutChangingThePage() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val base = WorkspaceState.demo()
        val (_, split) = splitSelectionPage(base.selectedPageId)
        val move = split.geometry!!.selectInRect(split, 0f, 180f, 55f, 220f)!!.move(20f, 40f)!!
        val selected = base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = emptyList()), ink = move.after) }
            .selectInk(move.selection!!)
        val state = mutableStateOf(selected)
        val math = Math()
        val development = mutableStateOf(false)
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, aiModels = Models(),
            drawMath = math, developmentBuild = development.value) } }
        onNodeWithTag(WorkspaceTestTags.InkMath).performMouseInput { click(center) }
        waitUntil(timeoutMillis = 5000) { math.reads.isNotEmpty() }
        onNodeWithTag("draw-math-source").assertIsDisplayed().assertTextContains("x^2-4=0")
        assertEquals(320f, onNodeWithTag("tool-pane").fetchSemanticsNode().boundsInRoot.width)
        mainClock.advanceTimeBy(300)
        waitUntil(timeoutMillis = 5000) { math.calls.isNotEmpty() }
        onNodeWithText("Understood as").assertDoesNotExist()
        runOnIdle { development.value = true }
        onNodeWithText("Understood as").assertIsDisplayed()
        runOnIdle { development.value = false }
        onNodeWithText("Understood as").assertDoesNotExist()
        onNodeWithTag("draw-math-action-simplify").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 5000) { math.calls.size == 2 }
        runOnIdle {
            assertEquals(listOf("x^2-4=0" to "solve", "x^2-4=0" to "simplify"), math.calls)
            val piece = math.reads.single().desktopGeometry().projections.single()
            assertEquals(selected.selectedInk!!.bounds.left, piece.pageBounds!!.left)
            assertTrue(piece.pageBounds!!.right < 80f)
            assertSame(selected.selectedPage!!.ink, state.value.selectedPage!!.ink)
            assertSame(selected.selectedPage!!.document, state.value.selectedPage!!.document)
            assertEquals(selected.pendingInkEdits, state.value.pendingInkEdits)
            assertEquals(selected.structuralUndo, state.value.structuralUndo)
        }
    }

    @Test
    fun recognitionCanRetryAndLosingTheModelClosesItsPane() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = mutableStateOf(workspace())
        val models = Models()
        val math = Math { if (it == 0) error("Recognition failed") else "x^2-4=0" }
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, aiModels = models, drawMath = math) } }
        onNodeWithTag(WorkspaceTestTags.InkMath).performClick()
        waitUntil(timeoutMillis = 5000) { math.reads.size == 1 }
        onNodeWithTag("ink-math-error").assertTextEquals("Recognition failed")
        onNodeWithTag("ink-math-retry").performClick()
        waitUntil(timeoutMillis = 5000) { math.reads.size == 2 }
        onNodeWithTag("draw-math-source").assertTextContains("x^2-4=0")
        runOnIdle { models.state.value = models.state.value.copy(formulaLatex = AiModelInstallState.NotInstalled) }
        onNodeWithTag("draw-math-source").assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.InkMath).assertDoesNotExist()
        runOnIdle { assertTrue(state.value.pendingInkEdits.isEmpty()) }
    }

    @Test
    fun aSlowOldRecognitionCannotReplaceTheMathForANewSelection() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val gates = List(2) { CompletableDeferred<Unit>() }
        val finished = mutableListOf<Int>()
        val state = mutableStateOf(workspace())
        val math = Math { index ->
            withContext(NonCancellable) { gates[index].await(); finished += index }
            if (index == 0) "old" else "new"
        }
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, aiModels = Models(), drawMath = math) } }
        onNodeWithTag(WorkspaceTestTags.InkMath).performClick()
        waitUntil(timeoutMillis = 5000) { math.reads.size == 1 }
        runOnIdle { state.value = state.value.selectPointer() }
        onNodeWithTag("ink-math-loading").assertDoesNotExist()
        runOnIdle { state.value = state.value.selectInkAt(40f, 240f) }
        onNodeWithTag(WorkspaceTestTags.InkMath).performClick()
        waitUntil(timeoutMillis = 5000) { math.reads.size == 2 }
        runOnIdle { gates[1].complete(Unit) }
        waitUntil(timeoutMillis = 5000) { 1 in finished }
        onNodeWithTag("draw-math-source").assertTextContains("new")
        runOnIdle { gates[0].complete(Unit) }
        waitUntil(timeoutMillis = 5000) { 0 in finished }
        onNodeWithTag("draw-math-source").assertTextContains("new")
        runOnIdle { assertTrue(math.calls.none { it.first == "old" }) }
    }

    @Test
    fun toolbarMatchesTheReferenceOrderColoursDimensionsAndCaptionedActions() = runDesktopComposeUiTest(width = 1000, height = 400) {
        val selected = workspace().selectedInk!!.recolor(0xffffffff.toInt())!!.selection!!
        setContent { ViveNotesTheme(darkTheme = true) {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Box(Modifier.size(500.dp, 200.dp)) {
                    InkSelectionToolkit(selected, Rect(20f, 180f, 300f, 250f), IntSize(1000, 400), emptyList(),
                        Color.White, enabled = true, showColor = true, onBoundsChange = {}, onCopy = {},
                        onDelete = {}, onColor = {}, showGroup = true, showMath = true, onGroup = {}, onMath = {})
                }
            }
        } }
        val tags = listOf(WorkspaceTestTags.ObjectColor, WorkspaceTestTags.InkColorDivider,
            WorkspaceTestTags.ObjectCopy, WorkspaceTestTags.InkMath, WorkspaceTestTags.InkGroup, WorkspaceTestTags.ObjectDelete)
        val bounds = tags.map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.zipWithNext().all { (left, right) -> left.right <= right.left })
        onNodeWithTag(WorkspaceTestTags.InkMath).assertTextEquals("Math")
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertTextEquals("Group")
        onNodeWithTag(WorkspaceTestTags.ObjectLock).assertDoesNotExist()
        val toolbar = onNodeWithTag(WorkspaceTestTags.ObjectToolkit)
        assertEquals(72f, toolbar.fetchSemanticsNode().boundsInRoot.height)
        val pixels = toolbar.captureToImage().toPixelMap()
        assertEquals(Color(0xff202124), pixels[pixels.width / 2, 4])
        val delete = onNodeWithTag(WorkspaceTestTags.ObjectDelete).captureToImage().toPixelMap()
        assertTrue((0 until delete.width).any { x -> (0 until delete.height).any { y -> delete[x, y] == Color(0xffffa8a8) } })
        Image.makeFromBitmap(toolbar.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                File("build/test-artifacts").apply { mkdirs() }.resolve("ink-toolkit-reference.png").writeBytes(it.bytes)
            }
        }
    }
}
