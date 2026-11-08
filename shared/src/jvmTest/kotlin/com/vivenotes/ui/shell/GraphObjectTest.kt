package com.vivenotes.ui.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.vivenotes.ui.canvas.RichTextColors
import com.vivenotes.ui.ribbon.draw.GraphToolkitActions
import com.vivenotes.ui.ribbon.draw.GraphToolkitTags
import com.vivenotes.ui.ribbon.draw.EquationToolkitTags
import com.vivenotes.ui.ribbon.draw.GraphSlidersDialog
import com.vivenotes.ui.ribbon.draw.GraphSlidersDialogTags
import com.vivenotes.ui.ribbon.draw.GraphableFormulas
import com.vivenotes.ui.ribbon.draw.SliderField
import com.vivenotes.ui.canvas.GraphSliderTags
import com.vivenotes.model.GraphParameter
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.vivenotes.workspace.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vivenotes.graph.GraphDrag
import com.vivenotes.model.GraphInterval
import com.vivenotes.model.Outline
import com.vivenotes.model.PageStyle
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A graph on the page, through the whole [EditorPane]: selected by a tap with its toolkit raised,
 * moved by a drag, and — with mutate on — rewritten by dragging one of its points.
 *
 * The arithmetic of points and drags is `GraphMathTest`, on the JVM. What needs a device is that the
 * layer sits where the page asks it to and the touches reach it.
 */
@OptIn(ExperimentalTestApi::class)
class GraphObjectTest {

    private lateinit var compose: ComposeUiTest
    private val workspace = mutableStateOf(WorkspaceState.demo())
    private fun scene(block: () -> Unit) = runDesktopComposeUiTest(width = 900, height = 900) { compose = this; block() }

    private val graphs = mutableStateOf(emptyList<Outline.Graph>())
    private val equations = mutableStateOf(emptyList<Outline.Equation>())
    private val slidersOf = mutableStateOf<String?>(null)
    private var graphable: GraphableFormulas = GraphableFormulas.None
    private var graphed = mutableListOf<Outline.Equation>()
    private var opened = mutableListOf<Outline.Equation>()
    private var moved: Pair<Float, Float>? = null
    private var mutated: Pair<String, GraphDrag>? = null
    private lateinit var density: Density

    /**
     * x² − 4 on −5…5 in both directions, filling a 320 × 260 box at (40, 40) with no header, so a
     * point's place on the page is plain arithmetic: x maps to 40 + (x + 5) × 32, y to 170 − y × 26.
     */
    private fun parabola(mutable: Boolean, traceX: Double? = null, area: GraphInterval? = null) = Outline.Graph(
        id = "graph",
        x = 40f,
        y = 40f,
        width = 320f,
        height = 260f,
        expression = "((x^2)+(-4))",
        latex = "x^{2} - 4",
        xMin = -5.0,
        xMax = 5.0,
        yMin = -5.0,
        yMax = 5.0,
        showEquation = false,
        mutable = mutable,
        traceX = traceX,
        area = area,
    )

    private fun pageX(x: Double): Float = (40 + (x + 5) * 32).toFloat()
    private fun pageY(y: Double): Float = (170 - y * 26).toFloat()

    /**
     * A sin(ωx), A and ω at 1: the parabola's box and plot with the two slider rows under it, so the
     * plot keeps its arithmetic. Row i is centred at 318 + 36i; every track runs from 146 to 319.
     */
    private fun wave() = parabola(mutable = false).copy(
        expression = "(\$0*sin((\$1*x)))",
        latex = "A \\sin{\\left(\\omega x \\right)}",
        height = 260f + 2 * 36f,
        parameters = listOf(GraphParameter("A", "A", 1.0), GraphParameter("omega", "ω", 1.0)),
    )

    private fun sliderX(value: Double): Float = (146 + (value + 10) / 20 * 173).toFloat()
    private fun sliderY(index: Int): Float = 318f + 36f * index

    private fun setPage(vararg page: Outline.Graph) {
        graphs.value = page.toList()
        moved = null
        mutated = null
        compose.setContent {
            density = LocalDensity.current
            ViveNotesTheme {
                val base = workspace.value
                val state = base.updatePage(base.selectedPageId) { it.copy(document = com.vivenotes.model.PageDoc(
                    style = PageStyle(hideTitle = true), outlines = graphs.value + equations.value)) }
                val current = rememberUpdatedState(state)
                val focus = remember { FocusRequester() }
                fun update(change: (Outline.Graph) -> Outline.Graph) {
                    graphs.value = graphs.value.map(change)
                }
                Box(Modifier.fillMaxSize().focusRequester(focus).focusable().testTag("graph-layer")) {
                    CanvasPrimeObjects(
                        viewport = { Rect(0f, 0f, 900f, 900f) }, state = state, workspaceState = current,
                        displayedOutlines = graphs.value + equations.value, groupBounds = null,
                        measuredTextHeights = remember { mutableMapOf() },
                        richColors = RichTextColors(Color.Blue, Color.Blue, Color.Gray), shapeInk = Color.Black,
                        pictures = null, pictureAssets = emptyMap(), paperColor = Color.White,
                        graphRewrites = emptyMap(),
                        onMutateGraph = { graph, drag -> mutated = graph.id to drag },
                        onTraceGraph = { id, x -> update { if (it.id == id) it.copy(traceX = x) else it } },
                        onGraphArea = { id, area -> update { if (it.id == id) it.copy(area = area) else it } },
                        editorFocusRequester = focus, canvasFocusRequester = focus,
                        onMoveObject = { _, dx, dy, _ -> moved = dx to dy },
                        onSelectObject = { workspace.value = state.selectObject(it) },
                        onResizeObjects = { _, _, _, _, _ -> }, onFocusTextBox = {},
                        onBodyChange = { _, _ -> }, onTextShortcut = { false },
                        onMoveShapeEnd = { _, _, _, _, _ -> }, onMoveShapeArm = { _, _, _, _ -> },
                        heldTable = null, onHoldTable = {}, onTableEdit = {},
                        onGraphParameter = { id, index, value ->
                            update { if (it.id == id) it.withParameterValue(index, value) else it }
                        },
                        onEditGraphSliders = { slidersOf.value = it })
                    CanvasObjectToolkit(state, null, emptyMap(), Color.Black, null,
                        onColorObjects = { _, _ -> }, onTableEdit = {}, onReleaseTable = {},
                        graphActions = GraphToolkitActions(
                            update = { change -> update(change) }, zoom = {}, fit = {}, reset = {},
                            editSliders = { slidersOf.value = state.selectedObjectIds.singleOrNull() }),
                        onEquationChange = {}, onShapeLineType = {}, onShapeWidth = {}, onShapeFill = {},
                        onImageBackground = {}, onCopyObjects = {}, onToggleObjectLock = {}, onDeleteObjects = {},
                        graphableFormulas = graphable, onGraphEquation = { graphed += it },
                        onOpenEquationInMath = { opened += it })
                    slidersOf.value?.let { id -> graphs.value.firstOrNull { it.id == id } }?.let { graph ->
                        GraphSlidersDialog(graph.parameters, onDismiss = { slidersOf.value = null }, onApply = { changed ->
                            update { if (it.id == graph.id) it.withParameters(changed) else it }
                            slidersOf.value = null
                        })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun offset(xDp: Float, yDp: Float): Offset = with(density) { Offset(xDp.dp.toPx(), yDp.dp.toPx()) }

    private fun tapPage(xDp: Float, yDp: Float) {
        val layer = compose.onNodeWithTag("graph-layer")
        layer.performTouchInput { down(offset(xDp, yDp)) }
        compose.waitForIdle()
        layer.performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun dragPage(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val layer = compose.onNodeWithTag("graph-layer")
        layer.performTouchInput { down(offset(fromX, fromY)) }
        compose.waitForIdle()
        // Two moves, so the first carries the gesture past the touch slop before the one that counts.
        layer.performTouchInput { moveTo(offset((fromX + toX) / 2, (fromY + toY) / 2)) }
        compose.waitForIdle()
        layer.performTouchInput { moveTo(offset(toX, toY)) }
        compose.waitForIdle()
        layer.performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun aTapSelectsTheGraphAndRaisesItsToolkit() = scene {
        setPage(parabola(mutable = false))

        tapPage(100f, 80f)

        compose.onNodeWithTag(WorkspaceTestTags.ObjectToolkit).assertIsDisplayed()
        compose.onNodeWithTag(GraphToolkitTags.MUTATE).assertIsDisplayed()
        compose.onNodeWithTag(GraphToolkitTags.OPTIONS).assertIsDisplayed()
    }

    @Test
    fun theMutateButtonTurnsMutateOn() = scene {
        setPage(parabola(mutable = false))
        tapPage(100f, 80f)

        compose.onNodeWithTag(GraphToolkitTags.MUTATE).performClick()
        compose.waitForIdle()

        assertTrue("mutate stayed off", graphs.value.single().mutable)
    }

    @Test
    fun withMutateOffADragOnAPointMovesTheGraph() = scene {
        setPage(parabola(mutable = false))
        tapPage(100f, 80f)

        dragPage(pageX(2.0), pageY(0.0), pageX(2.0) + 40f, pageY(0.0) + 30f)

        // Measured from where the drag crossed the slop — the midpoint, in [dragPage] — as an
        // equation's is, so the slop itself is never part of the move.
        val travel = assertNotNull("the graph did not move", moved).let { moved!! }
        assertEquals(20f, travel.first, 2f)
        assertEquals(15f, travel.second, 2f)
        assertNull("a point was dragged with mutate off", mutated)
    }

    @Test
    fun withMutateOnDraggingAZeroMovesThatRootAlone() = scene {
        setPage(parabola(mutable = true))
        tapPage(100f, 80f)

        // The zero at x = 2, dragged to x = 3 and somewhat off the axis — a zero stays on it.
        dragPage(pageX(2.0), pageY(0.0), pageX(3.0), pageY(0.0) - 15f)

        assertNull("the box moved instead of the point", moved)
        val (id, drag) = assertNotNull("no point was dragged", mutated).let { mutated!! }
        assertEquals("graph", id)
        drag as GraphDrag.Root
        assertEquals("3", drag.to.toPlainString())
        assertEquals(0.0, drag.evaluate(-2.0), 1e-9)
        assertEquals(0.0, drag.evaluate(3.0), 1e-9)
    }

    @Test
    fun withMutateOnDraggingTheVertexTranslatesTheCurve() = scene {
        setPage(parabola(mutable = true))
        tapPage(100f, 80f)

        // The vertex (0, −4) to (1, −2).
        dragPage(pageX(0.0), pageY(-4.0), pageX(1.0), pageY(-2.0))

        val drag = assertNotNull("no point was dragged", mutated).let { mutated!!.second }
        drag as GraphDrag.Shift
        assertEquals("1", drag.h.toPlainString())
        assertEquals("2", drag.k.toPlainString())
    }
    @Test
    fun theTraceEntryPutsATracerMidWindow() = scene {
        setPage(parabola(mutable = false))
        tapPage(100f, 80f)

        compose.onNodeWithTag(GraphToolkitTags.OPTIONS).performClick()
        compose.onNodeWithTag(GraphToolkitTags.TRACE).performClick()
        compose.waitForIdle()

        assertEquals(0.0, graphs.value.single().traceX!!, 0.0)
    }

    @Test
    fun theTracerSlidesAlongTheCurveAndNeverMovesTheGraph() = scene {
        // Locked and not mutable: the tracer reads the curve and is still free to move.
        setPage(parabola(mutable = false, traceX = 0.0).copy(lockGroup = "lock"))
        tapPage(100f, 80f)

        // Off the curve on purpose — the curve, not the finger, decides how high the tracer is.
        dragPage(pageX(0.0), pageY(-4.0), pageX(1.4), pageY(3.0))

        assertNull("the graph moved", moved)
        assertNull("a point was dragged", mutated)
        assertEquals(1.4, graphs.value.single().traceX!!, 1e-9)
    }

    @Test
    fun theTracerIsPulledOntoAPointOfInterest() = scene {
        setPage(parabola(mutable = true, traceX = 0.0))
        tapPage(100f, 80f)

        // Let go a little short of the zero at 2: the magnet takes it the rest of the way, and
        // with mutate on the tracer still wins over the vertex it started on.
        dragPage(pageX(0.0), pageY(-4.0), pageX(1.85), pageY(0.0))

        assertNull("a point was dragged instead of the tracer", mutated)
        assertEquals(2.0, graphs.value.single().traceX!!, 1e-6)
    }
    @Test
    fun theAreaEntryShadesBetweenTheZeros() = scene {
        setPage(parabola(mutable = false))
        tapPage(100f, 80f)

        compose.onNodeWithTag(GraphToolkitTags.OPTIONS).performClick()
        compose.onNodeWithTag(GraphToolkitTags.AREA).performClick()
        compose.waitForIdle()

        val area = graphs.value.single().area!!
        assertEquals(-2.0, area.from, 1e-6)
        assertEquals(2.0, area.to, 1e-6)
    }

    @Test
    fun aBoundIsDraggedByItsLine() = scene {
        setPage(parabola(mutable = false, area = GraphInterval(-2.0, 2.0)))
        tapPage(100f, 80f)

        // High up the dashed line, well away from the zero it stands on.
        dragPage(pageX(2.0), pageY(3.0), pageX(3.0), pageY(3.0))

        assertNull("the graph moved", moved)
        val area = graphs.value.single().area!!
        assertEquals(-2.0, area.from, 1e-6)
        assertEquals(3.0, area.to, 1e-9)
    }

    @Test
    fun withMutateOnAZeroUnderABoundStillMovesTheRoot() = scene {
        setPage(parabola(mutable = true, area = GraphInterval(-2.0, 2.0)))
        tapPage(100f, 80f)

        dragPage(pageX(2.0), pageY(0.0), pageX(3.0), pageY(0.0))

        assertTrue("the root did not move", mutated?.second is GraphDrag.Root)
        assertEquals(GraphInterval(-2.0, 2.0), graphs.value.single().area)
    }

    @Test
    fun aSelectedGraphsSliderIsDraggedAndWrittenOnTheLift() = scene {
        // Locked: a slider, like the tracer, is not a move.
        setPage(wave().copy(lockGroup = "lock"))
        tapPage(100f, 80f)

        dragPage(sliderX(1.0), sliderY(0), sliderX(5.0), sliderY(0) + 6f)

        assertNull("the graph moved", moved)
        val parameters = graphs.value.single().parameters
        assertEquals(5.0, parameters[0].value, 0.15)
        assertEquals("ω was moved too", 1.0, parameters[1].value, 0.0)
    }

    @Test
    fun anUnselectedGraphsSliderIsJustPartOfTheBox() = scene {
        setPage(wave())

        dragPage(sliderX(1.0), sliderY(0), sliderX(5.0), sliderY(0))

        assertNotNull("a drag on an unselected graph did not move it", moved)
        assertEquals(1.0, graphs.value.single().parameters[0].value, 0.0)
    }

    @Test
    fun assistiveTechnologySetsASlider() = scene {
        setPage(wave())
        tapPage(100f, 80f)

        compose.onNodeWithTag(GraphSliderTags.slider(1))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2.54f) }
        compose.waitForIdle()

        assertEquals(2.5, graphs.value.single().parameters[1].value, 0.0)
    }

    @Test
    fun playSweepsASliderAndPauseWritesWhereItStopped() = scene {
        setPage(wave())
        tapPage(100f, 80f)

        // A sweep asks for frames until it is paused, so the page never idles meanwhile: the clock is
        // driven by hand from here on.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(GraphSliderTags.play(0)).performClick()
        compose.mainClock.advanceTimeBy(800)
        // Nothing is written while it plays.
        assertEquals(1.0, graphs.value.single().parameters[0].value, 0.0)
        compose.onNodeWithTag(GraphSliderTags.play(0)).performClick()
        compose.mainClock.advanceTimeBy(100)

        val swept = graphs.value.single().parameters[0].value
        assertTrue("play did not move A: $swept", swept > 1.0)
    }

    @Test
    fun aSlidersNameOpensTheDialogWhichSetsItsRange() = scene {
        setPage(wave())
        tapPage(100f, 80f)

        tapPage(70f, sliderY(0))
        compose.onNodeWithTag(GraphSlidersDialogTags.DIALOG).assertIsDisplayed()

        compose.onNodeWithTag(GraphSlidersDialogTags.field(0, SliderField.Max)).performTextReplacement("20")
        compose.onNodeWithTag(GraphSlidersDialogTags.field(0, SliderField.Value)).performTextReplacement("15")
        compose.onNodeWithTag(GraphSlidersDialogTags.APPLY).performClick()
        compose.waitForIdle()

        val a = graphs.value.single().parameters[0]
        assertEquals(20.0, a.max, 0.0)
        assertEquals(15.0, a.value, 0.0)
    }

    @Test
    fun theOptionsMenuOpensTheSliders() = scene {
        setPage(wave())
        tapPage(100f, 80f)

        compose.onNodeWithTag(GraphToolkitTags.OPTIONS).performClick()
        compose.onNodeWithTag(GraphToolkitTags.SLIDERS).performClick()

        compose.onNodeWithTag(GraphSlidersDialogTags.DIALOG).assertIsDisplayed()
    }

    /** One equation on the page; [canGraph] is what the engine would answer about it. */
    private fun setEquationPage(canGraph: Boolean) {
        graphable = object : GraphableFormulas {
            override fun known(latex: String): Boolean? = null
            override suspend fun check(latex: String): Boolean = canGraph
        }
        graphed = mutableListOf()
        opened = mutableListOf()
        equations.value = listOf(Outline.Equation(id = "eq", x = 40f, y = 40f, width = 160f, height = 48f, latex = "y=x^2-4"))
        setPage()
        tapPage(120f, 64f)
    }

    @Test
    fun aGraphableEquationsToolkitGraphsIt() = scene {
        setEquationPage(canGraph = true)

        compose.onNodeWithTag(EquationToolkitTags.GRAPH).assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals(listOf("eq"), graphed.map { it.id })
    }

    @Test
    fun anEquationThatCannotBeGraphedHasNoGraphButton() = scene {
        setEquationPage(canGraph = false)

        compose.onNodeWithTag(EquationToolkitTags.EDIT).assertIsDisplayed()
        compose.onNodeWithTag(EquationToolkitTags.GRAPH).assertDoesNotExist()
    }

    @Test
    fun theEquationsMathButtonOpensItInTheMathPane() = scene {
        // Graphable or not, a formula can always go to the pane.
        setEquationPage(canGraph = false)

        compose.onNodeWithTag(EquationToolkitTags.MATH).assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals(listOf("y=x^2-4"), opened.map { it.latex })
    }
}
