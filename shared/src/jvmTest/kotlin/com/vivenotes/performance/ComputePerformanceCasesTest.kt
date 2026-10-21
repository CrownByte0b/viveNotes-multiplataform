package com.vivenotes.performance

import com.vivenotes.ai.FormulaTokenizerDecoder
import com.vivenotes.model.BlockType
import com.vivenotes.richtext.pastedHtmlBlocks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComputePerformanceCasesTest {
    @Test
    fun htmlFixturesKeepOneParagraphAndExactWordBoundariesAtMeasuredSizes() {
        for (words in listOf(250, 1_000, 4_000)) {
            val fixture = performanceHtmlParagraph(words)
            val block = assertNotNull(pastedHtmlBlocks(fixture.html)).single()
            assertEquals(BlockType.Paragraph, block.type)
            assertEquals(List(words) { "word" }, block.text.split(' '))
            assertEquals(fixture.text, block.runs.single().text)
            assertTrue(block.runs.single().marks.isEmpty())
            assertEquals(fixture, performanceHtmlParagraph(words))
        }
        assertFailsWith<IllegalArgumentException> { performanceHtmlParagraph(0) }
    }

    @Test
    fun tokenizerFixtureHasFullVocabularyMetadataAndStableUnicodeResult() {
        for (vocabularySize in listOf(1_000, 10_000, 50_000)) {
            val fixture = performanceFormulaTokenizer(vocabularySize)
            val root = Json.parseToJsonElement(fixture.json).jsonObject
            val model = root.getValue("model").jsonObject
            val vocabulary = model.getValue("vocab").jsonObject
            assertEquals(vocabularySize, vocabulary.size)
            assertEquals((0 until vocabularySize).toSet(), vocabulary.values.map { it.jsonPrimitive.int }.toSet())
            assertEquals(vocabularySize - 11, model.getValue("merges").jsonArray.size)
            assertEquals(listOf(0, 1, 2), root.getValue("added_tokens").jsonArray.map {
                it.jsonObject.getValue("id").jsonPrimitive.int
            })
            withPerformanceTokenizerFile(fixture) { file ->
                assertEquals(fixture.json, file.readText(Charsets.UTF_8))
                assertEquals(fixture.expected, FormulaTokenizerDecoder.decode(file, fixture.tokenIds))
                val lastId = (vocabularySize - 1).toLong()
                assertEquals("symbol${lastId.toString().padStart(5, '0')}",
                    FormulaTokenizerDecoder.decode(file, listOf(0, lastId, 2)))
            }
        }
        assertEquals(performanceFormulaTokenizer(100).json, performanceFormulaTokenizer(100).json)
        assertFailsWith<IllegalArgumentException> { performanceFormulaTokenizer(9) }
        assertFailsWith<IllegalArgumentException> { performanceFormulaTokenizer(50_001) }
    }

    @Test
    fun tokenizerFilesAreRemovedAfterSuccessAndFailure() {
        val fixture = performanceFormulaTokenizer(100)
        lateinit var successful: File
        withPerformanceTokenizerFile(fixture) { file ->
            successful = file
            assertTrue(file.isFile)
        }
        assertFalse(successful.parentFile.exists())

        lateinit var failed: File
        assertFailsWith<IllegalStateException> {
            withPerformanceTokenizerFile(fixture) { file ->
                failed = file
                error("fixture failure")
            }
        }
        assertFalse(failed.parentFile.exists())
    }
}
