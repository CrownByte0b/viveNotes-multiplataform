package com.vivenotes.search

import com.vivenotes.model.search.ContentUnit
import com.vivenotes.model.search.FuzzyMatcher
import com.vivenotes.model.search.MAX_HITS
import com.vivenotes.model.search.searchContent

internal fun List<ContentUnit>.searchCharacters(): Int = sumOf { unit ->
    unit.text.length + unit.alternatives.sumOf { it.length }
}

/** Worker-local bounded source projections. A lost/evicted process explicitly asks for the source. */
internal class PreparedSearchChunks(private val maxCharacters: Int = 2_000_000, private val maxChunks: Int = 128) {
    private class Chunk(val units: List<ContentUnit>) {
        val characters = units.searchCharacters()
        val prepared = buildMap {
            units.forEach { unit ->
                (listOf(unit.text) + unit.alternatives).forEach { text ->
                    if (text !in this) put(text, FuzzyMatcher.prepareText(text))
                }
            }
        }
    }
    private val chunks = LinkedHashMap<String, Chunk>(16, .75f, true)
    private var characters = 0

    fun match(request: WorkerRequest): WorkerReply {
        val chunk = when {
            request.units.isNotEmpty() || request.matchKey.isEmpty() -> Chunk(request.units).also { next ->
                if (request.matchKey.isNotEmpty() && next.characters <= maxCharacters) {
                    characters -= chunks.put(request.matchKey, next)?.characters ?: 0
                    characters += next.characters
                    while (characters > maxCharacters || chunks.size > maxChunks) {
                        characters -= chunks.remove(chunks.keys.first())!!.characters
                    }
                }
            }
            else -> chunks[request.matchKey] ?: return WorkerReply(cacheMiss = true)
        }
        return WorkerReply(hits = searchContent(chunk.units, request.query, MAX_HITS + 1) { text ->
            chunk.prepared.getValue(text)
        })
    }
}

internal class SearchChunkKeys(private val maxCharacters: Int = 2_000_000, private val maxChunks: Int = 128) {
    private val keys = LinkedHashMap<List<ContentUnit>, String>(16, .75f, true)
    private var sequence = 0L
    private var characters = 0

    @Synchronized fun keyFor(units: List<ContentUnit>): String {
        keys[units]?.let { return it }
        val key = (++sequence).toString()
        val weight = units.searchCharacters()
        if (weight <= maxCharacters) {
            keys[units.toList()] = key
            characters += weight
            while (characters > maxCharacters || keys.size > maxChunks) {
                val first = keys.keys.first()
                characters -= first.searchCharacters()
                keys.remove(first)
            }
        }
        return key
    }
}
