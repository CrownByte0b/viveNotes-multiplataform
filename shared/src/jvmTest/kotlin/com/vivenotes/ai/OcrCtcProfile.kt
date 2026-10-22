package com.vivenotes.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import com.vivenotes.diagnostics.DebugLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fresh-process diagnostic; independent of the frozen benchmark and never reads a user profile. */
object OcrCtcProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5) { "Expected report path, flat|native, lanes, threads and tensor width" }
        val output = File(args[0]).absoluteFile
        require(!output.exists()) { "Choose a new report path; existing captures are immutable" }
        val mode = args[1]
        val lanes = args[2].toInt()
        val threads = args[3].toInt()
        val width = args[4].toInt()
        require(mode in setOf("flat", "native") && lanes in 1..2 && threads in 1..4 && width in 320..3200)
        val memory = ArrayList<JsonObject>()
        fun snapshot(stage: String) { memory += memorySnapshot(stage) }
        snapshot("process-start")
        val counters = CtcProfileCounters()
        val fixtureStarted = System.nanoTime()
        val input = textImage(width, 48, 32f, listOf(Triple(
            "Café mañana picture search ".repeat((width / 400).coerceAtLeast(1)), 4f, 37f))).use(::preprocessText)
        require(input.width == width)
        val inputHash = hashFloats(FloatBuffer.wrap(input.values))
        val dictionaryBytes = resource(AiModelCatalog.TEXT_DICTIONARY.fileName)
        check(dictionaryBytes.size.toLong() == AiModelCatalog.TEXT_DICTIONARY.bytes)
        check(hash(dictionaryBytes) == AiModelCatalog.TEXT_DICTIONARY.sha256)
        val characters = buildList {
            add("")
            dictionaryBytes.inputStream().bufferedReader().useLines { addAll(it.toList()) }
            add(" ")
        }
        check(characters.size == 18_710)
        val fixtureNanos = System.nanoTime() - fixtureStarted
        snapshot("fixture-ready")
        val environment = OrtEnvironment.getEnvironment()
        snapshot("onnx-environment-ready")
        val events = Collections.synchronizedList(ArrayList<String>())
        val nativeOpens = AtomicInteger()
        val decoder = if (mode == "native") {
            Class.forName("com.vivenotes.ai.NativeCtcProfileAdapter").getDeclaredConstructor(
                OrtEnvironment::class.java, Integer.TYPE, DebugLog::class.java, AtomicInteger::class.java,
            ).newInstance(environment, threads, DebugLog(enabled = true, output = events::add), nativeOpens)
                as CtcProfileDecoder
        } else null
        val openingStarted = System.nanoTime()
        val session = openRecognizer(environment, threads)
        val sessionOpenNanos = System.nanoTime() - openingStarted
        val pool = Executors.newFixedThreadPool(lanes)
        val inputName = try {
            snapshot("recognizer-session-open")
            session.inputNames.single()
        } catch (error: Throwable) {
            pool.shutdownNow()
            try { decoder?.close() } finally { session.close() }
            throw error
        }
        val inputShape = longArrayOf(1, 3, 48, width.toLong())
        fun decode(logits: OnnxTensor): TextRecognitionResult {
            val shape = logits.info.shape
            check(shape.size == 3 && shape[0] == 1L && shape[2] == characters.size.toLong())
            return decoder?.decode(logits, characters)
                ?: decodeCtc(logits.floatBuffer, shape[1].toInt(), shape[2].toInt(), characters)
        }
        fun pipeline(): CtcProfileReading = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values), inputShape)
            .use { tensor -> session.run(mapOf(inputName to tensor)).use { result ->
                val logits = result[0] as OnnxTensor
                CtcProfileReading(decode(logits), logits.info.shape)
            } }
        fun batch(operation: () -> CtcProfileReading): CtcProfileBatch {
            val before = System.nanoTime()
            val results = pool.invokeAll(List(lanes) { Callable { counters.measure(operation) } }).map { it.get() }
            return CtcProfileBatch(System.nanoTime() - before, results)
        }
        var cold: CtcProfileBatch? = null
        var expected: CtcProfileReading? = null
        var pipelineSamples: List<CtcProfileBatch> = emptyList()
        var kernelSamples: List<CtcProfileBatch> = emptyList()
        var actualOutputHash = ""
        var pipelineCpu = -1L
        var kernelCpu = -1L
        try {
            val first = batch(::pipeline)
            cold = first
            val reference = first.samples.first().reading
            expected = reference
            check(reference.result.text.isNotBlank())
            fun checkReadings(values: CtcProfileBatch) {
                values.samples.forEach { sample ->
                    check(sample.reading.result == reference.result) { "Reading or confidence changed between identical inputs" }
                    check(sample.reading.shape.contentEquals(reference.shape))
                }
                check(events.none { it.contains("native CTC reduction unavailable") }) { "Native profile fell back to Java" }
            }
            checkReadings(first)
            check(mode == "flat" || nativeOpens.get() == 1)
            snapshot("cold-inference-and-decode-complete")
            repeat(PIPELINE_WARMUP) { checkReadings(batch(::pipeline)) }
            snapshot("pipeline-warmup-complete")
            val pipelineCpuBefore = counters.processCpuNanos()
            pipelineSamples = List(PIPELINE_SAMPLES) { batch(::pipeline).also(::checkReadings) }
            pipelineCpu = cpuDelta(pipelineCpuBefore, counters.processCpuNanos())
            snapshot("pipeline-samples-complete")
            Thread.sleep(IDLE_MILLIS)
            snapshot("pipeline-idle-before-close")

            // One real output is kept open for both modes' decode-only measurements. Hash/parity
            // setup makes one full copy in both modes, then explicitly collects it before timing.
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values), inputShape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    check(result.isResultOwner(0))
                    val logits = result[0] as OnnxTensor
                    val shape = logits.info.shape
                    val frozen = frozenOutput(logits, characters)
                    check(frozen.first == reference.result)
                    actualOutputHash = frozen.second
                    System.gc()
                    Thread.sleep(IDLE_MILLIS)
                    snapshot("frozen-output-validation-collected")
                    fun kernel() = CtcProfileReading(decode(logits), shape)
                    repeat(KERNEL_WARMUP) { checkReadings(batch(::kernel)) }
                    snapshot("kernel-warmup-complete")
                    val kernelCpuBefore = counters.processCpuNanos()
                    kernelSamples = List(KERNEL_SAMPLES) { batch(::kernel).also(::checkReadings) }
                    kernelCpu = cpuDelta(kernelCpuBefore, counters.processCpuNanos())
                    snapshot("kernel-samples-complete")
                    Thread.sleep(IDLE_MILLIS)
                    snapshot("kernel-idle-output-still-open")
                }
            }
            snapshot("all-result-tensors-closed")
            check(decoder == null || decoder.exceptionalCopies == 0) { "Native model output used the full-copy NaN guard" }
        } finally {
            pool.shutdown()
            try { check(pool.awaitTermination(30, TimeUnit.SECONDS)) }
            finally { try { decoder?.close() } finally { session.close() } }
        }
        snapshot("sessions-and-lanes-closed")
        Thread.sleep(IDLE_MILLIS)
        snapshot("closed-idle")
        System.gc()
        Thread.sleep(IDLE_MILLIS)
        snapshot("closed-after-explicit-gc")
        val reading = checkNotNull(expected)
        val report = buildJsonObject {
            put("schema", "vive-ocr-ctc-profile/1")
            put("capturedAt", Instant.now().toString())
            put("mode", mode)
            put("lanes", lanes)
            put("inferenceThreads", threads)
            put("inputShape", JsonArray(inputShape.map(::JsonPrimitive)))
            put("outputShape", JsonArray(reading.shape.map(::JsonPrimitive)))
            put("inputSha256", inputHash)
            put("outputSha256", actualOutputHash)
            put("modelSha256", AiModelCatalog.TEXT_MODEL.sha256)
            put("modelBytes", AiModelCatalog.TEXT_MODEL.bytes)
            put("dictionarySha256", AiModelCatalog.TEXT_DICTIONARY.sha256)
            put("dictionaryBytes", AiModelCatalog.TEXT_DICTIONARY.bytes)
            put("readingSha256", hash(reading.result.text.toByteArray(Charsets.UTF_8)))
            put("confidenceBits", reading.result.confidence.toRawBits())
            put("fixturePreparationNanos", fixtureNanos)
            put("sessionOpenNanos", sessionOpenNanos)
            put("sessionOpenScope", "bundled model read and SHA verification, options, native session creation/optimization")
            put("nativeReductionOpens", nativeOpens.get())
            put("nativeReductionThreads", decoder?.reductionThreads ?: 0)
            put("nativeExceptionalCopies", decoder?.exceptionalCopies ?: 0)
            put("cold", checkNotNull(cold).json())
            put("pipelineWarmupBatches", PIPELINE_WARMUP)
            put("pipelineWarm", samplesJson(pipelineSamples, pipelineCpu))
            put("kernelWarmupBatches", KERNEL_WARMUP)
            put("kernelWarm", samplesJson(kernelSamples, kernelCpu))
            put("memory", JsonArray(memory))
            put("nativeEvents", JsonArray(events.map(::JsonPrimitive)))
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
            put("timingScope", "session open separately; cold and warm recognizer inference+CTC include tensor allocation/close; image/preprocess excluded; kernel reuses one exact native output")
            put("allocationScope", "per measured lane-thread Java allocation samples; median is per lane; excludes main/native threads and native arenas; caller CPU excludes ORT workers, process CPU includes them")
            put("summaryScope", "upper median for even pipeline/kernel sample counts; every lane and batch observation is retained")
            put("memoryScope", "whole-process Linux RSS/HWM, heap and direct-buffer pools; includes JIT/libraries/allocators; not isolated native-memory accounting or an RSS cap")
            put("collectionScope", "explicit GC only after common frozen-output validation and after session closure; pipeline idle before close has no forced collection")
        }
        output.parentFile.mkdirs()
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
        println("OCR CTC profile: $mode lanes=$lanes threads=$threads width=$width ${output.absolutePath}")
    }

    private fun openRecognizer(environment: OrtEnvironment, threads: Int): OrtSession {
        val bytes = resource(AiModelCatalog.TEXT_MODEL.fileName)
        check(bytes.size.toLong() == AiModelCatalog.TEXT_MODEL.bytes)
        check(hash(bytes) == AiModelCatalog.TEXT_MODEL.sha256)
        return OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(threads)
            environment.createSession(bytes, options)
        }
    }

    private fun frozenOutput(logits: OnnxTensor, characters: List<String>): Pair<TextRecognitionResult, String> {
        val shape = logits.info.shape
        val copy = logits.floatBuffer
        return decodeCtc(copy, shape[1].toInt(), shape[2].toInt(), characters) to hashFloats(copy)
    }

    private fun samplesJson(batches: List<CtcProfileBatch>, processCpu: Long): JsonObject = buildJsonObject {
        val samples = batches.flatMap { it.samples }
        fun median(values: List<Long>) = values.sorted()[values.size / 2]
        put("batches", JsonArray(batches.map { it.json() }))
        put("processCpuNanos", processCpu)
        put("medianBatchWallNanos", median(batches.map { it.wallNanos }))
        put("medianLaneWallNanos", median(samples.map { it.wallNanos }))
        put("medianLaneCpuNanos", median(samples.map { it.callerCpuNanos }))
        put("medianLaneAllocatedBytes", median(samples.map { it.allocatedBytes }))
    }

    private fun memorySnapshot(stage: String): JsonObject = buildJsonObject {
        val status = File("/proc/self/status").takeIf { it.isFile }?.readLines().orEmpty()
        fun bytes(name: String): Long? = status.firstOrNull { it.startsWith("$name:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        put("stage", stage)
        put("atNanos", System.nanoTime())
        put("rssBytes", bytes("VmRSS")?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("peakRssBytes", bytes("VmHWM")?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("heapUsedBytes", heap.used)
        put("heapCommittedBytes", heap.committed)
        put("nonHeapUsedBytes", ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage.used)
        put("bufferPools", JsonArray(ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java).map { pool ->
            buildJsonObject { put("name", pool.name); put("bytes", pool.memoryUsed); put("buffers", pool.count) }
        }))
        put("gcCount", ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) })
        put("gcMillis", ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) })
    }

    private fun hashFloats(values: FloatBuffer): String {
        val source = values.asReadOnlyBuffer()
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteBuffer.allocate(8192)
        while (source.hasRemaining()) {
            block.clear()
            repeat(minOf(source.remaining(), block.capacity() / 4)) { block.putInt(source.get().toRawBits()) }
            digest.update(block.array(), 0, block.position())
        }
        return hex(digest.digest())
    }

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/ai/$name")!!.use { it.readBytes() }
    private fun cpuDelta(before: Long, after: Long): Long = if (before >= 0L && after >= before) after - before else -1L
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private const val PIPELINE_WARMUP = 8
    private const val PIPELINE_SAMPLES = 20
    private const val KERNEL_WARMUP = 40
    private const val KERNEL_SAMPLES = 80
    private const val IDLE_MILLIS = 500L
}

internal interface CtcProfileDecoder : AutoCloseable {
    val reductionThreads: Int
    val exceptionalCopies: Int
    fun decode(logits: OnnxTensor, characters: List<String>): TextRecognitionResult
}

private data class CtcProfileReading(val result: TextRecognitionResult, val shape: LongArray)
private data class CtcProfileSample(val wallNanos: Long, val callerCpuNanos: Long, val allocatedBytes: Long,
    val reading: CtcProfileReading) {
    fun json() = buildJsonObject {
        put("wallNanos", wallNanos); put("callerCpuNanos", callerCpuNanos); put("allocatedBytes", allocatedBytes)
    }
}
private data class CtcProfileBatch(val wallNanos: Long, val samples: List<CtcProfileSample>) {
    fun json() = buildJsonObject { put("wallNanos", wallNanos); put("lanes", JsonArray(samples.map { it.json() })) }
}
private class CtcProfileCounters {
    private val threads = ManagementFactory.getThreadMXBean() as? ThreadMXBean
    private val process = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
    init {
        threads?.let {
            if (it.isThreadAllocatedMemorySupported && !it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
            if (it.isThreadCpuTimeSupported && !it.isThreadCpuTimeEnabled) it.isThreadCpuTimeEnabled = true
        }
    }
    fun processCpuNanos(): Long = process?.processCpuTime ?: -1
    fun measure(operation: () -> CtcProfileReading): CtcProfileSample {
        val id = Thread.currentThread().id
        val beforeAllocated = allocated(id)
        val beforeCpu = threads?.getThreadCpuTime(id) ?: -1
        val before = System.nanoTime()
        val reading = operation()
        val wall = System.nanoTime() - before
        val cpu = threads?.getThreadCpuTime(id) ?: -1
        val afterAllocated = allocated(id)
        return CtcProfileSample(wall, if (beforeCpu >= 0 && cpu >= 0) cpu - beforeCpu else -1,
            if (beforeAllocated >= 0 && afterAllocated >= 0) afterAllocated - beforeAllocated else -1, reading)
    }
    private fun allocated(id: Long): Long =
        threads?.takeIf { it.isThreadAllocatedMemorySupported }?.getThreadAllocatedBytes(id) ?: -1
}
