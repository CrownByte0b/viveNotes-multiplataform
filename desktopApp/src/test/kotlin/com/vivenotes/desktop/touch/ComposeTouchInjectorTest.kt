package com.vivenotes.desktop.touch

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.GraphicsEnvironment
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract with Compose Desktop's internals the injector relies on: a real window and panel,
 * touch frames in window coordinates, and what the content's own pointer input sees. A Compose
 * upgrade that moves the scene or its mouse mapping fails here.
 */
class ComposeTouchInjectorTest {
    private var frame: JFrame? = null

    @AfterTest
    fun close() {
        frame?.let { SwingUtilities.invokeAndWait { it.dispose() } }
    }

    private data class Seen(val type: String, val pointers: List<Triple<PointerType, Offset, Boolean>>)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit): Pair<JFrame, ComposePanel> {
        lateinit var panel: ComposePanel
        SwingUtilities.invokeAndWait {
            val window = JFrame("Touch injection test")
            panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
            window.contentPane.add(panel)
            window.setSize(400, 300)
            window.isVisible = true
            panel.setContent(content)
            frame = window
        }
        return frame!! to panel
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun aTouchArrivesAsATouchPointerWhereItLanded() {
        if (GraphicsEnvironment.isHeadless()) return
        val seen = CopyOnWriteArrayList<Seen>()
        val (window, panel) = show {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        seen += Seen(event.type.toString(), event.changes.map { Triple(it.type, it.position, it.pressed) })
                    }
                }
            })
        }
        waitUntil("the panel to lay out") { panel.width > 0 && panel.isShowing }
        val injector = ComposeTouchInjector()
        lateinit var origin: java.awt.Point
        var scale = 1f
        SwingUtilities.invokeAndWait {
            origin = SwingUtilities.convertPoint(panel, 0, 0, window)
            scale = panel.graphicsConfiguration.defaultTransform.scaleX.toFloat()
        }
        val x = origin.x + 120.5
        val y = origin.y + 80.25
        fun send(vararg changes: TouchChange) = SwingUtilities.invokeAndWait { injector.accept(TouchFrame(1, changes.toList())) }

        send(TouchChange(1, TouchPhase.Down, window, x, y))
        send(TouchChange(1, TouchPhase.Move, window, x + 30, y))
        send(TouchChange(1, TouchPhase.Up, window, 0.0, 0.0))
        waitUntil("press, move and release") { seen.count { it.type in setOf("Press", "Move", "Release") } >= 3 }

        val events = seen.filter { it.type in setOf("Press", "Move", "Release") }
        assertEquals(listOf("Press", "Move", "Release"), events.map { it.type })
        events.flatMap { it.pointers }.forEach { assertEquals(PointerType.Touch, it.first) }
        val press = events[0].pointers.single()
        assertEquals(120.5f * scale, press.second.x, 0.01f)
        assertEquals(80.25f * scale, press.second.y, 0.01f)
        assertTrue(press.third)
        assertEquals(150.5f * scale, events[1].pointers.single().second.x, 0.01f)
        assertTrue(!events[2].pointers.single().third)
    }

    /** Mouse events as the app receives them: through AWT's event queue, to the component under them. */
    private fun mouse(target: java.awt.Component, id: Int, x: Int, y: Int) {
        java.awt.Toolkit.getDefaultToolkit().systemEventQueue.postEvent(
            java.awt.event.MouseEvent(target, id, System.currentTimeMillis(), 0, x, y, 0, false))
        SwingUtilities.invokeAndWait { }
    }

    /**
     * Compose keeps a mouse change on the elements under a resting cursor when a move changes
     * nothing — it sends one itself after each relayout — and a press arriving with that change is
     * not a press to `clickable`. The cursor rests where X11 puts it after a tap, and wherever a mouse
     * is left on any desktop: a finger tapping there must still click.
     */
    @Test
    fun aTapWhereTheMouseRestsStillClicks() {
        if (GraphicsEnvironment.isHeadless()) return
        val clicked = CopyOnWriteArrayList<String>()
        val footer = androidx.compose.runtime.mutableStateOf(10.dp)
        val (window, panel) = show {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                listOf("first", "second").forEach { name ->
                    Box(Modifier.fillMaxWidth().height(60.dp).hoverable(remember { MutableInteractionSource() })
                        .clickable { clicked += name })
                }
                Box(Modifier.fillMaxWidth().height(footer.value))
            }
        }
        waitUntil("the panel to lay out") { panel.width > 0 && panel.isShowing }
        val injector = ComposeTouchInjector()
        lateinit var origin: java.awt.Point
        lateinit var content: java.awt.Component
        SwingUtilities.invokeAndWait {
            origin = SwingUtilities.convertPoint(panel, 0, 0, window)
            content = SwingUtilities.getDeepestComponentAt(panel, 30, 30)
        }
        fun tap(id: Long, y: Int) {
            SwingUtilities.invokeAndWait {
                injector.accept(TouchFrame(1, listOf(TouchChange(id, TouchPhase.Down, window, origin.x + 30.0, origin.y + y.toDouble()))))
            }
            Thread.sleep(50)
            SwingUtilities.invokeAndWait {
                injector.accept(TouchFrame(2, listOf(TouchChange(id, TouchPhase.Up, window, 0.0, 0.0))))
            }
        }
        mouse(content, java.awt.event.MouseEvent.MOUSE_ENTERED, 30, 90)
        mouse(content, java.awt.event.MouseEvent.MOUSE_MOVED, 30, 90)
        // Something else on screen changes size while the cursor rests; Compose then re-sends the
        // cursor's unchanged position before the next pointer event.
        footer.value = 20.dp
        Thread.sleep(200)
        tap(1, 90)
        waitUntil("the tap under the resting cursor") { clicked.isNotEmpty() }
        mouse(content, java.awt.event.MouseEvent.MOUSE_MOVED, 30, 30)
        footer.value = 30.dp
        Thread.sleep(200)
        tap(2, 30)
        waitUntil("the next tap") { clicked.size >= 2 }
        assertEquals(listOf("second", "first"), clicked.toList())
    }

    /**
     * X11 moves the mouse pointer along with the first finger. Those moves are not the user's mouse:
     * reaching Compose mid-gesture, they would end the finger's gesture with a release.
     */
    @Test
    fun theMouseFollowingAFingerDoesNotBreakItsDrag() {
        if (GraphicsEnvironment.isHeadless()) return
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val ends = java.util.concurrent.atomic.AtomicInteger()
        var dragged = 0f
        val (window, panel) = show {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { starts.incrementAndGet() },
                    onDragEnd = { ends.incrementAndGet() },
                    onDragCancel = { ends.incrementAndGet() },
                ) { change, amount -> change.consume(); dragged += amount.x }
            })
        }
        waitUntil("the panel to lay out") { panel.width > 0 && panel.isShowing }
        val injector = ComposeTouchInjector()
        lateinit var origin: java.awt.Point
        lateinit var content: java.awt.Component
        SwingUtilities.invokeAndWait {
            origin = SwingUtilities.convertPoint(panel, 0, 0, window)
            content = SwingUtilities.getDeepestComponentAt(panel, 30, 30)
        }
        fun finger(phase: TouchPhase, x: Int) = SwingUtilities.invokeAndWait {
            injector.accept(TouchFrame(1, listOf(TouchChange(7, phase, window, origin.x + x.toDouble(), origin.y + 100.0))))
        }
        mouse(content, java.awt.event.MouseEvent.MOUSE_ENTERED, 40, 100)
        finger(TouchPhase.Down, 40)
        for (x in 50..240 step 10) {
            mouse(content, java.awt.event.MouseEvent.MOUSE_MOVED, x, 100)
            finger(TouchPhase.Move, x)
        }
        finger(TouchPhase.Up, 240)
        waitUntil("the drag to end") { ends.get() >= 1 }
        assertEquals(1, starts.get(), "one drag, not one per pointer move")
        assertEquals(1, ends.get())
        assertTrue(dragged > 150f, "the drag followed the finger: $dragged")
    }

    @Test
    fun twoFingersAreOneMultiTouchGestureToCompose() {
        if (GraphicsEnvironment.isHeadless()) return
        var zoom = 1f
        val (window, panel) = show {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, _, change, _ -> zoom *= change }
            })
        }
        waitUntil("the panel to lay out") { panel.width > 0 && panel.isShowing }
        val injector = ComposeTouchInjector()
        lateinit var origin: java.awt.Point
        SwingUtilities.invokeAndWait { origin = SwingUtilities.convertPoint(panel, 0, 0, window) }
        val cx = origin.x + 200.0
        val cy = origin.y + 120.0
        fun send(vararg changes: TouchChange) = SwingUtilities.invokeAndWait { injector.accept(TouchFrame(1, changes.toList())) }

        send(TouchChange(1, TouchPhase.Down, window, cx - 40, cy), TouchChange(2, TouchPhase.Down, window, cx + 40, cy))
        for (step in 1..10) {
            val half = 40.0 + step * 4
            send(TouchChange(1, TouchPhase.Move, window, cx - half, cy), TouchChange(2, TouchPhase.Move, window, cx + half, cy))
        }
        send(TouchChange(1, TouchPhase.Up, window, 0.0, 0.0), TouchChange(2, TouchPhase.Up, window, 0.0, 0.0))

        waitUntil("the spread to zoom") { zoom > 1.3f }
        var settled = zoom
        waitUntil("the zoom to settle") { Thread.sleep(150); (zoom == settled).also { settled = zoom } }
        // The fingers spread from 80 to 160 px; Compose's own slop takes the start of that.
        assertTrue(zoom in 1.45f..2.01f, "a two-finger spread zoomed: $zoom")
    }
}
