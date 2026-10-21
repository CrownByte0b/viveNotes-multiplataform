package com.vivenotes.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.execSQL
import java.io.File

/** A database-owned auxiliary reader. Its data_version counter never crosses connection identity. */
internal class SearchBodySnapshots(private val file: File) {
    private val lock = Any()
    private val bundled = BundledSQLiteDriver()
    private var reader: SQLiteConnection? = null
    private var closed = false
    private var version: Long? = null
    private var cachedIds: List<String>? = null
    private var cachedBodies: List<PageContentEntity>? = null

    val driver: SQLiteDriver = object : SQLiteDriver by bundled {
        override fun open(fileName: String): SQLiteConnection {
            val connection = bundled.open(fileName)
            return object : SQLiteConnection by connection {
                override fun close() {
                    // Room.close is final. Physical pool shutdown owns this independent handle too.
                    this@SearchBodySnapshots.close()
                    connection.close()
                }
            }
        }
    }

    fun read(pageIds: List<String>): List<PageContentEntity> = synchronized(lock) {
        check(!closed) { "Database is closed" }
        val connection = reader ?: bundled.open(file.absolutePath, SQLITE_OPEN_READONLY).also {
            reader = it
            it.execSQL("PRAGMA busy_timeout = 10000")
        }
        connection.execSQL("BEGIN DEFERRED TRANSACTION")
        try {
            // Establish the read snapshot before pairing its data_version with cached body rows.
            connection.prepare("SELECT rootpage FROM sqlite_schema LIMIT 1").use { it.step() }
            val currentVersion = connection.prepare("PRAGMA data_version").use { statement ->
                check(statement.step())
                statement.getLong(0)
            }
            val previous = cachedBodies
            val result = if (currentVersion == version && pageIds == cachedIds && previous != null) previous else {
                val rows = pageIds.chunked(400).flatMap { ids ->
                    connection.prepare("SELECT pageId, docJson, updatedAt, format FROM page_content WHERE pageId IN (" +
                        ids.joinToString(",") { "?" } + ")").use { statement ->
                        ids.forEachIndexed { index, id -> statement.bindText(index + 1, id) }
                        buildList {
                            while (statement.step()) add(PageContentEntity(statement.getText(0), statement.getText(1),
                                statement.getLong(2), statement.getText(3)))
                        }
                    }
                }
                version = currentVersion
                if (rows.sumOf { it.docJson.length.toLong() } <= MAX_BODY_CHARACTERS) {
                    cachedIds = pageIds.toList()
                    cachedBodies = rows
                } else {
                    cachedIds = null
                    cachedBodies = null
                }
                rows
            }
            connection.execSQL("COMMIT TRANSACTION")
            result
        } catch (failure: Throwable) {
            runCatching { connection.execSQL("ROLLBACK TRANSACTION") }
            // An interrupted read never installs a reusable snapshot.
            version = null
            cachedIds = null
            cachedBodies = null
            throw failure
        }
    }

    private fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            reader?.close()
            reader = null
            cachedIds = null
            cachedBodies = null
        }
    }

    private companion object { const val MAX_BODY_CHARACTERS = 8L * 1024 * 1024 }
}
