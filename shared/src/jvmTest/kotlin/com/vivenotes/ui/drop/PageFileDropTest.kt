package com.vivenotes.ui.drop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import com.vivenotes.model.Outline
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.workspace.PageContent
import com.vivenotes.workspace.WorkspaceState
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetContext
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import java.nio.file.Files
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Files dropped on the page in a real window: AWT drop events go to the drop target Compose puts
 * on its root container, as a file manager's drag would, and the workspace answers.
 *
 * The events stand in for the platform's only where it would carry the dragged data and hear the
 * answer; everything from Compose's drop target in — hit testing, the highlight, reading the files,
 * the drop point and the page edit — is the app's real path.
 */
@OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class PageFileDropTest {

    @Test
    fun picturesDroppedOnThePageLandWhereTheyWereDroppedWithTheHighlightShownMeanwhile() = withHost { host ->
        val sunset = host.file("sunset.png")
        val photo = host.file("Photo Two.JPG")
        val canvas = host.bounds(WorkspaceTestTags.PageCanvas)
        val at = host.onPointGrid(Offset(canvas.left + 300f, canvas.top + 260f))
        val files = fileList(sunset, photo)

        val entered = host.drag(at, files)
        assertFalse(entered.rejected, "a drag of files is welcome")
        host.awaitHighlight(true)

        val dropped = host.drop(at, files)
        assertEquals(true, dropped.completed)
        host.awaitHighlight(false)
        val images = host.waitFor("both pictures") {
            host.state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Image>().takeIf { it.size == 2 }
        }

        // The canvas starts unscrolled at 100%: a page dp is the page density's pixels.
        val scale = host.scale * Interface.displayScale
        assertClose((at.x - canvas.left) / scale, images[0].x, "x under the pointer")
        assertClose((at.y - canvas.top) / scale, images[0].y, "y under the pointer")
        assertEquals(listOf(host.library.stored(sunset), host.library.stored(photo)), images.map { it.attachmentId })
        assertTrue(images[1].y > images[0].y + images[0].height, "the second below the first")
    }

    @Test
    fun aDropOfFilesThatAreNotPicturesIsRefusedAndChangesNothing() = withHost { host ->
        val before = host.state.value.selectedPage!!.document
        val canvas = host.bounds(WorkspaceTestTags.PageCanvas)
        val at = Offset(canvas.center.x, canvas.center.y)
        val files = fileList(host.file("notes.txt"), host.file("report.pdf"))

        assertFalse(host.drag(at, files).rejected, "while dragging, only the kind of data is known")
        host.awaitHighlight(true)
        assertEquals(false, host.drop(at, files).completed)
        host.awaitHighlight(false)
        Thread.sleep(200)
        assertEquals(before, host.state.value.selectedPage!!.document)
        assertTrue(host.library.imported.isEmpty())
    }

    @Test
    fun aDragWithoutFilesIsTurnedAway() = withHost { host ->
        val canvas = host.bounds(WorkspaceTestTags.PageCanvas)
        assertTrue(host.drag(canvas.center, StringSelection("not a file")).rejected)
        Thread.sleep(200)
        host.assertNoHighlight()
    }

    @Test
    fun aPageThatMayNotChangeTakesNoDrop() = withHost { host ->
        host.update { it.updatePage(it.selectedPageId) { page -> page.copy(content = PageContent.Unreadable) } }
        val canvas = host.bounds(WorkspaceTestTags.PageCanvas)
        assertTrue(host.drag(canvas.center, fileList(host.file("sunset.png"))).rejected)
        Thread.sleep(200)
        host.assertNoHighlight()
    }

    @Test
    fun theHighlightGoesWhenTheDragLeavesTheWindow() = withHost { host ->
        val canvas = host.bounds(WorkspaceTestTags.PageCanvas)
        host.drag(canvas.center, fileList(host.file("sunset.png")))
        host.awaitHighlight(true)
        host.exit()
        host.awaitHighlight(false)
    }

    private companion object {
        /** The app's own default display scale, so page dp and pixels differ as they do for users. */
        val Interface = InterfaceSettings(displayScale = 0.75f)
    }

    private fun assertClose(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= 1f, "$what: expected $expected, was $actual")

    private fun withHost(test: (Host) -> Unit) {
        if (GraphicsEnvironment.isHeadless()) return
        lateinit var host: Host
        SwingUtilities.invokeAndWait { host = Host() }
        try { test(host) } finally {
            SwingUtilities.invokeAndWait { host.frame.dispose() }
            host.folder.deleteRecursively()
        }
    }

    private class Host {
        val folder: File = Files.createTempDirectory("page-file-drop").toFile()
        val library = Pictures()
        val state = mutableStateOf(WorkspaceState.demo())
        val frame = JFrame("Page file drop test")
        val panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())

        init {
            frame.contentPane.add(panel)
            frame.setSize(1400, 900)
            frame.isVisible = true
            panel.setContent {
                WorkspaceScreen(state.value, { state.value = it(state.value) }, pictures = library, interfaceSettings = Interface)
            }
        }

        /** The screen's scale, by which AWT's points become Compose's root pixels. */
        val scale: Float get() = target.component.graphicsConfiguration.defaultTransform.scaleX.toFloat()

        /** The drop target Compose puts on its root container: AWT's own class, not Swing's. */
        val target: DropTarget by lazy {
            waitFor("Compose's drop target") {
                fun find(component: Component): DropTarget? =
                    component.dropTarget?.takeIf { it.javaClass == DropTarget::class.java }
                        ?: (component as? Container)?.components?.firstNotNullOfOrNull(::find)
                find(frame)
            }
        }

        fun file(name: String) = File(folder, name).apply { writeBytes(name.encodeToByteArray()) }

        fun update(change: (WorkspaceState) -> WorkspaceState) = SwingUtilities.invokeAndWait { state.value = change(state.value) }

        fun bounds(tag: String): Rect = waitFor("bounds of $tag") {
            node(tag)?.boundsInRoot?.takeIf { it.width > 0f && it.height > 0f }
        }

        /** Enters the window at [at] (root pixels) and moves there, as a drag arriving from outside does. */
        fun drag(at: Offset, data: Transferable): DragEvent {
            val event = DragEvent(target.dropTargetContext, point(at), data)
            SwingUtilities.invokeAndWait {
                target.dragEnter(event)
                if (!event.rejected) target.dragOver(event)
            }
            return event
        }

        fun drop(at: Offset, data: Transferable): DropEvent =
            DropEvent(target.dropTargetContext, point(at), data).also { SwingUtilities.invokeAndWait { target.drop(it) } }

        fun exit() = SwingUtilities.invokeAndWait { target.dragExit(DropTargetEvent(target.dropTargetContext)) }

        fun awaitHighlight(shown: Boolean) = waitFor(if (shown) "the drop highlight" else "the highlight to go") {
            (node(FileDropTags.Highlight) != null).takeIf { it == shown }
        }

        fun assertNoHighlight() {
            var found: SemanticsNode? = null
            SwingUtilities.invokeAndWait { found = node(FileDropTags.Highlight) }
            assertNull(found, "no drop highlight")
        }

        private fun point(at: Offset) = Point((at.x / scale).toInt(), (at.y / scale).toInt())

        /**
         * [at] moved onto a whole AWT point, as every drop location is: at 175% one point is 1.75
         * pixels, and the drop can only be as exact as the point the platform reports.
         */
        fun onPointGrid(at: Offset): Offset = point(at).let { Offset(it.x * scale, it.y * scale) }

        private fun node(tag: String): SemanticsNode? = panel.semanticsOwners.firstNotNullOfOrNull { owner ->
            owner.getAllSemanticsNodes(mergingEnabled = false).firstOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == tag
            }
        }

        /** Polls [value] on the Swing event thread. */
        fun <T : Any> waitFor(what: String, value: () -> T?): T {
            repeat(250) {
                var found: T? = null
                SwingUtilities.invokeAndWait { found = value() }
                found?.let { return it }
                Thread.sleep(20)
            }
            error("timed out waiting for $what")
        }
    }

    /** A drag the platform would describe: it carries [data] and records the answer it gets. */
    private class DragEvent(context: DropTargetContext, at: Point, private val data: Transferable) :
        DropTargetDragEvent(context, at, DnDConstants.ACTION_COPY, DnDConstants.ACTION_COPY_OR_MOVE) {
        var rejected = false
        override fun getTransferable(): Transferable = data
        override fun acceptDrag(dragOperation: Int) { rejected = false }
        override fun rejectDrag() { rejected = true }
    }

    private class DropEvent(context: DropTargetContext, at: Point, private val data: Transferable) :
        DropTargetDropEvent(context, at, DnDConstants.ACTION_COPY, DnDConstants.ACTION_COPY_OR_MOVE) {
        var completed: Boolean? = null
        override fun getTransferable(): Transferable = data
        override fun acceptDrop(dropAction: Int) = Unit
        override fun rejectDrop() { completed = false }
        override fun dropComplete(success: Boolean) { completed = success }
    }

    private fun fileList(vararg files: File): Transferable = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.javaFileListFlavor) files.toList() else throw UnsupportedFlavorException(flavor)
    }

    /** Stores each file's bytes under an id made from them, so the test can tell which picture is which. */
    private class Pictures : PictureLibrary {
        val imported = mutableListOf<ByteArray>()
        fun stored(file: File): String = idFor(file.readBytes())
        private fun idFor(bytes: ByteArray) = bytes.contentHashCode().toUInt().toString(16).padStart(64, '0')
        override suspend fun choose(): ImportedPicture? = null
        override suspend fun import(bytes: ByteArray): ImportedPicture {
            imported += bytes
            return ImportedPicture(idFor(bytes), 800, 400)
        }
        override suspend fun bytes(attachmentId: String): ByteArray? = null
    }
}
