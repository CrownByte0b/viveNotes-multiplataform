package com.vivenotes.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Opt-in, fresh-process kernel experiment. It never changes models, production or prior captures. */
object NativeCtcKernelProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 7..9) {
            "Expected report, flat|scalar|current|topk|guarded-topk, reduction threads, spinning, width, lanes, recognizer threads[, arena, memory pattern]"
        }
        val report = File(args[0]).absoluteFile
        require(!report.exists()) { "Existing captures are immutable" }
        val mode = args[1]
        val threads = args[2].toInt()
        val spin = args[3].toInt()
        val width = args[4].toInt()
        val lanes = args[5].toInt()
        val recognizerThreads = args[6].toInt()
        val arena = args.getOrNull(7)?.toInt() ?: 1
        val pattern = args.getOrNull(8)?.toInt() ?: 1
        require(mode in setOf("flat", "scalar", "current", "topk", "guarded-topk"))
        require(threads in setOf(1, 4) && spin in 0..1 && width in setOf(320, 3200) && lanes in 1..2)
        require(recognizerThreads in setOf(1, 4) && arena in 0..1 && pattern in 0..1)
        report.parentFile.mkdirs()
        val environment = OrtEnvironment.getEnvironment()
        val model = when (mode) {
            "current" -> NativeCtcReduction.Companion.javaClass.getDeclaredMethod("getModel")
                .apply { isAccessible = true }.invoke(NativeCtcReduction.Companion) as ByteArray
            "topk" -> kernelTopKModel(clean = true)
            "guarded-topk" -> kernelTopKModel(clean = false)
            else -> null
        }
        fun openReduction(profile: Boolean = false): OrtSession = OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(threads)
            options.addConfigEntry("session.intra_op.allow_spinning", spin.toString())
            options.addConfigEntry("session.inter_op.allow_spinning", spin.toString())
            options.setCPUArenaAllocator(arena == 1)
            options.setMemoryPatternOptimization(pattern == 1)
            if (profile) options.enableProfiling(File(report.parentFile, report.nameWithoutExtension + "-ort").path)
            environment.createSession(checkNotNull(model), options)
        }
        val dictionary = resource(AiModelCatalog.TEXT_DICTIONARY.fileName)
        check(kernelHash(dictionary) == AiModelCatalog.TEXT_DICTIONARY.sha256)
        val characters = buildList {
            add("")
            dictionary.inputStream().bufferedReader().useLines { addAll(it.toList()) }
            add(" ")
        }
        check(characters.size == 18_710)
        val input = textImage(width, 48, 32f, listOf(Triple(
            "Café mañana picture search ".repeat((width / 400).coerceAtLeast(1)), 4f, 37f))).use(::preprocessText)
        check(input.width == width)
        val recognizerBytes = resource(AiModelCatalog.TEXT_MODEL.fileName)
        check(kernelHash(recognizerBytes) == AiModelCatalog.TEXT_MODEL.sha256)
        val recognizer = OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(recognizerThreads)
            environment.createSession(recognizerBytes, options)
        }
        val reduction = try { if (model == null) null else openReduction() }
            catch (error: Exception) { recognizer.close(); throw error }
        val fallbacks = AtomicInteger()
        val counters = KernelCounters()
        val pool = Executors.newFixedThreadPool(lanes)
        val memorySnapshots = ArrayList<JsonObject>()
        fun snapshot(stage: String) { memorySnapshots += kernelMemory(stage) }
        var trace: JsonObject? = null
        var samples: List<KernelBatch> = emptyList()
        var shape = longArrayOf()
        var actualOutputHash = ""
        var expected = TextRecognitionResult("", 0f)
        var preflightFallbacks = 0
        var sampleCpu = -1L
        try {
            if (reduction != null) {
                verifyKernelParity(environment, reduction, mode == "guarded-topk", fallbacks)
                preflightFallbacks = fallbacks.getAndSet(0)
            }
            snapshot("sessions-and-special-float-preflight-ready")
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values), longArrayOf(1, 3, 48, width.toLong())).use { tensor ->
                recognizer.run(mapOf(recognizer.inputNames.single() to tensor)).use { output ->
                    check(output.isResultOwner(0))
                    val logits = output[0] as OnnxTensor
                    shape = logits.info.shape
                    check(shape.size == 3 && shape[0] == 1L && shape[2] == characters.size.toLong())
                    val frozen = kernelOutputFingerprint(logits, characters)
                    actualOutputHash = frozen.second
                    expected = frozen.first
                    val scalar = if (mode == "scalar") logits.floatBuffer else null
                    check(expected.text.isNotBlank())
                    fun operation(): TextRecognitionResult = when (mode) {
                        "flat" -> decodeCtc(logits.floatBuffer, shape[1].toInt(), shape[2].toInt(), characters)
                        "scalar" -> decodeCtc(checkNotNull(scalar), shape[1].toInt(), shape[2].toInt(), characters)
                        else -> kernelChoices(checkNotNull(reduction), logits, mode == "guarded-topk", fallbacks) { ids, scores ->
                            decodeCtcChoices(ids, scores, characters)
                        }
                    }
                    fun batch(): KernelBatch {
                        val started = System.nanoTime()
                        val values = pool.invokeAll(List(lanes) { Callable { counters.sample(::operation) } }).map { it.get() }
                        values.forEach { check(it.reading.text == expected.text &&
                            it.reading.confidence.toRawBits() == expected.confidence.toRawBits()) }
                        return KernelBatch(System.nanoTime() - started, values)
                    }
                    System.gc()
                    Thread.sleep(100)
                    repeat(40) { batch() }
                    snapshot("kernel-warmup-complete")
                    val before = counters.processCpu()
                    samples = List(80) { batch() }
                    sampleCpu = kernelDelta(before, counters.processCpu())
                    check(fallbacks.get() == 0) { "Actual model output unexpectedly needs exceptional NaN fallback" }
                    snapshot("unprofiled-kernel-samples-complete")
                    Thread.sleep(500)
                    snapshot("unprofiled-kernel-idle")
                    if (model != null) openReduction(profile = true).use { profiled ->
                        repeat(8) { kernelChoices(profiled, logits, mode == "guarded-topk", fallbacks) { ids, scores ->
                            check(decodeCtcChoices(ids, scores, characters) == expected)
                        } }
                        val path = File(profiled.endProfiling())
                        trace = summarizeKernelTrace(path)
                    }
                }
            }
        } finally {
            pool.shutdown()
            try { check(pool.awaitTermination(30, TimeUnit.SECONDS)) }
            finally { try { reduction?.close() } finally { recognizer.close() } }
        }
        snapshot("all-sessions-closed")
        fun median(values: List<Long>): Long = values.sorted()[values.size / 2]
        val laneSamples = samples.flatMap { it.samples }
        val result = buildJsonObject {
            put("schema", "vive-native-ctc-kernel-profile/1")
            put("mode", mode); put("reductionThreads", threads); put("spinning", spin)
            put("cpuArena", arena); put("memoryPattern", pattern)
            put("width", width); put("lanes", lanes); put("recognizerThreads", recognizerThreads)
            put("shape", JsonArray(shape.map(::JsonPrimitive)))
            put("modelSha256", AiModelCatalog.TEXT_MODEL.sha256)
            put("dictionarySha256", AiModelCatalog.TEXT_DICTIONARY.sha256)
            put("graphSha256", model?.let { JsonPrimitive(kernelHash(it)) } ?: JsonNull)
            put("inputSha256", kernelHashFloats(FloatBuffer.wrap(input.values)))
            put("outputSha256", actualOutputHash)
            put("readingSha256", kernelHash(expected.text.toByteArray()))
            put("confidenceBits", expected.confidence.toRawBits())
            put("specialFloatParityPassed", true); put("specialFloatFallbacks", preflightFallbacks)
            put("actualOutputFallbacks", fallbacks.get())
            put("warmupBatches", 40); put("sampleBatches", 80)
            put("medianBatchWallNanos", median(samples.map { it.wall }))
            put("medianLaneWallNanos", median(laneSamples.map { it.wall }))
            put("medianLaneAllocatedBytes", median(laneSamples.map { it.allocated }))
            put("medianLaneCpuNanos", median(laneSamples.map { it.cpu }))
            put("sampleProcessCpuNanos", sampleCpu)
            put("batches", JsonArray(samples.map { batch -> buildJsonObject {
                put("wallNanos", batch.wall)
                put("lanes", JsonArray(batch.samples.map { sample -> buildJsonObject {
                    put("wallNanos", sample.wall); put("callerCpuNanos", sample.cpu); put("allocatedBytes", sample.allocated)
                } }))
            } }))
            put("memory", JsonArray(memorySnapshots)); put("ortProfile", trace ?: JsonNull)
            put("runtime", buildJsonObject {
                put("java", System.getProperty("java.runtime.version")); put("vendor", System.getProperty("java.vendor"))
                put("ort", environment.version); put("processors", Runtime.getRuntime().availableProcessors())
                put("vmArgs", JsonArray(ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
            })
            put("scope", "Exploratory kernel-only fresh-process diagnostic; same real native output shared across consumers. Scalar retains a common copied FloatBuffer; flat copies it every call. Native copies only choices, except guardedTopK copies logits if a chosen score is NaN. Recognizer stays open; its pooling configuration is fixed separately. ORT tracing uses a separate session AFTER latency samples and adds native pools/arenas only then. RSS includes all process libraries, JIT and allocators, not isolated native usage. No historical baseline claim.")
        }
        report.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result) + "\n")
        println("Native CTC kernel profile: $mode threads=$threads spin=$spin width=$width lanes=$lanes ${report.path}")
    }

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/ai/$name")!!.use { it.readBytes() }
}

private fun <T> kernelChoices(session: OrtSession, logits: OnnxTensor, guard: Boolean,
    fallbacks: AtomicInteger, block: (LongBuffer, FloatBuffer) -> T): T = session.run(mapOf("logits" to logits)).use { output ->
    val ids = (output.get("indices").get() as OnnxTensor).longBuffer
    val scores = (output.get("scores").get() as OnnxTensor).floatBuffer
    if (guard && (scores.position() until scores.limit()).any { scores.get(it).isNaN() }) {
        fallbacks.incrementAndGet()
        val shape = logits.info.shape
        val source = logits.floatBuffer
        val choices = LongArray(shape[1].toInt())
        val values = FloatArray(choices.size)
        repeat(choices.size) { step ->
            var best = 0
            var score = Float.NEGATIVE_INFINITY
            repeat(shape[2].toInt()) { index ->
                val candidate = source.get()
                if (candidate > score) { best = index; score = candidate }
            }
            choices[step] = best.toLong(); values[step] = score
        }
        block(LongBuffer.wrap(choices), FloatBuffer.wrap(values))
    } else block(ids, scores)
}

private fun verifyKernelParity(environment: OrtEnvironment, session: OrtSession, guard: Boolean, fallbacks: AtomicInteger) {
    val random = Random(119)
    val special = listOf(-0f, 0f, .5f, 1f, -1f, Float.NaN, Float.NEGATIVE_INFINITY,
        Float.POSITIVE_INFINITY, Float.MIN_VALUE, -Float.MIN_VALUE, Float.fromBits(0x7fc01234))
    val rows = Array(600) { FloatArray(6) { special.random(random) } }
    rows[0] = FloatArray(6) { Float.NaN }
    rows[1] = FloatArray(6) { Float.NEGATIVE_INFINITY }
    rows[2] = floatArrayOf(-1f, -0f, 0f, -1f, -1f, -1f)
    rows[3] = floatArrayOf(-1f, Float.NaN, .5f, .5f, Float.NaN, -1f)
    rows[4] = floatArrayOf(Float.NaN, .9f, .1f, Float.POSITIVE_INFINITY, -1f, Float.NaN)
    val wide = Array(24) { step -> FloatArray(18_710) { index ->
        if (index == 0 && step % 4 == 0) Float.NaN else special[(index + step * 17) % special.size]
    } }
    for (fixture in listOf(rows, wide)) {
        val classes = fixture.first().size
        val source = FloatArray(fixture.size * classes)
        fixture.forEachIndexed { index, row -> row.copyInto(source, index * classes) }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(source), longArrayOf(1, fixture.size.toLong(), classes.toLong())).use { tensor ->
            kernelChoices(session, tensor, guard, fallbacks) { ids, scores ->
                check(ids.remaining() == fixture.size && scores.remaining() == fixture.size)
                fixture.forEachIndexed { step, row ->
                    var best = 0
                    var score = Float.NEGATIVE_INFINITY
                    row.forEachIndexed { index, candidate -> if (candidate > score) { best = index; score = candidate } }
                    check(ids.get(step) == best.toLong()) { "Native index differs at step$step classes$classes" }
                    check(scores.get(step).toRawBits() == score.toRawBits()) { "Native score differs at step$step classes$classes" }
                }
            }
        }
    }
}

private fun kernelTopKModel(clean: Boolean): ByteArray = kernelProto {
    integer(1, 6); string(2, "ViveNotes CTC kernel experiment")
    message(8) { integer(2, 11) }
    message(7) {
        string(2, if (clean) "clean_topk" else "guarded_topk")
        if (clean) {
            node("IsNaN", listOf("logits"), listOf("nan"))
            node("Where", listOf("nan", "negative_infinity", "logits"), listOf("clean"))
            message(5) { integer(2, 1); string(8, "negative_infinity"); bytes(9, byteArrayOf(0, 0, 0x80.toByte(), 0xff.toByte())) }
        }
        node("TopK", listOf(if (clean) "clean" else "logits", "k"), listOf("scores", "indices"),
            "axis" to 2L, "largest" to 1L, "sorted" to 0L)
        message(5) {
            integer(1, 1); integer(2, 7); string(8, "k")
            bytes(9, byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
        }
        value(11, "logits", 1, false); value(12, "indices", 7, true); value(12, "scores", 1, true)
    }
}

private class KernelProtoWriter {
    private val output = ByteArrayOutputStream()
    fun integer(field: Int, value: Long) { varint((field shl 3).toLong()); varint(value) }
    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun bytes(field: Int, value: ByteArray) { varint(((field shl 3) or 2).toLong()); varint(value.size.toLong()); output.write(value) }
    fun message(field: Int, write: KernelProtoWriter.() -> Unit) = bytes(field, kernelProto(write))
    fun node(type: String, inputs: List<String>, outputs: List<String>, vararg attributes: Pair<String, Long>) = message(1) {
        inputs.forEach { string(1, it) }; outputs.forEach { string(2, it) }
        string(3, outputs.first()); string(4, type)
        attributes.forEach { (key, value) -> message(5) { string(1, key); integer(3, value); integer(20, 2) } }
    }
    fun value(field: Int, name: String, type: Long, reduced: Boolean) = message(field) {
        string(1, name)
        message(2) { message(1) {
            integer(1, type)
            message(2) {
                message(1) { integer(1, 1) }; message(1) { string(2, "steps") }
                message(1) { if (reduced) integer(1, 1) else string(2, "classes") }
            }
        } }
    }
    fun result(): ByteArray = output.toByteArray()
    private fun varint(value: Long) {
        var remaining = value
        while (remaining and -128L != 0L) { output.write(((remaining and 127L) or 128L).toInt()); remaining = remaining ushr 7 }
        output.write(remaining.toInt())
    }
}
private fun kernelProto(write: KernelProtoWriter.() -> Unit): ByteArray = KernelProtoWriter().apply(write).result()

private data class KernelSample(val wall: Long, val cpu: Long, val allocated: Long, val reading: TextRecognitionResult)
private data class KernelBatch(val wall: Long, val samples: List<KernelSample>)
private class KernelCounters {
    private val threads = ManagementFactory.getThreadMXBean() as? ThreadMXBean
    private val process = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
    init {
        threads?.let {
            if (it.isThreadAllocatedMemorySupported && !it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
            if (it.isThreadCpuTimeSupported && !it.isThreadCpuTimeEnabled) it.isThreadCpuTimeEnabled = true
        }
    }
    fun processCpu(): Long = process?.processCpuTime ?: -1L
    fun sample(operation: () -> TextRecognitionResult): KernelSample {
        val id = Thread.currentThread().id
        val allocated = threads?.getThreadAllocatedBytes(id) ?: -1L
        val cpu = threads?.getThreadCpuTime(id) ?: -1L
        val start = System.nanoTime()
        val result = operation()
        val elapsed = System.nanoTime() - start
        return KernelSample(elapsed, kernelDelta(cpu, threads?.getThreadCpuTime(id) ?: -1L),
            kernelDelta(allocated, threads?.getThreadAllocatedBytes(id) ?: -1L), result)
    }
}
private fun kernelDelta(before: Long, after: Long): Long = if (before >= 0 && after >= before) after - before else -1L
private fun kernelOutputFingerprint(logits: OnnxTensor, characters: List<String>): Pair<TextRecognitionResult, String> {
    val shape = logits.info.shape
    val values = logits.floatBuffer
    return decodeCtc(values, shape[1].toInt(), shape[2].toInt(), characters) to kernelHashFloats(values)
}
private fun kernelHash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
private fun kernelHashFloats(values: FloatBuffer): String {
    val source = values.asReadOnlyBuffer()
    val digest = MessageDigest.getInstance("SHA-256")
    val block = ByteBuffer.allocate(8192)
    while (source.hasRemaining()) {
        block.clear()
        repeat(minOf(source.remaining(), block.capacity() / 4)) { block.putInt(source.get().toRawBits()) }
        digest.update(block.array(), 0, block.position())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
private fun kernelMemory(stage: String): JsonObject = buildJsonObject {
    put("stage", stage)
    val status = File("/proc/self/status").takeIf { it.isFile }?.readLines().orEmpty()
    for (key in listOf("VmRSS", "VmHWM", "Threads")) {
        val value = status.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
        put(key, value?.let { JsonPrimitive(if (key == "Threads") it else it * 1024) } ?: JsonNull)
    }
    put("heapUsed", ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
}
private fun summarizeKernelTrace(path: File): JsonObject = buildJsonObject {
    val bytes = path.readBytes()
    val events = Json.parseToJsonElement(bytes.decodeToString()).jsonArray.filter { event ->
        val value = event.jsonObject
        value["cat"]?.jsonPrimitive?.content == "Node" && value["name"]?.jsonPrimitive?.content?.endsWith("_kernel_time") == true
    }
    put("path", path.absolutePath); put("sha256", kernelHash(bytes)); put("runs", 8)
    put("scope", "Separate profiled session after all unprofiled samples; includes first cold graph run; raw trace retained")
    put("operators", JsonArray(events.groupBy { it.jsonObject["args"]?.jsonObject?.get("op_name")?.jsonPrimitive?.content ?: "unknown" }
        .map { (name, values) -> buildJsonObject {
            val durations = values.map { it.jsonObject.getValue("dur").jsonPrimitive.long }.sorted()
            put("op", name); put("events", values.size); put("totalMicros", durations.sum()); put("medianMicros", durations[durations.size / 2])
        } }))
}
