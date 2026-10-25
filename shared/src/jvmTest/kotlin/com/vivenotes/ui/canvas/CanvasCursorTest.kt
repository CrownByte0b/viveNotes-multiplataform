package com.vivenotes.ui.canvas

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.Run
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.model.newId
import com.vivenotes.platform.XcursorImage
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.workspace.WorkspaceState
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class CanvasCursorTest {
    private val allScroll get() = canvasAwtCursor(CanvasCursor.AllScroll)
    private val grabbing get() = canvasAwtCursor(CanvasCursor.Grabbing)

    @Test
    fun desktopCursorsAreTheThemesCssCursorsOrNativeFallbacks() {
        if (GraphicsEnvironment.isHeadless()) {
            assertEquals(Cursor.MOVE_CURSOR, allScroll.type)
            return
        }
        // From the cursor theme where there is one; Windows' four-headed Move cursor otherwise.
        assertTrue(allScroll.name == "all-scroll" || allScroll.type == Cursor.MOVE_CURSOR, allScroll.name)
        // A theme's closed hand, or a drawn one: never the plain arrow.
        assertTrue(grabbing.name == "grabbing" || grabbing.type == Cursor.MOVE_CURSOR, grabbing.name)
        assertNotEquals(Cursor.DEFAULT_CURSOR, grabbing.type)
        assertSame(grabbing, canvasAwtCursor(CanvasCursor.Grabbing), "cursors are made once")
        assertTrue(grabbing.isCanvasCursor() && allScroll.isCanvasCursor())
        assertFalse(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR).isCanvasCursor())
    }

    @Test
    fun cursorImagesAreFittedWithoutMovingTheirHotspot() {
        val image = XcursorImage(48, 48, 48, 23, 12, IntArray(48 * 48) { 0x7f000030 })
        // Wayland pads to the scale's multiple and X11 may offer a larger cursor: padded at the top left.
        val (padded, paddedHotspot) = fitCursorImage(image, Dimension(64, 64))
        assertEquals(64, padded.width)
        assertEquals(java.awt.Point(23, 12), paddedHotspot)
        assertEquals(0, padded.getRGB(60, 60) ushr 24)
        assertEquals(0x7f, padded.getRGB(10, 10) ushr 24)
        // A smaller cursor scales the image and its hotspot together.
        val (scaled, scaledHotspot) = fitCursorImage(image, Dimension(32, 32))
        assertEquals(32, scaled.width)
        assertEquals(java.awt.Point(15, 8), scaledHotspot)
        // Premultiplied pixels come back as straight alpha, as AWT's cursor code reads them.
        val (exact, _) = fitCursorImage(XcursorImage(2, 2, 2, 0, 0, intArrayOf(0x80404040.toInt(), 0, 0, 0)), Dimension(2, 2))
        val straight = exact.getRGB(0, 0)
        assertEquals(0x80, straight ushr 24)
        assertTrue((straight shr 16 and 0xff) in 0x7e..0x81, "red un-premultiplied: ${Integer.toHexString(straight)}")
    }

    @Test
    fun theDrawnGrabbingHandIsAClosedFistAroundItsHotspot() {
        val image = grabbingCursorImage()
        assertEquals(32, image.width)
        // Knuckles across the top, the back of the hand below them, nothing at the corners.
        for ((x, y) in listOf(11 to 9, 15 to 9, 19 to 9, 23 to 9, 16 to 18, 7 to 16)) {
            assertEquals(0xff, image.getRGB(x, y) ushr 24, "hand missing at $x,$y")
        }
        for ((x, y) in listOf(1 to 1, 30 to 1, 1 to 30, 30 to 30)) assertEquals(0, image.getRGB(x, y) ushr 24)
        val directory = java.io.File("build/test-artifacts").apply { mkdirs() }
        javax.imageio.ImageIO.write(grabbingCursorImage(128), "png", java.io.File(directory, "grabbing.png"))
    }

    @Test
    fun heldCursorOverridesRenderChildCursorAndRestoresIt() {
        val child = JPanel().apply { cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR) }
        val inheritedChild = JPanel()
        val host = JPanel().apply { add(child); add(inheritedChild) }
        val originalHostCursor = host.cursor
        val originalInheritedCursor = inheritedChild.cursor
        val override = CanvasCursorOverride(host, grabbing)

        override.apply()
        assertSame(grabbing, host.cursor)
        assertSame(grabbing, child.cursor)
        assertSame(grabbing, inheritedChild.cursor)

        override.restore()
        assertEquals(originalHostCursor, host.cursor)
        assertEquals(Cursor.TEXT_CURSOR, child.cursor.type)
        assertEquals(originalInheritedCursor, inheritedChild.cursor)
    }

    @Test
    fun releaseKeepsTheCursorComposeSetAndNeverRestoresACanvasCursor() {
        val render = JPanel()
        val inheritedChild = JPanel()
        val host = JPanel().apply { add(render); add(inheritedChild) }

        // Compose shows its held icon before the override runs, then its hover cursor on release.
        render.cursor = allScroll
        val first = CanvasCursorOverride(host, allScroll)
        first.apply()
        render.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        first.restore()
        assertEquals(Cursor.TEXT_CURSOR, render.cursor.type)
        assertFalse(host.isCursorSet)
        assertFalse(inheritedChild.isCursorSet)

        // Without a new cursor from Compose, a stale canvas cursor must not come back, nor the other one.
        render.cursor = grabbing
        val second = CanvasCursorOverride(host, allScroll)
        second.apply()
        second.restore()
        assertFalse(render.isCursorSet)
    }

    @Test
    fun objectGrabBelongsToTheDragThatHoldsIt() {
        val grab = ObjectGrab()
        val first = Any()
        val second = Any()
        assertFalse(grab.held)
        grab.releaseAll()
        assertFalse(grab.held)
        val mouse = androidx.compose.ui.input.pointer.PointerInputChange(
            androidx.compose.ui.input.pointer.PointerId(1), 0L, Offset.Zero, true, 0L, Offset.Zero, true,
            isInitiallyConsumed = false, type = androidx.compose.ui.input.pointer.PointerType.Mouse)
        val finger = androidx.compose.ui.input.pointer.PointerInputChange(
            androidx.compose.ui.input.pointer.PointerId(2), 0L, Offset.Zero, true, 0L, Offset.Zero, true,
            isInitiallyConsumed = false, type = androidx.compose.ui.input.pointer.PointerType.Touch)
        grab.hold(first, finger)
        assertFalse(grab.held, "a finger has no cursor")
        grab.hold(first, mouse)
        assertTrue(grab.held)
        grab.release(second)
        assertTrue(grab.held, "another handler stopping does not drop it")
        grab.release(first)
        assertFalse(grab.held)
        grab.hold(second, mouse)
        grab.releaseAll()
        assertFalse(grab.held)
    }

    @Test
    fun embeddedComposePanelAppliesTheHeldCursorAndRestoresIt() {
        if (GraphicsEnvironment.isHeadless()) return
        val held = mutableStateOf<CanvasCursor?>(null)
        lateinit var frame: JFrame
        lateinit var panel: ComposePanel
        SwingUtilities.invokeAndWait {
            frame = JFrame("Canvas cursor test")
            panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
        }
        try {
            SwingUtilities.invokeAndWait {
                frame.contentPane.add(panel)
                frame.setSize(120, 90)
                frame.isVisible = true
                panel.setContent { ApplyCanvasCursorWhilePressed(held.value) }
            }
            val initial = waitFor("render component") { panel.renderComponent().cursor }
            held.value = CanvasCursor.AllScroll
            waitFor("all-scroll on the render component") { panel.renderComponent().cursor.takeIf { it === allScroll } }
            waitFor("all-scroll on the frame") { frame.cursor.takeIf { it === allScroll } }
            held.value = CanvasCursor.Grabbing
            waitFor("grabbing on the render component") { panel.renderComponent().cursor.takeIf { it === grabbing } }
            held.value = null
            waitFor("cursor restored") { panel.renderComponent().cursor.takeIf { it == initial } }
            waitFor("frame restored") { frame.cursor.takeUnless { it.isCanvasCursor() } }
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    /** Middle-click auto-scroll shows all-scroll the whole time, wherever the mouse is steered. */
    @Test
    fun middlePressShowsAllScrollUntilReleaseOverTheWorkspace() = withWorkspace { host ->
        val canvas = host.bounds(WorkspaceTestTags.PageCanvas)
        val anchor = host.point(Offset(canvas.left + 80f, canvas.top + 260f))
        val steered = Point(anchor.x + 120, anchor.y + 90)

        host.mouse(MouseEvent.MOUSE_ENTERED, anchor)
        host.mouse(MouseEvent.MOUSE_MOVED, anchor)
        val normal = waitFor("hover cursor") { host.render.cursor.takeUnless { it.isCanvasCursor() } }

        host.mouse(MouseEvent.MOUSE_PRESSED, anchor, MouseEvent.BUTTON2)
        host.awaitCursor(allScroll, "after a middle press")
        host.mouse(MouseEvent.MOUSE_RELEASED, anchor, MouseEvent.BUTTON2)
        val restored = host.settledCursors()
        assertTrue(restored.none { it.isCanvasCursor() }, "all-scroll stayed after release: ${restored.map { it.name }}")
        assertEquals(normal, restored.first())

        host.mouse(MouseEvent.MOUSE_PRESSED, anchor, MouseEvent.BUTTON2)
        host.mouse(MouseEvent.MOUSE_DRAGGED, steered, MouseEvent.BUTTON2)
        host.awaitCursor(allScroll, "after steering")
        Thread.sleep(150)
        SwingUtilities.invokeAndWait { assertSame(allScroll, host.render.cursor, "the cursor no longer follows the direction") }
        host.mouse(MouseEvent.MOUSE_RELEASED, steered, MouseEvent.BUTTON2)
        val afterSteering = host.settledCursors()
        assertTrue(afterSteering.none { it.isCanvasCursor() }, "all-scroll stayed after steering: ${afterSteering.map { it.name }}")
    }

    @Test
    fun movingAnObjectShowsGrabbingOnlyWhileItIsHeldAndMoving() = withWorkspace { host ->
        val shape = Outline.Shape(id = "shape", kind = ShapeKind.Rectangle, borderWidth = 4f,
            segments = seedSegments(ShapeKind.Rectangle, 200f, 260f, 360f, 380f, ::newId)).withRecomputedBounds()
        host.update(workspace(listOf(shape)).selectObject(shape.id))
        val body = host.point(host.bounds(WorkspaceTestTags.primeObject(shape.id)).center)

        // A click without moving is no grab.
        host.mouse(MouseEvent.MOUSE_ENTERED, body)
        host.mouse(MouseEvent.MOUSE_MOVED, body)
        host.mouse(MouseEvent.MOUSE_PRESSED, body)
        Thread.sleep(150)
        SwingUtilities.invokeAndWait { assertFalse(host.render.cursor.isCanvasCursor(), "pressed, not yet moved") }
        host.mouse(MouseEvent.MOUSE_RELEASED, body)

        host.mouse(MouseEvent.MOUSE_PRESSED, body)
        val startX = host.state.value.objectX(shape.id)
        for (step in 1..4) {
            host.mouse(MouseEvent.MOUSE_DRAGGED, Point(body.x + step * 15, body.y + step * 10))
            host.awaitCursor(grabbing, "drag step $step")
            waitFor("frame grabbing") { host.frame.cursor.takeIf { it === grabbing } }
        }
        waitFor("the object moved") { host.state.value.objectX(shape.id).takeIf { it > startX } }
        SwingUtilities.invokeAndWait { assertSame(grabbing, host.render.cursor, "grabbing held after layout") }
        host.mouse(MouseEvent.MOUSE_RELEASED, Point(body.x + 60, body.y + 40))
        val released = host.settledCursors()
        assertTrue(released.none { it.isCanvasCursor() }, "grabbing stayed after release: ${released.map { it.name }}")

        // A locked object stays put, so nothing is grabbed.
        host.update(host.state.value.toggleObjectLock())
        val locked = host.point(host.bounds(WorkspaceTestTags.primeObject(shape.id)).center)
        host.mouse(MouseEvent.MOUSE_MOVED, locked)
        host.mouse(MouseEvent.MOUSE_PRESSED, locked)
        repeat(4) { step -> host.mouse(MouseEvent.MOUSE_DRAGGED, Point(locked.x + step * 15, locked.y + step * 10)) }
        Thread.sleep(200)
        SwingUtilities.invokeAndWait { assertFalse(host.render.cursor.isCanvasCursor(), "a locked object is not grabbed") }
        host.mouse(MouseEvent.MOUSE_RELEASED, Point(locked.x + 45, locked.y + 30))
    }

    @Test
    fun draggingATextBoxByItsGripShowsGrabbing() = withWorkspace { host ->
        val box = Outline.Text(id = "box", x = 200f, y = 300f, blocks = listOf(Block.of("Drag me")))
        host.update(workspace(listOf(box)).focusTextBox(box.id))
        val grip = host.point(host.bounds(WorkspaceTestTags.textGrip(box.id)).center)
        host.mouse(MouseEvent.MOUSE_ENTERED, grip)
        host.mouse(MouseEvent.MOUSE_MOVED, grip)
        host.mouse(MouseEvent.MOUSE_PRESSED, grip)
        for (step in 1..3) {
            host.mouse(MouseEvent.MOUSE_DRAGGED, Point(grip.x + step * 15, grip.y + step * 10))
            host.awaitCursor(grabbing, "grip drag step $step")
        }
        host.mouse(MouseEvent.MOUSE_RELEASED, Point(grip.x + 45, grip.y + 30))
        val released = host.settledCursors()
        assertTrue(released.none { it.isCanvasCursor() }, "grabbing stayed after release: ${released.map { it.name }}")
        val moved = waitFor("the box moved") {
            host.state.value.selectedPage!!.document.outlines.single().x.takeIf { it > box.x }
        }
        assertTrue(moved > box.x)
    }

    /** A link in a box nobody is editing reads as a link: the hand over its text and its icon. */
    @Test
    fun hyperlinksInAPreviewShowThePointer() = withWorkspace { host ->
        val linked = Block(id = "l", runs = listOf(Run("docs docs docs", setOf(Mark.Link("https://example.com"))),
            Run(" and plain text after it")))
        val box = Outline.Text(id = "box", x = 200f, y = 300f, blocks = listOf(linked, Block.of("second line")))
        host.update(workspace(listOf(box)))
        val preview = host.node("text-box-preview")
        val layout = preview.textLayout()
        val link = host.point(preview.charCentre(layout, 1))
        val plain = host.point(preview.charCentre(layout, "docs docs docs and pl".length))
        val second = host.point(preview.charCentre(layout, layout.layoutInput.text.text.indexOf("second") + 2))

        host.mouse(MouseEvent.MOUSE_ENTERED, plain)
        host.mouse(MouseEvent.MOUSE_MOVED, plain)
        host.awaitCursorType(Cursor.DEFAULT_CURSOR, "over plain text")
        host.mouse(MouseEvent.MOUSE_MOVED, link)
        host.awaitCursorType(Cursor.HAND_CURSOR, "over the link")
        host.mouse(MouseEvent.MOUSE_MOVED, second)
        host.awaitCursorType(Cursor.DEFAULT_CURSOR, "off the link again")
        val icon = host.point(host.bounds("link-icon-0").center)
        host.mouse(MouseEvent.MOUSE_MOVED, icon)
        host.awaitCursorType(Cursor.HAND_CURSOR, "over the link icon")
    }

    /** While editing, a plain click places the caret, so the hand waits for Ctrl as Ctrl+click opens. */
    @Test
    fun hyperlinksInTheEditorShowThePointerWithCtrl() = withWorkspace { host ->
        val linked = Block(id = "l", runs = listOf(Run("docs docs docs", setOf(Mark.Link("https://example.com"))),
            Run(" and plain text after it")))
        val box = Outline.Text(id = "box", x = 200f, y = 300f, blocks = listOf(linked))
        host.update(workspace(listOf(box)))
        // A click on the box's plain text starts editing there, as it would for a person.
        val preview = host.node("text-box-preview")
        val clickAt = host.point(preview.charCentre(preview.textLayout(), "docs docs docs and pl".length))
        host.mouse(MouseEvent.MOUSE_ENTERED, clickAt)
        host.mouse(MouseEvent.MOUSE_PRESSED, clickAt)
        host.mouse(MouseEvent.MOUSE_RELEASED, clickAt)
        // The editor sits under an unfocused box's preview; hover only once the preview has gone.
        waitFor("the preview to give way to the editor") { true.takeIf { host.nodeNow("text-box-preview") == null } }
        waitFor("the editor to take the keys") {
            true.takeIf { host.nodeNow(WorkspaceTestTags.BodyEditor)?.config?.getOrNull(SemanticsProperties.Focused) == true }
        }
        val editor = host.node(WorkspaceTestTags.BodyEditor)
        val layout = editor.textLayout()
        val link = host.point(editor.charCentre(layout, 2))
        val plain = host.point(editor.charCentre(layout, "docs docs docs and pl".length))

        host.mouse(MouseEvent.MOUSE_MOVED, plain)
        host.awaitCursorType(Cursor.TEXT_CURSOR, "editing, over plain text")
        host.mouse(MouseEvent.MOUSE_MOVED, link)
        host.assertCursorStays(Cursor.TEXT_CURSOR, "editing, over the link without Ctrl")
        host.mouse(MouseEvent.MOUSE_MOVED, link, modifiers = InputEvent.CTRL_DOWN_MASK)
        host.awaitCursorType(Cursor.HAND_CURSOR, "editing, over the link with Ctrl")
        host.mouse(MouseEvent.MOUSE_MOVED, plain, modifiers = InputEvent.CTRL_DOWN_MASK)
        host.awaitCursorType(Cursor.TEXT_CURSOR, "editing, over plain text with Ctrl")
        host.mouse(MouseEvent.MOUSE_MOVED, link)
        host.assertCursorStays(Cursor.TEXT_CURSOR, "Ctrl released")
        // Ctrl pressed and released while the mouse rests on the link: only the keys say so.
        host.key(KeyEvent.KEY_PRESSED, InputEvent.CTRL_DOWN_MASK)
        host.awaitCursorType(Cursor.HAND_CURSOR, "Ctrl pressed without moving")
        host.key(KeyEvent.KEY_RELEASED, 0)
        host.awaitCursorType(Cursor.TEXT_CURSOR, "Ctrl released without moving")
    }

    private fun WorkspaceState.objectX(id: String): Float =
        selectedPage!!.document.outlines.single { it.id == id }.x

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
        val frame = JFrame("Canvas cursor workspace test")
        val panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
        val render: Component get() = panel.renderComponent()

        init {
            frame.contentPane.add(panel)
            frame.setSize(1400, 900)
            frame.isVisible = true
            panel.setContent { WorkspaceScreen(state.value, { state.value = it(state.value) }) }
        }

        fun update(value: WorkspaceState) = SwingUtilities.invokeAndWait { state.value = value }

        fun nodeNow(tag: String): SemanticsNode? = panel.semanticsOwners.firstNotNullOfOrNull { owner ->
            owner.getAllSemanticsNodes(mergingEnabled = false).firstOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == tag && it.boundsInRoot.width > 0f
            }
        }

        fun node(tag: String): SemanticsNode = waitFor("layout of $tag") { nodeNow(tag) }

        fun bounds(tag: String): Rect = waitFor("bounds of $tag") {
            nodeNow(tag)?.boundsInRoot?.takeIf { it.width > 0f && it.height > 0f }
        }

        /** A point in Compose root pixels, as an AWT point on the render component. */
        fun point(at: Offset): Point {
            val scale = render.graphicsConfiguration.defaultTransform.scaleX.toFloat()
            return Point((at.x / scale).toInt(), (at.y / scale).toInt())
        }

        fun mouse(id: Int, at: Point, button: Int = MouseEvent.BUTTON1, modifiers: Int = 0) {
            val pressing = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED
            val held = if (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_DRAGGED)
                if (button == MouseEvent.BUTTON2) MouseEvent.BUTTON2_DOWN_MASK else MouseEvent.BUTTON1_DOWN_MASK else 0
            SwingUtilities.invokeAndWait {
                render.dispatchEvent(MouseEvent(render, id, System.currentTimeMillis(), held or modifiers, at.x, at.y,
                    if (pressing) 1 else 0, false, if (pressing) button else MouseEvent.NOBUTTON))
            }
        }

        fun awaitCursor(cursor: Cursor, context: String) = try {
            waitFor("cursor ${cursor.name} $context") { render.cursor.takeIf { it === cursor } }
        } catch (failure: IllegalStateException) {
            error("${failure.message}; host=${frame.cursor.name}, render=${render.cursor.name}")
        }

        /** A Ctrl key event, to whichever of the panel's components Compose listens to keys on. */
        fun key(id: Int, modifiers: Int) = SwingUtilities.invokeAndWait {
            fun listening(component: Component): Component? =
                component.takeIf { it.keyListeners.isNotEmpty() }
                    ?: (component as? Container)?.components?.firstNotNullOfOrNull(::listening)
            val target = listening(panel) ?: render
            target.dispatchEvent(KeyEvent(target, id, System.currentTimeMillis(), modifiers, KeyEvent.VK_CONTROL,
                KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_LEFT))
        }

        fun assertCursorStays(type: Int, context: String) {
            Thread.sleep(250)
            SwingUtilities.invokeAndWait { assertEquals(type, render.cursor.type, "$context: ${render.cursor.name}") }
        }

        fun awaitCursorType(type: Int, context: String) = try {
            waitFor("cursor type $type $context") { render.cursor.takeIf { it.type == type } }
        } catch (failure: IllegalStateException) {
            error("${failure.message}; render=${render.cursor.name}")
        }

        /** The render component's cursor, then every Swing component's, once Compose has settled. */
        fun settledCursors(): List<Cursor> {
            Thread.sleep(300)
            var cursors = emptyList<Cursor>()
            SwingUtilities.invokeAndWait {
                fun collect(component: Component): List<Cursor> = listOf(component.cursor) +
                    ((component as? Container)?.components.orEmpty().flatMap(::collect))
                cursors = listOf(render.cursor) + collect(frame)
            }
            return cursors
        }
    }

    companion object {
        private fun SemanticsNode.textLayout(): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            return results.single()
        }

        /** The middle of character [index] of this text node, in root pixels. */
        private fun SemanticsNode.charCentre(layout: TextLayoutResult, index: Int): Offset =
            layout.getBoundingBox(index).center + boundsInRoot.topLeft

        /** The Compose surface that receives mouse input, not the zero-size focus helper beside it. */
        private fun ComposePanel.renderComponent(): Component = components.first { it.width > 0 && it.height > 0 }

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
    }
}
