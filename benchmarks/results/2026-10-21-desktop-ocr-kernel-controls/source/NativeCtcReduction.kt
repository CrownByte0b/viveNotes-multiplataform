package com.vivenotes.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.localization.Strings
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** An OCR output is passed to this graph by its native handle, without copying the class axis. */
internal class NativeCtcReduction(private val session: OrtSession) : Closeable {
    fun <T> withChoices(logits: OnnxTensor, block: (LongBuffer, FloatBuffer) -> T): T =
        session.run(mapOf("logits" to logits)).use { output ->
            val indices = output.get("indices").get() as OnnxTensor
            val scores = output.get("scores").get() as OnnxTensor
            block(indices.longBuffer, scores.floatBuffer)
        }

    override fun close() = session.close()

    companion object {
        fun create(environment: OrtEnvironment, threads: Int): NativeCtcReduction =
            OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setIntraOpNumThreads(threads.coerceAtLeast(1))
                NativeCtcReduction(environment.createSession(model, options))
            }

        /** Independent opset11 graph; none of the bundled model bytes or pins are rewritten. */
        private val model: ByteArray by lazy {
            proto {
                integer(1, 6) // ONNX IR6, compatible with the bundled opset11 models.
                string(2, "ViveNotes CTC reduction")
                message(8) { integer(2, 11) }
                message(7) {
                    string(2, "ctc_choices")
                    node("IsNaN", listOf("logits"), "nan")
                    node("Where", listOf("nan", "negative_infinity", "logits"), "clean")
                    node("ArgMax", listOf("clean"), "indices", "axis" to 2L, "keepdims" to 1L)
                    node("GatherElements", listOf("clean", "indices"), "scores", "axis" to 2L)
                    message(5) {
                        integer(2, 1) // FLOAT scalar.
                        string(8, "negative_infinity")
                        bytes(9, byteArrayOf(0, 0, 0x80.toByte(), 0xff.toByte()))
                    }
                    valueInfo(11, "logits", 1, false)
                    valueInfo(12, "indices", 7, true)
                    valueInfo(12, "scores", 1, true)
                }
            }
        }
    }
}

/** Owns the small reduction session for exactly the lifetime of the OCR recognizer session. */
internal class NativeCtcDecoder(
    environment: OrtEnvironment,
    threads: Int,
    private val log: DebugLog,
    private val createReduction: () -> NativeCtcReduction = { NativeCtcReduction.create(environment, threads) },
) : Closeable {
    private class Lifetime {
        var reduction: NativeCtcReduction? = null
        var unavailable = false
        var active = 0
        var retired = false
    }
    private var lifetime = Lifetime()

    fun decode(logits: OnnxTensor, characters: List<String>): TextRecognitionResult {
        val shape = (logits.info as TensorInfo).shape
        require(shape.size == 3 && shape[0] == 1L && shape[1] in 0..Int.MAX_VALUE.toLong())
        require(shape[2] == characters.size.toLong()) { Strings.ocr_dictionary_does_not_match_model }
        require(characters.isNotEmpty())
        if (shape[1] == 0L) return TextRecognitionResult("", 0f)
        val current = synchronized(this) { lifetime.also { it.active++ } }
        try {
            val native = synchronized(current) {
                if (current.unavailable) null else current.reduction ?: try {
                    createReduction().also {
                        current.reduction = it
                        log.event("ai") { "opened native CTC reduction session" }
                    }
                } catch (error: OrtException) {
                    current.unavailable = true
                    log.failure("ai", "native CTC reduction unavailable", error)
                    null
                }
            }
            if (native != null) {
                try {
                    return native.withChoices(logits) { indices, scores ->
                        require(indices.remaining().toLong() == shape[1] && scores.remaining().toLong() == shape[1])
                        decodeCtcChoices(indices, scores, characters)
                    }
                } catch (error: OrtException) {
                    synchronized(current) {
                        if (!current.unavailable) log.failure("ai", "native CTC reduction unavailable", error)
                        current.unavailable = true
                    }
                }
            }
            return decodeCtc(logits.floatBuffer, shape[1].toInt(), shape[2].toInt(), characters)
        } finally {
            val shouldRelease = synchronized(this) { --current.active == 0 && current.retired }
            if (shouldRelease) release(current)
        }
    }

    /** Retire this OCR lifetime without releasing a native session still used by an admitted lane. */
    override fun close() {
        val previous = synchronized(this) {
            lifetime.also {
                it.retired = true
                lifetime = Lifetime()
            }.takeIf { it.active == 0 }
        }
        previous?.let(::release)
    }

    private fun release(previous: Lifetime) = synchronized(previous) {
        val native = previous.reduction
        previous.reduction = null
        native?.let {
            it.close()
            log.event("ai") { "closed native CTC reduction session" }
        }
    }
}

internal fun decodeCtcChoices(indices: LongBuffer, scores: FloatBuffer,
    characters: List<String>): TextRecognitionResult {
    require(indices.remaining() == scores.remaining())
    val choices = indices.asReadOnlyBuffer()
    val values = scores.asReadOnlyBuffer()
    val result = StringBuilder()
    var previous = 0
    var confidence = 0f
    var count = 0
    while (choices.hasRemaining()) {
        val choice = choices.get()
        require(choice >= 0 && choice < characters.size.toLong())
        val best = choice.toInt()
        val score = values.get()
        if (best != 0 && best != previous) {
            result.append(characters[best])
            confidence += score
            count++
        }
        previous = best
    }
    return TextRecognitionResult(result.toString(), if (count == 0) 0f else confidence / count)
}

// Only writes the fixed, dependency-free graph above. It does not parse or alter external models.
private class ProtoWriter {
    private val output = ByteArrayOutputStream()
    fun integer(field: Int, value: Long) { varint((field shl 3).toLong()); varint(value) }
    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun bytes(field: Int, value: ByteArray) {
        varint(((field shl 3) or 2).toLong()); varint(value.size.toLong()); output.write(value)
    }
    fun message(field: Int, write: ProtoWriter.() -> Unit) = bytes(field, proto(write))
    fun node(type: String, inputs: List<String>, name: String, vararg attributes: Pair<String, Long>) = message(1) {
        inputs.forEach { string(1, it) }
        string(2, name)
        string(3, name)
        string(4, type)
        attributes.forEach { (key, value) -> message(5) { string(1, key); integer(3, value); integer(20, 2) } }
    }
    fun valueInfo(field: Int, name: String, elementType: Long, reduced: Boolean) = message(field) {
        string(1, name)
        message(2) { message(1) {
            integer(1, elementType)
            message(2) {
                message(1) { integer(1, 1) }
                message(1) { string(2, "steps") }
                message(1) { if (reduced) integer(1, 1) else string(2, "classes") }
            }
        } }
    }
    fun result(): ByteArray = output.toByteArray()
    private fun varint(value: Long) {
        var remaining = value
        while (remaining and -128L != 0L) {
            output.write(((remaining and 127L) or 128L).toInt())
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
    }
}

private fun proto(write: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(write).result()
