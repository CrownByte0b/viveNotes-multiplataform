package com.vivenotes.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals

class FormulaTokenizerCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun source(token: String) =
        """{"added_tokens":[{"id":0,"special":true},{"id":2,"special":true}],"model":{"vocab":{"<s>":0,"</s>":2,"$token":5}}}"""

    @Test
    fun concurrentWarmDecodesShareImmutableVocabularyAndPreserveUnicode() = runBlocking {
        val tokenizer = temporary.newFile("tokenizer.json").apply { writeText(source("ĠCafÃ©")) }
        val decoded = List(24) { async(Dispatchers.Default) {
            FormulaTokenizerDecoder.decode(tokenizer, listOf(0, 5, 1, 2, 5))
        } }.awaitAll()
        assertEquals(List(24) { "Café" }, decoded)
    }

    @Test
    fun replacingSlotWithSameLengthAndTimestampDoesNotReuseOldFileIdentity() {
        val tokenizer = temporary.newFile("tokenizer.json").apply { writeText(source("x")) }
        assertEquals("x", FormulaTokenizerDecoder.decode(tokenizer, listOf(5, 2)))
        val oldTime = Files.getLastModifiedTime(tokenizer.toPath())
        val replacement = temporary.newFile("replacement.json").apply { writeText(source("y")) }
        Files.setLastModifiedTime(replacement.toPath(), oldTime)
        Files.move(replacement.toPath(), tokenizer.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertEquals("y", FormulaTokenizerDecoder.decode(tokenizer, listOf(5, 2)))
    }

    @Test
    fun explicitInstallGenerationInvalidationHandlesSameFileAndMetadata() {
        val tokenizer = temporary.newFile("tokenizer.json").apply { writeText(source("x")) }
        assertEquals("x", FormulaTokenizerDecoder.decode(tokenizer, listOf(5, 2)))
        val oldTime = Files.getLastModifiedTime(tokenizer.toPath())
        tokenizer.writeText(source("y"))
        Files.setLastModifiedTime(tokenizer.toPath(), oldTime)
        FormulaTokenizerDecoder.invalidate(tokenizer)
        assertEquals("y", FormulaTokenizerDecoder.decode(tokenizer, listOf(5, 2)))
    }
}
