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

    private fun setPage(vararg page: Outline.Graph) {
        graphs.value = page.toList()
        moved = null
        mutated = null
        compose.setContent {
            density = LocalDensity.current
            ViveNotesTheme {
                val base = workspace.value
                val state = base.updatePage(base.selectedPageId) { it.copy(document = com.vivenotes.model.PageDoc(
                    style = PageStyle(hideTitle = true), outlines = graphs.value)) }
                val current = rememberUpdatedState(state)
                val focus = remember { FocusRequester() }
                fun update(change: (Outline.Graph) -> Outline.Graph) {
                    graphs.value = graphs.value.map(change)
                }
                Box(Modifier.fillMaxSize().focusRequester(focus).focusable().testTag("graph-layer")) {
                    CanvasPrimeObjects(
                        viewport = { Rect(0f, 0f, 900f, 900f) }, state = state, workspaceState = current,
                        displayedOutlines = graphs.value, groupBounds = null,
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
                        heldTable = null, onHoldTable = {}, onTableEdit = {})
                    CanvasObjectToolkit(state, null, emptyMap(), Color.Black, null,
                        onColorObjects = { _, _ -> }, onTableEdit = {}, onReleaseTable = {},
                        graphActions = GraphToolkitActions(
                            update = { change -> update(change) }, zoom = {}, fit = {}, reset = {}),
                        onEquationChange = {}, onShapeLineType = {}, onShapeWidth = {}, onShapeFill = {},
                        onImageBackground = {}, onCopyObjects = {}, onToggleObjectLock = {}, onDeleteObjects = {})
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
}
