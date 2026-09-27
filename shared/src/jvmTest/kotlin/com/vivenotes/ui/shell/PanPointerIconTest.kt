package com.vivenotes.ui.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.vivenotes.workspace.WorkspaceState
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanPointerIconTest {
    @Test
    fun desktopPanUsesDirectionalCustomCursorsWhenSupported() {
        val east = panPointerIcon(PanDirection.East)
        val north = panPointerIcon(PanDirection.North)
        val supportsCustom = !GraphicsEnvironment.isHeadless() &&
            Toolkit.getDefaultToolkit().getBestCursorSize(32, 32).width >= 24
        if (supportsCustom) {
            assertTrue(east != PointerIcon.Default)
            assertTrue(east != north)
        }
        assertEquals(PanDirection.NorthWest, panDirection(-50f, -30f))
        assertEquals(PanDirection.Center, panDirection(15f, -16f))
        assertEquals(PanDirection.South, panDirection(0f, 40f))
    }

    @Test
    fun pressedPanOverridesRenderChildCursorAndRestoresIt() {
        val child = JPanel().apply { cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR) }
        val inheritedChild = JPanel()
        val host = JPanel().apply { add(child); add(inheritedChild) }
        val originalHostCursor = host.cursor
        val originalInheritedCursor = inheritedChild.cursor
        val override = PanCursorOverride(host, Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR))

        override.apply()
        assertEquals(Cursor.MOVE_CURSOR, host.cursor.type)
        assertEquals(Cursor.MOVE_CURSOR, child.cursor.type)
        assertEquals(Cursor.MOVE_CURSOR, inheritedChild.cursor.type)

        override.restore()
        assertEquals(originalHostCursor, host.cursor)
        assertEquals(Cursor.TEXT_CURSOR, child.cursor.type)
        assertEquals(originalInheritedCursor, inheritedChild.cursor)
    }

    @Test
    fun releaseKeepsTheCursorComposeSetAndNeverRestoresAPanCursor() {
        val pan = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        val render = JPanel()
        val inheritedChild = JPanel()
        val host = JPanel().apply { add(render); add(inheritedChild) }

        // Compose shows its pan hover icon before the override runs, then its hover cursor on release.
        render.cursor = pan
        val first = PanCursorOverride(host, pan)
        first.apply()
        render.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        first.restore()
        assertEquals(Cursor.TEXT_CURSOR, render.cursor.type)
        assertFalse(host.isCursorSet)
        assertFalse(inheritedChild.isCursorSet)

        // Without a new cursor from Compose, the stale pan snapshot must not come back.
        render.cursor = pan
        val second = PanCursorOverride(host, pan)
        second.apply()
        second.restore()
        assertEquals(Cursor.DEFAULT_CURSOR, render.cursor.type)
        assertFalse(render.isCursorSet)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun embeddedComposePanelAppliesCursorDuringPan() {
        if (GraphicsEnvironment.isHeadless()) return
        val active = mutableStateOf(false)
        val direction = mutableStateOf(PanDirection.Center)
        lateinit var frame: JFrame
        lateinit var panel: ComposePanel
        SwingUtilities.invokeAndWait {
            frame = JFrame("Pan cursor test")
            panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
        }
        try {
            SwingUtilities.invokeAndWait {
                frame.contentPane.add(panel)
                frame.setSize(120, 90)
                frame.isVisible = true
                panel.setContent { ApplyPanCursorWhilePressed(active.value, direction.value) }
            }
            val initialCursor = renderCursorType(panel)
            active.value = true
            val expected = if (Toolkit.getDefaultToolkit().getBestCursorSize(32, 32).width >= 24)
                Cursor.CUSTOM_CURSOR else Cursor.MOVE_CURSOR
            assertTrue(waitForRenderCursor(panel, expected), "pan cursor did not reach ComposePanel")
            if (expected == Cursor.CUSTOM_CURSOR) {
                direction.value = PanDirection.East
                assertTrue(waitForRenderCursorName(panel, "Canvas auto-scroll East"),
                    "directional cursor did not reach ComposePanel")
            }
            active.value = false
            assertTrue(waitForRenderCursor(panel, initialCursor), "cursor was not restored; current=${renderCursorType(panel)}, initial=$initialCursor")
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)
    @Test
    fun middleReleaseOverWorkspaceCanvasRestoresTheNormalCursor() {
        if (GraphicsEnvironment.isHeadless() || Toolkit.getDefaultToolkit().getBestCursorSize(32, 32).width < 24) return
        val state = mutableStateOf(WorkspaceState.demo())
        lateinit var frame: JFrame
        lateinit var panel: ComposePanel
        SwingUtilities.invokeAndWait {
            frame = JFrame("Workspace pan cursor test")
            panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
            frame.contentPane.add(panel)
            frame.setSize(1400, 900)
            frame.isVisible = true
            panel.setContent { WorkspaceScreen(state.value, { change -> state.value = change(state.value) }) }
        }
        try {
            val canvas = waitFor("page canvas layout") {
                panel.semanticsOwners.firstNotNullOfOrNull { owner ->
                    owner.getAllSemanticsNodes(mergingEnabled = false).firstOrNull {
                        it.config.getOrNull(SemanticsProperties.TestTag) == WorkspaceTestTags.PageCanvas
                    }?.boundsInRoot?.takeIf { it.width > 400f && it.height > 300f }
                }
            }
            lateinit var content: Component
            SwingUtilities.invokeAndWait { content = panel.renderComponent() }
            val scale = content.graphicsConfiguration.defaultTransform.scaleX.toFloat()
            val anchor = Point(((canvas.left + 80f) / scale).toInt(), ((canvas.top + 260f) / scale).toInt())
            val steered = Point(anchor.x + 120, anchor.y + 90)

            content.mouse(MouseEvent.MOUSE_ENTERED, anchor)
            content.mouse(MouseEvent.MOUSE_MOVED, anchor)
            val normal = waitFor("hover cursor") { panel.renderComponent().cursor.name.takeIf { !it.isPanCursor() } }

            content.mouse(MouseEvent.MOUSE_PRESSED, anchor, MouseEvent.BUTTON2)
            waitFor("center pan cursor") {
                panel.renderComponent().cursor.name.takeIf { it == "Canvas auto-scroll Center" }
            }
            content.mouse(MouseEvent.MOUSE_RELEASED, anchor, MouseEvent.BUTTON2)
            val restored = settledCursorNames(panel)
            assertTrue(restored.none { it.isPanCursor() }, "pan cursor stayed after release: $restored")
            assertEquals(normal, restored.first())

            content.mouse(MouseEvent.MOUSE_PRESSED, anchor, MouseEvent.BUTTON2)
            content.mouse(MouseEvent.MOUSE_DRAGGED, steered, MouseEvent.BUTTON2)
            waitFor("directional pan cursor") {
                panel.renderComponent().cursor.name.takeIf { it == "Canvas auto-scroll SouthEast" }
            }
            content.mouse(MouseEvent.MOUSE_RELEASED, steered, MouseEvent.BUTTON2)
            val afterSteering = settledCursorNames(panel)
            assertTrue(afterSteering.none { it.isPanCursor() }, "pan cursor stayed after steering: $afterSteering")
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    private fun Component.mouse(id: Int, at: Point, button: Int = MouseEvent.NOBUTTON) {
        val held = if (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_DRAGGED)
            MouseEvent.BUTTON2_DOWN_MASK else 0
        SwingUtilities.invokeAndWait {
            dispatchEvent(MouseEvent(this, id, System.currentTimeMillis(), held, at.x, at.y,
                if (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED) 1 else 0,
                false, button))
        }
    }

    /** Cursor names of the Compose render component and every Swing component around it. */
    private fun settledCursorNames(panel: ComposePanel): List<String> {
        Thread.sleep(300)
        var names = emptyList<String>()
        SwingUtilities.invokeAndWait {
            val window = SwingUtilities.getWindowAncestor(panel)
            fun collect(component: Component): List<String> = listOf(component.cursor.name) +
                ((component as? Container)?.components.orEmpty().flatMap(::collect))
            names = listOf(panel.renderComponent().cursor.name) + collect(window)
        }
        return names
    }

    /** The Compose surface that receives mouse input, not the zero-size focus helper beside it. */
    private fun ComposePanel.renderComponent(): Component = components.first { it.width > 0 && it.height > 0 }

    private fun String.isPanCursor() = startsWith("Canvas auto-scroll")

    /** Polls [value] on the Swing event thread. */
    private fun <T : Any> waitFor(what: String, value: () -> T?): T {
        repeat(250) {
            var found: T? = null
            SwingUtilities.invokeAndWait { found = value() }
            found?.let { return it }
            Thread.sleep(20)
        }
        error("timed out waiting for $what")
    }

    private fun waitForRenderCursor(panel: ComposePanel, type: Int): Boolean {
        repeat(100) {
            if (renderCursorType(panel) == type) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun renderCursorType(panel: ComposePanel): Int {
        var cursorType = -1
        SwingUtilities.invokeAndWait { cursorType = panel.renderComponent().cursor?.type ?: -1 }
        return cursorType
    }

    private fun waitForRenderCursorName(panel: ComposePanel, name: String): Boolean {
        repeat(100) {
            var current: String? = null
            SwingUtilities.invokeAndWait { current = panel.renderComponent().cursor?.name }
            if (current == name) return true
            Thread.sleep(20)
        }
        return false
    }
}
