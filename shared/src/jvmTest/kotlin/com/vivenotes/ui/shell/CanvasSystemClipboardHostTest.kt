package com.vivenotes.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.HtmlSelection
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import com.vivenotes.data.SystemClipboardContent
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Outline
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.newTable
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.TextSelection
import androidx.compose.ui.text.TextLayoutResult
import com.vivenotes.ui.canvas.LocalClipboardHtml
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.CANVAS_PASTE_MARGIN
import com.vivenotes.workspace.TABLE_GUTTER
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Native keys and the real desktop clipboard through the application's displayed workspace. */
@OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class CanvasSystemClipboardHostTest {
    @Test
    fun nativeCtrlDoubleClickSelectsWordsAcrossCellsAndCopyFormattingUndoUseEveryRange() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val table = newTable(2, 1, x = 60f, y = 300f).let { grid -> grid.copy(rows = grid.rows.map { row ->
            row.copy(cells = row.cells.mapIndexed { index, cell ->
                cell.copy(blocks = listOf(Block.of(if (index == 0) "alpha beta" else "gamma omega")))
            })
        }) }
        val base = WorkspaceState.demo()
        val state = mutableStateOf(base.updatePage(base.selectedPageId) { page ->
            page.copy(document = page.document.copy(outlines = listOf(table)))
        }.selectObject(table.id))
        lateinit var window: JFrame
        lateinit var panel: ComposePanel
        onEdt {
            window = JFrame("Native word selection")
            panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
            window.contentPane.add(panel)
            window.setSize(1400, 900)
            window.isVisible = true
            panel.setContent { WorkspaceScreen(state.value, { state.value = it(state.value) }) }
        }
        try {
            val left = table.cellAt(0, 0)!!.id
            val right = table.cellAt(0, 1)!!.id
            fun node(id: String) = panel.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }.firstOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == com.vivenotes.ui.ribbon.draw.TableTags.cell(id)
            }
            waitFor { node(left) != null && node(right) != null }
            val robot = Robot().apply { autoDelay = 35 }
            fun doubleWord(id: String, offset: Int) {
                val point = onEdt {
                    val field = node(id)!!
                    val layouts = mutableListOf<TextLayoutResult>()
                    field.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    val at = field.layoutInfo.coordinates.localToRoot(layouts.single().getBoundingBox(offset).center)
                    val origin = panel.locationOnScreen
                    java.awt.Point(origin.x + at.x.toInt(), origin.y + at.y.toInt())
                }
                robot.mouseMove(point.x, point.y)
                repeat(2) {
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                    robot.delay(80)
                }
                robot.waitForIdle()
            }
            fun ctrl(key: Int) {
                robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(key)
                robot.keyRelease(key); robot.keyRelease(KeyEvent.VK_CONTROL)
                robot.waitForIdle()
            }
            robot.keyPress(KeyEvent.VK_CONTROL)
            doubleWord(right, 8)
            waitFor { state.value.selectedText == "omega" }
            doubleWord(left, 2)
            waitFor { state.value.selectedText == "alpha\nomega" }
            robot.keyRelease(KeyEvent.VK_CONTROL)
            ctrl(KeyEvent.VK_C)
            waitFor { runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) }
                .getOrNull() == "alpha\nomega" }
            ctrl(KeyEvent.VK_B)
            waitFor { state.value.structuralUndo.size == 1 }
            onEdt {
                val changed = state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
                assertTrue(Mark.Bold in RichTextBuffer(changed.cellAt(0, 0)!!.blocks).select(TextSelection(0, 5)).activeMarks)
                assertTrue(Mark.Bold in RichTextBuffer(changed.cellAt(0, 1)!!.blocks).select(TextSelection(6, 11)).activeMarks)
            }
            ctrl(KeyEvent.VK_Z)
            waitFor { state.value.structuralUndo.isEmpty() }
            onEdt {
                assertEquals(table, state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single())
                assertEquals("alpha\nomega", state.value.selectedText)
            }
        } finally { onEdt { panel.setContent { }; window.dispose() } }
    }

    @Test
    fun nativePasteInADesktopWindowInsertsTextTablesAndStoredPictures() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("canvas-clipboard-host").toFile()
        val database = NotesDatabase.create(root.resolve(NotesDatabase.FILE_NAME))
        val store = AttachmentStore(root.resolve("attachments"), database)
        val pictures = object : PictureLibrary {
            override suspend fun choose(): ImportedPicture? = null
            override suspend fun import(bytes: ByteArray) = store.import(bytes)
            override suspend fun bytes(attachmentId: String) = store.bytes(attachmentId)
        }
        val state = mutableStateOf(WorkspaceState.demo().clearCanvasFocus())
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val source = SystemClipboardContent()
        lateinit var window: JFrame
        lateinit var panel: ComposePanel
        onEdt {
            window = JFrame("Canvas clipboard integration")
            panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
            window.contentPane.add(panel)
            window.setSize(1400, 900)
            window.isVisible = true
            panel.setContent {
                CompositionLocalProvider(LocalClipboardHtml provides source) {
                    WorkspaceScreen(state.value, { state.value = it(state.value) }, pictures = pictures)
                }
            }
        }
        try {
            waitFor {
                panel.semanticsOwners.any { owner -> owner.getAllSemanticsNodes(false).any {
                    it.config.getOrNull(SemanticsProperties.TestTag) == WorkspaceTestTags.PageCanvas
                } }
            }
            val point = onEdt {
                val canvas = panel.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }.first {
                    it.config.getOrNull(SemanticsProperties.TestTag) == WorkspaceTestTags.PageCanvas
                }.boundsInRoot
                val origin = panel.locationOnScreen
                java.awt.Point(origin.x + canvas.left.toInt() + 600, origin.y + canvas.top.toInt() + 450)
            }
            val robot = Robot().apply { autoDelay = 35 }
            robot.mouseMove(point.x, point.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            fun ctrl(key: Int) {
                robot.keyPress(KeyEvent.VK_CONTROL)
                robot.keyPress(key)
                robot.keyRelease(key)
                robot.keyRelease(KeyEvent.VK_CONTROL)
                robot.waitForIdle()
            }
            fun paste(contents: Transferable) {
                onEdt { clipboard.setContents(contents, null) }
                ctrl(KeyEvent.VK_V)
            }
            paste(StringSelection("Native clipboard text"))
            waitFor { state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>().any {
                it.blocks.any { block -> block.text == "Native clipboard text" }
            } }
            paste(HtmlSelection("<p>Before table</p><table><tr><th>Column</th></tr><tr><td>Native cell</td></tr></table><p>After table</p>",
                "Before table\nColumn\nNative cell\nAfter table"))
            waitFor { state.value.selectedPage!!.document.outlines.any { it is Outline.Table } }
            waitFor {
                val tags = panel.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }
                    .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.toSet()
                state.value.selectedPage!!.document.outlines.takeLast(3).all {
                    (if (it is Outline.Text) WorkspaceTestTags.textBox(it.id)
                    else WorkspaceTestTags.primeObject(it.id)) in tags
                }
            }
            onEdt {
                assertEquals("Native cell", state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single().cellAt(1, 0)!!.plainText)
                val pasted = state.value.selectedPage!!.document.outlines.takeLast(3)
                fun bounds(outline: Outline): Rect {
                    val tag = if (outline is Outline.Text) WorkspaceTestTags.textBox(outline.id)
                        else WorkspaceTestTags.primeObject(outline.id)
                    val node = panel.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }.first {
                        it.config.getOrNull(SemanticsProperties.TestTag) == tag
                    }
                    val coordinates = node.layoutInfo.coordinates
                    return Rect(coordinates.localToRoot(Offset.Zero), coordinates.localToRoot(
                        Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
                }
                pasted.zipWithNext().forEach { (before, after) ->
                    val nextTop = bounds(after).top - if (after is Outline.Table) TABLE_GUTTER else 0f
                    assertEquals(CANVAS_PASTE_MARGIN, nextTop - bounds(before).bottom, 1f)
                }
                state.value = state.value.selectObject(pasted.filterIsInstance<Outline.Table>().single().id)
            }
            ctrl(KeyEvent.VK_C)
            waitFor { source.ownsInternalCopy() }
            ctrl(KeyEvent.VK_V)
            waitFor { state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().size == 2 }
            paste(object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor?) = flavor == DataFlavor.imageFlavor
                override fun getTransferData(flavor: DataFlavor?): Any = if (flavor == DataFlavor.imageFlavor)
                    BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB) else throw UnsupportedFlavorException(flavor)
            })
            waitFor { state.value.selectedPage!!.document.outlines.any { it is Outline.Image } }
            val image = onEdt { state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Image>().single() }
            runBlocking { assertNotNull(store.bytes(image.attachmentId)) }
            onEdt {
                assertEquals(320f, image.width)
                assertEquals(160f, image.height)
                assertTrue(state.value.structuralUndo.size >= 3)
            }
        } finally {
            onEdt { panel.setContent { }; window.dispose() }
            database.close()
            root.deleteRecursively()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        repeat(250) {
            if (onEdt(condition)) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for native clipboard paste")
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
