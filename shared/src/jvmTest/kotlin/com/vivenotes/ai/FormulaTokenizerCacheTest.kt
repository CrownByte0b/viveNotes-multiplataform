package com.vivenotes.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

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
        assertEquals(tokenizer.length(), replacement.length())
        Files.setLastModifiedTime(replacement.toPath(), oldTime)
        Files.move(replacement.toPath(), tokenizer.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertEquals(source("y"), tokenizer.readText())
        assertEquals(oldTime, Files.getLastModifiedTime(tokenizer.toPath()))
        assertEquals("y", FormulaTokenizerDecoder.decode(tokenizer, listOf(5, 2)))
    }

    @Test
    fun providerWithoutFileKeysDetectsReplacementWhenEveryOtherAttributeMatches() {
        val tokenizer = temporary.newFile("tokenizer.json").apply { writeText(source("x")) }
        val attributes = Files.readAttributes(tokenizer.toPath(), BasicFileAttributes::class.java)
        val unidentified = object : BasicFileAttributes by attributes {
            override fun fileKey(): Any? = null
        }
        var reads = 0
        val cache = FormulaTokenizerDecoder.VocabularyCache(
            readAttributes = { unidentified },
            readBytes = { reads++; Files.readAllBytes(it) },
        )
        val original = cache.vocabulary(tokenizer)
        assertEquals("x", original.tokens[5])
        assertEquals(setOf(0, 2), original.specialIds)
        assertEquals(1, reads)

        val replacementSource = source("y").replace("\"id\":0", "\"id\":1").replace("\"y\":5", "\"y\":6")
        val replacement = temporary.newFile("replacement.json").apply { writeText(replacementSource) }
        assertEquals(tokenizer.length(), replacement.length())
        Files.setLastModifiedTime(replacement.toPath(), attributes.lastModifiedTime())
        Files.move(replacement.toPath(), tokenizer.toPath(), StandardCopyOption.REPLACE_EXISTING)

        val updated = cache.vocabulary(tokenizer)
        assertNotSame(original, updated)
        assertFalse(5 in updated.tokens)
        assertEquals("y", updated.tokens[6])
        assertEquals(setOf(1, 2), updated.specialIds)
        assertEquals(2, reads)
        assertSame(updated, cache.vocabulary(tokenizer))
        assertEquals(3, reads)
    }

    @Test
    fun providerWithFileKeysKeepsWarmMetadataFastPathAndExplicitInvalidation() {
        val tokenizer = temporary.newFile("tokenizer.json").apply { writeText(source("x")) }
        val attributes = Files.readAttributes(tokenizer.toPath(), BasicFileAttributes::class.java)
        val identified = object : BasicFileAttributes by attributes {
            override fun fileKey(): Any = "stable-file-key"
        }
        var reads = 0
        val cache = FormulaTokenizerDecoder.VocabularyCache(
            readAttributes = { identified },
            readBytes = { reads++; Files.readAllBytes(it) },
        )
        val original = cache.vocabulary(tokenizer)
        assertSame(original, cache.vocabulary(tokenizer))
        assertEquals(1, reads)
        cache.invalidate(tokenizer)
        assertNotSame(original, cache.vocabulary(tokenizer))
        assertEquals(2, reads)
    }

    @Test
    fun providerWithoutFileKeysStillEvictsLeastRecentlyUsedVocabulary() {
        val cache = FormulaTokenizerDecoder.VocabularyCache(readAttributes = { path ->
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            object : BasicFileAttributes by attributes {
                override fun fileKey(): Any? = null
            }
        })
        val tokenizers = List(4) { index ->
            temporary.newFile("tokenizer-$index.json").apply { writeText(source("$index")) }
        }
        val first = cache.vocabulary(tokenizers[0])
        val second = cache.vocabulary(tokenizers[1])
        cache.vocabulary(tokenizers[2])
        assertSame(first, cache.vocabulary(tokenizers[0]))
        cache.vocabulary(tokenizers[3])
        assertSame(first, cache.vocabulary(tokenizers[0]))
        assertNotSame(second, cache.vocabulary(tokenizers[1]))
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
