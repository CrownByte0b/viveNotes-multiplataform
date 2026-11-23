package com.vivenotes.desktop.touch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.vivenotes.ui.canvas.LocalStylusHover
import com.vivenotes.ui.canvas.StylusHover
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.desktop.rendering.WaylandRendering
import com.vivenotes.desktop.rendering.waylandComposePanel
import java.awt.Toolkit
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Runs on JBR in a private Wayland session; the ordinary X11/Windows suite skips it. */
class NativeWaylandInputTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun nativeCaptureWaitsForTheConfiguredSurfaceAndReattachesAfterHideShow() {
        assumeTrue(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit")
        WaylandRendering.prepare()
        val lines = CopyOnWriteArrayList<String>()
        val log = DebugLog(true) { lines += it }
        var frame: JFrame? = null
        fun waitForAttachments(count: Int) {
            val deadline = System.currentTimeMillis() + 5000
            while (lines.count { "native pointers attached: LINUX_WAYLAND_TABLET" in it } < count) {
                check(System.currentTimeMillis() < deadline) { "Native attachment timed out: $lines" }
                Thread.sleep(10)
            }
        }
        try {
            SwingUtilities.invokeAndWait {
                val window = JFrame("Wayland native input")
                frame = window
                val panel = waylandComposePanel(log)
                window.add(panel)
                panel.setContent { Box(Modifier.fillMaxSize()) }
                window.setSize(400, 300)
                window.isVisible = true
                DesktopPointerSource.attach(window, ComposeTouchInjector(log), log)
            }
            val window = checkNotNull(frame)
            waitForAttachments(1)
            SwingUtilities.invokeAndWait { window.isVisible = false }
            SwingUtilities.invokeAndWait { window.isVisible = true }
            waitForAttachments(2)
            Thread.sleep(300)
            assertFalse(lines.any { "failed" in it }, lines.joinToString("\n"))
        } finally {
            frame?.let { window -> SwingUtilities.invokeAndWait { window.dispose() } }
        }
    }

    /**
     * The touch source's own pointer and pinch gesture, on the runtime's connection: a request the
     * compositor rejects is a protocol error, which ends the connection and the app with it. Where the
     * compositor has no pointer gestures (Weston) touch must come up as before.
     */
    @Test fun touchpadPinchIsAskedForWithoutAProtocolError() {
        assumeTrue(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit")
        val lines = CopyOnWriteArrayList<String>()
        val displayClass = Class.forName("sun.awt.wl.WLDisplay")
        val instance = displayClass.getMethod("getInstance").invoke(null)
        val display = MemorySegment.ofAddress(displayClass.getMethod("getDisplayPtr").invoke(instance) as Long)
        val source = WaylandTouchSource(display, {}, DebugLog(true) { lines += it })
        source.start()
        try {
            val deadline = System.currentTimeMillis() + 5000
            while (lines.none { "wayland touch listening" in it }) {
                check(System.currentTimeMillis() < deadline) { "the source never listened: $lines" }
                Thread.sleep(10)
            }
            // The compositor's answer to the pinch requests, if it has one, arrives after the listening line.
            Thread.sleep(500)
            val getError = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("libwayland-client.so.0", Arena.global()).find("wl_display_get_error").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS))
            assertEquals(0, getError.invoke(display) as Int, lines.joinToString("\n"))
            assertFalse(lines.any { "unavailable" in it || "failed" in it }, lines.joinToString("\n"))
            println(lines.joinToString("\n"))
        } finally {
            source.stop()
        }
    }

    /**
     * Issue #27 end to end, on ByteInk's private tablet compositor (skipped on any other): a hovering
     * pen moves the mouse in Compose, Compose's pointer icon there becomes the pen's cursor, and the
     * cursor follows the pen from element to element.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun aHoveringPenShowsTheCursorComposeShowsForTheMouseThere() {
        assumeTrue(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit")
        val displayClass = Class.forName("sun.awt.wl.WLDisplay")
        val display = displayClass.getMethod("getDisplayPtr").invoke(displayClass.getMethod("getInstance").invoke(null)) as Long
        val fixture = TabletFixture.open(display)
        assumeTrue("needs ByteInk's private tablet test compositor", fixture != null)
        WaylandRendering.prepare()
        val lines = CopyOnWriteArrayList<String>()
        val log = DebugLog(true) { lines += it }
        val mouseEntered = java.util.concurrent.atomic.AtomicBoolean()
        var frame: JFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                val window = JFrame("Wayland pen cursor")
                frame = window
                val panel = waylandComposePanel(log)
                window.add(panel)
                panel.setContent {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().weight(1f).pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Enter && event.changes.any { it.type == PointerType.Mouse }) mouseEntered.set(true)
                                }
                            }
                        }.pointerHoverIcon(PointerIcon.Text))
                        Box(Modifier.fillMaxWidth().weight(1f))
                    }
                }
                window.setSize(400, 300)
                window.isVisible = true
                DesktopPointerSource.attach(window, ComposeTouchInjector(log), log)
            }
            val window = checkNotNull(frame)
            lateinit var origin: java.awt.Point
            var height = 0
            SwingUtilities.invokeAndWait {
                origin = SwingUtilities.convertPoint(window.contentPane, 0, 0, window)
                height = window.contentPane.height
            }
            val surface = surfaceOf(window)
            var time = 1000
            // Hover until the capture is ready: hover arriving before readiness is ignored.
            fun hoverUntil(what: String, y: Int, done: (List<TabletFixture.Cursor>) -> Boolean) {
                val deadline = System.currentTimeMillis() + 10_000
                while (true) {
                    fixture!!.send(surface, 0, 1, origin.x + 30.0, origin.y + y.toDouble(), time++)
                    Thread.sleep(50)
                    val cursors = fixture.cursors()
                    if (done(cursors)) return
                    check(System.currentTimeMillis() < deadline) { "timed out waiting for $what: $cursors\n${lines.joinToString("\n")}" }
                }
            }
            hoverUntil("the text cursor Compose shows over the upper box", height / 4) { cursors ->
                cursors.any { it.shape == TEXT && it.shapeSerial }
            }
            assertTrue(mouseEntered.get(), "the pen's hover reached Compose as the mouse")
            hoverUntil("the arrow over the lower box", height * 3 / 4) { cursors ->
                cursors.any { it.shape == DEFAULT && it.shapeSerial && it.shapes >= 2 }
            }
            fixture!!.send(surface, 0, 3, origin.x + 30.0, origin.y + height * 3 / 4.0, time++)
            // The once-per-run "first pen hover" line may belong to another test in this JVM.
            assertFalse(lines.any { "failed" in it }, lines.joinToString("\n"))
        } finally {
            frame?.let { window -> SwingUtilities.invokeAndWait { window.dispose() } }
            fixture?.close()
        }
    }

    /**
     * Issue #27 with a Draw tool, end to end on ByteInk's private tablet compositor: the page draws
     * the tool's circle under the hovering pen and asks for no cursor, so the pen's cursor is hidden.
     */
    @OptIn(ExperimentalComposeUiApi::class, androidx.compose.runtime.tooling.ComposeToolingApi::class)
    @Test fun aHoveringPenWithADrawToolTradesItsCursorForTheToolsCircle() {
        assumeTrue(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit")
        val displayClass = Class.forName("sun.awt.wl.WLDisplay")
        val display = displayClass.getMethod("getDisplayPtr").invoke(displayClass.getMethod("getInstance").invoke(null)) as Long
        val fixture = TabletFixture.open(display)
        assumeTrue("needs ByteInk's private tablet test compositor", fixture != null)
        WaylandRendering.prepare()
        val lines = CopyOnWriteArrayList<String>()
        val log = DebugLog(true) { lines += it }
        val stylus = StylusHover()
        val state = androidx.compose.runtime.mutableStateOf(WorkspaceState.demo().selectInkTool(InkTool.Pen))
        var frame: JFrame? = null
        lateinit var panel: androidx.compose.ui.awt.ComposePanel
        try {
            SwingUtilities.invokeAndWait {
                val window = JFrame("Wayland pen circle")
                frame = window
                panel = waylandComposePanel(log)
                window.add(panel)
                panel.setContent {
                    androidx.compose.runtime.CompositionLocalProvider(LocalStylusHover provides stylus) {
                        WorkspaceScreen(state.value, { state.value = it(state.value) })
                    }
                }
                window.setSize(1200, 800)
                window.isVisible = true
                DesktopPointerSource.attach(window, ComposeTouchInjector(log), log, stylus)
            }
            val window = checkNotNull(frame)
            // The middle of the page canvas, in the window's coordinates.
            val deadline = System.currentTimeMillis() + 10_000
            var canvas: androidx.compose.ui.geometry.Rect? = null
            while (canvas == null) {
                SwingUtilities.invokeAndWait {
                    canvas = panel.semanticsOwners.firstNotNullOfOrNull { owner ->
                        owner.getAllSemanticsNodes(mergingEnabled = false).firstOrNull {
                            it.config.getOrNull(SemanticsProperties.TestTag) == WorkspaceTestTags.PageCanvas
                        }?.boundsInRoot?.takeIf { it.width > 0f }
                    }
                }
                if (canvas == null) { check(System.currentTimeMillis() < deadline) { "the page never laid out" }; Thread.sleep(50) }
            }
            lateinit var at: java.awt.geom.Point2D.Double
            SwingUtilities.invokeAndWait {
                val origin = SwingUtilities.convertPoint(panel, 0, 0, window)
                val scale = panel.graphicsConfiguration.defaultTransform.scaleX
                at = java.awt.geom.Point2D.Double(origin.x + canvas!!.center.x / scale, origin.y + canvas!!.center.y / scale)
            }
            val surface = surfaceOf(window)
            var time = 1000
            val until = System.currentTimeMillis() + 10_000
            while (true) {
                fixture!!.send(surface, 0, 1, at.x + (time % 2), at.y, time++)
                Thread.sleep(50)
                val cursors = fixture.cursors()
                if (cursors.any { it.hidden > 0 && it.surfaceSerial }) break
                check(System.currentTimeMillis() < until) { "the pen's cursor was never hidden: $cursors\n${lines.joinToString("\n")}" }
            }
            assertTrue(stylus.active, "the page knows the pen hovers")
            val hidden = fixture!!.cursors().single { it.hidden > 0 }
            // A stroke, reached as compositors report one: the tip's approach as hover just before it
            // touches, then hover again once it lifts. Never a visible cursor for the pen meanwhile.
            fixture.send(surface, 0, 1, at.x, at.y, time++)
            fixture.send(surface, 0, 0, at.x, at.y, time++)
            for (step in 1..5) { fixture.send(surface, 0, 1, at.x + step * 6, at.y, time++); Thread.sleep(10) }
            fixture.send(surface, 0, 2, at.x + 30, at.y, time++)
            for (step in 1..3) { fixture.send(surface, 0, 1, at.x + 30 + step, at.y, time++); Thread.sleep(30) }
            Thread.sleep(300)
            val after = fixture.cursors().single { it.tool == hidden.tool }
            assertEquals(hidden.shapes, after.shapes, "a visible cursor asked for during or after the stroke: $after")
            assertEquals(hidden.surfaces, after.surfaces, "a cursor surface during or after the stroke: $after")
            SwingUtilities.invokeAndWait {
                val under = SwingUtilities.getDeepestComponentAt(window, (at.x + 33).toInt(), at.y.toInt())
                assertEquals("none", under?.cursor?.name, "the cursor ByteInk reads for the pen")
            }
            assertTrue(stylus.active, "the pen hovers on")
            fixture.send(surface, 0, 3, at.x, at.y, time++)
            val left = System.currentTimeMillis() + 5_000
            while (stylus.active) { check(System.currentTimeMillis() < left) { "the hover never ended" }; Thread.sleep(20) }
        } finally {
            frame?.let { window -> SwingUtilities.invokeAndWait { window.dispose() } }
            fixture?.close()
        }
    }

    /** The window's wl_surface, as JBR's peer holds it. */
    private fun surfaceOf(window: java.awt.Window): Long {
        var surface = 0L
        val deadline = System.currentTimeMillis() + 5000
        while (surface == 0L) {
            SwingUtilities.invokeAndWait {
                val peers = Class.forName("sun.awt.wl.WLToolkit").getDeclaredField("wlSurfaceToPeerMap")
                    .apply { isAccessible = true }.get(null) as Map<*, *>
                val peer = synchronized(peers) {
                    peers.values.firstOrNull { it != null && it.javaClass.getMethod("getTarget").apply { isAccessible = true }.invoke(it) === window }
                }
                val wlSurface = peer?.javaClass?.getMethod("getSurface")?.apply { isAccessible = true }?.invoke(peer)
                surface = wlSurface?.javaClass?.getMethod("getWlSurfacePtr")?.apply { isAccessible = true }?.invoke(wlSurface) as Long? ?: 0L
            }
            if (surface == 0L) {
                check(System.currentTimeMillis() < deadline) { "the window's surface was never configured" }
                Thread.sleep(20)
            }
        }
        return surface
    }

    private companion object {
        // cursor-shape-v1 shapes
        const val DEFAULT = 1
        const val TEXT = 9
    }
}
