package com.vivenotes.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import com.vivenotes.data.TextClipboard
import com.vivenotes.data.LocalTextClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.vivenotes.data.CanvasClipboardSource
import com.vivenotes.data.ClipboardContent
import com.vivenotes.data.ClipboardHtml
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.model.Outline
import com.vivenotes.model.findVideoLinks
import com.vivenotes.ui.canvas.LocalClipboardHtml
import com.vivenotes.workspace.CANVAS_PASTE_MARGIN
import com.vivenotes.workspace.TABLE_GUTTER
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState
import java.io.File
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CanvasPasteSpacingTest {
    @Test
    fun shortTextAroundATableLeavesAMarginIncludingTheMinimumTextBoxHeight() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val html = System.getenv("VIVE_PASTE_SPACING_FIXTURE")?.let { File(it).readText() }
                ?: "<p>Before</p><table><tr><th>A</th><th>B</th></tr>" +
                    "<tr><td>One</td><td>Two</td></tr><tr><td>Three</td><td>Four</td></tr>" +
                    "<tr><td>Five</td><td>Six</td></tr></table><p>After</p>"
            verifyGaps(html)
        }

    @Test
    fun largeHeadingsWrappedCellsPicturesAndTextLeaveAMarginAtScaledZoom() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            verifyGaps("<h1>" + "A large heading wraps across the canvas. ".repeat(5) + "</h1>" +
                "<table><tr><th>Heading</th><th>Value</th></tr><tr><td>" + "Wrapped cell text. ".repeat(20) +
                "</td><td>Detail</td></tr></table><p>Before image</p>" +
                "<img src='https://example.org/picture.png'><p>After image</p>",
                fontSize = 28, fontScale = 1.4f, zoom = .75f)
        }

    @Test
    fun inlineEquationPreviewsAreIncludedInThePasteHeight() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            verifyGaps("<p>Formula: &#36;&#36;\\frac{\\frac{1}{2}}{\\frac{3}{4}}&#36;&#36;</p>" +
                "<table><tr><td>A</td></tr></table><p>After</p>")
        }

    @Test
    fun delayedVideoPreviewsAreMeasuredBeforeFollowingObjectsArePositioned() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val png = ByteArrayOutputStream().also { out ->
                ImageIO.write(BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "png", out)
            }.toByteArray()
            val thumbnails = object : VideoThumbnailSource {
                override suspend fun load(videoId: String): ByteArray { delay(100); return png }
            }
            verifyGaps("<p>https://youtu.be/dQw4w9WgXcQ</p><table><tr><td>Cell</td></tr></table><p>After</p>",
                thumbnails = thumbnails)
        }

    private fun ComposeUiTest.verifyGaps(html: String, fontSize: Int = 15, fontScale: Float = 1f, zoom: Float = 1f,
        thumbnails: VideoThumbnailSource? = null) {
        val initial = WorkspaceState.demo().clearCanvasFocus().chooseFontSize(fontSize)
        val initialCount = initial.selectedPage!!.document.outlines.size
        var observed = initial
        val source = Source(html)
        val clipboard = object : TextClipboard {
            override fun getText(): AnnotatedString? = null
            override fun setText(annotatedString: AnnotatedString) {}
        }
        val pictures = object : PictureLibrary {
            override suspend fun choose(): ImportedPicture? = null
            override suspend fun import(bytes: ByteArray) = ImportedPicture("image", 400, 200)
            override suspend fun bytes(attachmentId: String): ByteArray? = null
        }
        setContent {
            var state by remember { mutableStateOf(initial) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalTextClipboard provides clipboard, LocalClipboardHtml provides source,
                LocalDensity provides Density(density.density, fontScale)) {
                WorkspaceScreen(state, { state = it(state); observed = state }, pictures = pictures, thumbnails = thumbnails,
                    viewSettings = ViewSettings(zoom = zoom))
            }
        }
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { moveTo(Offset(60f, 200f)) }
        ctrl(Key.V)
        waitUntil(timeoutMillis = 10_000) { observed.selectedPage!!.document.outlines.size > initialCount }
        val pasted = observed.selectedPage!!.document.outlines.drop(initialCount)
        assertTrue(pasted.size > 1)
        if (thumbnails != null) pasted.filterIsInstance<Outline.Text>().flatMap { outline ->
            findVideoLinks(outline.blocks.joinToString("\n") { it.text })
        }.forEach { video ->
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("video-preview-${video.videoId}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        waitForIdle()
        fun bounds(outline: Outline): Rect {
            val node = onNodeWithTag(if (outline is Outline.Text) WorkspaceTestTags.textBox(outline.id)
                else WorkspaceTestTags.primeObject(outline.id)).fetchSemanticsNode()
            val coordinates = node.layoutInfo.coordinates
            return Rect(coordinates.localToRoot(Offset.Zero), coordinates.localToRoot(
                Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
        }
        val first = bounds(pasted.first())
        val scale = first.width / pasted.first().width
        pasted.zipWithNext().forEach { (before, after) ->
            val previous = bounds(before)
            val next = bounds(after)
            val nextTop = next.top - if (after is Outline.Table) TABLE_GUTTER * scale else 0f
            val gap = nextTop - previous.bottom
            assertEquals(CANVAS_PASTE_MARGIN * scale, gap, 1.5f,
                "${before::class.simpleName} → ${after::class.simpleName} must leave the paste margin")
        }
        val document = observed.selectedPage!!.document
        assertEquals(1, observed.structuralUndo.size)
        ctrl(Key.Z)
        runOnIdle { assertEquals(initial.selectedPage!!.document, observed.selectedPage!!.document) }
        ctrl(Key.R)
        runOnIdle { assertEquals(document, observed.selectedPage!!.document) }
    }

    private fun ComposeUiTest.ctrl(key: Key) = onRoot().performKeyInput {
        keyDown(Key.CtrlLeft); keyDown(key); keyUp(key); keyUp(Key.CtrlLeft)
    }

    private class Source(private val html: String) : CanvasClipboardSource, ClipboardHtml {
        override fun read() = html
        override fun hasContent() = true
        override suspend fun readContent() = ClipboardContent(html = html)
        override suspend fun imageBytes(address: String) = byteArrayOf(1)
        override fun markInternalCopy() {}
        override fun ownsInternalCopy() = false
    }
}
