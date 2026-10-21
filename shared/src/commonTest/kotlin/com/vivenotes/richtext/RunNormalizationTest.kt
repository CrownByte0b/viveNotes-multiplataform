package com.vivenotes.richtext

import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Run
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RunNormalizationTest {
    @Test
    fun maximalRunsKeepMarksAndAtomicEquationsAcrossEmptyPieces() {
        val plain = List(10_000) { Run("word ") }
        assertEquals(listOf(Run("word ".repeat(10_000))), normalizedRuns(plain))
        val equation = Mark.Equation("x")
        val runs = listOf(Run("a"), Run(""), Run("b"), Run("x", setOf(equation)),
            Run("y", setOf(equation)), Run("bold", setOf(Mark.Bold)))
        assertEquals(listOf(Run("ab"), runs[3], runs[4], runs[5]), normalizedRuns(runs))
        assertEquals(listOf(Run("ab"), Run("xy", setOf(equation)), runs[5]), normalizedRuns(runs, true))
        assertSame(runs.last(), normalizedRuns(runs).last())
    }

    @Test
    fun normalizingRandomRunsMatchesExistingMergeSemantics() {
        val random = Random(745)
        val marks = listOf(emptySet(), setOf(Mark.Bold), setOf(Mark.Italic), setOf(Mark.Equation("x")))
        repeat(300) {
            val runs = List(random.nextInt(100)) { Run("x".repeat(random.nextInt(4)), marks.random(random)) }
            for (equations in listOf(false, true)) {
                val original = mutableListOf<Run>()
                for (run in runs) {
                    if (run.text.isEmpty()) continue
                    val last = original.lastOrNull()
                    if (last != null && last.marks == run.marks &&
                        (equations || last.marks.none { it is Mark.Equation })) {
                        original[original.lastIndex] = last.copy(text = last.text + run.text)
                    } else original += run
                }
                assertEquals(original, normalizedRuns(runs, equations))
            }
        }
    }

    @Test
    fun indexedCaretLocationsKeepEmptyLinesAndAtomicEquationOffsets() {
        val buffer = RichTextBuffer(listOf(Block("one", runs = listOf(Run("ab"))),
            Block("empty"), Block("equation", runs = listOf(Run("long latex", setOf(Mark.Equation("x^2")))))))
        assertEquals("ab\n\n\uFFFC", buffer.text)
        for ((offset, id) in listOf(0 to "one", 2 to "one", 3 to "empty", 4 to "equation", 5 to "equation")) {
            assertEquals(id, buffer.select(TextSelection(offset)).currentBlock.id)
        }
        assertEquals("changed", buffer.copy(blocks = listOf(Block("other", runs = listOf(Run("changed"))))).text)
    }
}
