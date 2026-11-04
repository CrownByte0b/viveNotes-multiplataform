package com.vivenotes.ui.ribbon.draw

import com.vivenotes.math.MathEngine
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.InkPage
import kotlinx.coroutines.awaitCancellation

interface DrawMath {
    val engine: MathEngine
    /** [lines] are the line and arrow shapes selected with [ink], such as a fraction bar. */
    suspend fun readFormula(ink: InkPage, lines: List<Outline.Shape> = emptyList()): String
    suspend fun holdFormulaModel(): Nothing = awaitCancellation()
}
