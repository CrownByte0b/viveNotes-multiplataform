package com.vivenotes.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The desktop database is the Android one — the Android `MigrationTest`, plus the fixture that
 * makes it a cross-platform promise.
 *
 * `android-baseline-<version>.json` are the Android app's committed `app/schemas/…/<version>.json`,
 * frozen as fixtures rather than read from `shared/schemas/`, because the build rewrites that export
 * from the entities and so can never disagree with them. A `.vive` bundle is a copy of these tables,
 * and Android validates it column by column, so this is the compatibility contract for transfer too.
 *
 * Each schema change adds its migration case here, as it does on Android.
 */
class SchemaCompatibilityTest {

    private val directory: File = Files.createTempDirectory("schema-compatibility").toFile()

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    /**
     * A database built from nothing but the Android baseline — its tables, indices and the identity
     * hash Room writes — opens as this build's [NotesDatabase]. Room compares the stored hash with
     * the one compiled from these entities, so this fails for any schema difference at all.
     */
    @Test
    fun aDatabaseBuiltFromTheAndroidBaselineOpensAsThisBuildsSchema() {
        val file = databaseFrom(androidBaseline(NEWEST))

        val database = NotesDatabase.create(file)
        try {
            runBlocking { assertEquals(emptyList(), database.attachmentDao().allIds()) }
        } finally {
            database.close()
        }
    }

    /** The control for the test above: it only proves something if a mismatch is refused. */
    @Test
    fun aDatabaseFromAnyOtherSchemaIsRefusedRatherThanOpened() {
        val file = databaseFrom(androidBaseline(NEWEST), identityHash = "0".repeat(32))

        val database = NotesDatabase.create(file)
        try {
            assertFailsWith<IllegalStateException> {
                runBlocking { database.attachmentDao().allIds() }
            }
        } finally {
            database.close()
        }
    }

    /**
     * Table by table, so that a failure above names what changed. The export is what KSP wrote for
     * these entities; it must say exactly what Android's does.
     */
    @Test
    fun theExportedSchemaMatchesTheAndroidBaselineTableByTable() {
        (1..NEWEST).forEach { version ->
            val exported = File("schemas/com.vivenotes.data.db.NotesDatabase/$version.json")
            val desktop = createStatements(Json.parseToJsonElement(exported.readText()).jsonObject)
            val android = createStatements(androidBaseline(version))

            assertEquals(android.keys, desktop.keys, "version $version")
            android.forEach { (table, statements) -> assertEquals(statements, desktop[table], "$table v$version") }
        }
    }

    /**
     * Version 2 adds the picture release and the saved-version picture index.
     *
     * Every existing picture comes through unreleased, because nothing had ever been released, and
     * every existing saved version comes through unindexed rather than as "places nothing", which
     * would let the first sweep delete a file a version still shows. The release trigger exists
     * afterwards — `onCreate` never runs again for a database that already exists — and fires on a
     * release but not on the `refCount` updates every import makes.
     */
    @Test
    fun versionOneMigratesWithEveryPictureUnreleasedAndEveryVersionUnindexed() {
        val file = databaseFrom(androidBaseline(1))
        withConnection(file) { connection ->
            connection.execSQL(
                "INSERT INTO attachments(id, mimeType, pixelWidth, pixelHeight, byteCount, refCount, createdAt) " +
                    "VALUES('sha-one', 'image/webp', 8, 8, 10, 1, 42)",
            )
            connection.execSQL(
                "INSERT INTO sync_state(singleton, accountId, cursor, applyingRemote) VALUES(0, 'account', 0, 0)",
            )
        }

        // Room runs MIGRATION_1_2 and then validates every table against these entities.
        val database = NotesDatabase.create(file)
        try {
            runBlocking { assertEquals(listOf("sha-one"), database.attachmentDao().allIds()) }
            assertEquals(null, runBlocking { database.attachmentDao().byId("sha-one") }?.releasedAt)
        } finally {
            database.close()
        }

        withConnection(file) { connection ->
            assertEquals(2L, connection.scalar("PRAGMA user_version"))
            connection.execSQL("UPDATE attachments SET refCount = 2 WHERE id = 'sha-one'")
            assertEquals(0L, connection.scalar(OUTBOX_ROWS))

            connection.execSQL("UPDATE attachments SET releasedAt = 99 WHERE id = 'sha-one'")
            assertEquals(1L, connection.scalar(OUTBOX_ROWS))
        }
    }

    /**
     * A double release must not drive an attachment's reference count negative.
     *
     * A negative count reads as "sweepable" everywhere it is asked, so the floor is what stands
     * between a mistimed release and deleting the bytes of a picture a page still shows. It is
     * `MAX(refCount - 1, 0)` in one DAO query and asserting it needs a real table.
     */
    @Test
    fun anAttachmentStartsUnreferencedAndCannotBeReleasedBelowZero() = withDatabase { database ->
        val attachments = database.attachmentDao()
        attachments.insert(
            AttachmentEntity(
                id = "sha-one",
                mimeType = "image/jpeg",
                pixelWidth = 100,
                pixelHeight = 80,
                byteCount = 2048,
                createdAt = 42L,
            ),
        )
        assertEquals(0, attachments.byId("sha-one")?.refCount)

        attachments.retain("sha-one")
        attachments.release("sha-one")
        attachments.release("sha-one")

        assertEquals(0, attachments.byId("sha-one")?.refCount)
    }

    /**
     * A picture's recognized text dies with the picture.
     *
     * The foreign key is the only thing that removes the reading with the bytes, which also makes
     * this the proof that the bundled driver runs with foreign keys enforced at all — every
     * `ON DELETE CASCADE` in the schema depends on it.
     */
    @Test
    fun deletingAnAttachmentDeletesItsRecognizedText() = withDatabase { database ->
        database.attachmentDao().insert(
            AttachmentEntity(
                id = "sha-one",
                mimeType = "image/webp",
                pixelWidth = 800,
                pixelHeight = 600,
                byteCount = 1024,
                createdAt = 10L,
            ),
        )
        database.imageTextDao().upsert(
            AttachmentTextEntity(
                attachmentId = "sha-one",
                text = "hello",
                lineCount = 1,
                confidence = 0.9f,
                engine = "ppocrv5-en/1",
                status = ImageTextStatus.Read,
                durationMs = 42L,
                updatedAt = 10L,
            ),
        )
        assertEquals(1, database.imageTextDao().byIds(listOf("sha-one")).size)

        database.attachmentDao().deleteIfUnreferenced("sha-one")

        assertEquals(emptyList(), database.imageTextDao().byIds(listOf("sha-one")))
    }

    private fun withDatabase(block: suspend (NotesDatabase) -> Unit) {
        val database = NotesDatabase.inMemory()
        try {
            runBlocking { block(database) }
        } finally {
            database.close()
        }
    }

    private fun androidBaseline(version: Int): JsonObject {
        val text = checkNotNull(javaClass.getResource("/schemas/android-baseline-$version.json")).readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun withConnection(file: File, block: (androidx.sqlite.SQLiteConnection) -> Unit) {
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            block(connection)
        } finally {
            connection.close()
        }
    }

    private fun androidx.sqlite.SQLiteConnection.scalar(sql: String): Long = prepare(sql).use { statement ->
        statement.step()
        statement.getLong(0)
    }

    /** Every table's `CREATE TABLE` and `CREATE INDEX` statements, with the table name filled in. */
    private fun createStatements(schema: JsonObject): Map<String, List<String>> =
        schema.getValue("database").jsonObject.getValue("entities").jsonArray.associate { element ->
            val entity = element.jsonObject
            val table = entity.getValue("tableName").jsonPrimitive.content
            val statements = listOf(entity.getValue("createSql").jsonPrimitive.content) +
                entity["indices"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("createSql").jsonPrimitive.content }
            table to statements.map { it.replace("\${TABLE_NAME}", table) }
        }

    /**
     * What Room's `MigrationTestHelper.createDatabase` builds on Android: the schema's statements,
     * its setup queries — the identity row — and the version in `user_version`.
     */
    private fun databaseFrom(schema: JsonObject, identityHash: String? = null): File {
        val file = File(directory, "baseline.db")
        val database = schema.getValue("database").jsonObject
        val setup = database.getValue("setupQueries").jsonArray.map { it.jsonPrimitive.content }.let { queries ->
            if (identityHash == null) queries else {
                val stored = (database.getValue("identityHash") as JsonPrimitive).content
                queries.map { it.replace(stored, identityHash) }
            }
        }
        BundledSQLiteDriver().open(file.absolutePath).let { connection ->
            try {
                createStatements(schema).values.flatten().forEach(connection::execSQL)
                setup.forEach(connection::execSQL)
                connection.execSQL("PRAGMA user_version = ${database.getValue("version").jsonPrimitive.content}")
            } finally {
                connection.close()
            }
        }
        return file
    }

    private companion object {
        /** The newest schema Android has committed, and the version [NotesDatabase] declares. */
        const val NEWEST = 2

        const val OUTBOX_ROWS = "SELECT COUNT(*) FROM sync_outbox WHERE kind = 'attachment' AND entityId = 'sha-one'"
    }
}
