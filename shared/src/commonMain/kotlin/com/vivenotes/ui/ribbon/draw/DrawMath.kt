package com.vivenotes.ui.ribbon.draw

import com.vivenotes.math.MathEngine
import com.vivenotes.model.ink.InkPage
import kotlinx.coroutines.awaitCancellation

interface DrawMath {
    val engine: MathEngine
    suspend fun readFormula(ink: InkPage): String
    suspend fun holdFormulaModel(): Nothing = awaitCancellation()
}
