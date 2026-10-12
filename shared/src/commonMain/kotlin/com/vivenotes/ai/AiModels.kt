package com.vivenotes.ai

import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToLong

/** Installation state of one model package, as Settings → Models shows it. */
sealed interface AiModelInstallState {
    data object NotInstalled : AiModelInstallState
    data object Verifying : AiModelInstallState
    data object Installed : AiModelInstallState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : AiModelInstallState
    data class Failed(val message: String) : AiModelInstallState
}

/** Every recognition model on this device, and which formula model is in use. */
data class AiModelsState(
    /** The bundled PP-OCRv5 recognizer, detector and dictionary, verified together. */
    val handwritingText: AiModelInstallState = AiModelInstallState.Verifying,
    /** PP-FormulaNet-S's package. */
    val formulaLatex: AiModelInstallState = AiModelInstallState.Verifying,
    /** UniMERNet-T's package. */
    val uniMerNet: AiModelInstallState = AiModelInstallState.Verifying,
    /** The model formula recognition runs — see [formulaEngineInUse]. */
    val formulaEngine: FormulaEngine = DEFAULT_FORMULA_ENGINE,
) {
    fun formula(engine: FormulaEngine): AiModelInstallState = when (engine) {
        FormulaEngine.UniMerNetTiny -> uniMerNet
        FormulaEngine.FormulaNetS -> formulaLatex
    }

    fun withFormula(engine: FormulaEngine, install: AiModelInstallState): AiModelsState =
        when (engine) {
            FormulaEngine.UniMerNetTiny -> copy(uniMerNet = install)
            FormulaEngine.FormulaNetS -> copy(formulaLatex = install)
        }

    val installedFormulaEngines: Set<FormulaEngine>
        get() = FormulaEngine.entries.filterTo(mutableSetOf()) {
            formula(it) == AiModelInstallState.Installed
        }

    /** Whether formula recognition has a model behind it: the selected one, installed. */
    val formulaReady: Boolean
        get() = formula(formulaEngine) == AiModelInstallState.Installed
}

/** What Settings → Models can ask of this device's recognition models. */
interface AiModels {
    val state: StateFlow<AiModelsState>

    /** Downloads one formula model's package. Repeated requests share the download under way. */
    fun download(engine: FormulaEngine)

    /** Removes an installed formula model from this device. */
    fun delete(engine: FormulaEngine)

    /** Makes an installed formula model the one recognition runs. */
    fun selectFormulaEngine(engine: FormulaEngine)
}

/** One pinned file of a downloadable model package. */
data class ModelArtifact(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val url: String,
)

/** One file bundled with the app, verified before it is used. */
data class BundledArtifact(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * Every model file the app bundles or downloads, pinned by size and SHA-256 — the Android app's
 * catalog, byte for byte. A changed pin here means a different model, not a new copy of the same one.
 */
object AiModelCatalog {
    val TEXT_MODEL = BundledArtifact(
        fileName = "en_pp-ocrv5_mobile_rec.onnx",
        bytes = 7_876_014L,
        sha256 = "8307465d3c9ef2ba4055c3bd0be55aafe11f518630212b7598b70ccb376028ac",
    )
    val TEXT_DICTIONARY = BundledArtifact(
        fileName = "ppocrv5_en_dict.txt",
        bytes = 1_416L,
        sha256 = "e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6",
    )

    /**
     * PP-OCRv5_mobile_det, from the same pinned oar-ocr v0.3.0 release as everything else here.
     *
     * It is not a second recognizer and cannot replace one: its only output is a per-pixel
     * probability map, `[1, 1, H, W]`, with no box, class or score tensor anywhere in the graph.
     * Turning that map into text quads is `model/ocr/TextDetection.kt`.
     */
    val DETECTION_MODEL = BundledArtifact(
        fileName = "pp-ocrv5_mobile_det.onnx",
        bytes = 4_826_518L,
        sha256 = "1eb7b4f7ab657ebd1c66d5f79bca7497f29768a2e3c15e52daecbba1a8e4a039",
    )

    /** The detector, recognizer and dictionary: one capability, so verified and counted together. */
    val BUNDLED_TEXT: List<BundledArtifact> = listOf(TEXT_MODEL, TEXT_DICTIONARY, DETECTION_MODEL)

    const val FORMULA_MODEL_URL =
        "https://github.com/GreatV/oar-ocr/releases/download/v0.3.0/pp-formulanet-s.onnx"
    const val FORMULA_TOKENIZER_URL =
        "https://huggingface.co/PaddlePaddle/PP-FormulaNet-L_safetensors/resolve/main/tokenizer.json"

    /**
     * PP-FormulaNet-S.
     *
     * `PP-FormulaNet_plus-S` was tried on Android on 2026-08-10 and reverted the same day, because
     * handwriting recognition was markedly worse with it. Do not swap it in on the strength of its
     * published numbers; they do not measure this app's input. Printed BLEU does not predict this
     * app's accuracy; only a real eval set will.
     */
    val FORMULA_MODEL = ModelArtifact(
        fileName = "pp-formulanet-s.onnx",
        bytes = 231_878_904L,
        sha256 = "0ee32c7bfbd9e586364f89f71860476ccb5334e35674a61f3df5e0553d6a6dcc",
        url = FORMULA_MODEL_URL,
    )
    val FORMULA_TOKENIZER = ModelArtifact(
        fileName = "pp-formulanet-tokenizer.json",
        bytes = 2_140_014L,
        sha256 = "2811d82701ec97c192fa256aa2b4516929373870ae660326cc5b1dc879b95ff2",
        url = FORMULA_TOKENIZER_URL,
    )

    /**
     * The project's own release of the two UniMERNet-T graphs. A model release, tagged apart from
     * the Android app's `v*` tags and never marked Latest.
     */
    const val UNIMERNET_RELEASE_URL =
        "https://github.com/AquilaIgnis/viveNotes/releases/download/models-unimernet-tiny-v1/"

    /**
     * UniMERNet-T (`wanderkid/unimernet_tiny`, Apache-2.0) as the Android repository's
     * `simulations/formula-models` export and quantize scripts write it: int8 weights in MatMul and
     * Gather, float convolutions. The export is deterministic, so a change here means the export
     * changed and its parity check against PyTorch has to be re-run.
     */
    val UNIMERNET_ENCODER = ModelArtifact(
        fileName = "unimernet-tiny-encoder-int8.onnx",
        bytes = 29_081_861L,
        sha256 = "cf6cc98a54c97254adee4e251f56af2da045062503663b001a254303dab2aa97",
        url = UNIMERNET_RELEASE_URL + "unimernet-tiny-encoder-int8.onnx",
    )
    val UNIMERNET_DECODER = ModelArtifact(
        fileName = "unimernet-tiny-decoder-int8.onnx",
        bytes = 82_108_192L,
        sha256 = "b765a9c522f43cff7dcc6379e9c39dfc441d6ee189822cb71be8f5e7cd703a69",
        url = UNIMERNET_RELEASE_URL + "unimernet-tiny-decoder-int8.onnx",
    )

    /** One formula model's whole package. UniMERNet's tokenizer is FormulaNet's, bar one newline. */
    fun formulaArtifacts(engine: FormulaEngine): List<ModelArtifact> = when (engine) {
        FormulaEngine.UniMerNetTiny -> listOf(UNIMERNET_ENCODER, UNIMERNET_DECODER, FORMULA_TOKENIZER)
        FormulaEngine.FormulaNetS -> listOf(FORMULA_MODEL, FORMULA_TOKENIZER)
    }

    /** A formula model's whole download, for its row in the Models pane. */
    fun downloadBytes(engine: FormulaEngine): Long = formulaArtifacts(engine).sumOf(ModelArtifact::bytes)

    /** What the bundled OCR costs on disk. */
    val bundledTextBytes: Long get() = BUNDLED_TEXT.sumOf(BundledArtifact::bytes)
}

/** A model's size the way a file manager writes it: decimal megabytes, `113 MB`, `12.7 MB`. */
fun formatModelBytes(bytes: Long): String {
    if (bytes < 1_000_000L) return "${(bytes / 1_000.0).roundToLong().coerceAtLeast(1)} kB"
    val tenths = (bytes / 100_000.0).roundToLong()
    return if (tenths >= 1_000L) "${(tenths + 5) / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
}
