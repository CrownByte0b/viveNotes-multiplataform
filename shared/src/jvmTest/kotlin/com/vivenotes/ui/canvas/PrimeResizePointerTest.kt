@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.vivenotes.ui.canvas

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerIconService
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.model.newId
import com.vivenotes.model.newTable
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.workspace.WorkspaceState
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class PrimeResizePointerTest {
    @Test
    fun desktopUsesExplicitBidirectionalDiagonalCursors() {
        if (GraphicsEnvironment.isHeadless()) return
        for (diagonal in PrimeResizeDiagonal.entries) {
            val cursor = diagonal.cursor
            assertEquals(Cursor.CUSTOM_CURSOR, cursor.type)
            assertEquals(diagonal.keyword, cursor.name)
            assertEquals(PointerIcon(cursor), primeResizePointerIcon(diagonal))
        }
    }

    @Test
    fun diagonalImagesHaveArrowheadsAtBothEndsOfTheirAxis() {
        val directory = java.io.File("build/test-artifacts").apply { mkdirs() }
        for (diagonal in PrimeResizeDiagonal.entries) {
            val image = primeResizeCursorImage(diagonal)
            val rising = diagonal == PrimeResizeDiagonal.NorthEastSouthWest
            val tips = if (rising) listOf(8 to 24, 24 to 8) else listOf(8 to 8, 24 to 24)
            val empty = if (rising) listOf(8 to 8, 24 to 24) else listOf(8 to 24, 24 to 8)
            for ((x, y) in tips) assertTrue((image.getRGB(x, y) ushr 24) > 0, "missing arrowhead at $x,$y")
            for ((x, y) in empty) assertEquals(0, image.getRGB(x, y) ushr 24)
            assertTrue((image.getRGB(16, 16) ushr 24) > 0)
            javax.imageio.ImageIO.write(primeResizeCursorImage(diagonal, 128), "png",
                java.io.File(directory, "${diagonal.keyword}.png"))
        }
    }

    @Test
    fun pressedResizeRestoresInheritedAndExplicitCursorsAndRespectsLaterHover() {
        for (diagonal in PrimeResizeDiagonal.entries) {
            val text = JPanel().apply { cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR) }
            val inherited = JPanel()
            val newHover = JPanel()
            val host = JPanel().apply { add(text); add(inherited); add(newHover) }
            val previous = host.cursor
            val delegate = RecordingPointerService { icon ->
                newHover.cursor = when (icon) {
                    PointerIcon.Hand -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    PointerIcon.Text -> Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                    primeResizePointerIcon(diagonal) -> diagonal.cursor
                    else -> null
                }
            }
            val service = PrimeResizePointerService(delegate, host)
            val owner = Any()
            service.hold(owner, diagonal)
            assertSame(diagonal.cursor, host.cursor)
            assertSame(diagonal.cursor, inherited.cursor)
            assertEquals(Cursor.TEXT_CURSOR, text.cursor.type)
            service.setIcon(PointerIcon.Hand)
            assertSame(diagonal.cursor, newHover.cursor)
            service.release(owner)
            assertEquals(previous, host.cursor)
            assertEquals(Cursor.TEXT_CURSOR, text.cursor.type)
            assertEquals(previous, inherited.cursor)
            assertFalse(inherited.isCursorSet)
            assertEquals(Cursor.HAND_CURSOR, newHover.cursor.type)
            service.release(owner)
            assertEquals(previous, host.cursor)
            assertEquals(Cursor.HAND_CURSOR, newHover.cursor.type)
        }
    }

    @Test
    fun layoutHoverResetsNeverReachThePlatformDuringAHeldResize() {
        for (diagonal in PrimeResizeDiagonal.entries) {
            val delegate = RecordingPointerService()
            val service = PrimeResizePointerService(delegate)
            val owner = Any()
            service.hold(owner, diagonal)
            val expected = primeResizePointerIcon(diagonal)
            delegate.assignments.clear()
            repeat(20) {
                service.setIcon(null)
                service.setIcon(PointerIcon.Default)
                service.setIcon(PointerIcon.Text)
                service.setIcon(PointerIcon.Hand)
            }
            assertEquals(80, delegate.assignments.size)
            delegate.assignments.forEach { assertEquals(expected, it) }
            service.release(owner)
            assertEquals(PointerIcon.Hand, delegate.getIcon())
            val nextOwner = Any()
            service.hold(nextOwner, diagonal)
            service.release(owner)
            assertEquals(expected, delegate.getIcon())
            service.releaseCurrent()
            assertEquals(PointerIcon.Hand, delegate.getIcon())
        }
    }

    @Test
    fun allPrimeObjectKindsShowMatchingCursorsAtEveryCornerAndThroughoutResizing() = withWorkspace { host ->
        val objects = listOf(
            Outline.Shape(id = "shape", kind = ShapeKind.Rectangle, borderWidth = 20f,
                segments = seedSegments(ShapeKind.Rectangle, 100f, 220f, 240f, 320f, ::newId)).withRecomputedBounds(),
            newTable(2, 2, 100f, 220f).copy(id = "table"),
            Outline.Equation("equation", 100f, 220f, 140f, 100f, "x^2"),
            Outline.Image("image", 100f, 220f, 140f, attachmentId = "fixture", height = 100f),
        )
        for (outline in objects) {
            host.update(workspace(listOf(outline)).selectObject(outline.id))
            // A table's top and left edges carry its row and column handles, so it scales from the
            // bottom-right alone, as on Android; the other corners are no handles at all.
            val corners = if (outline is Outline.Table) listOf(3) else (0..3).toList()
            host.bounds(WorkspaceTestTags.objectCorner(outline.id, 3))
            (0..3).filterNot { it in corners }.forEach {
                SwingUtilities.invokeAndWait { assertNull(host.boundsNow(WorkspaceTestTags.objectCorner(outline.id, it))) }
            }
            corners.forEach { corner ->
                val tag = WorkspaceTestTags.objectCorner(outline.id, corner)
                host.resize(tag, corner) { host.state.value.selectedPage!!.document.outlines.single().width }
            }
        }
    }

    @Test
    fun groupCornersUseBothDiagonalsAndLockingDuringAPressRestoresTheCursor() = withWorkspace { host ->
        val first = Outline.Image("first", 100f, 220f, 140f, attachmentId = "fixture", height = 100f)
        val second = first.copy(id = "second", x = 300f)
        host.update(workspace(listOf(first, second)).selectObjectsInRect(80f, 200f, 460f, 340f))
        repeat(4) { corner ->
            host.resize(WorkspaceTestTags.groupCorner(corner), corner) {
                host.state.value.selectedPage!!.document.outlines.first().width
            }
        }
        val tag = WorkspaceTestTags.groupCorner(1)
        val point = host.point(host.bounds(tag))
        host.render.mouse(MouseEvent.MOUSE_MOVED, point)
        host.render.mouse(MouseEvent.MOUSE_PRESSED, point)
        host.awaitCursor(PrimeResizeDiagonal.NorthEastSouthWest.cursor)
        SwingUtilities.invokeAndWait { host.state.value = host.state.value.toggleObjectLock() }
        waitFor("locked group has no resize handles") { true.takeIf { host.boundsNow(tag) == null } }
        host.awaitNormalCursor()
        host.render.mouse(MouseEvent.MOUSE_RELEASED, point)
        val locked = host.state.value
        val frameCenter = host.point(host.bounds(WorkspaceTestTags.GroupSelectionFrame))
        host.render.mouse(MouseEvent.MOUSE_MOVED, frameCenter)
        host.awaitNormalCursor()
        host.render.mouse(MouseEvent.MOUSE_PRESSED, frameCenter)
        host.render.mouse(MouseEvent.MOUSE_DRAGGED, Point(frameCenter.x + 35, frameCenter.y + 20))
        host.render.mouse(MouseEvent.MOUSE_RELEASED, Point(frameCenter.x + 35, frameCenter.y + 20))
        SwingUtilities.invokeAndWait {
            assertEquals(locked.selectedPage!!.document.outlines, host.state.value.selectedPage!!.document.outlines)
        }
        host.update(host.state.value.toggleObjectLock())
        host.bounds(tag)
        host.render.mouse(MouseEvent.MOUSE_MOVED, host.point(host.bounds(tag)))
        host.awaitCursor(PrimeResizeDiagonal.NorthEastSouthWest.cursor)
    }

    private fun workspace(outlines: List<Outline>): WorkspaceState {
        val base = WorkspaceState.demo()
        return base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = outlines)) }
    }

    private fun withWorkspace(test: (Host) -> Unit) {
        if (GraphicsEnvironment.isHeadless()) return
        lateinit var host: Host
        SwingUtilities.invokeAndWait { host = Host() }
        try { test(host) } finally { SwingUtilities.invokeAndWait { host.frame.dispose() } }
    }

    private class Host {
        val state = mutableStateOf(WorkspaceState.demo())
        val frame = JFrame("Prime object resize cursor test")
        val panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
        val render: Component get() = panel.renderComponent()

        init {
            frame.contentPane.add(panel)
            frame.setSize(1500, 1000)
            frame.isVisible = true
            panel.setContent { WorkspaceScreen(state.value, { state.value = it(state.value) }) }
        }

        fun update(value: WorkspaceState) = SwingUtilities.invokeAndWait { state.value = value }

        fun boundsNow(tag: String): Rect? = panel.semanticsOwners.firstNotNullOfOrNull { owner ->
            owner.getAllSemanticsNodes(mergingEnabled = false).firstOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == tag
            }?.boundsInRoot?.takeIf { it.width > 0f && it.height > 0f }
        }

        fun bounds(tag: String): Rect = waitFor("layout of $tag") { boundsNow(tag) }

        fun point(rect: Rect): Point {
            val scale = render.graphicsConfiguration.defaultTransform.scaleX.toFloat()
            return Point((rect.center.x / scale).toInt(), (rect.center.y / scale).toInt())
        }

        fun awaitCursor(cursor: Cursor, context: String = ""): Cursor = try {
            waitFor("cursor ${cursor.name} $context") { render.cursor.takeIf { it === cursor } }
        } catch (failure: IllegalStateException) {
            error("${failure.message}; host=${frame.cursor.name}, render=${render.cursor.name}")
        }

        fun awaitNormalCursor() = waitFor("diagonal cursor cleared") {
            render.cursor.takeUnless { cursor -> PrimeResizeDiagonal.entries.any { it.cursor === cursor } }
        }

        fun resize(tag: String, corner: Int, width: () -> Float) {
            val initial = bounds(tag)
            val at = point(initial)
            val cursor = primeResizeDiagonal(corner).cursor
            render.mouse(MouseEvent.MOUSE_ENTERED, at)
            render.mouse(MouseEvent.MOUSE_MOVED, at)
            awaitCursor(cursor, "$tag hover at $at within ${bounds(tag)}, toolkit ${bounds(WorkspaceTestTags.ObjectToolkit)}")
            render.mouse(MouseEvent.MOUSE_PRESSED, at)
            awaitCursor(cursor, "$tag pressed")
            waitFor("pressed host cursor of $tag") { frame.cursor.takeIf { it === cursor } }
            for (step in 1..4) {
                val beforeBounds = bounds(tag)
                val before = waitFor("object width") { width() }
                render.mouse(MouseEvent.MOUSE_DRAGGED, Point(at.x + step * 12, at.y + step * 8))
                SwingUtilities.invokeAndWait { assertSame(cursor, render.cursor, "cursor changed during drag dispatch") }
                waitFor("live object resize of $tag from width $before") { width().takeIf { it != before } }
                waitFor("live corner movement of $tag") { boundsNow(tag)?.takeIf { it != beforeBounds } }
                SwingUtilities.invokeAndWait { assertSame(cursor, render.cursor, "cursor changed after layout") }
            }
            val dragged = Point(at.x + 48, at.y + 32)
            render.mouse(MouseEvent.MOUSE_RELEASED, dragged)
            render.mouse(MouseEvent.MOUSE_MOVED, point(bounds(WorkspaceTestTags.PageCanvas)).apply { x += 250 })
            awaitNormalCursor()
        }
    }

    companion object {
        private val PrimeResizeDiagonal.cursor: Cursor get() = primeResizeAwtCursor(this)

        private fun Component.mouse(id: Int, at: Point) {
            val held = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_DRAGGED
            val button = if (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED)
                MouseEvent.BUTTON1 else MouseEvent.NOBUTTON
            SwingUtilities.invokeAndWait {
                dispatchEvent(MouseEvent(this, id, System.currentTimeMillis(),
                    if (held) MouseEvent.BUTTON1_DOWN_MASK else 0, at.x, at.y,
                    if (button == MouseEvent.BUTTON1) 1 else 0, false, button))
            }
        }

        private fun Container.renderComponent(): Component = components.firstNotNullOfOrNull { child ->
            if (child.mouseListeners.any { "ComposeSceneMediator" in it.javaClass.name }) child
            else (child as? Container)?.let { runCatching { it.renderComponent() }.getOrNull() }
        } ?: error("Compose rendering component is not attached")

        private fun <T : Any> waitFor(what: String, value: () -> T?): T {
            repeat(250) {
                var found: T? = null
                SwingUtilities.invokeAndWait { found = value() }
                found?.let { return it }
                Thread.sleep(20)
            }
            error("timed out waiting for $what")
        }
    }

    private class RecordingPointerService(private val onSet: (PointerIcon?) -> Unit = {}) : PointerIconService {
        val assignments = mutableListOf<PointerIcon?>()
        private var icon: PointerIcon? = PointerIcon.Default
        override fun getIcon(): PointerIcon = icon ?: PointerIcon.Default
        override fun setIcon(value: PointerIcon?) { icon = value; assignments += value; onSet(value) }
        override fun getStylusHoverIcon(): PointerIcon? = null
        override fun setStylusHoverIcon(value: PointerIcon?) = Unit
    }
}
