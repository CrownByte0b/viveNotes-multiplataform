package com.vivenotes.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeCtcReductionTest {
    private val environment = OrtEnvironment.getEnvironment()
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun nativeChoicesKeepFirstTiesSpecialFloatsBlankRepeatsAndExactConfidence() {
        val characters = listOf("", "é", "🛅", " ", "你", "ñ")
        val random = Random(119)
        val values = listOf(-0f, 0f, .5f, 1f, -1f, Float.NaN,
            Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.MIN_VALUE)
        val rows = Array(600) { FloatArray(characters.size) { values.random(random) } }
        rows[0] = FloatArray(characters.size) { Float.NaN }
        rows[1] = FloatArray(characters.size) { Float.NEGATIVE_INFINITY }
        rows[2] = floatArrayOf(-1f, -0f, 0f, -1f, -1f, -1f)
        rows[3] = floatArrayOf(-1f, Float.NaN, .5f, .5f, Float.NaN, -1f)
        rows[4] = rows[3].copyOf()
        rows[5] = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f)
        rows[6] = rows[3].copyOf()
        val expected = decodeCtc(rows, characters)
        tensor(rows).use { logits ->
            NativeCtcReduction.create(environment, 1).use { reduction ->
                reduction.withChoices(logits) { indices, scores ->
                    assertEquals(rows.size, indices.remaining())
                    assertEquals(rows.size, scores.remaining())
                    rows.forEachIndexed { step, row ->
                        var best = 0
                        var score = Float.NEGATIVE_INFINITY
                        row.forEachIndexed { index, candidate ->
                            if (candidate > score) { best = index; score = candidate }
                        }
                        assertEquals(best.toLong(), indices.get(step), "choice at $step")
                        assertEquals(score.toBits(), scores.get(step).toBits(), "score at $step")
                    }
                    val actual = decodeCtcChoices(indices, scores, characters)
                    assertEquals(expected.text, actual.text)
                    assertEquals(expected.confidence.toBits(), actual.confidence.toBits())
                    assertEquals(0, indices.position())
                    assertEquals(0, scores.position())
                }
                assertEquals(1, reduction.exceptionalCopyCount, "NaN recovery copies logits only for this exceptional request")
                assertEquals(expected, decodeCtc(logits.floatBuffer, rows.size, characters.size, characters))
            }
        }
    }

    @Test
    fun fullMultilingualClassAxisCopiesOnlyOneNativeChoicePerStep() {
        val characters = List(18_710) { if (it == 0) "" else "token$it" }
        val rows = Array(160) { step -> FloatArray(characters.size) { index ->
            if (index == (step * 89) % characters.size) .99f else (index % 101) * .0001f
        } }
        tensor(rows).use { logits ->
            NativeCtcReduction.create(environment, 2).use { reduction ->
                reduction.withChoices(logits) { indices, scores ->
                    assertEquals(160, indices.remaining())
                    assertEquals(160, scores.remaining())
                    assertEquals(decodeCtc(rows, characters), decodeCtcChoices(indices, scores, characters))
                }
                assertEquals(0, reduction.exceptionalCopyCount)
            }
        }
    }

    @Test
    fun fullClassAxisFastPathPreservesSignedZeroFirstTiesInfinitiesAndLaterNaNsWithoutCopyingLogits() {
        val classes = 18_710
        val characters = List(classes) { if (it == 0) "" else "token$it" }
        val rows = Array(10) { FloatArray(classes) { -1f } }
        rows[0].fill(Float.NEGATIVE_INFINITY)
        rows[1][1] = -0f; rows[1][2] = 0f
        rows[2][1] = 0f; rows[2][2] = -0f
        rows[3].fill(Float.NaN); rows[3][0] = Float.NEGATIVE_INFINITY
        rows[3][500] = .9f; rows[3][501] = .9f
        rows[4][0] = Float.POSITIVE_INFINITY; rows[4][500] = Float.POSITIVE_INFINITY
        rows[5][0] = -0f; rows[5][classes - 1] = 0f
        rows[6][0] = 0f; rows[6][classes - 1] = Float.MIN_VALUE
        rows[7].fill(Float.NaN); rows[7][0] = Float.NEGATIVE_INFINITY
        rows[8][1] = Float.POSITIVE_INFINITY; rows[8][classes - 1] = Float.POSITIVE_INFINITY
        rows[9][1] = Float.NaN; rows[9][classes - 2] = -.5f; rows[9][classes - 1] = -.5f
        tensor(rows).use { logits ->
            NativeCtcReduction.create(environment).use { reduction ->
                reduction.withChoices(logits) { indices, scores ->
                    assertChoiceBits(rows, indices, scores)
                    val actual = decodeCtcChoices(indices, scores, characters)
                    val expected = decodeCtc(rows, characters)
                    assertEquals(expected.text, actual.text)
                    assertEquals(expected.confidence.toRawBits(), actual.confidence.toRawBits())
                }
                assertEquals(0, reduction.exceptionalCopyCount,
                    "Finite class0 and later NaNs must exercise native TopK without the full-copy guard")
            }
        }
    }

    @Test
    fun firstClassNaNRecoveryIsPerRequestAndFollowingNormalReadsKeepTheSameNativeSession() {
        val opens = AtomicInteger()
        val events = ArrayList<String>()
        var reduction: NativeCtcReduction? = null
        val characters = listOf("", "é", "🛅", " ")
        val exceptional = arrayOf(floatArrayOf(Float.NaN, .9f, .1f, .2f),
            FloatArray(4) { Float.NaN }, floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))
        val normal = arrayOf(floatArrayOf(-1f, -0f, 0f, Float.NaN),
            floatArrayOf(0f, .1f, Float.NaN, .8f))
        NativeCtcDecoder(environment, NativeCtcReduction.DEFAULT_THREADS, DebugLog(enabled = true) { events.add(it) }) {
            opens.incrementAndGet()
            NativeCtcReduction.create(environment).also { reduction = it }
        }.use { decoder ->
            tensor(exceptional).use { logits -> assertEquals(decodeCtc(exceptional, characters), decoder.decode(logits, characters)) }
            assertEquals(1, reduction!!.exceptionalCopyCount)
            repeat(3) {
                tensor(normal).use { logits ->
                    assertEquals(decodeCtc(normal, characters), decoder.decode(logits, characters))
                    reduction!!.withChoices(logits) { indices, scores -> assertChoiceBits(normal, indices, scores) }
                }
            }
            assertEquals(1, opens.get())
            assertEquals(1, reduction!!.exceptionalCopyCount)
            assertTrue(events.none { it.contains("unavailable") }, "Input-specific NaN must not disable the native decoder")
        }
    }

    @Test
    fun unsupportedNativeReductionFallsBackOnceAndCanReopenAfterOcrEviction() {
        val attempts = AtomicInteger()
        val characters = listOf("", "é", "🛅")
        val rows = arrayOf(floatArrayOf(.1f, .9f, 0f), floatArrayOf(0f, .1f, .8f))
        NativeCtcDecoder(environment, 1, DebugLog()) {
            attempts.incrementAndGet()
            throw OrtException(OrtException.OrtErrorCode.ORT_NOT_IMPLEMENTED, "injected unsupported reduction")
        }.use { decoder ->
            tensor(rows).use { logits ->
                repeat(3) { assertEquals(decodeCtc(rows, characters), decoder.decode(logits, characters)) }
                assertEquals(1, attempts.get())
                decoder.close()
                assertEquals(decodeCtc(rows, characters), decoder.decode(logits, characters))
                assertEquals(2, attempts.get())
            }
        }
    }

    @Test
    fun nativeRunFailureFallsBackForItsLifetimeAndEvictionCanOpenAWorkingReduction() {
        val attempts = AtomicInteger()
        val failures = AtomicInteger()
        val characters = listOf("", "é", "🛅")
        val rows = arrayOf(floatArrayOf(.1f, .9f, 0f), floatArrayOf(0f, .1f, .8f))
        val log = DebugLog(enabled = true) { if (it.contains("native CTC reduction unavailable")) failures.incrementAndGet() }
        NativeCtcDecoder(environment, 1, log) {
            if (attempts.incrementAndGet() == 1) {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(1)
                    val wrongGraph = environment.createSession(resource(AiModelCatalog.TEXT_MODEL.fileName), options)
                    assertFalse("logits" in wrongGraph.inputNames, "The native run must reject this incompatible input name")
                    NativeCtcReduction(wrongGraph)
                }
            } else NativeCtcReduction.create(environment, 1)
        }.use { decoder ->
            tensor(rows).use { logits ->
                repeat(3) { assertEquals(decodeCtc(rows, characters), decoder.decode(logits, characters)) }
                assertEquals(1, attempts.get())
                assertEquals(1, failures.get())
                decoder.close()
                assertEquals(decodeCtc(rows, characters), decoder.decode(logits, characters))
                assertEquals(2, attempts.get())
                assertEquals(1, failures.get())
            }
        }
    }

    @Test
    fun retiringAnAdmittedDecodeDoesNotCloseItsHandleOrAReopenedLifetime() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val created = ConcurrentHashMap<Int, NativeCtcReduction>()
        val characters = listOf("", "é", "🛅")
        val rows = arrayOf(floatArrayOf(.1f, .9f, 0f), floatArrayOf(0f, .1f, .8f))
        val expected = decodeCtc(rows, characters)
        val pool = Executors.newFixedThreadPool(2)
        val decoder = NativeCtcDecoder(environment, 1, DebugLog()) {
            val attempt = attempts.incrementAndGet()
            NativeCtcReduction.create(environment, 1).also {
                created[attempt] = it
                if (attempt == 1) {
                    entered.countDown()
                    assertTrue(release.await(30, TimeUnit.SECONDS))
                }
            }
        }
        try {
            val old = pool.submit(Callable { tensor(rows).use { decoder.decode(it, characters) } })
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            decoder.close()
            decoder.close()
            val reopened = pool.submit(Callable { tensor(rows).use { decoder.decode(it, characters) } })
            assertEquals(expected, reopened.get(30, TimeUnit.SECONDS))
            assertEquals(2, attempts.get())
            release.countDown()
            assertEquals(expected, old.get(30, TimeUnit.SECONDS))
            tensor(rows).use { logits ->
                assertFailsWith<IllegalStateException> { created.getValue(1).withChoices(logits) { _, _ -> } }
                assertEquals(expected, created.getValue(2).withChoices(logits) { ids, scores ->
                    decodeCtcChoices(ids, scores, characters)
                })
                assertEquals(expected, decoder.decode(logits, characters))
                assertEquals(2, attempts.get(), "An old lifetime's final release must leave the new session alive")
                decoder.close()
                assertFailsWith<IllegalStateException> { created.getValue(2).withChoices(logits) { _, _ -> } }
                decoder.close()
            }
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            decoder.close()
            created.values.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun engineCloseDefersNativeReleaseRejectsQueuedAndNewWorkAndDoesNotLeakCancelledAdmissions() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        val log = DebugLog(enabled = true) { event ->
            events.add(event)
            if (event.contains("opened native CTC reduction session")) {
                entered.countDown()
                assertTrue(release.await(30, TimeUnit.SECONDS))
            }
        }
        val models = AiModelStore(temporary.newFolder("models"))
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val engine = OnnxInkRecognitionEngine(models, inferenceDispatcher = dispatcher, lanes = 1,
                inferenceThreads = 1, log = log)
            try {
                textImage(320, 64, 44f, listOf(Triple("hello", 16f, 48f))).use { image ->
                    supervisorScope {
                        val admitted = async(dispatcher) { engine.recognizeText(image) }
                        assertTrue(entered.await(30, TimeUnit.SECONDS))
                        val queued = async(dispatcher) { engine.recognizeText(image) }
                        val cancelled = async(dispatcher) { engine.recognizeText(image) }
                        async(dispatcher) { Unit }.await() // Both prior requests reached their suspended lane admission.
                        assertFalse(queued.isCompleted)
                        cancelled.cancelAndJoin()

                        engine.close()
                        engine.close()
                        assertFalse(events.any { it.contains("closed Text session") || it.contains("closed native CTC reduction session") })
                        release.countDown()
                        assertTrue(admitted.await().text.contains("hello", ignoreCase = true))
                        assertFailsWith<IllegalStateException> { queued.await() }
                        assertEquals(1, events.count { it.contains("opened Text session") })
                        assertEquals(1, events.count { it.contains("opened native CTC reduction session") })
                        assertEquals(1, events.count { it.contains("closed Text session") })
                        assertEquals(1, events.count { it.contains("closed native CTC reduction session") })
                        assertFailsWith<IllegalStateException> { engine.recognizeText(image) }
                        assertFailsWith<IllegalStateException> { engine.keepFormulaModelLoaded() }
                        assertEquals(1, events.count { it.contains("opened Text session") })
                        assertEquals(1, events.count { it.contains("opened native CTC reduction session") })
                    }
                }
            } finally {
                release.countDown()
                engine.close()
                models.close()
            }
        }
    }

    @Test
    fun failedDecodingReleasesItsEngineLeaseAndCloseBeforeFirstUseNeverOpensNativeSessions() = runBlocking {
        val events = ArrayList<String>()
        val bundled = BundledModelFiles { name ->
            if (name == AiModelCatalog.TEXT_DICTIONARY.fileName) byteArrayOf().inputStream()
            else BundledModelFiles.Classpath.open(name)
        }
        val models = AiModelStore(temporary.newFolder("bad-dictionary"), bundled = bundled)
        val engine = OnnxInkRecognitionEngine(models, lanes = 1, inferenceThreads = 1,
            log = DebugLog(enabled = true) { events.add(it) })
        try {
            textImage(320, 64, 44f, listOf(Triple("hello", 16f, 48f))).use { image ->
                assertFailsWith<IllegalArgumentException> { engine.recognizeText(image) }
                engine.close()
                engine.close()
                assertEquals(1, events.count { it.contains("opened Text session") })
                assertEquals(1, events.count { it.contains("closed Text session") }, "failure must return the native lease")
                assertFalse(events.any { it.contains("opened native CTC reduction session") })
                val neverUsed = OnnxInkRecognitionEngine(models, lanes = 1, inferenceThreads = 1,
                    log = DebugLog(enabled = true) { events.add(it) })
                neverUsed.close()
                neverUsed.close()
                assertFailsWith<IllegalStateException> { neverUsed.recognizeText(image) }
                assertEquals(1, events.count { it.contains("opened Text session") })
            }
        } finally {
            engine.close()
            models.close()
        }
    }

    @Test
    fun malformedDictionariesFailBeforeOpeningReductionAndEmptySequencesDoNotOpenIt() {
        val attempts = AtomicInteger()
        NativeCtcDecoder(environment, 1, DebugLog()) {
            attempts.incrementAndGet()
            error("No native run is needed")
        }.use { decoder ->
            tensor(arrayOf(floatArrayOf(1f, 0f))).use { logits ->
                assertFailsWith<IllegalArgumentException> { decoder.decode(logits, listOf("")) }
            }
            OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, 0, 2)).use { logits ->
                assertEquals(TextRecognitionResult("", 0f), decoder.decode(logits, listOf("", "a")))
            }
            assertEquals(0, attempts.get())
        }
    }

    @Test
    fun concurrentLanesShareOneReductionSessionWithoutSharingResultBuffers() {
        val attempts = AtomicInteger()
        val decoder = NativeCtcDecoder(environment, 1, DebugLog()) {
            attempts.incrementAndGet()
            NativeCtcReduction.create(environment, 1)
        }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = List(8) { lane -> Callable {
                val characters = listOf("", "a", "é", "🛅", " ")
                val rows = Array(80 + lane) { step -> FloatArray(characters.size) { index ->
                    if (index == (step + lane) % characters.size) .9f else .05f
                } }
                tensor(rows).use { logits ->
                    repeat(3) { assertEquals(decodeCtc(rows, characters), decoder.decode(logits, characters)) }
                }
            } }
            pool.invokeAll(tasks).forEach { it.get() }
            assertEquals(1, attempts.get())
        } finally {
            pool.shutdownNow()
            decoder.close()
        }
    }

    @Test
    fun unchangedV6AndAndroidV5NativeOutputsMatchTheOriginalDecoderExactly() {
        val models = listOf(AiModelCatalog.TEXT_MODEL.fileName to AiModelCatalog.TEXT_DICTIONARY.fileName,
            "android-ocr/en_pp-ocrv5_mobile_rec.onnx" to "android-ocr/ppocrv5_en_dict.txt")
        textImage(520, 90, 64f, listOf(Triple("Café mañana hello", 12f, 70f))).use { image ->
            val input = preprocessText(image)
            for ((model, dictionary) in models) {
                val characters = buildList {
                    add("")
                    addAll(resource(dictionary).decodeToString().lineSequence().filter { it.isNotEmpty() })
                    add(" ")
                }
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    environment.createSession(resource(model), options).use { recognizer ->
                        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values),
                            longArrayOf(1, 3, 48, input.width.toLong())).use { tensor ->
                            recognizer.run(mapOf(recognizer.inputNames.single() to tensor)).use { output ->
                                val logits = output[0] as OnnxTensor
                                val shape = logits.info.shape
                                assertTrue(output.isResultOwner(0), "This fixture must exercise an ORT-owned output")
                                assertFalse(logits.ownsBuffer(), "The output must not own a copied user input buffer")
                                val expected = decodeCtc(logits.floatBuffer, shape[1].toInt(), shape[2].toInt(), characters)
                                NativeCtcReduction.create(environment, 2).use { reduction ->
                                    reduction.withChoices(logits) { indices, scores ->
                                        assertEquals(shape[1].toInt(), indices.remaining())
                                        assertEquals(shape[1].toInt(), scores.remaining())
                                        val actual = decodeCtcChoices(indices, scores, characters)
                                        assertEquals(expected.text, actual.text, model)
                                        assertEquals(expected.confidence.toBits(), actual.confidence.toBits(), model)
                                    }
                                    assertEquals(0, reduction.exceptionalCopyCount, model)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun tensor(rows: Array<FloatArray>): OnnxTensor {
        val values = FloatArray(rows.sumOf { it.size })
        rows.forEachIndexed { index, row -> row.copyInto(values, index * row.size) }
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values),
            longArrayOf(1, rows.size.toLong(), rows.first().size.toLong()))
    }

    private fun assertChoiceBits(rows: Array<FloatArray>, indices: java.nio.LongBuffer, scores: FloatBuffer) {
        assertEquals(rows.size, indices.remaining())
        assertEquals(rows.size, scores.remaining())
        rows.forEachIndexed { step, row ->
            var best = 0
            var score = Float.NEGATIVE_INFINITY
            row.forEachIndexed { index, candidate -> if (candidate > score) { best = index; score = candidate } }
            assertEquals(best.toLong(), indices.get(step), "choice at$step")
            assertEquals(score.toRawBits(), scores.get(step).toRawBits(), "score at$step")
        }
    }

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/ai/$name")!!.use { it.readBytes() }
}
