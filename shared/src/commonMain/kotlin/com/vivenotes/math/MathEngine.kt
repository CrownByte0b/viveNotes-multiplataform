package com.vivenotes.math

import com.vivenotes.localization.Strings
import kotlinx.serialization.Serializable

/*
 * The Android app's `math/SympyMathEngine.kt` contract: the JSON `math_engine.py` answers in, and
 * the calls Kotlin makes. On Android the module runs in-process through Chaquopy; on the desktop it
 * runs in a uv-managed CPython beside the app — see `SympyMathEngine` in jvmMain.
 */

@Serializable
data class MathAction(
    val id: String,
    val label: String,
)

@Serializable
data class MathAnalysis(
    val normalizedLatex: String = "",
    val summary: String = "",
    val variables: List<String> = emptyList(),
    val actions: List<MathAction> = emptyList(),
    val error: String? = null,
)

@Serializable
data class MathGraph(
    val xLabel: String,
    val yLabel: String,
    val xValues: List<Double>,
    val yValues: List<Double?>,
)

@Serializable
data class MathOperationResult(
    val title: String = Strings.result,
    val latex: String? = null,
    val message: String? = null,
    val graph: MathGraph? = null,
    val error: String? = null,
    /** [latex] with every number evaluated to 12 significant digits; null where that reads the same. */
    val decimal: String? = null,
)

interface MathEngine {
    suspend fun analyze(latex: String): MathAnalysis
    suspend fun execute(latex: String, actionId: String): MathOperationResult

    /**
     * Runs the first of [preferred] that applies to [latex], or returns null when none does.
     *
     * [analyze] then [execute], which is all this does unless an engine can do both in one pass.
     */
    suspend fun calculate(latex: String, preferred: List<String>): MathOperationResult? {
        val analysis = analyze(latex)
        analysis.error?.let { return MathOperationResult(error = it) }
        val action = preferred.firstOrNull { id -> analysis.actions.any { it.id == id } } ?: return null
        return execute(latex, action)
    }

    /**
     * Pays the engine's first-use costs ahead of the first formula. Best effort: an engine that
     * fails here fails again on the formula, where the error is shown.
     */
    suspend fun warmUp() {}
}

/**
 * The math engine could not run at all — not installed, or its process failed — as opposed to
 * SymPy answering that it could not read a formula, which arrives as an `error` in the result.
 */
class MathEngineUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
