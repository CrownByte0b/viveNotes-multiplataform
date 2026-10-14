package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ai.*
import com.vivenotes.math.*
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.InkPage
import com.vivenotes.ui.canvas.INK_LAYER_TAG
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class CalculatorInteractionTest {
    private class Models(ready: Boolean) : AiModels {
        override val state = MutableStateFlow(AiModelsState(formulaEngine = FormulaEngine.FormulaNetS,
            formulaLatex = if (ready) AiModelInstallState.Installed else AiModelInstallState.NotInstalled))
        override fun download(engine: FormulaEngine) {}
        override fun delete(engine: FormulaEngine) {}
        override fun selectFormulaEngine(engine: FormulaEngine) {}
    }
    private class Math : DrawMath {
        val reads = mutableListOf<InkPage>()
        val calls = mutableListOf<Pair<String, String>>()
        override val engine = object : MathEngine {
            override suspend fun analyze(latex: String) = MathAnalysis(actions = listOf(MathAction("simplify", "Simplify")))
            override suspend fun execute(latex: String, actionId: String): MathOperationResult {
                calls += latex to actionId
                return MathOperationResult(latex = "\\frac{1}{2}", decimal = "0.5")
            }
        }
        override suspend fun readFormula(ink: InkPage): String { reads += ink; return "1 / 2 = " }
    }

    @Test
    fun calculatorIsAbsentWithoutAFormulaModelAndAppearsWhenTheSelectedModelIsReady() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Draw))
        val models = Models(false)
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, aiModels = models, drawMath = Math()) } }
        onNodeWithTag("draw-calculator").assertDoesNotExist()
        runOnIdle { models.state.value = models.state.value.copy(formulaLatex = AiModelInstallState.Installed) }
        onNodeWithTag("draw-calculator").assertIsDisplayed().performClick()
        onNodeWithTag(CalculatorTags.Window).assertIsDisplayed()
        runOnIdle { models.state.value = models.state.value.copy(formulaLatex = AiModelInstallState.NotInstalled) }
        onNodeWithTag(CalculatorTags.Window).assertDoesNotExist()
    }

    @Test
    fun scratchInkSolvesThroughRecognitionAndMathWithoutEditingThePageAndCanOpenTheMathPane() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        val state = mutableStateOf(base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = emptyList())) })
        val original = state.value.selectedPage!!.document
        val math = Math()
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, aiModels = Models(true), drawMath = math) } }
        onNodeWithTag("draw-calculator").performClick()
        onNodeWithTag(CalculatorTags.Equals).assertIsNotEnabled()
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(20f, 80f)); press(); moveTo(Offset(200f, 80f), delayMillis = 80); release()
        }
        onNodeWithTag(CalculatorTags.Equals).performClick()
        waitUntil(timeoutMillis = 5000) { math.calls.isNotEmpty() }
        onNodeWithTag(CalculatorTags.Result).assertExists()
        onNodeWithTag(CalculatorTags.Decimal).performClick()
        runOnIdle {
            assertEquals(listOf("1 / 2" to "simplify"), math.calls)
            assertEquals(1, math.reads.single().strokes.size)
            assertSame(original, state.value.selectedPage!!.document)
            assertTrue(state.value.pendingInkEdits.isEmpty())
            assertTrue(state.value.structuralUndo.isEmpty())
        }
        onNodeWithTag(CalculatorTags.OpenInPanel).performClick()
        onNodeWithTag(CalculatorTags.Window).assertIsDisplayed()
        onNodeWithTag(CalculatorTags.Equals).assertIsEnabled()
        val calculatorBounds = onNodeWithTag(CalculatorTags.Window).fetchSemanticsNode().boundsInRoot
        val sourceBounds = onNodeWithTag("draw-math-source").fetchSemanticsNode().boundsInRoot
        assertTrue(calculatorBounds.right <= sourceBounds.left, "the scratchpad leaves the Math pane reachable")
        onNodeWithTag(CalculatorTags.Undo).performClick()
        onNodeWithTag(CalculatorTags.Equals).assertIsNotEnabled()
        onNodeWithTag("draw-math-source").assertTextContains("1 / 2")
        mainClock.advanceTimeBy(300)
        onNodeWithTag("draw-math-action-simplify").assertExists()
    }
}
