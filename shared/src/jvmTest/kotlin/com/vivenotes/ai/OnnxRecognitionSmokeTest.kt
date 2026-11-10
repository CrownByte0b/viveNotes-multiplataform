package com.vivenotes.ai

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.toEntity
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Files
import java.util.Collections

/**
 * The real graphs through ONNX Runtime's JVM package: Android's `OnnxRecognitionSmokeTest`, on the
 * desktop. The bundled OCR always runs. The formula models run when the development profile has
 * them installed (`devdb/ai_models`, Gradle's `vivenotes.test.formulaModels`) — downloaded once
 * from Settings → Models — and the tests that need one skip otherwise, as Android's do without
 * their debug bundle.
 */
class OnnxRecognitionSmokeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun bundledOcrGraphReadsALine() = runBlocking {
        val models = settledStore()
        assertEquals(AiModelInstallState.Installed, models.state.value.handwritingText)
        val image = textImage(320, 64, 44f, listOf(Triple("hello", 16f, 48f)))

        val engine = OnnxInkRecognitionEngine(models)
        try {
            val result = withTimeout(30_000) { engine.recognizeText(image) }
            assertTrue("OCR read \"${result.text}\"", result.text.contains("hello", ignoreCase = true))
            assertTrue(result.confidence > 0.5f)
        } finally {
            engine.close()
            models.close()
        }
    }

    @Test
    fun bundledSmallRecognizerPreservesLatinDiacritics() = runBlocking {
        val models = settledStore()
        val engine = OnnxInkRecognitionEngine(models)
        val image = textImage(520, 90, 64f, listOf(Triple("Café mañana", 12f, 70f)))
        try {
            val result = withTimeout(30_000) { engine.recognizeText(image) }
            assertEquals("Café mañana", result.text.trim())
            assertTrue(result.confidence > 0.5f)
        } finally {
            image.close()
            engine.close()
            models.close()
        }
    }

    @Test
    fun bundledSmallPipelineReadsBlueAndRedInk() = runBlocking {
        val models = settledStore()
        val engine = OnnxInkRecognitionEngine(models)
        val lines = listOf("Blue ink", "Red ink")
        val pixels = textImage(600, 240, 64f, listOf(
            Triple(lines[0], 24f, 80f), Triple(lines[1], 24f, 200f),
        )).use { it.argbPixels() }
        val image = imageOf(600, 240) { x, y ->
            val level = red(pixels[y * 600 + x])
            if (y < 120) 0xFF0000FF.toInt() or (level shl 16) or (level shl 8)
            else 0xFFFF0000.toInt() or (level shl 8) or level
        }
        try {
            val result = withTimeout(60_000) { engine.recognizeImageText(image) }
            assertEquals(lines, result.lines.map { it.text })
        } finally {
            image.close()
            engine.close()
            models.close()
        }
    }

    /**
     * The whole picture pipeline: detector, DB post-processing, crop, recognizer. Three lines
     * rather than one, because a recognizer alone reads a multi-line picture as a single blank
     * strip, so a one-line fixture would pass with the detector doing nothing at all.
     */
    @Test
    fun bundledDetectorReadsEveryLineOfAPicture() = runBlocking {
        val models = settledStore()
        val lines = listOf("Release notes", "Search covers pictures", "Known issue")
        val image = picture(lines)

        val engine = OnnxInkRecognitionEngine(models)
        try {
            val result = withTimeout(60_000) { engine.recognizeImageText(image) }
            assertEquals(
                "expected one reading per drawn line, got ${result.lines.map { it.text }}",
                lines.size,
                result.lines.size,
            )
            // Reading order, not detection order: the lines come back top to bottom.
            assertTrue("readings do not resemble what was drawn: ${result.text}",
                result.lines.first().text.contains("Release", ignoreCase = true))
            assertTrue(result.lines.last().text.contains("issue", ignoreCase = true))
        } finally {
            engine.close()
            models.close()
        }
    }

    @Test
    fun aWordsBoxSitsOnThatWord() = runBlocking {
        val models = settledStore()
        val text = "Alpha Bravo Charlie"
        val typeface = org.jetbrains.skia.FontMgr.default.matchFamilyStyle("sans-serif", org.jetbrains.skia.FontStyle.NORMAL)!!
        val font = org.jetbrains.skia.Font(typeface, 48f)
        val image = textImage(760, 140, 48f, listOf(Triple(text, 40f, 88f)))
        val engine = OnnxInkRecognitionEngine(models)
        try {
            val result = withTimeout(60_000) { engine.recognizeImageText(image) }
            val line = result.lines.single()
            assertEquals(text, line.text)
            val layout = com.vivenotes.model.ocr.ImageTextLayout.decode(result.layout(image.width, image.height)!!)!!.single()
            val word = text.indexOf("Bravo")
            val quad = com.vivenotes.model.ocr.PictureHighlights.forSpans(line.text, layout,
                listOf(com.vivenotes.model.search.MatchSpan(word, word + 5)))!!.quads
            assertEquals(40f + font.measureTextWidth(text.substring(0, word)), quad[0] * image.width, 16f)
            assertEquals(40f + font.measureTextWidth(text.substring(0, word + 5)), quad[2] * image.width, 16f)
        } finally { engine.close(); models.close(); image.close(); font.close(); typeface.close() }
    }

    /** Two lanes reading one picture four times at once agree with one lane reading it alone. */
    @Test
    fun parallelLanesReadWhatOneLaneReads() = runBlocking {
        val models = settledStore()
        val image = picture(listOf("Release notes", "Search covers pictures", "Known issue"))

        val serial = OnnxInkRecognitionEngine(models, lanes = 1)
        val parallel = OnnxInkRecognitionEngine(models, lanes = 2)
        try {
            val expected = withTimeout(60_000) { serial.recognizeImageText(image) }.lines.map { it.text }
            assertEquals(3, expected.size)
            val readings = withTimeout(120_000) {
                coroutineScope { List(4) { async { parallel.recognizeImageText(image) } }.awaitAll() }
            }
            readings.forEach { reading -> assertEquals(expected, reading.lines.map { it.text }) }
        } finally {
            serial.close()
            parallel.close()
            models.close()
        }
    }

    /** A blank page has no lines to read, rather than texture read as letters. */
    @Test
    fun aBlankPictureReadsAsNothing() = runBlocking {
        val models = settledStore()
        val engine = OnnxInkRecognitionEngine(models)
        try {
            val result = withTimeout(30_000) { engine.recognizeImageText(imageOf(640, 480) { _, _ -> WHITE }) }
            assertEquals(ImageTextResult.Empty, result)
        } finally {
            engine.close()
            models.close()
        }
    }

    /**
     * The hold: OCR leaves a held formula model open, and closes it once the hold ends — whichever
     * formula model is selected, since the rule is the same for both.
     */
    @Test
    fun heldFormulaModelOutlivesTextRecognitionUntilReleased() = runBlocking {
        val models = seededStore(FormulaEngine.UniMerNetTiny) ?: seededStore(FormulaEngine.FormulaNetS)
        assumeTrue("no formula model is installed in the development profile", models != null)
        models!!
        assertTrue(models.state.value.formulaReady)
        val line = textImage(320, 64, 44f, listOf(Triple("hello", 16f, 48f)))

        val engine = OnnxInkRecognitionEngine(models)
        val hold = launch { engine.keepFormulaModelLoaded() }
        try {
            withTimeout(60_000) { while (!engine.formulaModelOpen) delay(50) }
            withTimeout(30_000) { engine.recognizeText(line) }
            assertTrue("OCR closed the formula model while it was held", engine.formulaModelOpen)

            hold.cancelAndJoin()
            withTimeout(30_000) { engine.recognizeText(line) }
            assertFalse("OCR after the hold left the formula model open", engine.formulaModelOpen)
        } finally {
            hold.cancel()
            engine.close()
            models.close()
        }
    }

    @Test
    fun formulaNetReadsAPrintedFormula() = runBlocking {
        val models = seededStore(FormulaEngine.FormulaNetS)
        assumeTrue("PP-FormulaNet-S is not installed in the development profile", models != null)
        assertFormulaRead(models!!, FormulaEngine.FormulaNetS)
    }

    /**
     * UniMERNet-T on the JVM: the Kotlin half of its export — the greedy loop, the cache carried
     * from one step's outputs into the next step's inputs, and the empty first-step cache.
     */
    @Test
    fun uniMerNetReadsAPrintedFormula() = runBlocking {
        val models = seededStore(FormulaEngine.UniMerNetTiny)
        assumeTrue("UniMERNet-T is not installed in the development profile", models != null)
        assertFormulaRead(models!!, FormulaEngine.UniMerNetTiny)
    }

    @Test
    fun uniMerNetSmallReadsAPrintedFormula() = runBlocking {
        val models = seededStore(FormulaEngine.UniMerNetSmall)
        assumeTrue("UniMERNet Small is not installed in the development profile", models != null)
        assertFormulaRead(models!!, FormulaEngine.UniMerNetSmall)
    }

    @Test
    fun switchingBetweenTinyAndSmallReloadsTheCorrectGraphsWhileHeld() = runBlocking {
        val models = seededStore(FormulaEngine.UniMerNetTiny, FormulaEngine.UniMerNetSmall)
        assumeTrue("Tiny and Small are not both installed in the development profile", models != null)
        models!!
        models.selectFormulaEngine(FormulaEngine.UniMerNetTiny)
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val engine = OnnxInkRecognitionEngine(models, log = DebugLog(enabled = true) { lines += it })
        val bytes = javaClass.getResourceAsStream("/ai/unimernet-small-handwriting.png")!!.use { it.readBytes() }
        val image = Image.makeFromEncoded(bytes)
        val hold = launch { engine.keepFormulaModelLoaded() }
        try {
            withTimeout(60_000) { while (!engine.formulaModelOpen) delay(50) }
            val tiny = withTimeout(60_000) { engine.recognizeFormula(image).latex }
            models.selectFormulaEngine(FormulaEngine.UniMerNetSmall)
            val small = withTimeout(60_000) { engine.recognizeFormula(image).latex }
            assertTrue("Small did not read the handwritten exponent: $small",
                small.replace(Regex("\\s+"), "").contains("e^{-7n}"))
            assertTrue(lines.toString(), lines.any { it.contains("opened UniMerNetSmallEncoder session") })
            assertTrue(lines.toString(), lines.any { it.contains("closed UniMerNetEncoder session") })

            models.selectFormulaEngine(FormulaEngine.UniMerNetTiny)
            assertEquals(tiny, withTimeout(60_000) { engine.recognizeFormula(image).latex })
            assertTrue(lines.toString(), lines.any { it.contains("closed UniMerNetSmallEncoder session") })
        } finally {
            hold.cancelAndJoin()
            image.close()
            engine.close()
            models.close()
        }
    }

    /**
     * A fraction bar drawn with the Line tool is a shape, not ink, so the Math toolkit hands it to
     * [renderDrawInk] beside the strokes. Without it these models read a handwritten 1 over 2 as
     * anything but a fraction; with it inked at their stem width they read `\frac{1}{2}`.
     */
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun formulaModelsReadALineShapeBarAsAFraction() = runBlocking {
        registerSkikoComposeImplementation()
        val installed = listOf(FormulaEngine.UniMerNetSmall, FormulaEngine.FormulaNetS)
            .mapNotNull { engine -> seededStore(engine)?.let { engine to it } }
        assumeTrue("no formula model is installed in the development profile", installed.isNotEmpty())
        val ink = InkPageReader.read("page", listOf(
            inkStroke("one", 42f to 110f, 50f to 102f, 50f to 140f),
            inkStroke("two", 34f to 160f, 38f to 153f, 46f to 150f, 55f to 152f, 58f to 159f, 54f to 168f,
                44f to 178f, 33f to 190f, 61f to 190f),
        ), emptyList(), emptyList()).desktopGeometry().projections
        var next = 0
        val bar = Outline.Shape(id = "bar", kind = ShapeKind.Line,
            segments = seedSegments(ShapeKind.Line, 25f, 146f, 70f, 146f) { "bar-${next++}" }).withRecomputedBounds()
        for ((engine, models) in installed) {
            val recognition = OnnxInkRecognitionEngine(models, formulaEngine = { engine })
            try {
                val withBar = renderDrawInk(ink, engine.stemPx, listOf(bar)).use {
                    withTimeout(60_000) { recognition.recognizeFormula(it).latex }
                }
                val withoutBar = renderDrawInk(ink, engine.stemPx).use {
                    withTimeout(60_000) { recognition.recognizeFormula(it).latex }
                }
                assertTrue("$engine read \"$withBar\" with the bar",
                    withBar.replace(Regex("\\s+"), "").contains("\\frac{1}{2}"))
                assertFalse("$engine read a fraction without its bar: \"$withoutBar\"", withoutBar.contains("\\frac"))
            } finally {
                recognition.close()
                models.close()
            }
        }
    }

    private fun inkStroke(id: String, vararg points: Pair<Float, Float>): InkStrokeEntity =
        ViveInkTool(sizeDp = 3f, colorArgb = 0xff000000.toInt()).let { tool ->
            val inputs = MutableStrokeInputBatch().apply {
                points.forEachIndexed { index, (x, y) -> add(InputToolType.MOUSE, x, y, index * 12L) }
            }
            tool.complete(Stroke(tool.brush, inputs), id, "page", 0, 1L, null).row.toEntity()
        }

    private suspend fun assertFormulaRead(models: AiModelStore, formula: FormulaEngine) {
        val image = textImage(900, 180, 112f, listOf(Triple("x² + y² = z²", 24f, 130f)))
        val engine = OnnxInkRecognitionEngine(models, formulaEngine = { formula })
        try {
            val latex = withTimeout(60_000) { engine.recognizeFormula(image).latex }
            assertTrue("$formula returned no LaTeX", latex.isNotBlank())
            assertTrue("$formula read \"$latex\"", latex.contains("x") && latex.contains("="))
        } finally {
            engine.close()
            models.close()
        }
    }

    private suspend fun settledStore(root: File = temporary.newFolder(AiModelStore.DIRECTORY)): AiModelStore {
        val store = AiModelStore(root)
        withTimeout(60_000) {
            store.state.first { state ->
                state.handwritingText !is AiModelInstallState.Verifying &&
                    FormulaEngine.entries.none { state.formula(it) is AiModelInstallState.Verifying }
            }
        }
        return store
    }

    /**
     * A store over a fresh models directory holding [engine]'s package, linked from the development
     * profile's install — verified in full, since the copy has no marker. Null when that profile
     * does not have the whole package.
     */
    private suspend fun seededStore(vararg engines: FormulaEngine): AiModelStore? {
        val seedRoot = System.getProperty("vivenotes.test.formulaModels")?.let(::File) ?: return null
        if (engines.any { engine ->
                AiModelCatalog.formulaArtifacts(engine).any {
                    File(seedRoot, "${AiModelStore.slot(engine)}/${it.fileName}").length() != it.bytes
                }
            }) return null
        val root = temporary.newFolder()
        engines.forEach { engine ->
            val seed = File(seedRoot, AiModelStore.slot(engine))
            val slot = File(root, AiModelStore.slot(engine)).apply { mkdirs() }
            AiModelCatalog.formulaArtifacts(engine).forEach { artifact ->
                val source = File(seed, artifact.fileName).toPath()
                val target = File(slot, artifact.fileName).toPath()
                runCatching { Files.createLink(target, source) }.getOrElse { Files.copy(source, target) }
            }
        }
        val store = settledStore(root)
        return store.takeIf { state -> engines.all { state.state.value.formula(it) == AiModelInstallState.Installed } }
            ?: error("${engines.toList()} in the development profile failed verification")
    }

    private fun picture(lines: List<String>) =
        textImage(760, 340, 48f, lines.mapIndexed { index, line -> Triple(line, 32f, 80f + index * 96f) })
}
