package com.vivenotes.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded by entries and encoded text size; identity is the complete immutable stored body. */
internal class DecodedDocumentCache(
    private val maxEntries: Int = 8,
    private val maxCharacters: Int = 4 * 1024 * 1024,
) {
    private data class Key(val format: String, val body: String)
    private val mutex = Mutex()
    private val entries = LinkedHashMap<Key, PageLoad>()
    private var characters = 0

    suspend fun get(format: String, body: String, decode: () -> PageLoad): PageLoad = mutex.withLock {
        val key = Key(format, body)
        entries.remove(key)?.let { cached ->
            entries[key] = cached
            return@withLock cached
        }
        val value = decode()
        if (body.length > maxCharacters || maxEntries == 0) return@withLock value
        entries[key] = value
        characters += body.length
        while (entries.size > maxEntries || characters > maxCharacters) {
            val oldest = entries.keys.first()
            characters -= oldest.body.length
            entries.remove(oldest)
        }
        value
    }
}
