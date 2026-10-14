package com.vivenotes.ui.ribbon.draw

import com.vivenotes.math.MathEngine
import com.vivenotes.math.MathOperationResult



data class CalculatorAnswer(
    
    val latex: String,
    val result: MathOperationResult? = null,
    val error: String? = null,
)


internal suspend fun calculate(recognized: String, engine: MathEngine): CalculatorAnswer {
    val latex = withoutTrailingEquals(recognized)
    val result = engine.calculate(latex, AUTOMATIC_MATH_ACTIONS)
        ?: return CalculatorAnswer(latex, error = NOTHING_TO_RUN)
    return CalculatorAnswer(latex, result = result.takeIf { it.error == null }, error = result.error)
}


internal fun withoutTrailingEquals(latex: String): String = latex.trimEnd().removeSuffix("=").trimEnd()

internal const val NOTHING_TO_RUN = "Nothing to calculate automatically. Open it in the panel for every action."
internal val AUTOMATIC_MATH_ACTIONS = listOf("solve", "evaluate", "simplify")
