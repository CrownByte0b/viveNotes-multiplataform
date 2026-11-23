package com.vivenotes.desktop.touch

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.ink.brush.InputToolType
import com.vivenotes.byteink.compose.InkHoverEvent
import com.vivenotes.byteink.compose.InkInputEvent
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.ui.canvas.LocalStylusHover
import com.vivenotes.ui.canvas.StylusHover
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.WorkspaceState
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #27 on native Wayland, short of the compositor: a pen's hover reaches the page as the mouse
 * it moves and its contacts as a stylus, wired as [DesktopPointerSource] wires them. ByteInk gives
 * the pen the cursor of the component under it, so over the page with a Draw tool that cursor must
 * be none all along, hovering, touching and lifting, while the tool's circle shows under the tip.
 */
class NativePenCircleTest {
    private var frame: JFrame? = null

    @AfterTest
    fun close() {
        frame?.let { SwingUtilities.invokeAndWait { it.dispose() } }
    }

    @OptIn(ExperimentalComposeUiApi::class, androidx.compose.runtime.tooling.ComposeToolingApi::class)
    @Test
    fun thePenShowsTheToolsCircleAndNoCursorOverThePageFromHoverThroughItsStrokes() {
        if (GraphicsEnvironment.isHeadless()) return
        val stylus = StylusHover()
        val state = mutableStateOf(WorkspaceState.demo().selectInkTool(InkTool.Eraser))
        lateinit var panel: ComposePanel
        SwingUtilities.invokeAndWait {
            val window = JFrame("Pen circle test")
            panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
            window.contentPane.add(panel)
            window.setSize(1400, 900)
            window.isVisible = true
            panel.setContent {
                CompositionLocalProvider(LocalStylusHover provides stylus) {
                    ViveNotesTheme(darkTheme = false) { WorkspaceScreen(state.value, { state.value = it(state.value) }) }
                }
            }
            frame = window
        }
        val window = frame!!
        val canvas = waitFor("the page canvas") {
            var bounds: Rect? = null
            SwingUtilities.invokeAndWait {
                bounds = panel.semanticsOwners.firstNotNullOfOrNull { owner ->
                    owner.getAllSemanticsNodes(mergingEnabled = false).firstOrNull {
                        it.config.getOrNull(SemanticsProperties.TestTag) == WorkspaceTestTags.PageCanvas
                    }?.boundsInRoot?.takeIf { it.width > 0f && it.height > 0f }
                }
            }
            bounds
        }
        // Layout and composition settle before the pen arrives, as for a displayed editor.
        Thread.sleep(300)
        lateinit var at: Point
        SwingUtilities.invokeAndWait {
            val scale = panel.graphicsConfiguration.defaultTransform.scaleX
            val origin = SwingUtilities.convertPoint(panel, 0, 0, window)
            at = Point(origin.x + ((canvas.left + canvas.width / 2) / scale).toInt(),
                origin.y + ((canvas.top + canvas.height / 2) / scale).toInt())
        }
        // Wired as DesktopPointerSource wires them on native Wayland.
        val queue = TouchAwareEventQueue.install()
        val source = NativePointerFrames(window, mouse = false, touch = false, emit = ComposeTouchInjector()::accept,
            onHover = queue::penHover, onHoverEnd = queue::penHoverEnd, stylusHover = stylus)
        SwingUtilities.invokeAndWait { queue.mouseUsed[window] = source::mouseUsed }
        var time = 1L
        fun sample(x: Int, pressure: Float = 0f) =
            InkPointerSample((at.x + x).toFloat(), at.y.toFloat(), time++, InputToolType.STYLUS, pressure)
        fun pen(event: () -> Unit) = SwingUtilities.invokeAndWait(event)
        // What ByteInk reads for the pen: the cursor of the component under it.
        fun cursorAt(x: Int): String? {
            var name: String? = null
            SwingUtilities.invokeAndWait { name = SwingUtilities.getDeepestComponentAt(window, at.x + x, at.y)?.cursor?.name }
            return name
        }
        fun assertCursorStays(x: Int, expected: String, context: String) {
            val until = System.currentTimeMillis() + 300
            while (System.currentTimeMillis() < until) {
                assertEquals(expected, cursorAt(x), context)
                Thread.sleep(25)
            }
        }
        // Near-black pixels the window shows around the pen: on an empty stretch of page, the circle's ring.
        fun darkAround(x: Int): Int {
            var dark = 0
            SwingUtilities.invokeAndWait {
                val image = BufferedImage(window.width, window.height, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                try { window.paint(graphics) } finally { graphics.dispose() }
                for (py in at.y - 30..at.y + 30) for (px in at.x + x - 30..at.x + x + 30) {
                    val rgb = image.getRGB(px, py)
                    if ((rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF) < 3 * 80) dark++
                }
            }
            return dark
        }
        assertEquals(0, darkAround(0), "an empty stretch of page")
        assertEquals(0, darkAround(40))

        // Hovering: the circle under the tip, and no cursor.
        pen { source.hover(InkHoverEvent.Move(sample(0), 7)) }
        waitFor("no cursor for the hovering pen") { true.takeIf { cursorAt(0) == "none" } }
        waitFor("the eraser's circle under the hovering pen") { darkAround(0).takeIf { it > 0 } }
        assertTrue(stylus.active)

        // Touching: as the pen touches down, the cursor stays none, and the eraser keeps its circle.
        pen { source.accept(InkInputEvent.Begin(sample(0, .5f), 7)) }
        pen { source.accept(InkInputEvent.Move(sample(40, .5f), 7)) }
        waitFor("the eraser's circle where it erases") { darkAround(40).takeIf { it > 0 } }
        assertCursorStays(40, "none", "erasing")

        // Lifting and hovering on: no cursor still, not even for a moment as the hover comes back.
        pen { source.accept(InkInputEvent.Finish(sample(40), 7)) }
        pen { source.hover(InkHoverEvent.Move(sample(40), 7)) }
        assertCursorStays(40, "none", "hovering after the stroke")
        assertTrue(stylus.active)

        // A pen stroke shows its ink, not a cursor.
        SwingUtilities.invokeAndWait { state.value = state.value.selectInkTool(InkTool.Pen) }
        pen { source.hover(InkHoverEvent.Move(sample(0), 7)) }
        waitFor("no cursor for the pen tool") { true.takeIf { cursorAt(0) == "none" } }
        pen { source.accept(InkInputEvent.Begin(sample(0, .5f), 7)) }
        pen { source.accept(InkInputEvent.Move(sample(-40, .5f), 7)) }
        assertCursorStays(-40, "none", "drawing")
        pen { source.accept(InkInputEvent.Finish(sample(-40), 7)) }
        waitFor("the stroke") { true.takeIf { state.value.pendingInkEdits.isNotEmpty() } }

        // The pen leaves: the mouse is the mouse again, and the circle goes with the pen.
        pen { source.hover(InkHoverEvent.Move(sample(40), 7)) }
        waitFor("the circle back under the pen") { darkAround(40).takeIf { it > 0 } }
        pen { source.hover(InkHoverEvent.Exit(7)) }
        assertFalse(stylus.active)
        waitFor("the circle gone with the pen") { true.takeIf { darkAround(40) == 0 } }

        // A pen that lifts and leaves at once sends no exit: the mouse moving takes over from it.
        pen { source.hover(InkHoverEvent.Move(sample(40), 7)) }
        pen { source.accept(InkInputEvent.Begin(sample(40, .5f), 7)) }
        pen { source.accept(InkInputEvent.Finish(sample(40), 7)) }
        assertTrue(stylus.active)
        // The mouse comes in beside where the pen touched, clear of any ink it left.
        lateinit var content: java.awt.Component
        SwingUtilities.invokeAndWait { content = SwingUtilities.getDeepestComponentAt(window, at.x + 100, at.y) }
        assertEquals(0, darkAround(100))
        for (id in listOf(java.awt.event.MouseEvent.MOUSE_ENTERED, java.awt.event.MouseEvent.MOUSE_MOVED)) {
            val point = SwingUtilities.convertPoint(window, at.x + 100, at.y, content)
            java.awt.Toolkit.getDefaultToolkit().systemEventQueue.postEvent(
                java.awt.event.MouseEvent(content, id, System.currentTimeMillis(), 0, point.x, point.y, 0, false))
        }
        waitFor("the mouse taking over") { true.takeIf { !stylus.active } }
        waitFor("the mouse's own cursor") { true.takeIf { cursorAt(100) != "none" } }
        assertEquals(0, darkAround(100), "no circle under the mouse")
    }

    private fun <T : Any> waitFor(what: String, value: () -> T?): T {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            value()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }
}
