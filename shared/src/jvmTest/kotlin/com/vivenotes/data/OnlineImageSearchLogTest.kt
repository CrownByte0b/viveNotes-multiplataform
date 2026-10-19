package com.vivenotes.data

import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The opt-in `--debug` log of Search online: which source answered and how, never what was asked. */
class OnlineImageSearchLogTest {

    @Test
    fun theLogFollowsTheHandOverWithoutTheWordsSearched() = runBlocking {
        val lines = mutableListOf<String>()
        val search = OnlineImageSearch(
            commons = { _, _ -> SourceResult.Unreachable },
            openverse = { _, _ -> SourceResult.Page(emptyList(), hasMore = false, onTopic = 0) },
            log = DebugLog(enabled = true, output = { lines += it }),
        )

        search.search("private diary sketch", null)

        assertEquals(
            listOf(
                "ViveNotes DEBUG [pictures] commons Unreachable; asking openverse",
                "ViveNotes DEBUG [pictures] openverse page=1 results=0 more=false",
            ),
            lines,
        )
        assertTrue(lines.none { "diary" in it })
    }

    @Test
    fun aSilentLogWritesNothing() = runBlocking {
        val lines = mutableListOf<String>()
        OnlineImageSearch(
            commons = { _, _ -> SourceResult.RateLimited },
            openverse = { _, _ -> SourceResult.RateLimited },
            log = DebugLog(enabled = false, output = { lines += it }),
        ).search("fox", null)
        assertTrue(lines.isEmpty())
    }
}
