package com.vivenotes.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The ByteLevel BPE decoding both formula models share. */
class FormulaTokenizerDecoderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    /** Android's `byteLevelTokenizerDecodesOnlyRequestedFormulaTokens`. */
    @Test
    fun byteLevelTokenizerDecodesOnlyRequestedFormulaTokens() {
        val tokenizer = temporary.newFile("minimal-formula-tokenizer.json")
        tokenizer.writeText(
            """{"added_tokens":[{"id":0,"special":true},{"id":2,"special":true}],"model":{"vocab":{"<s>":0,"</s>":2,"Ġx":4}}}""",
        )

        val decoded = FormulaTokenizerDecoder.decode(tokenizer, listOf(0, 4, 2))

        assertEquals("x", decoded)
    }

    /** Decoding stops at `</s>`, whatever the model emitted after it, and pads are dropped. */
    @Test
    fun decodingStopsAtTheEndTokenAndDropsPadding() {
        val tokenizer = temporary.newFile("tokenizer.json")
        tokenizer.writeText(
            """{"added_tokens":[{"id":0,"special":true},{"id":1,"special":true},{"id":2,"special":true}],""" +
                """"model":{"vocab":{"<s>":0,"<pad>":1,"</s>":2,"x":5,"Ġ^":6,"Ġ2":7,"Ġ=":8}}}""",
        )

        val decoded = FormulaTokenizerDecoder.decode(tokenizer, listOf(0, 5, 1, 6, 7, 2, 8, 8))

        assertEquals("x ^ 2", decoded)
    }

    /** A token the vocabulary does not have means the tokenizer belongs to another model. */
    @Test
    fun aTokenMissingFromTheVocabularyIsRefused() {
        val tokenizer = temporary.newFile("other.json")
        tokenizer.writeText("""{"added_tokens":[],"model":{"vocab":{"x":5}}}""")

        val failure = runCatching { FormulaTokenizerDecoder.decode(tokenizer, listOf(5, 9)) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("Formula tokenizer does not match model", failure?.message)
    }

    @Test
    fun nothingButControlTokensDecodesToEmpty() {
        val tokenizer = temporary.newFile("unused.json")
        assertEquals("", FormulaTokenizerDecoder.decode(tokenizer, listOf(0, 1, 2)))
    }
}
