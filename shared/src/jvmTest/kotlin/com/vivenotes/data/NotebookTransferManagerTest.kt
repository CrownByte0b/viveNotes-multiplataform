package com.vivenotes.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkMoveTargetEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.LocalMetadataEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.StrokeColor
import com.vivenotes.data.db.SyncEntityStateEntity
import com.vivenotes.data.db.SyncStateEntity
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkFixtures
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.newId
import com.vivenotes.model.plainText
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `.vive` export and import. The first fourteen cases are the Android app's
 * `NotebookTransferManagerTest`, ported case for case (its MIME-picker case has no desktop
 * counterpart); the rest cover what only the desktop has to get right — above all, writing a file
 * Android's own importer accepts.
 *
 * Databases are real files opened the way the app opens them, sync triggers included, because an
 * export of a database shape that only exists in tests is how a bundle no build can import once
 * passed every test on Android.
 */
class NotebookTransferManagerTest {

    private lateinit var root: File
    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var attachmentStore: AttachmentStore
    private lateinit var transfers: NotebookTransferManager
    private val opened = mutableListOf<NotesDatabase>()
    private var now = 1_000_000L

    private fun openDatabase(name: String): NotesDatabase =
        NotesDatabase.create(File(root, name)).also { opened += it }

    private fun storeFor(database: NotesDatabase, name: String) = AttachmentStore(File(root, "attachments-$name"), database)

    private fun transfersFor(database: NotesDatabase, store: AttachmentStore, name: String) =
        NotebookTransferManager(database, store, File(root, "transfers-$name"), clock = { now })

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("notebook-transfer-manager-test").toFile()
        db = openDatabase("source.db")
        repository = NotesRepository(db, clock = { now })
        attachmentStore = storeFor(db, "source")
        transfers = transfersFor(db, attachmentStore, "source")
    }

    @AfterTest
    fun tearDown() {
        opened.forEach(NotesDatabase::close)
        root.deleteRecursively()
    }

    @Test
    fun importReplacesUntouchedCleanInstallStarterByItsRecordedUuid() = runBlocking<Unit> {
        val importedId = repository.createNotebook("Restored Notebook")
        val importedSection = repository.createSection(importedId, "Pages")
        repository.createPage(importedSection, "From backup")
        val bundle = export(importedId)

        val destinationDb = openDatabase("clean-install.db")
        val destinationRepository = NotesRepository(destinationDb, clock = { now })
        destinationRepository.seedIfEmpty()
        val starterId = destinationDb.localMetadataDao().value(NotesRepository.REPLACEABLE_STARTER_KEY)!!
        assertTrue(starterId != importedId)

        val result = transfersFor(destinationDb, storeFor(destinationDb, "clean-install"), "clean-install")
            .importNotebook(ByteArrayInputStream(bundle))

        assertEquals(importedId, result.notebookId)
        assertEquals(1, destinationDb.notebookDao().count())
        // Tombstoned, not erased: the row stays so its removal can be pushed to sync.
        assertTrue(destinationDb.notebookDao().byId(starterId)?.deletedAt != null)
        assertTrue(destinationDb.notebookDao().byId(importedId) != null)
    }

    @Test
    fun importKeepsStarterAfterItHasBeenEdited() = runBlocking<Unit> {
        val importedId = repository.createNotebook("Restored Notebook")
        val importedSection = repository.createSection(importedId, "Pages")
        repository.createPage(importedSection, "From backup")
        val bundle = export(importedId)

        val destinationDb = openDatabase("edited-install.db")
        val destinationRepository = NotesRepository(destinationDb, clock = { now })
        destinationRepository.seedIfEmpty()
        val starterId = destinationDb.localMetadataDao().value(NotesRepository.REPLACEABLE_STARTER_KEY)!!
        destinationRepository.renameNotebook(starterId, "My real notes")

        transfersFor(destinationDb, storeFor(destinationDb, "edited-install"), "edited-install")
            .importNotebook(ByteArrayInputStream(bundle))

        assertEquals(2, destinationDb.notebookDao().count())
        assertEquals("My real notes", destinationDb.notebookDao().byId(starterId)?.name)
        assertTrue(destinationDb.notebookDao().byId(importedId) != null)
    }

    @Test
    fun importingADeletedNotebookRestoresItsStableId() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Field Notes")
        val sectionId = repository.createSection(notebookId, "Observations")
        val pageId = repository.createPage(sectionId, "Heron")
        val bundle = export(notebookId)

        now += 1
        repository.deleteNotebook(notebookId)
        val deletionTime = db.notebookDao().byId(notebookId)!!.updatedAt
        assertEquals(0, db.notebookDao().count())

        val result = transfers.importNotebook(ByteArrayInputStream(bundle))
        val restored = db.notebookDao().byId(notebookId)!!

        assertFalse(result.created)
        assertTrue(result.restored)
        assertEquals(sectionId, result.firstSectionId)
        assertEquals(pageId, result.firstPageId)
        assertEquals(1, db.notebookDao().count())
        assertEquals(null, restored.deletedAt)
        assertTrue(restored.updatedAt > deletionTime, "restore did not supersede the deletion timestamp")
    }

    @Test
    fun anImportMarksTheNotebookUntilTheServerHasTakenIt() = runBlocking<Unit> {
        // The mark sync reads to tell an import under a retired id from a notebook the account erased.
        val notebookId = repository.createNotebook("Field Notes")
        repository.createSection(notebookId, "Observations")
        val bundle = export(notebookId)
        db.localMetadataDao().delete(NotesRepository.importedNotebookKey(notebookId))

        transfers.importNotebook(ByteArrayInputStream(bundle))

        assertEquals("$now", db.localMetadataDao().value(NotesRepository.importedNotebookKey(notebookId)))
    }

    @Test
    fun aReImportUpdatesTheNotebookAPurgeMovedRatherThanInstallingASecondCopy() = runBlocking<Unit> {
        val archiveNotebookId = repository.createNotebook("Field Notes")
        val sectionId = repository.createSection(archiveNotebookId, "Observations")
        val pageId = repository.createPage(sectionId, "Heron")
        val bundle = export(archiveNotebookId)

        // What sync leaves once the account has permanently deleted the id this archive names: the
        // same rows under a fresh notebook id, and a note of where they went.
        val movedId = "01a0039f-1bbc-7979-ad06-000000000001"
        db.notebookDao().upsert(db.notebookDao().byId(archiveNotebookId)!!.copy(id = movedId))
        db.sectionDao().repointNotebook(archiveNotebookId, movedId)
        db.notebookDao().hardDelete(archiveNotebookId)
        db.localMetadataDao().put(LocalMetadataEntity(NotesRepository.importRemapKey(archiveNotebookId), movedId))

        now += 1
        val result = transfers.importNotebook(ByteArrayInputStream(bundle))

        assertEquals(1, db.notebookDao().count(), "the file must update its copy, not add one")
        assertEquals(movedId, result.notebookId)
        assertFalse(result.created, "it is an update of the moved notebook")
        assertNull(db.notebookDao().byId(archiveNotebookId), "the retired id must not come back")
        assertEquals(listOf(sectionId), db.sectionDao().allInNotebook(movedId).map { it.id })
        assertEquals(movedId, db.sectionDao().byId(sectionId)!!.notebookId)
        assertNotNull(db.pageDao().byId(pageId))
    }

    @Test
    fun aNotebookTheServerAlreadyHoldsIsNotMarkedAsAnUnsentImport() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Field Notes")
        repository.createSection(notebookId, "Observations")
        val bundle = export(notebookId)
        db.syncDao().putEntityState(SyncEntityStateEntity("notebook", notebookId, 7L, "{}"))
        db.localMetadataDao().put(LocalMetadataEntity(NotesRepository.importedNotebookKey(notebookId), "stale"))

        transfers.importNotebook(ByteArrayInputStream(bundle))

        assertNull(
            db.localMetadataDao().value(NotesRepository.importedNotebookKey(notebookId)),
            "a stale marker must go with the import that found it",
        )
    }

    @Test
    fun importRestoresArchivedPageMetadataAndDocumentOverANewerLocalDeletion() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Field Notes")
        val sectionId = repository.createSection(notebookId, "Observations")
        val pageId = repository.createPage(sectionId, "Archived page")
        repository.saveDoc(pageId, text("archived"))
        val bundle = export(notebookId)

        now += 1
        repository.renamePage(pageId, "Local page")
        repository.saveDoc(pageId, text("local"))
        repository.deletePage(pageId)
        val deletionTime = db.pageDao().byId(pageId)!!.updatedAt

        val result = transfers.importNotebook(ByteArrayInputStream(bundle))
        val restoredPage = db.pageDao().byId(pageId)!!
        val restoredDoc = repository.loadDoc(pageId) as PageLoad.Loaded

        assertFalse(result.created)
        assertTrue(result.restored)
        assertEquals("Archived page", restoredPage.title)
        assertEquals(null, restoredPage.deletedAt)
        assertTrue(restoredPage.updatedAt > deletionTime, "restored page did not supersede its deletion")
        assertEquals("archived", restoredDoc.doc.plainText())
    }

    @Test
    fun exportThenImportCopiesTextInkHistoryAndAttachments() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Field Notes")
        val sectionId = repository.createSection(notebookId, "Observations")
        val pageId = repository.createPage(sectionId, "Heron")
        val attachment = makeAttachment()
        repository.saveDoc(pageId, withPhoto("river bank", attachment.id))

        val storedStroke = repository.addStroke(stroke(pageId, seq = 0, colorArgb = 0xFF112233.toInt()))
        now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS
        repository.setInkColors(mapOf(storedStroke.id to StrokeColor(0xFF556677.toInt(), false)))
        val sourceRevisionCount = repository.revisionHistory(pageId).size

        val firstBundle = export(notebookId)
        val destinationDb = openDatabase("destination.db")
        val destinationStore = storeFor(destinationDb, "destination")
        val destinationTransfers = transfersFor(destinationDb, destinationStore, "destination")
        val destinationRepository = NotesRepository(destinationDb, clock = { now })
        val result = destinationTransfers.importNotebook(ByteArrayInputStream(firstBundle))

        assertTrue(result.created)
        assertEquals(notebookId, result.notebookId)
        assertEquals("Field Notes", result.notebookName)
        val importedPageId = result.firstPageId!!
        val imported = destinationRepository.loadDoc(importedPageId) as PageLoad.Loaded
        assertEquals("river bank", imported.doc.plainText())
        assertEquals(attachment.id, imported.doc.outlines.filterIsInstance<Outline.Image>().single().attachmentId)
        assertEquals(0xFF556677.toInt(), destinationRepository.inkFor(importedPageId).single().colorArgb)
        assertEquals(sourceRevisionCount, destinationRepository.revisionHistory(importedPageId).size)

        val destinationOnlyStroke = destinationRepository.addStroke(
            stroke(importedPageId, seq = 1, colorArgb = 0xFF112233.toInt()),
        )
        val destinationOnlySection = destinationRepository.createSection(notebookId, "Local section")
        val pageInDestinationOnlySection = destinationRepository.createPage(destinationOnlySection, "Local section page")
        assertEquals(2, destinationRepository.inkFor(importedPageId).size)

        val revisionRows = destinationRepository.revisionHistory(importedPageId).map { summary ->
            destinationDb.pageRevisionDao().byId(importedPageId, summary.id)!!
        }
        assertTrue(
            revisionRows.map(InkRevisionPayload::unpack).any { snapshot ->
                snapshot.strokes.any { it.id == storedStroke.id && it.colorArgb == 0xFF112233.toInt() }
            },
            "ink history did not retain the stable stroke id",
        )

        now += 1
        repository.saveDoc(pageId, withPhoto("river bank updated", attachment.id))
        val changedBundle = export(notebookId)
        val synced = destinationTransfers.importNotebook(ByteArrayInputStream(changedBundle))

        assertFalse(synced.created)
        assertEquals(1, destinationDb.notebookDao().count())
        assertEquals("river bank updated", (destinationRepository.loadDoc(pageId) as PageLoad.Loaded).doc.plainText())
        assertTrue(
            destinationDb.inkStrokeDao().byIds(listOf(destinationOnlyStroke.id)).single().deletedAt != null,
            "the authoritative import kept destination-only ink live",
        )
        assertTrue(
            destinationDb.sectionDao().byId(destinationOnlySection)!!.deletedAt != null,
            "the authoritative import kept a destination-only section live",
        )
        assertTrue(
            destinationDb.pageDao().inNotebook(notebookId).none { it.id == pageInDestinationOnlySection },
            "a page under a removed local-only section should no longer be reachable",
        )
        assertEquals(1, destinationRepository.inkFor(importedPageId).size)
        val revisionsAfterSync = destinationRepository.revisionHistory(pageId).size

        destinationTransfers.importNotebook(ByteArrayInputStream(changedBundle))

        assertEquals(1, destinationDb.notebookDao().count(), "reimport duplicated the notebook")
        assertEquals(revisionsAfterSync, destinationRepository.revisionHistory(pageId).size,
            "reimport duplicated version history")
        assertEquals(1, destinationDb.attachmentDao().byId(attachment.id)!!.refCount)
        assertTrue(destinationStore.fileFor(attachment.id)!!.isFile)

        val destinationOnlyPage = destinationRepository.createPage(sectionId, "destination only")
        now += 1
        repository.deletePage(pageId)
        destinationTransfers.importNotebook(ByteArrayInputStream(export(notebookId)))

        assertTrue(destinationDb.pageDao().byId(pageId)!!.deletedAt != null, "newer deletion did not sync")
        assertTrue(
            destinationDb.pageDao().byId(destinationOnlyPage)!!.deletedAt != null,
            "the authoritative import kept a destination-only page live",
        )
    }

    @Test
    fun exportThenImportPreservesTargetsWhoseStrokeWasPurged() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Field Notes")
        val sectionId = repository.createSection(notebookId, "Observations")
        val pageId = repository.createPage(sectionId, "Heron")
        val stroke = stroke(pageId, seq = 0, colorArgb = 0xFF112233.toInt())
        db.inkStrokeDao().insert(stroke)

        val erase = InkEraseEntity(
            id = "01a06591-0000-7000-8000-000000000001",
            pageId = pageId,
            sizeDp = 12f,
            points = stroke.points,
            enc = stroke.enc,
            createdAt = now + 1,
        )
        val move = move(pageId, listOf(5f to 5f, 35f to 5f, 35f to 45f), dx = 4f, dy = 6f)
            .copy(id = "01a06591-0000-7000-8000-000000000002", createdAt = now + 2)
        db.inkEraseDao().insert(erase)
        db.inkEraseDao().insertTargets(listOf(InkEraseTargetEntity(erase.id, stroke.id)))
        db.inkMoveDao().insert(move)
        db.inkMoveDao().insertTargets(listOf(InkMoveTargetEntity(move.id, stroke.id)))

        db.inkStrokeDao().softDelete(listOf(stroke.id), now)
        now += NotesRepository.DELETION_RETENTION_MILLIS
        assertEquals(1, repository.purgeExpiredDeletions(now).inkStrokes)
        assertTrue(db.inkStrokeDao().byIds(listOf(stroke.id)).isEmpty())

        val bundle = export(notebookId)
        val destinationDb = openDatabase("purged-target-destination.db")
        transfersFor(destinationDb, storeFor(destinationDb, "purged"), "purged")
            .importNotebook(ByteArrayInputStream(bundle))

        assertTrue(destinationDb.inkStrokeDao().byIds(listOf(stroke.id)).isEmpty())
        assertEquals(
            listOf(InkEraseTargetEntity(erase.id, stroke.id)),
            destinationDb.inkEraseDao().targetsForErases(listOf(erase.id)),
        )
        assertEquals(
            listOf(InkMoveTargetEntity(move.id, stroke.id)),
            destinationDb.inkMoveDao().targetsForMoves(listOf(move.id)),
        )
    }

    @Test
    fun pathTraversalIsRejectedBeforeTheLiveDatabaseChanges() = runBlocking<Unit> {
        val before = db.notebookDao().count()
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("../escape"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }.toByteArray()

        val failure = runCatching { transfers.importNotebook(ByteArrayInputStream(archive)) }.exceptionOrNull()

        assertIs<NotebookTransferException>(failure)
        assertEquals(before, db.notebookDao().count())
        assertFalse(File(root, "escape").exists())
    }

    @Test
    fun corruptedManifestIsRejectedBeforeTheLiveDatabaseChanges() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Original")
        val sectionId = repository.createSection(notebookId, "Section")
        repository.createPage(sectionId, "Page")
        val corrupted = rewriteEntry(export(notebookId), "manifest.json") { bytes ->
            bytes.copyOf().also { it[it.lastIndex] = '!'.code.toByte() }
        }
        val before = db.notebookDao().count()

        val failure = runCatching { transfers.importNotebook(ByteArrayInputStream(corrupted)) }.exceptionOrNull()

        assertIs<NotebookTransferException>(failure)
        assertEquals(before, db.notebookDao().count())
    }

    /**
     * A bundle carries the notebook and nothing about the account that exported it: the `sync_*`
     * tables and their triggers would be refused by the importer, fire while the export rewrites
     * `attachments`, and hand the importer another account's cursor.
     */
    @Test
    fun exportStripsTheSyncLayerFromTheBundle() = runBlocking<Unit> {
        db.syncDao().putState(SyncStateEntity(accountId = "account-under-test"))
        val notebookId = repository.createNotebook("Connected")
        val sectionId = repository.createSection(notebookId, "Section")
        repository.createPage(sectionId, "Page")
        makeAttachment()
        // The triggers only fire for a connected database, so a silent no-op here would make the
        // rest of the test vacuous.
        assertTrue(db.syncDao().outbox(limit = 100).isNotEmpty())

        val objects = bundleDatabase(export(notebookId)) { bundle ->
            bundle.rows("SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") {
                it.getText(0) to it.getText(1)
            }
        }

        assertEquals(emptyList(), objects.filter { it.first == "trigger" })
        assertEquals(emptyList(), objects.filter { it.first == "view" })
        assertEquals(emptyList(), objects.filter { it.first == "table" }.map { it.second }.filter { it.startsWith("sync_") })
        // The export edits its own snapshot: what this device still owes its server survives.
        assertTrue(db.syncDao().outbox(limit = 100).isNotEmpty())
        assertTrue(db.syncDao().state() != null)
    }

    /** Which shelf a notebook sits on is the account's business, not the notebook's content. */
    @Test
    fun exportDropsTheShelfColumnsSoTheBundleFormatIsUnchanged() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Closed")
        val sectionId = repository.createSection(notebookId, "Section")
        repository.createPage(sectionId, "Page")
        repository.closeNotebook(notebookId)

        val columns = bundleDatabase(export(notebookId)) { bundle ->
            bundle.rows("PRAGMA table_info(notebooks)") { it.getText(1) }
        }

        assertEquals(
            listOf("id", "name", "colorArgb", "sortIndex", "expanded", "createdAt", "updatedAt", "deletedAt"),
            columns,
        )
        assertEquals(now, db.notebookDao().byId(notebookId)!!.closedAt, "the export edits its snapshot only")
    }

    /** A cloud-only notebook has pages and no bodies; exporting it would look like a backup and not be one. */
    @Test
    fun exportRefusesANotebookWhoseContentsAreInTheCloud() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Elsewhere")
        val sectionId = repository.createSection(notebookId, "Section")
        repository.createPage(sectionId, "Page")
        db.notebookDao().setClosed(notebookId, now, now)
        db.notebookDao().setCloudOnly(notebookId, now, now)

        val failure = runCatching { transfers.exportNotebook(notebookId, ByteArrayOutputStream()) }.exceptionOrNull()

        assertIs<NotebookTransferException>(failure)
        assertTrue(failure.message!!.contains("Bring it back"), "the message has to say what to do about it")
    }

    // ---------------------------------------------------------------------------------------------
    // Desktop cases.

    /**
     * What Android's importer checks before it reads a row, against the file the desktop writes:
     * exactly its table set, `android_metadata` included, the format stamped in the SQLite header
     * and in `vive_bundle`, and a rollback-journal file that needs no `-wal` beside it.
     */
    @Test
    fun theBundleHasTheShapeAndroidsImporterRequires() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Shapes")
        repository.createPage(repository.createSection(notebookId, "Section"), "Page")
        val archive = export(notebookId)
        val entries = zipEntries(archive)

        assertEquals(listOf("manifest.json", "notebook.sqlite", "checksums.sha256"), entries.map { it.name })
        val database = entries.single { it.name == "notebook.sqlite" }.bytes
        assertEquals(1, database[18].toInt(), "file format write version: rollback journal")
        assertEquals(1, database[19].toInt(), "file format read version: rollback journal")

        bundleDatabase(archive) { bundle ->
            assertEquals(
                listOf(
                    "android_metadata", "attachments", "ink_erase_targets", "ink_erases", "ink_move_targets",
                    "ink_moves", "ink_strokes", "notebooks", "page_content", "page_revisions", "pages",
                    "room_master_table", "sections", "vive_bundle",
                ),
                bundle.rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name") {
                    it.getText(0)
                },
            )
            assertEquals(listOf("locale"), bundle.rows("PRAGMA table_info(android_metadata)") { it.getText(1) })
            assertEquals(1, bundle.rows("SELECT locale FROM android_metadata") { it.getText(0) }.size)
            assertEquals(listOf(0x56495645L), bundle.rows("PRAGMA application_id") { it.getLong(0) })
            assertEquals(listOf(1L), bundle.rows("PRAGMA user_version") { it.getLong(0) })
            assertEquals(
                mapOf("format" to "com.vivenotes.notebook", "formatVersion" to "1", "appSchemaVersion" to "13",
                    "notebookId" to notebookId),
                bundle.rows("SELECT key, value FROM vive_bundle") { it.getText(0) to it.getText(1) }.toMap(),
            )
            assertEquals(listOf("716b15d1aa8dc9904a8694705a2e8502"),
                bundle.rows("SELECT identity_hash FROM room_master_table") { it.getText(0) })
            assertEquals(listOf("ok"), bundle.rows("PRAGMA quick_check") { it.getText(0) })
        }
    }

    @Test
    fun manifestAndChecksumsDescribeEveryEntryAsAndroidWritesThem() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Checked")
        val pageId = repository.createPage(repository.createSection(notebookId, "Section"), "Page")
        val attachment = makeAttachment()
        repository.saveDoc(pageId, withPhoto("caption", attachment.id))
        val entries = zipEntries(export(notebookId)).associateBy { it.name }

        assertEquals(setOf("manifest.json", "notebook.sqlite", "checksums.sha256", "attachments/${attachment.id}"),
            entries.keys)
        assertEquals(ZipEntry.STORED, entries.getValue("attachments/${attachment.id}").method,
            "pictures are already compressed")
        assertEquals(ZipEntry.DEFLATED, entries.getValue("notebook.sqlite").method)
        val manifest = entries.getValue("manifest.json").bytes.decodeToString()
        val revisions = repository.revisionHistory(pageId).size
        listOf("\"format\":\"com.vivenotes.notebook\"", "\"formatVersion\":1", "\"appSchemaVersion\":13",
            "\"sourceNotebookId\":\"$notebookId\"", "\"notebookName\":\"Checked\"",
            "\"counts\":{\"sections\":1,\"pages\":1,\"strokes\":0,\"revisions\":$revisions,\"attachments\":1}",
            "\"mimeType\":\"image/webp\",\"pixelWidth\":2,\"pixelHeight\":2",
        ).forEach { assertTrue(it in manifest, "manifest lacks $it: $manifest") }
        val sums = entries.getValue("checksums.sha256").bytes.decodeToString().lines().filter { it.isNotBlank() }
        assertEquals(
            listOf("attachments/${attachment.id}", "manifest.json", "notebook.sqlite"),
            sums.map { it.substringAfter("  ") },
            "sorted by path, and never the checksum list itself",
        )
        sums.forEach { line ->
            assertEquals(line.substringBefore("  "), entries.getValue(line.substringAfter("  ")).bytes.sha256())
        }
    }

    @Test
    fun exportingToAFileWritesItCompletelyAndLeavesNothingElse() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("On disk")
        repository.createPage(repository.createSection(notebookId, "Section"), "Page")
        val folder = File(root, "exports").apply { mkdirs() }
        val destination = File(folder, "On disk.vive")

        val result = transfers.exportNotebook(notebookId, destination)

        assertEquals("On disk", result.notebookName)
        assertEquals(destination.length(), result.byteCount)
        assertEquals(listOf("On disk.vive"), folder.list()!!.toList(), "no partial file is left beside it")
        assertEquals(0, File(root, "transfers-source").list()!!.size, "the staging directory is removed")
        val destinationDb = openDatabase("from-file.db")
        val imported = transfersFor(destinationDb, storeFor(destinationDb, "from-file"), "from-file")
            .importNotebook(destination)
        assertEquals(notebookId, imported.notebookId)
    }

    @Test
    fun aFailedExportLeavesTheFileItWouldHaveReplaced() = runBlocking<Unit> {
        val destination = File(root, "Existing.vive").apply { writeText("an earlier export") }

        val failure = runCatching { transfers.exportNotebook("no-such-notebook", destination) }.exceptionOrNull()

        assertIs<NotebookTransferException>(failure)
        assertEquals("The selected notebook no longer exists.", failure.message)
        assertEquals("an earlier export", destination.readText())
        assertEquals(listOf("Existing.vive"), root.list()!!.filter { it.endsWith(".vive") || it.endsWith(".part") })
    }

    @Test
    fun aDestinationThatCannotBeWrittenIsReportedAsSuch() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Nowhere")
        repository.createPage(repository.createSection(notebookId, "Section"), "Page")

        val failure = runCatching {
            transfers.exportNotebook(notebookId, File(root, "missing-folder/Nowhere.vive"))
        }.exceptionOrNull()

        assertIs<NotebookTransferException>(failure)
        assertEquals("The selected destination could not be opened.", failure.message)
    }

    @Test
    fun aFileThatCannotBeReadIsReportedAsSuch() = runBlocking<Unit> {
        val failure = runCatching { transfers.importNotebook(File(root, "absent.vive")) }.exceptionOrNull()

        assertIs<NotebookTransferException>(failure)
        assertEquals("The selected notebook file could not be opened.", failure.message)
    }

    @Test
    fun aDamagedFileIsRefusedWithoutWritingAnythingOrLeavingStagingFiles() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Damaged")
        repository.createPage(repository.createSection(notebookId, "Section"), "Page")
        val damaged = rewriteEntry(export(notebookId), "notebook.sqlite") { bytes ->
            bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        }
        val destinationDb = openDatabase("damaged-destination.db")
        val destinationTransfers = transfersFor(destinationDb, storeFor(destinationDb, "damaged"), "damaged")

        val failure = runCatching { destinationTransfers.importNotebook(ByteArrayInputStream(damaged)) }.exceptionOrNull()

        assertIs<NotebookTransferException>(failure)
        assertEquals("Checksum verification failed for notebook.sqlite.", failure.message)
        assertEquals(0, destinationDb.notebookDao().count())
        assertEquals(0, File(root, "transfers-damaged").list()!!.size)
    }

    /** A crash can leave a staging directory behind, and a desktop cache is never emptied for us. */
    @Test
    fun stagingLeftByAnEarlierRunIsSweptAndRecentStagingKept() = runBlocking<Unit> {
        val staging = File(root, "transfers-source").apply { mkdirs() }
        val abandoned = File(staging, "import-abandoned").apply { mkdirs(); File(this, "incoming.vive").writeText("x") }
        abandoned.setLastModified(System.currentTimeMillis() - NotebookTransferManager.STALE_STAGING_MILLIS - 60_000)
        val recent = File(staging, "import-in-another-window").apply { mkdirs() }
        val notebookId = repository.createNotebook("Sweep")
        repository.createPage(repository.createSection(notebookId, "Section"), "Page")

        export(notebookId)

        assertFalse(abandoned.exists())
        assertTrue(recent.exists())
    }

    /**
     * Android reads a picture's size with `BitmapFactory`, which ignores EXIF orientation, so the
     * size a bundle declares is the stored one. A reader that applied the orientation would refuse
     * every rotated camera JPEG an older Android build stored.
     */
    @Test
    fun aJpegIsMeasuredAsStoredNotAsItsExifOrientationWouldDrawIt() = runBlocking<Unit> {
        val jpeg = withExifOrientation(encode(4, 2, EncodedImageFormat.JPEG), orientation = 6)
        val attachment = putAttachment(jpeg, "image/jpeg", width = 4, height = 2)
        val notebookId = repository.createNotebook("Rotated")
        val pageId = repository.createPage(repository.createSection(notebookId, "Section"), "Page")
        repository.saveDoc(pageId, withPhoto("rotated", attachment.id))

        val destinationDb = openDatabase("rotated.db")
        val result = transfersFor(destinationDb, storeFor(destinationDb, "rotated"), "rotated")
            .importNotebook(ByteArrayInputStream(export(notebookId)))

        assertTrue(result.created)
        assertEquals(4, destinationDb.attachmentDao().byId(attachment.id)!!.pixelWidth)
    }

    @Test
    fun aPictureWhoseSizeDisagreesWithItsMetadataIsRefused() = runBlocking<Unit> {
        val attachment = putAttachment(encode(3, 3, EncodedImageFormat.WEBP), "image/webp", width = 4, height = 3)
        val notebookId = repository.createNotebook("Mismeasured")
        val pageId = repository.createPage(repository.createSection(notebookId, "Section"), "Page")
        repository.saveDoc(pageId, withPhoto("wrong", attachment.id))

        val destinationDb = openDatabase("mismeasured.db")
        val failure = runCatching {
            transfersFor(destinationDb, storeFor(destinationDb, "mismeasured"), "mismeasured")
                .importNotebook(ByteArrayInputStream(export(notebookId)))
        }.exceptionOrNull()

        assertIs<NotebookTransferException>(failure)
        assertEquals("Attachment ${attachment.id.take(12)} has incorrect image dimensions.", failure.message)
        assertEquals(0, destinationDb.notebookDao().count())
    }

    /** Ink the desktop cannot draw yet still has to be ink Android would accept, byte for byte. */
    @Test
    fun inkThatAndroidWouldRefuseIsRefused() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Scribbles")
        val pageId = repository.createPage(repository.createSection(notebookId, "Section"), "Page")
        db.inkStrokeDao().insert(stroke(pageId, seq = 0, colorArgb = 0xFF000000.toInt()).copy(points = byteArrayOf(1, 2, 3)))

        val destinationDb = openDatabase("scribbles.db")
        val failure = runCatching {
            transfersFor(destinationDb, storeFor(destinationDb, "scribbles"), "scribbles")
                .importNotebook(ByteArrayInputStream(export(notebookId)))
        }.exceptionOrNull()

        assertIs<NotebookTransferException>(failure)
        assertEquals("The notebook contains invalid ink data.", failure.message)
    }

    @Test
    fun inkRowsTravelByteForByte() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Ink")
        val pageId = repository.createPage(repository.createSection(notebookId, "Section"), "Page")
        val stroke = repository.addStroke(stroke(pageId, seq = 0, colorArgb = 0xFF336699.toInt()))

        val destinationDb = openDatabase("ink.db")
        transfersFor(destinationDb, storeFor(destinationDb, "ink"), "ink")
            .importNotebook(ByteArrayInputStream(export(notebookId)))

        val copied = destinationDb.inkStrokeDao().byIds(listOf(stroke.id)).single()
        assertContentEquals(InkFixtures.twoPointStroke, copied.points)
        assertEquals(stroke.copy(points = copied.points), copied.copy(points = copied.points))
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun export(notebookId: String): ByteArray =
        ByteArrayOutputStream().also { transfers.exportNotebook(notebookId, it) }.toByteArray()

    private fun text(body: String) = PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of(body)))))

    private fun withPhoto(body: String, attachmentId: String) = PageDoc(
        outlines = listOf(
            Outline.Text(id = "text", blocks = listOf(Block.of(body))),
            Outline.Image(id = "photo", attachmentId = attachmentId, width = 120f, height = 80f),
        ),
    )

    /** A stroke as Android's pen writes one, over the golden two-point input batch. */
    private fun stroke(pageId: String, seq: Int, colorArgb: Int) = InkStrokeEntity(
        id = newId(), pageId = pageId, seq = seq, brushFamily = "calligraphy-v1-p2", brushVersion = 1,
        sizeDp = 3f, colorArgb = colorArgb, colorFollowsTheme = false, epsilon = 0.25f, stabilization = 1,
        minX = 8.5f, minY = 18.5f, maxX = 31.5f, maxY = 41.5f, points = InkFixtures.twoPointStroke,
        enc = InkCodec.ENCODING, createdAt = now,
    )

    /** Android's `InkCodec.encodeMove`: a count and x/y pairs, little-endian. */
    private fun move(pageId: String, path: List<Pair<Float, Float>>, dx: Float, dy: Float) = InkMoveEntity(
        id = newId(), pageId = pageId, dxDp = dx, dyDp = dy,
        points = ByteBuffer.allocate(4 + path.size * 8).order(ByteOrder.LITTLE_ENDIAN).putInt(path.size)
            .apply { path.forEach { (x, y) -> putFloat(x).putFloat(y) } }.array(),
        enc = InkCodec.MOVE_ENCODING, createdAt = now,
    )

    /** Android's test picture: 2 × 2, one colour, lossy WebP, referenced once. */
    private suspend fun makeAttachment(): AttachmentEntity =
        putAttachment(encode(2, 2, EncodedImageFormat.WEBP), "image/webp", width = 2, height = 2)

    private suspend fun putAttachment(bytes: ByteArray, mimeType: String, width: Int, height: Int): AttachmentEntity {
        val id = bytes.sha256()
        attachmentStore.fileFor(id)!!.apply { parentFile.mkdirs() }.writeBytes(bytes)
        return AttachmentEntity(id, mimeType, width, height, bytes.size.toLong(), refCount = 1, createdAt = now)
            .also { db.attachmentDao().insert(it) }
    }

    private fun encode(width: Int, height: Int, format: EncodedImageFormat): ByteArray =
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(Color.makeRGB(0x33, 0x66, 0x99))
            surface.makeImageSnapshot().use { image -> image.encodeToData(format, 88)!!.use { it.bytes } }
        }

    /** [jpeg] with an EXIF APP1 segment saying to rotate it: a camera photo held sideways. */
    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // big-endian TIFF, first IFD at 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00, // Orientation
            0x00, 0x00, 0x00, 0x00, // no next IFD
        )
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        check(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private class Entry(val name: String, val method: Int, val bytes: ByteArray)

    private fun zipEntries(archive: ByteArray): List<Entry> = ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
        buildList {
            while (true) {
                val entry = zip.nextEntry ?: break
                add(Entry(entry.name, entry.method, zip.readBytes()))
                zip.closeEntry()
            }
        }
    }

    private fun <T> bundleDatabase(archive: ByteArray, read: (SQLiteConnection) -> T): T {
        val file = File(root, "bundle-${newId()}.sqlite")
        file.writeBytes(zipEntries(archive).single { it.name == "notebook.sqlite" }.bytes)
        val connection = BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY)
        return try { read(connection) } finally { connection.close() }
    }

    private fun <T> SQLiteConnection.rows(sql: String, mapper: (androidx.sqlite.SQLiteStatement) -> T): List<T> =
        prepare(sql).use { statement -> buildList { while (statement.step()) add(mapper(statement)) } }

    private fun rewriteEntry(archive: ByteArray, target: String, transform: (ByteArray) -> ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { destination ->
                zipEntries(archive).forEach { entry ->
                    destination.putNextEntry(ZipEntry(entry.name))
                    destination.write(if (entry.name == target) transform(entry.bytes) else entry.bytes)
                    destination.closeEntry()
                }
            }
        }.toByteArray()

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
