package com.vivenotes.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
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

class NavigationResizePointerIconTest {
    @Test
    fun desktopUsesTheNativeWestResizeCursor() {
        assertEquals(PointerIcon(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)), navigationResizePointerIcon())
    }

    @Test
    fun pressedResizeRestoresExplicitAndInheritedCursorsAndKeepsNewHoverChoices() {
        val text = JPanel().apply { cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR) }
        val inherited = JPanel()
        val newHover = JPanel()
        val host = JPanel().apply { add(text); add(inherited); add(newHover) }
        val previous = host.cursor
        val override = NavigationResizeCursorOverride(host)
        override.apply()
        assertEquals(Cursor.W_RESIZE_CURSOR, host.cursor.type)
        assertEquals(Cursor.W_RESIZE_CURSOR, text.cursor.type)
        assertEquals(Cursor.W_RESIZE_CURSOR, inherited.cursor.type)
        newHover.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        override.restore()
        assertEquals(previous, host.cursor)
        assertEquals(Cursor.TEXT_CURSOR, text.cursor.type)
        assertEquals(previous, inherited.cursor)
        assertEquals(false, inherited.isCursorSet)
        assertEquals(Cursor.HAND_CURSOR, newHover.cursor.type)
    }

    @OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)
    @Test
    fun embeddedDesktopDividersShowResizeOnHoverAndDuringDragAndRestoreOnExit() {
        if (GraphicsEnvironment.isHeadless()) return
        val state = mutableStateOf(WorkspaceState.demo())
        lateinit var frame: JFrame
        lateinit var panel: ComposePanel
        SwingUtilities.invokeAndWait {
            frame = JFrame("Navigation divider cursor test")
            panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
            frame.contentPane.add(panel)
            frame.setSize(1400, 900)
            frame.isVisible = true
            panel.setContent { WorkspaceScreen(state.value, { state.value = it(state.value) }) }
        }
        try {
            fun boundsNow(tag: String): Rect? =
                panel.semanticsOwners.firstNotNullOfOrNull { owner ->
                    owner.getAllSemanticsNodes(mergingEnabled = false).firstOrNull {
                        it.config.getOrNull(SemanticsProperties.TestTag) == tag
                    }?.boundsInRoot?.takeIf { it.width > 0f && it.height > 200f }
                }
            fun bounds(tag: String): Rect = waitFor("layout of $tag") { boundsNow(tag) }
            val render = waitFor("Compose rendering component") { runCatching { panel.renderComponent() }.getOrNull() }
            val scale = render.graphicsConfiguration.defaultTransform.scaleX.toFloat()
            for (tag in listOf(WorkspaceTestTags.NotebookDivider, WorkspaceTestTags.PageDivider)) {
                val divider = bounds(tag)
                val anchor = Point((divider.center.x / scale).toInt(), (divider.center.y / scale).toInt())
                render.mouse(MouseEvent.MOUSE_ENTERED, anchor)
                render.mouse(MouseEvent.MOUSE_MOVED, anchor)
                waitFor("resize cursor on hover") { render.cursor.type.takeIf { it == Cursor.W_RESIZE_CURSOR } }
                render.mouse(MouseEvent.MOUSE_PRESSED, anchor)
                render.mouse(MouseEvent.MOUSE_DRAGGED, Point(anchor.x + 60, anchor.y))
                waitFor("live divider movement") { boundsNow(tag)?.left?.takeIf { it > divider.left + 40f } }
                waitFor("resize cursor while pressed") { render.cursor.type.takeIf { it == Cursor.W_RESIZE_CURSOR } }
                render.mouse(MouseEvent.MOUSE_RELEASED, Point(anchor.x + 60, anchor.y))

                val canvas = bounds(WorkspaceTestTags.PageCanvas)
                render.mouse(MouseEvent.MOUSE_MOVED,
                    Point(((canvas.right - 50f) / scale).toInt(), (canvas.center.y / scale).toInt()))
                waitFor("resize cursor cleared after leaving divider") {
                    render.cursor.type.takeIf { it != Cursor.W_RESIZE_CURSOR }
                }
            }
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

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
