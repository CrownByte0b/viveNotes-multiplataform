package com.vivenotes.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.vivenotes.diagnostics.DebugLog
import java.util.concurrent.atomic.AtomicInteger

/** Test-only native binding; the identical generic profile also compiles on the old flat decoder. */
internal class NativeCtcProfileAdapter(
    environment: OrtEnvironment,
    threads: Int,
    log: DebugLog,
    opens: AtomicInteger,
) : CtcProfileDecoder {
    override val reductionThreads: Int = NativeCtcReduction.DEFAULT_THREADS
    private var reduction: NativeCtcReduction? = null
    override val exceptionalCopies: Int get() = reduction?.exceptionalCopyCount ?: 0
    private val decoder = NativeCtcDecoder(environment, reductionThreads, log) {
        opens.incrementAndGet()
        NativeCtcReduction.create(environment).also { reduction = it }
    }

    override fun decode(logits: OnnxTensor, characters: List<String>) = decoder.decode(logits, characters)
    override fun close() = decoder.close()
}
