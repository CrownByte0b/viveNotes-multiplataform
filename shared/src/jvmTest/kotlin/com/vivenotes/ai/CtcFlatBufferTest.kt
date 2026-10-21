package com.vivenotes.ai

import java.nio.FloatBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CtcFlatBufferTest {
    @Test
    fun flatDecoderMatchesArrayDecoderForTiesBlanksUnicodeAndSpecialFloats() {
        val alphabet = listOf("", "é", "🛅", " ")
        val random = Random(91)
        val scores = listOf(0f, .5f, 1f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NaN)
        repeat(500) {
            val rows = Array(random.nextInt(30)) { FloatArray(alphabet.size) { scores.random(random) } }
            val data = FloatArray(rows.size * alphabet.size + 2)
            rows.forEachIndexed { row, values -> values.copyInto(data, row * alphabet.size + 1) }
            val buffer = FloatBuffer.wrap(data).apply { position(1); limit(data.lastIndex) }
            val result = decodeCtc(buffer, rows.size, alphabet.size, alphabet)
            assertEquals(decodeCtc(rows, alphabet), result)
            assertEquals(1, buffer.position())
            assertEquals(data.lastIndex, buffer.limit())
        }
    }

    @Test
    fun malformedFlatShapesAndDictionaryMismatchAreRefused() {
        assertFailsWith<IllegalArgumentException> { decodeCtc(FloatBuffer.allocate(4), 2, 3, listOf("", "a", "b")) }
        assertFailsWith<IllegalArgumentException> { decodeCtc(FloatBuffer.allocate(4), 2, 2, listOf("")) }
        assertEquals(TextRecognitionResult("", 0f), decodeCtc(FloatBuffer.allocate(0), 0, 2, listOf("", "a")))
    }
}
