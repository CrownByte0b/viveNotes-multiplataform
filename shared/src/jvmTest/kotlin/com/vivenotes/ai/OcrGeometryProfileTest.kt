package com.vivenotes.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vivenotes.model.ocr.TextDetection
import kotlinx.serialization.json.*
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertTrue

/** Identical test-only diagnostic in both checkouts; original capture harness remains untouched. */
class OcrGeometryProfileTest {
    private val cases = ArrayList<JsonObject>()
    private val images = ArrayList<JsonObject>()

    @Test
    fun profileRealDetectorHullsAndReadingOrder() {
        assumeTrue(System.getenv("VIVE_OCR_GEOMETRY_PROFILE") == "1")
        val output = File(requireNotNull(System.getProperty("vivenotes.ocr.geometry.report")))
        require(!output.exists()) { "Choose a new report path; existing captures are immutable" }
        val environment = OrtEnvironment.getEnvironment()
        val model = javaClass.getResourceAsStream("/ai/${AiModelCatalog.DETECTION_MODEL.fileName}")!!
            .use { it.readBytes() }
        check(model.size.toLong() == AiModelCatalog.DETECTION_MODEL.bytes)
        check(hash(model) == AiModelCatalog.DETECTION_MODEL.sha256)
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(4)
            environment.createSession(model, options).use { session ->
                textImage(800, 600, 36f, List(7) { index ->
                    Triple("Release notes and picture search ${index + 1}", 24f, 54f + index * 76f)
                }).use { image -> profileImage("seven-lines", image, environment, session) }
                textImage(960, 960, 19f, List(38) { index ->
                    Triple("Desktop recognition preserves exact crop coordinates ${index + 1}", 20f, 28f + index * 24f)
                }).use { image -> profileImage("dense-page", image, environment, session) }
                textImage(800, 600, 36f, List(7) { index ->
                    Triple("Slanted text and picture search ${index + 1}", 60f, 54f + index * 76f)
                }).use { source ->
                    raster(800, 600) { canvas ->
                        canvas.clear(Color.WHITE)
                        canvas.translate(400f, 300f)
                        canvas.rotate(13f)
                        canvas.translate(-400f, -300f)
                        canvas.drawImage(source, 0f, 0f)
                    }.use { image -> profileImage("slanted-page", image, environment, session) }
                }
            }
        }
        for (vertices in listOf(16, 64, 256, 1024)) {
            val hull = List(vertices) { index ->
                val angle = index * 2.0 * Math.PI / vertices
                TextDetection.Point((480.0 + 420.0 * cos(angle)).toFloat(),
                    (480.0 + 140.0 * sin(angle)).toFloat())
            }
            val rectangle = TextDetection.minAreaRect(hull)
            measure("hull-$vertices", vertices, hashData { points(hull) }, hashData { rect(rectangle) }) {
                TextDetection.minAreaRect(hull)
            }
        }
        for (rows in listOf(40, 400)) {
            val quads = List(rows) { index ->
                val top = index * 18f
                TextDetection.Quad(listOf(TextDetection.Point(0f, top), TextDetection.Point(120f, top),
                    TextDetection.Point(120f, top + 12f), TextDetection.Point(0f, top + 12f)), 1f)
            }.reversed()
            val ordered = TextDetection.readingOrder(quads) { it }
            assertTrue(ordered.zipWithNext().all { (a, b) -> a.top <= b.top })
            measure("rows-$rows", rows, hashData { quads(quads) }, hashData { quads(ordered) }) {
                TextDetection.readingOrder(quads) { it }
            }
        }
        val report = buildJsonObject {
            put("schema", "vive-ocr-geometry-profile/1")
            put("modelSha256", AiModelCatalog.DETECTION_MODEL.sha256)
            put("modelBytes", AiModelCatalog.DETECTION_MODEL.bytes)
            put("cases", JsonArray(cases))
            put("images", JsonArray(images))
            put("runtime", buildJsonObject {
                put("java", System.getProperty("java.runtime.version"))
                put("vendor", System.getProperty("java.vendor"))
                put("vm", System.getProperty("java.vm.name"))
                put("ort", environment.version)
                put("os", System.getProperty("os.name"))
                put("arch", System.getProperty("os.arch"))
                put("processors", Runtime.getRuntime().availableProcessors())
                put("maxHeapBytes", Runtime.getRuntime().maxMemory())
                put("vmArgs", JsonArray(ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
            })
            put("timingScope", "warm synchronous geometry/postprocessing only; fixture/model inference/hash validation excluded; 31 ten-operation batch averages after at least 400ms warmup per case")
            put("fixtureScope", "three real pinned detector output maps plus synthetic elliptical hulls and separated rows; exact Float-bit geometry and stable ordered outputs hashed")
        }
        output.absoluteFile.parentFile.mkdirs()
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
        println("OCR geometry profile: ${output.absolutePath}")
    }

    private fun profileImage(name: String, image: Image, environment: OrtEnvironment, session: OrtSession) {
        val input = preprocessDetection(image, channelOrder = DetectionChannelOrder.Bgr)
        val probability = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values),
            longArrayOf(1, 3, input.height.toLong(), input.width.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { output ->
                FloatArray(input.width * input.height).also { (output[0] as OnnxTensor).floatBuffer.get(it) }
            }
        }
        val hulls = componentHulls(probability, input.width, input.height)
        val quads = ppOcrV6SmallQuads(probability, input.width, input.height)
        assertTrue(quads.isNotEmpty(), "The diagnostic fixture must produce real text regions")
        val probabilityHash = hashData {
            writeInt(input.width); writeInt(input.height)
            probability.forEach { writeInt(it.toRawBits()) }
        }
        val hullHash = hashData { hulls(hulls) }
        measure("$name-rectangles", hulls.size, hullHash,
            hashData { writeInt(hulls.size); hulls.forEach { rect(TextDetection.minAreaRect(it)) } }) {
            hulls.map(TextDetection::minAreaRect)
        }
        measure("$name-postprocess", probability.size, probabilityHash, hashData { quads(quads) }) {
            ppOcrV6SmallQuads(probability, input.width, input.height)
        }
        images += buildJsonObject {
            put("name", name); put("mapWidth", input.width); put("mapHeight", input.height)
            put("probabilitySha256", probabilityHash); put("hullsSha256", hullHash)
            put("orderedQuadsSha256", hashData { quads(quads) }); put("regions", quads.size)
            put("hullSizes", JsonArray(hulls.map { JsonPrimitive(it.size) }))
        }
    }

    private fun measure(name: String, units: Int, fixture: String, output: String, operation: () -> Any) {
        val start = System.nanoTime()
        do { sink = operation() } while (System.nanoTime() - start < 400_000_000L)
        val warmup = System.nanoTime() - start
        val samples = List(31) {
            val before = System.nanoTime()
            repeat(10) { sink = operation() }
            (System.nanoTime() - before) / 10
        }
        cases += buildJsonObject {
            put("name", name); put("units", units); put("fixtureSha256", fixture); put("outputSha256", output)
            put("sampleCount", samples.size); put("warmupNanos", warmup); put("batchOperations", 10)
            put("sampleNanosPerOperation", JsonArray(samples.map(::JsonPrimitive)))
            put("medianNanos", samples.sorted()[15])
        }
        println("OCR geometry $name: ${samples.sorted()[15]} ns")
    }

    private fun DataOutputStream.points(values: List<TextDetection.Point>) {
        writeInt(values.size); values.forEach { writeInt(it.x.toRawBits()); writeInt(it.y.toRawBits()) }
    }
    private fun DataOutputStream.hulls(values: List<List<TextDetection.Point>>) {
        writeInt(values.size); values.forEach { points(it) }
    }
    private fun DataOutputStream.quads(values: List<TextDetection.Quad>) {
        writeInt(values.size); values.forEach { points(it.corners); writeInt(it.score.toRawBits()) }
    }
    private fun DataOutputStream.rect(value: TextDetection.Rect) {
        points(value.corners); writeInt(value.shortSide.toRawBits()); writeInt(value.longSide.toRawBits())
    }
    private fun hashData(write: DataOutputStream.() -> Unit): String = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { it.write() }; hash(buffer.toByteArray())
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    // Independent fixture extraction: the production flood fill remains private and uninstrumented.
    private fun componentHulls(values: FloatArray, width: Int, height: Int): List<List<TextDetection.Point>> {
        val visited = BooleanArray(values.size)
        val stack = IntArray(values.size)
        val component = IntArray(values.size)
        val result = ArrayList<List<TextDetection.Point>>()
        for (start in values.indices) {
            if (visited[start] || values[start] <= .2f) continue
            visited[start] = true
            var pending = 1
            var count = 0
            stack[0] = start
            while (pending > 0) {
                val index = stack[--pending]
                component[count++] = index
                val x = index % width
                val y = index / width
                for (ny in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(height - 1)) {
                    for (nx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(width - 1)) {
                        val neighbour = ny * width + nx
                        if (!visited[neighbour] && values[neighbour] > .2f) {
                            visited[neighbour] = true
                            stack[pending++] = neighbour
                        }
                    }
                }
            }
            if (count < 9) continue
            val boundary = ArrayList<TextDetection.Point>()
            for (position in 0 until count) {
                val index = component[position]
                val x = index % width
                val y = index / width
                val interior = x > 0 && y > 0 && x < width - 1 && y < height - 1 &&
                    values[index - 1] > .2f && values[index + 1] > .2f &&
                    values[index - width] > .2f && values[index + width] > .2f
                if (!interior) boundary += TextDetection.Point(x.toFloat(), y.toFloat())
            }
            if (boundary.isNotEmpty()) result += TextDetection.convexHull(boundary)
        }
        return result
    }

    companion object { @Volatile private var sink: Any? = null }
}
