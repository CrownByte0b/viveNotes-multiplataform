package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ai.*
import com.vivenotes.math.*
import com.vivenotes.model.*
import com.vivenotes.model.ink.InkPage
import com.vivenotes.ui.canvas.INK_LAYER_TAG
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class GraphUiTest {
    private class Models : AiModels {
        override val state = MutableStateFlow(AiModelsState(formulaEngine = FormulaEngine.FormulaNetS,
            formulaLatex = AiModelInstallState.Installed))
        override fun download(engine: FormulaEngine) {}
        override fun delete(engine: FormulaEngine) {}
        override fun selectFormulaEngine(engine: FormulaEngine) {}
    }
    private class Math : DrawMath {
        var supported = true
        var parameters = false
        override val engine = object : MathEngine {
            override suspend fun analyze(latex: String) = MathAnalysis(actions = listOf(MathAction("simplify", "Simplify"), MathAction("graph", "Graph")))
            override suspend fun execute(latex: String, actionId: String) = if (actionId == "graph")
                MathOperationResult(title = "Graph", graph = if (parameters) MathGraph("x", "y", listOf(-2.0, 0.0, 2.0), listOf(4.0, 0.0, 4.0),
                    "((\$0*(x^2))+\$1)", "a x^{2} + \\omega",
                    listOf(GraphParameter("a", "a", 1.0), GraphParameter("omega", "ω", 0.0)))
                    else MathGraph("x", "y", listOf(-2.0, 0.0, 2.0), listOf(0.0, -4.0, 0.0),
                    if (supported) "((x^2)+(-4))" else null, "x^{2}-4"))
                else MathOperationResult(latex = "x^{2}-4")
        }
        override suspend fun readFormula(ink: InkPage, lines: List<Outline.Shape>) = "x^2-4"
    }

    @Test
    fun calculatorToMathPaneToPlacedGraphUsesTheWorkspaceAndCanUndo() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Draw))
        val before = state.value.selectedPage!!.document
        val math = Math(); val models = Models()
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, aiModels = models, drawMath = math) } }
        onNodeWithTag("draw-calculator").performClick()
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(20f, 80f)); press(); moveTo(Offset(200f, 80f), delayMillis = 80); release()
        }
        onNodeWithTag(CalculatorTags.Equals).performClick()
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(CalculatorTags.OpenInPanel).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CalculatorTags.OpenInPanel).performClick()
        mainClock.advanceTimeBy(300)
        onNodeWithTag(MathPaneTags.action("graph")).performClick()
        onNodeWithTag("draw-math-place-graph").performScrollTo().assertIsDisplayed().performClick()
        val graph = runOnIdle { state.value.selectedPrimeObjects.single() as Outline.Graph }
        onNodeWithTag(WorkspaceTestTags.primeObject(graph.id)).assertIsDisplayed()
        onNodeWithTag(GraphToolkitTags.MUTATE).assertIsDisplayed()
        runOnIdle {
            assertEquals("((x^2)+(-4))", graph.expression)
            assertFalse(state.value.objectLassoArmed)
            assertEquals(before, state.value.undoStructure().selectedPage!!.document)
        }
    }

    private fun equationPage(): MutableState<WorkspaceState> {
        val equation = Outline.Equation(id = "eq", x = 120f, y = 160f, width = 160f, height = 48f, latex = "y=x^2-4")
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        return mutableStateOf(base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(equation))) }
            .selectObject(equation.id))
    }

    /** Typed formulas reach the pane with no formula model; a graph placed from there lands below them. */
    @Test
    fun anEquationsMathButtonOpensItInTheMathPaneAndItsGraphLandsBelowIt() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = equationPage()
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, drawMath = Math()) } }

        onNodeWithTag(EquationToolkitTags.MATH).assertIsDisplayed().performClick()
        onNodeWithTag(MathPaneTags.Source).assert(hasText("y=x^2-4"))
        mainClock.advanceTimeBy(300)
        onNodeWithTag(MathPaneTags.action("graph")).performClick()
        onNodeWithTag("draw-math-place-graph").performScrollTo().performClick()

        runOnIdle {
            val graph = state.value.selectedPrimeObjects.single() as Outline.Graph
            assertEquals(120f, graph.x)
            assertEquals(160f + 48f + 24f, graph.y)
            // The pane held a copy: the equation is as it was.
            assertEquals("y=x^2-4", state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Equation>().single().latex)
        }
        onNodeWithTag(MathPaneTags.Source).assertDoesNotExist()
    }

    @Test
    fun anEquationsGraphButtonPlacesItsGraphBelowItAsOneUndo() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = equationPage()
        val before = state.value.selectedPage!!.document
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, drawMath = Math()) } }

        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(EquationToolkitTags.GRAPH).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(EquationToolkitTags.GRAPH).performClick()

        runOnIdle {
            val graph = state.value.selectedPrimeObjects.single() as Outline.Graph
            assertEquals("((x^2)+(-4))", graph.expression)
            assertEquals(160f + 48f + 24f, graph.y)
            assertEquals(before, state.value.undoStructure().selectedPage!!.document)
        }
    }

    @Test
    fun withoutAnEngineAnEquationHasNeitherMathNorGraph() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = equationPage()
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(EquationToolkitTags.EDIT).assertIsDisplayed()
        onNodeWithTag(EquationToolkitTags.MATH).assertDoesNotExist()
        onNodeWithTag(EquationToolkitTags.GRAPH).assertDoesNotExist()
    }

    /** The preview is one setting of the sliders the placed graph will have; the pane says which. */
    @Test
    fun aFormulaWithSlidersSaysWhereItWasDrawnAndArrivesWithThem() = runDesktopComposeUiTest(width = 900, height = 1000) {
        val math = Math().apply { parameters = true }
        var placed: MathGraph? = null
        setContent { ViveNotesTheme { MathPane("y=a x^2", math, onPlaceGraph = { placed = it }) {} } }
        mainClock.advanceTimeBy(300)
        onNodeWithTag(MathPaneTags.action("graph")).performClick()
        onNodeWithTag(MathPaneTags.GraphParameters).performScrollTo()
            .assert(hasText("Drawn at a = 1.0, ω = 0.0. Each gets a slider once placed."))
        onNodeWithTag("draw-math-place-graph").performScrollTo().performClick()
        assertEquals(listOf("a", "omega"), placed?.parameters?.map { it.symbol })
    }

    /** The Sliders dialog belongs to the window; its answer is one Undo step. */
    @Test
    fun theSlidersDialogFromTheOptionsMenuSetsARangeAsOneUndo() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val graph = Outline.Graph("graph", x = 100f, y = 200f, width = 480f, height = 260f + 2 * 36f,
            expression = "((\$0*(x^2))+\$1)", latex = "a x^{2} + \\omega", xMin = -5.0, xMax = 5.0, yMin = -5.0, yMax = 5.0,
            parameters = listOf(GraphParameter("a", "a", 1.0), GraphParameter("omega", "ω", 0.0)))
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        val state = mutableStateOf(base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(graph))) }.selectObject(graph.id))
        val before = state.value.selectedPage!!.document
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, drawMath = Math()) } }

        onNodeWithTag(GraphToolkitTags.OPTIONS).performClick()
        onNodeWithTag(GraphToolkitTags.SLIDERS).performClick()
        onNodeWithTag(GraphSlidersDialogTags.field(1, SliderField.Min)).performTextReplacement("0")
        onNodeWithTag(GraphSlidersDialogTags.field(1, SliderField.Step)).performTextReplacement("20")
        onNodeWithTag(GraphSlidersDialogTags.APPLY).assertIsNotEnabled()
        onNodeWithTag(GraphSlidersDialogTags.field(1, SliderField.Step)).performTextReplacement("0.5")
        onNodeWithTag(GraphSlidersDialogTags.field(1, SliderField.Value)).performTextReplacement("−3,7")
        onNodeWithTag(GraphSlidersDialogTags.APPLY).performClick()

        onNodeWithTag(GraphSlidersDialogTags.DIALOG).assertDoesNotExist()
        runOnIdle {
            val omega = (state.value.selectedPrimeObjects.single() as Outline.Graph).parameters[1]
            // Pulled into the new range, then onto its steps.
            assertEquals(0.0, omega.min); assertEquals(0.5, omega.step); assertEquals(0.0, omega.value)
            assertEquals(before, state.value.undoStructure().selectedPage!!.document)
        }
    }

    @Test
    fun unsupportedPreviewOffersNoPlacement() = runDesktopComposeUiTest(width = 900, height = 1000) {
        val math = Math().apply { supported = false }
        var placed = false
        setContent { ViveNotesTheme { MathPane("x", math, onPlaceGraph = { placed = true }) {} } }
        mainClock.advanceTimeBy(300)
        onNodeWithTag(MathPaneTags.action("graph")).performClick()
        onNodeWithTag("draw-math-graph").performScrollTo().assertIsDisplayed()
        onNodeWithTag("draw-math-place-graph").assertDoesNotExist()
        assertFalse(placed)
    }

    @Test
    fun toolkitOptionsPersistAndTheRenderedGraphShowsItsOfficialIconsAndMeasurements() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val graph = Outline.Graph("graph", x = 100f, y = 200f, width = 480f, height = 390f,
            expression = "((x^2)+(-4))", latex = "x^{2}-4", xMin = -5.0, xMax = 5.0, yMin = -5.0, yMax = 5.0)
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        val state = mutableStateOf(base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(graph))) }.selectObject(graph.id))
        val math = Math()
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, drawMath = math) } }
        onNodeWithTag(GraphToolkitTags.MUTATE).performClick()
        onNodeWithTag(GraphToolkitTags.OPTIONS).performClick()
        onNodeWithTag(GraphToolkitTags.background(GraphBackground.Dotted)).performClick()
        onNodeWithTag(GraphToolkitTags.DERIVATIVE).performClick()
        onNodeWithTag(GraphToolkitTags.TRACE).performClick()
        onNodeWithTag(GraphToolkitTags.AREA).performClick()
        onNodeWithTag(GraphToolkitTags.ZOOM_IN).performClick()
        onNodeWithTag(GraphToolkitTags.FIT).performClick()
        runOnIdle {
            val changed = state.value.selectedPrimeObjects.single() as Outline.Graph
            assertTrue(changed.mutable); assertTrue(changed.showDerivative)
            assertEquals(GraphBackground.Dotted, changed.background)
            assertNotNull(changed.traceX); assertNotNull(changed.area)
        }
        val icon = onNodeWithTag(GraphToolkitTags.OPTIONS).captureToImage().toPixelMap()
        val middleColors = (icon.width / 4 until icon.width * 3 / 4).flatMap { x ->
            (icon.height / 4 until icon.height * 3 / 4).map { y -> icon[x, y] }
        }.distinct()
        assertTrue(middleColors.size > 3, "official options icon must be visible")
        val image = onRoot().captureToImage()
        File("build/ui-references").mkdirs()
        Image.makeFromBitmap(image.asSkiaBitmap()).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
            File("build/ui-references/graph-options.png").writeBytes(data.bytes)
        } }
        onNodeWithTag(WorkspaceTestTags.ObjectCopy).performClick()
        runOnIdle { assertEquals(state.value.selectedPrimeObjects.single(), state.value.canvasClipboard.objects.single()) }
        onNodeWithTag(WorkspaceTestTags.ObjectLock).performClick()
        repeat(4) { onNodeWithTag(WorkspaceTestTags.objectCorner(graph.id, it)).assertDoesNotExist() }
        onNodeWithTag(GraphToolkitTags.MUTATE).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.ObjectDelete).performClick()
        runOnIdle { assertTrue(state.value.selectedPage!!.document.outlines.isEmpty()) }
    }

    @Test
    fun draggingARootThroughTheRealEngineCommitsOneExactUndoableFunction() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val realEngine = MathRuntimeFixture.runtime().engine
        val math = object : DrawMath {
            override val engine = realEngine
            override suspend fun readFormula(ink: InkPage, lines: List<Outline.Shape>) = "x^2-4"
        }
        val graph = Outline.Graph("graph", x = 100f, y = 200f, width = 400f, height = 300f,
            expression = "((x^2)+(-4))", latex = "x^{2}-4", mutable = true, showEquation = false,
            xMin = -5.0, xMax = 5.0, yMin = -5.0, yMax = 5.0)
        val base = WorkspaceState.demo()
        val state = mutableStateOf(base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(graph))) }.selectObject(graph.id))
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, drawMath = math) } }
        val objectNode = onNodeWithTag(WorkspaceTestTags.primeObject(graph.id))
        objectNode.performTouchInput { down(Offset(width * .7f, height * .5f)) }
        objectNode.performTouchInput { moveTo(Offset(width * .75f, height * .48f)) }
        objectNode.performTouchInput { moveTo(Offset(width * .8f, height * .45f)) }
        runOnIdle {
            assertEquals(graph.expression, (state.value.selectedPrimeObjects.single() as Outline.Graph).expression)
            assertTrue(state.value.structuralUndo.isEmpty())
        }
        objectNode.performTouchInput { up() }
        waitUntil(timeoutMillis = 10_000) { (state.value.selectedPrimeObjects.single() as Outline.Graph).expression != graph.expression }
        runOnIdle {
            val changed = state.value.selectedPrimeObjects.single() as Outline.Graph
            val f = com.vivenotes.graph.GraphExpression.parse(changed.expression)!!
            assertEquals(0.0, f.evaluate(-2.0), 1e-9)
            assertEquals(0.0, f.evaluate(3.0), 1e-9)
            assertEquals(graph.expression, changed.originalExpression)
            assertEquals(1, state.value.structuralUndo.size)
            assertEquals(graph, state.value.undoStructure().selectedPage!!.document.outlines.single())
        }
    }
}
