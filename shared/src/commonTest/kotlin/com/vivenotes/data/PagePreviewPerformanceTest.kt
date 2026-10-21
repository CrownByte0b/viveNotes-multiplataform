package com.vivenotes.data

import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.Run
import com.vivenotes.model.newTable
import com.vivenotes.model.plainText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class PagePreviewPerformanceTest {
    private fun reference(doc: PageDoc): String = doc.plainText().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(140)

    @Test fun streamedPreviewPreservesWhitespaceEquationsTablesAndUtf16Truncation() {
        val texts = listOf("", " \t", "\r\n ", "\n\r\nfirst\rnext", " ".repeat(160) + "x", "λ😀".repeat(90), "\u2003\nlast")
        for (value in texts) {
            var id = 0
            val table = newTable(2, 2, newId = { "id-${id++}" }).let { table ->
                table.copy(rows = table.rows.map { row -> row.copy(cells = row.cells.map { cell ->
                    cell.copy(blocks = listOf(Block("${cell.id}-b", runs = listOf(Run(value)))))
                }) })
            }
            for (outline in listOf(Outline.Text("text", blocks = listOf(Block("b", runs = listOf(Run(value))))),
                table, Outline.Equation("equation", latex = value))) {
                val doc = PageDoc(outlines = listOf(Outline.Text("empty", blocks = emptyList()), outline))
                assertEquals(reference(doc), pagePreview(doc), value)
            }
        }
        val equation = PageDoc(outlines = listOf(Outline.Text("text", blocks = listOf(
            Block("first", runs = listOf(Run("  "), Run("placeholder", setOf(Mark.Equation("x+y\nnext")))))))))
        assertEquals(reference(equation), pagePreview(equation))
    }

    @Test fun randomizedRunAndParagraphBoundariesMatchOriginalFlattenedRule() {
        val random = Random(8791)
        val chunks = listOf("", "\r", "\n", " ", "\t", "word", "λ", "😀", "x\ny")
        repeat(300) { attempt ->
            val doc = PageDoc(outlines = List(random.nextInt(1, 8)) { outline ->
                Outline.Text("o-$outline", blocks = List(random.nextInt(0, 8)) { paragraph ->
                    Block("b-$paragraph", runs = List(random.nextInt(0, 8)) { Run(chunks.random(random)) })
                })
            })
            assertEquals(reference(doc), pagePreview(doc), "fixture $attempt")
        }
    }

    @Test fun knownFirstLineDoesNotReadAnyLaterOutline() {
        val outlines = object : AbstractList<Outline>() {
            override val size = 2
            override fun get(index: Int): Outline {
                check(index == 0) { "unnecessary later document content read" }
                return Outline.Text("first", blocks = listOf(Block("b", runs = listOf(Run("first line\nmore")))))
            }
        }
        assertEquals("first line", pagePreview(PageDoc(outlines = outlines)))
    }
}
