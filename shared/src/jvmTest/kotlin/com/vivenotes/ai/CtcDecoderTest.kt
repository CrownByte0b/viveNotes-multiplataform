package com.vivenotes.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CtcDecoderTest {
    private val alphabet = listOf("", "é", "你", "🛅", " ")

    @Test
    fun multilingualTokensStayWholeAndRepeatsCollapseOnlyWithoutABlank() {
        val ids = listOf(1, 1, 0, 1, 2, 2, 0, 3, 4, 4, 0)
        val logits = ids.map { id -> FloatArray(alphabet.size) { if (it == id) 0.9f else 0.01f } }.toTypedArray()

        val result = decodeCtc(logits, alphabet)

        assertEquals("éé你🛅 ", result.text)
        assertEquals(0.9f, result.confidence, 0.0001f)
    }

    @Test
    fun aMismatchedDictionaryFailsInsteadOfDroppingCharacters() {
        for (classes in listOf(alphabet.size - 1, alphabet.size + 1)) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                decodeCtc(arrayOf(FloatArray(classes) { 0.9f }), alphabet)
            }
            assertEquals("OCR dictionary does not match model", failure.message)
        }
    }

    @Test
    fun blankOnlyAndEmptySequencesHaveNoTextOrConfidence() {
        val blanks = Array(3) { FloatArray(alphabet.size) { if (it == 0) 1f else 0f } }
        assertEquals(TextRecognitionResult("", 0f), decodeCtc(blanks, alphabet))
        assertEquals(TextRecognitionResult("", 0f), decodeCtc(emptyArray(), alphabet))
    }
}
