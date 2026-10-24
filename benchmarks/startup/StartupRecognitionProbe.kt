package com.vivenotes.startupprobe

import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModelStore
import com.vivenotes.ai.OnnxInkRecognitionEngine
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.jetbrains.skia.FontMgr
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest

/** Fresh-process, real-public-API diagnostic. No production switches or user profiles. */
object StartupRecognitionProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        val output = File(args[1])
        check(!output.exists())
        if (mode == "fixture") {
            Data.makeFromBytes(File(args[2]).readBytes()).use { data ->
                requireNotNull(FontMgr.default.makeFromData(data)).use { face ->
                    Font(face, 44f).use { font ->
                        Paint().use { paint ->
                            paint.color = 0xff000000.toInt()
                            paint.isAntiAlias = true
                            Surface.makeRasterN32Premul(320, 64).use { surface ->
                                surface.canvas.clear(0xffffffff.toInt())
                                surface.canvas.drawString("hello", 16f, 48f, font, paint)
                                surface.makeImageSnapshot().use { image ->
                                    image.encodeToData()!!.use { output.writeBytes(it.bytes) }
                                }
                            }
                        }
                    }
                }
            }
            return
        }
        require(mode == "unused" || mode == "ocr")
        val stages = linkedMapOf<String, Long>()
        val snapshots = linkedMapOf<String, JsonObject>()
        fun snapshot(name: String) {
            val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
            snapshots[name] = JsonObject(mapOf("heapUsed" to JsonPrimitive(heap.used),
                "heapCommitted" to JsonPrimitive(heap.committed),
                "uptimeMillis" to JsonPrimitive(ManagementFactory.getRuntimeMXBean().uptime)))
        }
        fun <T> measured(name: String, block: () -> T): T {
            val start = System.nanoTime()
            return block().also { stages[name] = System.nanoTime() - start; snapshot(name) }
        }
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        val models = AiModelStore(File(output.parentFile, "models"))
        var image: Image? = null
        try {
            runBlocking { withTimeout(30_000) {
                models.state.first { it.handwritingText != AiModelInstallState.Verifying }
            } }
            val fixture = if (mode == "ocr") File(args[2]).readBytes() else null
            if (fixture != null) image = Image.makeFromEncoded(fixture)
            snapshot("beforeConstruction")
            val engine = measured("construct") {
                OnnxInkRecognitionEngine(models, lanes = 1, inferenceThreads = 1,
                    log = DebugLog(enabled = true) { events.add(it) })
            }
            var reading: String? = null
            var confidence: Int? = null
            try {
                if (image != null) {
                    val first = measured("firstOcr") { runBlocking { engine.recognizeText(requireNotNull(image)) } }
                    check(first.text.trim() == "hello" && first.confidence > .5f) { "Unexpected synthetic reading" }
                    reading = first.text
                    confidence = first.confidence.toRawBits()
                    repeat(3) { index ->
                        val next = measured("warmOcr$index") { runBlocking { engine.recognizeText(requireNotNull(image)) } }
                        check(next == first) { "Warm OCR differs" }
                    }
                }
            } finally {
                measured("close") { engine.close(); engine.close() }
            }
            val value = JsonObject(mapOf(
                "schema" to JsonPrimitive("vive-startup-recognition/1"), "mode" to JsonPrimitive(mode),
                "java" to JsonPrimitive(System.getProperty("java.runtime.version")),
                "vm" to JsonPrimitive(System.getProperty("java.vm.name")),
                "os" to JsonPrimitive(System.getProperty("os.name")),
                "arch" to JsonPrimitive(System.getProperty("os.arch")),
                "processors" to JsonPrimitive(Runtime.getRuntime().availableProcessors()),
                "collectors" to JsonArray(ManagementFactory.getGarbageCollectorMXBeans().map { JsonPrimitive(it.name) }),
                "stagesNs" to JsonObject(stages.mapValues { JsonPrimitive(it.value) }),
                "snapshots" to JsonObject(snapshots),
                "fixtureSha256" to JsonPrimitive(fixture?.let(::sha)),
                "readingSha256" to JsonPrimitive(reading?.toByteArray()?.let(::sha)),
                "confidenceBits" to JsonPrimitive(confidence),
                "events" to JsonArray(events.map(::JsonPrimitive)),
            ))
            output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), value) + "\n")
        } finally {
            image?.close()
            models.close()
        }
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
