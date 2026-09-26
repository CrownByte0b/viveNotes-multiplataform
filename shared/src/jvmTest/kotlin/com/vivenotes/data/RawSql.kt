package com.vivenotes.data

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import com.vivenotes.data.db.NotesDatabase

/**
 * Raw SQL for tests, standing in for the Android suites' `openHelper.writableDatabase.execSQL` and
 * `query` calls: a way to damage or inspect rows underneath the repository.
 */
internal suspend fun NotesDatabase.execute(sql: String, vararg arguments: Any?) {
    useWriterConnection { connection ->
        connection.usePrepared(sql) { statement ->
            statement.bindAll(arguments)
            statement.step()
        }
    }
}

/** The first column of every row, as text. */
internal suspend fun NotesDatabase.strings(sql: String, vararg arguments: Any?): List<String?> =
    useReaderConnection { connection ->
        connection.usePrepared(sql) { statement ->
            statement.bindAll(arguments)
            buildList {
                while (statement.step()) add(if (statement.isNull(0)) null else statement.getText(0))
            }
        }
    }

/** The first column of the first row, as a number. */
internal suspend fun NotesDatabase.long(sql: String, vararg arguments: Any?): Long =
    useReaderConnection { connection ->
        connection.usePrepared(sql) { statement ->
            statement.bindAll(arguments)
            check(statement.step()) { "no row for $sql" }
            statement.getLong(0)
        }
    }

internal suspend fun NotesDatabase.rowCount(table: String, column: String, id: String): Int =
    long("SELECT COUNT(*) FROM $table WHERE $column = ?", id).toInt()

private fun SQLiteStatement.bindAll(arguments: Array<out Any?>) {
    arguments.forEachIndexed { offset, argument ->
        val index = offset + 1
        when (argument) {
            null -> bindNull(index)
            is String -> bindText(index, argument)
            is Long -> bindLong(index, argument)
            is Int -> bindLong(index, argument.toLong())
            is ByteArray -> bindBlob(index, argument)
            else -> error("cannot bind ${argument::class}")
        }
    }
}
