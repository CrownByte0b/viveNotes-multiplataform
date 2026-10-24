package com.vivenotes.ai

import com.vivenotes.diagnostics.DebugLog
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecognitionStartupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun constructionCloseAndRejectedWorkDoNotInitializeOnnxInAFreshProcess() {
        val directory = temporary.newFolder("cold")
        val output = File(directory, "process.log")
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/$javaName").absolutePath,
            "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-Xlog:class+init=info",
            "-cp", System.getProperty("java.class.path"), RecognitionUnusedProcess::class.java.name,
            directory.absolutePath,
        ).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Cold recognition process timed out")
            val text = output.readText()
            assertEquals(0, process.exitValue(), text.takeLast(6000))
            assertTrue(text.contains("unused recognition lifecycle passed"))
            val initialized = Regex("Initializing '([^']+)'").findAll(text).map { it.groupValues[1] }.toSet()
            assertTrue("com/vivenotes/ai/OnnxInkRecognitionEngine" in initialized, "Missing JVM initialization evidence")
            assertFalse("ai/onnxruntime/OrtEnvironment" in initialized, "Unused engine initialized ONNX environment")
            assertFalse("ai/onnxruntime/OnnxRuntime" in initialized, "Unused engine loaded ONNX JNI")
            assertFalse("com/vivenotes/ai/NativeCtcDecoder" in initialized, "Unused engine prepared its text decoder")
        } finally {
            process.destroyForcibly().waitFor()
        }
    }

    @Test
    fun concurrentFirstRequestsPrepareOnceAndReleaseTheirNativeSessions() = runBlocking {
        val events = Collections.synchronizedList(ArrayList<String>())
        val models = AiModelStore(temporary.newFolder("parallel"))
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val engine = OnnxInkRecognitionEngine(models, inferenceDispatcher = dispatcher, lanes = 2,
                inferenceThreads = 1, log = DebugLog(enabled = true) { events.add(it) })
            try {
                assertTrue(events.isEmpty())
                textImage(320, 64, 44f, listOf(Triple("hello", 16f, 48f))).use { image ->
                    val results = withTimeout(30_000) {
                        List(4) { async(dispatcher) { engine.recognizeText(image) } }.awaitAll()
                    }
                    assertTrue(results.first().text.contains("hello", ignoreCase = true))
                    assertTrue(results.all { it == results.first() })
                }
            } finally {
                engine.close()
                engine.close()
                models.close()
            }
            assertEquals(1, events.count { it.contains("native recognition runtime prepared") })
            assertEquals(1, events.count { it.contains("opened Text session") })
            assertEquals(1, events.count { it.contains("opened native CTC reduction session") })
            assertEquals(1, events.count { it.contains("closed Text session") })
            assertEquals(1, events.count { it.contains("closed native CTC reduction session") })
        }
    }

    @Test
    fun cancellationBeforeWorkerAdmissionDoesNotPrepareRecognition() = runTest {
        val events = ArrayList<String>()
        val models = AiModelStore(temporary.newFolder("cancelled"))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = OnnxInkRecognitionEngine(models, inferenceDispatcher = dispatcher,
            log = DebugLog(enabled = true) { events.add(it) })
        try {
            imageOf(8, 8) { _, _ -> WHITE }.use { image ->
                val pending = launch(dispatcher) { engine.recognizeText(image) }
                pending.cancelAndJoin()
            }
        } finally {
            engine.close()
            models.close()
        }
        assertTrue(events.isEmpty(), events.toString())
    }

    @Test
    fun failedFirstModelOpenCanRetryWithoutRepreparingTheRuntime() = runBlocking {
        val failNext = AtomicBoolean(false)
        val bundled = BundledModelFiles { name ->
            if (name == AiModelCatalog.TEXT_MODEL.fileName && failNext.compareAndSet(true, false)) {
                byteArrayOf(0).inputStream()
            } else BundledModelFiles.Classpath.open(name)
        }
        val models = AiModelStore(temporary.newFolder("retry"), bundled = bundled)
        withTimeout(30_000) { models.state.first { it.handwritingText != AiModelInstallState.Verifying } }
        val events = ArrayList<String>()
        val engine = OnnxInkRecognitionEngine(models, lanes = 1, inferenceThreads = 1,
            log = DebugLog(enabled = true) { events.add(it) })
        try {
            textImage(320, 64, 44f, listOf(Triple("hello", 16f, 48f))).use { image ->
                failNext.set(true)
                assertFailsWith<ai.onnxruntime.OrtException> { engine.recognizeText(image) }
                assertFalse(events.any { it.contains("opened Text session") })
                assertTrue(engine.recognizeText(image).text.contains("hello", ignoreCase = true))
            }
        } finally {
            engine.close()
            models.close()
        }
        assertEquals(1, events.count { it.contains("native recognition runtime prepared") })
        assertEquals(1, events.count { it.contains("opened Text session") })
        assertEquals(1, events.count { it.contains("closed Text session") })
    }

    @Test
    fun preparationLoggingRemainsOptIn() = runBlocking {
        val events = ArrayList<String>()
        val models = AiModelStore(temporary.newFolder("silent"))
        val engine = OnnxInkRecognitionEngine(models, lanes = 1, inferenceThreads = 1,
            log = DebugLog(enabled = false) { events.add(it) })
        try {
            textImage(320, 64, 44f, listOf(Triple("hello", 16f, 48f))).use { image ->
                assertTrue(engine.recognizeText(image).text.contains("hello", ignoreCase = true))
            }
        } finally {
            engine.close()
            models.close()
        }
        assertTrue(events.isEmpty())
    }
}

/** The fresh VM prevents another native test from hiding an eager initialization. */
object RecognitionUnusedProcess {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val models = AiModelStore(File(args.single(), "models"))
        try {
            val engine = OnnxInkRecognitionEngine(models)
            engine.close()
            engine.close()
            imageOf(8, 8) { _, _ -> WHITE }.use { image ->
                assertFailsWith<IllegalStateException> { engine.recognizeText(image) }
                assertFailsWith<IllegalStateException> { engine.recognizeImageText(image) }
            }
            assertFailsWith<IllegalStateException> { engine.keepFormulaModelLoaded() }
        } finally {
            models.close()
        }
        println("unused recognition lifecycle passed")
    }
}
