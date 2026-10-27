package com.vivenotes.ai

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import com.vivenotes.ink.*
import com.vivenotes.model.*
import com.vivenotes.model.ink.InkSelectionBounds
import com.vivenotes.model.search.inkUnits
import com.vivenotes.model.search.searchContent
import kotlinx.coroutines.*
import org.jetbrains.skia.Image
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HandwritingRecognitionTest {
    @OptIn(InternalComposeUiApi::class)
    @BeforeTest fun graphics() = registerSkikoComposeImplementation()

    @Test fun cellsUseTheRenderedGridGutterAndTypedTablesDoNotAffectSegmentation() {
        val table = Outline.Table(id = "table", x = 20f, y = 30f, inkOnly = true,
            columns = listOf(100f, 150f), rows = listOf(TableRow(id = "row", minHeight = 80f,
                cells = listOf(TableCell(id = "left"), TableCell(id = "right")))))
        val layout = PageDoc(outlines = listOf(table)).inkPageLayout()
        assertEquals(InkSelectionBounds(36f, 46f, 136f, 126f), layout.cells.first().bounds)
        assertEquals(InkSelectionBounds(136f, 46f, 286f, 126f), layout.cells.last().bounds)
        assertNotEquals(layout.hash, PageDoc(outlines = listOf(table.copy(x = 40f))).inkPageLayout().hash)
        assertEquals(inkPageLayout(emptyList()), PageDoc(outlines = listOf(table.copy(inkOnly = false))).inkPageLayout())
    }

    @Test fun regionsRunSideBySideInReadingOrderAndRetainTheLowConfidenceAlternate() = runBlocking {
        val page = InkPageReader.read("page", listOf(selectionLine("first"), selectionLine("second", y = 300f)),
            emptyList(), emptyList())
        val layout = InkPageLayout("cells", listOf(
            InkCellBounds("first", InkSelectionBounds(0f, 180f, 150f, 220f)),
            InkCellBounds("second", InkSelectionBounds(0f, 280f, 150f, 320f))))
        val calls = AtomicInteger()
        val both = CompletableDeferred<Unit>()
        val engine = object : InkRecognitionEngine {
            override val parallelism = 2
            override suspend fun recognizeText(image: Image): TextRecognitionResult {
                val call = calls.incrementAndGet()
                if (call == 2) both.complete(Unit)
                if (call <= 2) {
                    withTimeout(5_000) { both.await() }
                    return TextRecognitionResult("trernple reason", .72f)
                }
                return TextRecognitionResult("trample reason", .91f)
            }
            override suspend fun recognizeFormula(image: Image) = FormulaRecognitionResult("")
            override suspend fun recognizeImageText(image: Image) = ImageTextResult.Empty
        }
        val regions = HandwritingRecognizer(engine).recognize(page.desktopGeometry().projections, layout)
        assertEquals(listOf("cell-first", "cell-second"), regions.map { it.id })
        assertEquals(listOf(listOf("first"), listOf("second")), regions.map { it.strokeIds })
        assertTrue(regions.all { it.alternateText == "trample reason" })
        assertEquals(4, calls.get())
        assertEquals(2, searchContent(inkUnits("page", "section", regions), "trample").size)
        assertEquals(regions, InkTextRegionsCodec.decode(InkTextRegionsCodec.encode(regions)))
    }

    @Test fun handwritingRasterPreservesPartialEraseGapsAndMovedFragments() {
        val (_, split) = splitSelectionPage()
        val strokes = split.desktopGeometry().projections
        val group = segmentHandwriting(strokes, listOf(InkCellBounds("cell",
            InkSelectionBounds(0f, 180f, 130f, 220f)))).single()
        for (stem in listOf(1f, 1.5f)) renderHandwriting(group, stem).use { image ->
            assertEquals(48, image.height)
            val pixels = image.argbPixels()
            assertTrue(pixels.any { red(it) < 128 })
            // The eraser crossed the middle of this horizontal source stroke.
            assertEquals(0xffffffff.toInt(), pixels[(image.height / 2) * image.width + image.width / 2])
            assertTrue(pixels.all { (it ushr 24) == 255 })
        }
        val moved = split.geometry!!.selectInRect(split, 0f, 180f, 55f, 220f)!!.move(20f, 40f)!!.selection!!.forRecognition()!!
        val movedGroup = segmentHandwriting(moved.desktopGeometry().projections, emptyList()).single()
        assertTrue(movedGroup.bounds.top > group.bounds.top)
        assertEquals(setOf("source"), movedGroup.strokes.map { it.id }.toSet())
    }
}
