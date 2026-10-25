// Ported from the Android app's `data/db/NotesDatabase.kt`, with the JVM builders in place of the
// Android `Context` one. The entities, version, migrations and triggers are the Android ones unchanged.
package com.vivenotes.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.vivenotes.data.DebugLog
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * The app's database.
 *
 * Version 1 is a consolidated baseline, not the first schema this project ever had: twenty-one
 * development migrations were collapsed into the entity definitions below once it was certain that
 * no installation outside this repository had ever run one. Nothing about the tables changed in the
 * collapse, but every earlier version is now unreachable, so a database written by a pre-baseline
 * build cannot be upgraded and has to be cleared.
 *
 * From here the ordinary rule applies: every schema change needs an explicit `Migration` registered
 * in [create], the exported schema JSON committed under `shared/schemas/`, and a case in
 * `SchemaCompatibilityTest` proving what happens to rows that already exist. The migration's KDoc
 * explains why each column is backfilled or left null — the one-line `ALTER TABLE` never shows that.
 * [MIGRATION_1_2] is the first.
 */
@Database(
    entities = [
        NotebookEntity::class,
        SectionEntity::class,
        PageEntity::class,
        PageContentEntity::class,
        PageRevisionEntity::class,
        InkStrokeEntity::class,
        InkEraseEntity::class,
        InkEraseTargetEntity::class,
        InkMoveEntity::class,
        InkMoveTargetEntity::class,
        AttachmentEntity::class,
        AttachmentTextEntity::class,
        InkTextEntity::class,
        InkTextGenerationEntity::class,
        LocalMetadataEntity::class,
        SyncStateEntity::class,
        SyncEntityStateEntity::class,
        SyncOutboxEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class NotesDatabase : RoomDatabase() {
    internal var searchBodySnapshots: SearchBodySnapshots? = null
    internal var storageFile: File? = null

    abstract fun notebookDao(): NotebookDao
    abstract fun sectionDao(): SectionDao
    abstract fun pageDao(): PageDao
    abstract fun pageContentDao(): PageContentDao
    abstract fun pageRevisionDao(): PageRevisionDao
    abstract fun inkStrokeDao(): InkStrokeDao
    abstract fun inkEraseDao(): InkEraseDao
    abstract fun inkMoveDao(): InkMoveDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun imageTextDao(): ImageTextDao
    abstract fun inkTextDao(): InkTextDao
    abstract fun localMetadataDao(): LocalMetadataDao
    abstract fun syncDao(): SyncDao
    abstract fun deletionRecoveryDao(): DeletionRecoveryDao
    abstract fun deletionPurgeDao(): DeletionPurgeDao

    companion object {

        /**
         * Installs the sync triggers on a database Room has just created.
         *
         * Triggers are not part of the entity schema, so Room neither creates nor validates them,
         * and `onCreate` is the only hook that fires on a database it has just built — which, since
         * the baseline, is every database this build opens. They stay dormant until `sync_state`
         * holds its singleton row, so an installation that has never connected an account queues
         * nothing. Anything that opens a `NotesDatabase` of its own — the transfer tests, the sync
         * tests — has to add this callback, or writes that should queue a push silently do not.
         */
        val SYNC_TRIGGER_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(connection: SQLiteConnection) {
                installSyncTriggers(connection)
            }
        }

        /**
         * Version 2: pictures can be let go of.
         *
         * `attachments.releasedAt` is null on every existing row, which is the truth until the first
         * sweep looks: nothing has been released yet, because nothing ever was. The sweep then
         * releases what no document places, so an upgraded device hands back what it had been
         * holding on the server's quota since the first picture.
         *
         * `page_revisions.pictureIds` is null on every existing row, meaning "not indexed yet" rather
         * than "no pictures". Computing it here would inflate every saved version on the device
         * inside the migration, which every caller of the first open waits for. The sweep indexes
         * them in the background instead, and deletes no file before it has.
         *
         * The trigger is created here as well as in [installSyncTriggers], because `onCreate` never
         * runs again for a database that already exists.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                DebugLog.i(DebugLog.DB) { "Migrating notes.db from version 1 to 2" }
                connection.execSQL("ALTER TABLE attachments ADD COLUMN releasedAt INTEGER")
                connection.execSQL("ALTER TABLE page_revisions ADD COLUMN pictureIds TEXT")
                installAttachmentReleaseTrigger(connection)
            }
        }

        /** The file name the Android app uses too, inside the platform's data directory. */
        const val FILE_NAME = "notes.db"

        fun create(file: File): NotesDatabase {
            val snapshots = SearchBodySnapshots(file)
            return Room.databaseBuilder<NotesDatabase>(name = file.absolutePath)
                // Never `fallbackToDestructiveMigration`, which answers a forgotten migration by
                // deleting the notes.
                .addMigrations(MIGRATION_1_2)
                // Write-ahead logging, as Room chooses on Android: readers never wait for autosave,
                // and `DatabaseBackupManager` copies with `VACUUM INTO` for exactly this reason.
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .configured(snapshots.driver)
                .addCallback(OPEN_LOG_CALLBACK)
                .build().also { it.searchBodySnapshots = snapshots; it.storageFile = file.absoluteFile }
        }

        /** With `--debug` only: when `notes.db` is created and opened, and at which version. */
        private val OPEN_LOG_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(connection: SQLiteConnection) {
                DebugLog.i(DebugLog.DB) { "Created notes.db" }
            }

            override fun onOpen(connection: SQLiteConnection) {
                DebugLog.i(DebugLog.DB) {
                    val version = connection.prepare("PRAGMA user_version").use { statement ->
                        statement.step()
                        statement.getLong(0)
                    }
                    "Opened notes.db at schema version $version"
                }
            }
        }

        /** A private, throwaway database with the same schema and triggers — for tests. */
        fun inMemory(): NotesDatabase = Room.inMemoryDatabaseBuilder<NotesDatabase>().configured().build()

        /**
         * What every desktop database is built with.
         *
         * The bundled driver ships its own SQLite, so Linux and Windows run one engine version
         * rather than whatever the system happens to have. Queries run on [Dispatchers.IO]: the
         * callers are the UI thread and a maintenance coroutine, and neither may block on disk.
         */
        private fun RoomDatabase.Builder<NotesDatabase>.configured(driver: SQLiteDriver = BundledSQLiteDriver()): RoomDatabase.Builder<NotesDatabase> =
            setDriver(driver)
                .setQueryCoroutineContext(Dispatchers.IO)
                .addCallback(SYNC_TRIGGER_CALLBACK)

        private fun installSyncTriggers(connection: SQLiteConnection) {
            listOf(
                SyncedTable("notebook", "notebooks", "id"),
                SyncedTable("section", "sections", "id"),
                SyncedTable("page", "pages", "id"),
                SyncedTable("pageContent", "page_content", "pageId"),
                // Ink. The target tables get none: they are never an entity, they travel
                // inside their operation's payload, and they are written in the same transaction
                // as the operation whose insert already queued it.
                SyncedTable("inkStroke", "ink_strokes", "id"),
                SyncedTable("inkErase", "ink_erases", "id"),
                SyncedTable("inkMove", "ink_moves", "id"),
                // Attachments get no general update trigger. `refCount` is per-device reachability
                // and deliberately not synced, so one would re-push an identical row every time a
                // picture was counted and every other device would pull it back. The one synced
                // column that changes, `releasedAt`, has a trigger of its own below.
                SyncedTable("attachment", "attachments", "id", queueUpdates = false),
            ).forEach { (kind, table, entityIdColumn, queueUpdates) ->
                val events = if (queueUpdates) {
                    listOf("insert" to "INSERT", "update" to "UPDATE")
                } else {
                    listOf("insert" to "INSERT")
                }
                events.forEach { (suffix, event) ->
                    connection.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS sync_${table}_$suffix
                        AFTER $event ON $table
                        WHEN EXISTS (
                            SELECT 1 FROM sync_state
                            WHERE singleton = 0 AND applyingRemote = 0
                        )
                        BEGIN
                            INSERT INTO sync_outbox(kind, entityId, generation, changedAt)
                            VALUES(
                                '$kind',
                                NEW.$entityIdColumn,
                                1,
                                CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)
                            )
                            ON CONFLICT(kind, entityId) DO UPDATE
                            SET generation = generation + 1, changedAt = excluded.changedAt;
                        END
                        """.trimIndent(),
                    )
                }
            }
            installAttachmentReleaseTrigger(connection)
        }

        /**
         * Queues an attachment when this device releases it or places it again.
         *
         * Scoped to `releasedAt` and to a real change of it, so a sweep that finds nothing new to say
         * queues nothing, and the `refCount` updates that every import and pulled document make stay
         * silent as before.
         */
        private fun installAttachmentReleaseTrigger(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS sync_attachments_release
                AFTER UPDATE OF releasedAt ON attachments
                WHEN OLD.releasedAt IS NOT NEW.releasedAt AND EXISTS (
                    SELECT 1 FROM sync_state
                    WHERE singleton = 0 AND applyingRemote = 0
                )
                BEGIN
                    INSERT INTO sync_outbox(kind, entityId, generation, changedAt)
                    VALUES(
                        'attachment',
                        NEW.id,
                        1,
                        CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)
                    )
                    ON CONFLICT(kind, entityId) DO UPDATE
                    SET generation = generation + 1, changedAt = excluded.changedAt;
                END
                """.trimIndent(),
            )
        }

        /**
         * One row of [installSyncTriggers]' table: which kind a table's rows are pushed as, where
         * the entity id lives on them, and whether an update to one is worth telling the server
         * about. A data class rather than a `Triple` because the fourth field is a boolean, and a
         * boolean in a tuple is unreadable at the call site.
         */
        private data class SyncedTable(
            val kind: String,
            val table: String,
            val entityIdColumn: String,
            val queueUpdates: Boolean = true,
        )
    }
}
