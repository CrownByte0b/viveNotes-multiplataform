package com.vivenotes.search

import com.vivenotes.model.search.ContentKind
import com.vivenotes.model.search.ContentUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PreparedSearchChunksTest {
    private fun units(text: String) = listOf(ContentUnit("page", "section", ContentKind.Text, "box", text = text))

    @Test
    fun preparedSourcesSupportNewQueriesAndExplicitEvictionRecovery() {
        val cache = PreparedSearchChunks(maxChunks = 1)
        assertTrue(cache.match(WorkerRequest(SearchOperation.Match, matchKey = "first", query = "alpha")).cacheMiss)
        val source = units("alpha beta")
        val first = cache.match(WorkerRequest(SearchOperation.Match, units = source, matchKey = "first", query = "alpha"))
        assertEquals(0, first.hits.single().spans.single().start)
        val warm = cache.match(WorkerRequest(SearchOperation.Match, matchKey = "first", query = "beta"))
        assertFalse(warm.cacheMiss)
        assertEquals(6, warm.hits.single().spans.single().start)
        cache.match(WorkerRequest(SearchOperation.Match, units = units("other"), matchKey = "second", query = "other"))
        assertTrue(cache.match(WorkerRequest(SearchOperation.Match, matchKey = "first", query = "alpha")).cacheMiss)
        assertEquals(first.hits, cache.match(WorkerRequest(SearchOperation.Match,
            units = source, matchKey = "first", query = "alpha")).hits)
    }

    @Test
    fun sourceKeysFollowExactProjectionAndEnforceCharacterBudget() {
        val keys = SearchChunkKeys(maxCharacters = 12, maxChunks = 2)
        val first = keys.keyFor(units("alpha beta"))
        assertEquals(first, keys.keyFor(units("alpha beta")))
        assertNotEquals(first, keys.keyFor(units("alpha gamma")))
        assertNotEquals(first, keys.keyFor(units("alpha beta")))
        val cache = PreparedSearchChunks(maxCharacters = 2)
        val request = WorkerRequest(SearchOperation.Match, units = units("alpha"), query = "alpha", matchKey = "large")
        assertEquals(1, cache.match(request).hits.size)
        assertTrue(cache.match(request.copy(units = emptyList())).cacheMiss)
    }
}
