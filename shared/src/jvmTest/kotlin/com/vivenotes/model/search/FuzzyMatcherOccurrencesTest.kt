package com.vivenotes.model.search

import org.junit.Test
import org.junit.Assert.*

class FuzzyMatcherOccurrencesTest {

    private fun occurrences(query: String, text: String): List<String> =
        FuzzyMatcher.occurrences(query, text).map { text.substring(it.start, it.end) }

    @Test
    fun `occurrences finds every exact occurrence of a term`() {
        assertEquals(listOf("cat", "cat", "cat"), occurrences("cat", "cat concatenate cat"))
    }

    @Test
    fun `occurrences ignores case and reports the original text`() {
        assertEquals(listOf("Cat", "CAT"), occurrences("cat", "Cat and CAT"))
    }

    @Test
    fun `occurrences reports each term of a query`() {
        assertEquals(listOf("red", "fox", "red"), occurrences("red fox", "red fox and red hen"))
    }

    @Test
    fun `occurrences folds touching matches into one span`() {
        assertEquals(listOf("catdog"), occurrences("cat dog", "catdog"))
    }

    @Test
    fun `occurrences falls back to the fuzzy span when a term is not exactly there`() {
        // One typo in a six-letter term: `match` finds this line, so a box has to go somewhere.
        assertEquals(listOf("invoice"), occurrences("invoce", "the invoice total"))
    }

    @Test
    fun `occurrences is empty when a term is not on the line`() {
        assertEquals(emptyList<String>(), occurrences("red zebra", "red fox"))
    }

    @Test
    fun `occurrences accepts exactly the lines match accepts`() {
        val lines = listOf(
            "the quick brown fox",
            "invoice total due",
            "cntr holds nothing",
            "container",
            "",
        )
        val queries = listOf("the", "invioc", "cntr", "fox quick", "zebra", "a b", "   ")
        queries.forEach { query ->
            lines.forEach { line ->
                assertEquals(
                    "query '$query' on line '$line'",
                    FuzzyMatcher.match(query, line) != null,
                    FuzzyMatcher.occurrences(query, line).isNotEmpty(),
                )
            }
        }
    }
}
