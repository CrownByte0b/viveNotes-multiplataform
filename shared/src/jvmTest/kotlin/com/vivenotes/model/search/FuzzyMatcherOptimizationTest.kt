package com.vivenotes.model.search

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class FuzzyMatcherOptimizationTest {
    @Test
    fun preparedAndDirectMatchesRetainOriginalScoresSpansAndUnicodeOffsets() {
        val random = Random(391)
        val fragments = listOf("a", "container", "concatenate", "cat", "abcdefx", "café", "İ", "", "aa", "x-y", "12")
        val queries = listOf("a", "cat", "contn", "cntr", "abcdefg", "café", "İ", "a cat", "x-y", "a a", " ")
        repeat(2_000) {
            val text = List(random.nextInt(1, 20)) { fragments.random(random) }.joinToString(" ")
            val query = queries.random(random)
            val expected = OriginalFuzzyMatcher.match(query, text)
            assertEquals(expected, FuzzyMatcher.match(query, text), "$query / $text")
            assertEquals(expected, FuzzyMatcher.match(FuzzyMatcher.prepareQuery(query), FuzzyMatcher.prepareText(text)))
        }
    }

    @Test
    fun repeatedOccurrencesAndLongTypoSuffixesKeepEarliestBestOccurrence() {
        val examples = listOf("a" to "a ".repeat(10_000), "cat" to "concatenate cat cat",
            "abcdefg" to ("abcdefx" + "z".repeat(20_000)), "contn" to "container",
            "abcd" to "ab abcd", "abcdefg" to "abcde abcdefxy", "." to "a... b..")
        for ((query, text) in examples) assertEquals(OriginalFuzzyMatcher.match(query, text), FuzzyMatcher.match(query, text))
    }
}
