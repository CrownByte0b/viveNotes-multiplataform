package com.vivenotes.data

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** VACUUM INTO reads a consistent source snapshot without occupying Room's sole writer lease. */
internal suspend fun NotesDatabase.copyConsistentlyTo(target: File) {
    val source = storageFile
    if (source == null) {
        useWriterConnection { connection -> connection.usePrepared("VACUUM INTO ?") { statement ->
            statement.bindText(1, target.absolutePath); statement.step()
        } }
        return
    }
    useReaderConnection { connection -> connection.usePrepared("SELECT 1") { it.step() } }
    withContext(Dispatchers.IO) {
        BundledSQLiteDriver().open(source.absolutePath).use { connection ->
            connection.execSQL("PRAGMA busy_timeout = 10000")
            connection.prepare("VACUUM INTO ?").use { statement ->
                statement.bindText(1, target.absolutePath); statement.step()
            }
        }
    }
}
