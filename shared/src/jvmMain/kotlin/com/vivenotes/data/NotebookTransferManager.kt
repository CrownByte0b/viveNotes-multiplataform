// Ported from the Android app's `data/NotebookTransferManager.kt`. The `.vive` container, its
// limits, every validation step and the restore semantics are Android's; what changed is how the
// desktop reaches them: files for document-provider URIs, the bundled SQLite driver for
// `android.database`, Skia for `BitmapFactory`'s header read, and `InkCodec`'s blob reader for
// AndroidX Ink. The `android_metadata` table Android's framework adds to every database is written
// into desktop bundles, because Android's importer requires it.
package com.vivenotes.data

import com.vivenotes.localization.Strings
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkMoveTargetEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.LocalMetadataEntity
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.PageRevisionEntity
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.data.db.deferredNotebookContentKey
import com.vivenotes.ink.InkCodec
import com.vivenotes.model.DocumentCodecs
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.migrated
import com.vivenotes.model.newId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NotebookTransferException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Reads and writes the portable `.vive` notebook container.
 *
 * Imports are deliberately two-phase. The untrusted archive is copied and completely validated in
 * a fresh private cache directory first. Only a [ValidatedBundle] can reach [install], whose Room
 * transaction is the first write to the live database. ZIP paths are never trusted as filesystem
 * paths: the tiny allowlist and canonical-child check are both applied before extraction.
 */
class NotebookTransferManager(
    private val db: NotesDatabase,
    private val attachmentStore: AttachmentStore,
    /** Private staging space: the platform cache directory's `notebook_transfers`. */
    private val transferRoot: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /**
     * Writes the notebook to [destination]. The file is written beside it and moved into place, so
     * a failure never leaves a truncated `.vive` there, nor damages a file it was replacing.
     */
    suspend fun exportNotebook(notebookId: String, destination: File): NotebookExportResult =
        mutex.withLock {
            withContext(io) {
                exportNotebookLocked(notebookId) { archive -> deliver(archive, destination) }
            }
        }

    suspend fun importNotebook(source: File): NotebookImportResult = mutex.withLock {
        withContext(io) {
            val input = try {
                FileInputStream(source)
            } catch (failure: IOException) {
                throw NotebookTransferException(Strings.the_selected_notebook_file_could_not_be_opened, failure)
            }
            input.use { importNotebookLocked(it) }
        }
    }

    /** Test seam which exercises the exact package writer without a file. */
    internal suspend fun exportNotebook(notebookId: String, output: OutputStream): NotebookExportResult =
        mutex.withLock {
            withContext(io) {
                exportNotebookLocked(notebookId) { archive ->
                    FileInputStream(archive).use { it.copyTo(output) }
                    output.flush()
                }
            }
        }

    /** Test seam which still stages and validates every byte before importing. */
    internal suspend fun importNotebook(input: InputStream): NotebookImportResult =
        mutex.withLock { withContext(io) { importNotebookLocked(input) } }

    private suspend fun exportNotebookLocked(
        notebookId: String,
        deliver: (File) -> Unit,
    ): NotebookExportResult {
        val root = freshDirectory("export")
        try {
            val bundleDb = File(root, DATABASE_ENTRY)
            createConsistentSnapshot(bundleDb)
            val prepared = prepareNotebookDatabase(bundleDb, notebookId, root)

            val databaseFile = BundleFile(
                path = DATABASE_ENTRY,
                byteCount = bundleDb.length(),
                sha256 = bundleDb.sha256(),
            )
            val manifest = NotebookBundleManifest(
                bundleId = newId(),
                createdAt = clock(),
                sourceNotebookId = prepared.notebook.id,
                notebookName = prepared.notebook.name,
                database = databaseFile,
                attachments = prepared.attachments.map { staged ->
                    BundleAttachment(
                        id = staged.metadata.id,
                        path = "$ATTACHMENTS_PREFIX${staged.metadata.id}",
                        mimeType = staged.metadata.mimeType,
                        pixelWidth = staged.metadata.pixelWidth,
                        pixelHeight = staged.metadata.pixelHeight,
                        byteCount = staged.file.length(),
                        sha256 = staged.metadata.id,
                    )
                },
                counts = prepared.counts,
            )
            val manifestBytes = JSON.encodeToString(NotebookBundleManifest.serializer(), manifest).encodeToByteArray()
            require(manifestBytes.size <= MAX_MANIFEST_BYTES)

            val checksums = buildList {
                add(ManifestChecksum(MANIFEST_ENTRY, manifestBytes.sha256()))
                add(ManifestChecksum(DATABASE_ENTRY, databaseFile.sha256))
                prepared.attachments.forEach {
                    add(ManifestChecksum("$ATTACHMENTS_PREFIX${it.metadata.id}", it.metadata.id))
                }
            }.sortedBy { it.path }
            val checksumBytes = checksums.joinToString(separator = "\n", postfix = "\n") {
                "${it.sha256}  ${it.path}"
            }.encodeToByteArray()

            val archive = File(root, "notebook.vive")
            ZipOutputStream(FileOutputStream(archive).buffered()).use { zip ->
                zip.putBytes(MANIFEST_ENTRY, manifestBytes)
                zip.putFile(DATABASE_ENTRY, bundleDb, stored = false)
                prepared.attachments.forEach { staged ->
                    zip.putFile("$ATTACHMENTS_PREFIX${staged.metadata.id}", staged.file, stored = true)
                }
                zip.putBytes(CHECKSUMS_ENTRY, checksumBytes)
            }
            deliver(archive)
            return NotebookExportResult(prepared.notebook.name, archive.length())
        } catch (failure: NotebookTransferException) {
            throw failure
        } catch (failure: Throwable) {
            throw NotebookTransferException(Strings.the_notebook_could_not_be_exported, failure)
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun importNotebookLocked(input: InputStream): NotebookImportResult {
        val root = freshDirectory("import")
        try {
            val archive = File(root, "incoming.vive")
            FileOutputStream(archive).use { output ->
                input.copyBounded(output, MAX_ARCHIVE_BYTES, Strings.the_notebook_file_is_too_large)
            }
            val validated = validateArchive(archive, root)
            return install(validated)
        } catch (failure: NotebookTransferException) {
            throw failure
        } catch (failure: Throwable) {
            throw NotebookTransferException(Strings.this_vive_file_is_damaged_or_unsafe_and_was_not_imported, failure)
        } finally {
            root.deleteRecursively()
        }
    }

    /** On the writer, as `DatabaseBackupManager` does: SQLite refuses `VACUUM INTO` on a reader. */
    private suspend fun createConsistentSnapshot(target: File) {
        db.useWriterConnection { connection ->
            connection.usePrepared("VACUUM INTO ?") { statement ->
                statement.bindText(1, target.absolutePath)
                statement.step()
            }
        }
        check(target.isFile && target.length() > 0L)
    }

    /**
     * Writes [archive] next to [destination] and moves it into place. A destination that cannot be
     * written is the user's to fix, so it gets Android's message for a destination it cannot open.
     */
    private fun deliver(archive: File, destination: File) {
        val directory = destination.absoluteFile.parentFile
        val pending = File(directory, ".${destination.name}.${UUID.randomUUID()}.part")
        try {
            // Not `File.copyTo`, which would quietly create a folder that has gone missing.
            Files.copy(archive.toPath(), pending.toPath())
            moveAtomically(pending, destination)
        } catch (failure: IOException) {
            pending.delete()
            throw NotebookTransferException(Strings.the_selected_destination_could_not_be_opened, failure)
        }
    }

    private fun prepareNotebookDatabase(
        bundleFile: File,
        notebookId: String,
        root: File,
    ): PreparedExport = openBundle(bundleFile, SQLITE_OPEN_READWRITE).use { source ->
        // `VACUUM INTO` writes a rollback-journal file even from the WAL-mode live database, so
        // every commit below lands in the file that is zipped, with no `-wal` beside it.
        source.execSQL("PRAGMA foreign_keys = ON")
        val notebook = source.queryRows(
            "SELECT * FROM notebooks WHERE id = ? AND deletedAt IS NULL",
            notebookId,
            mapper = ::readNotebook,
        ).singleOrNull() ?: throw NotebookTransferException(Strings.the_selected_notebook_no_longer_exists)

        // A notebook whose contents were moved to the cloud has pages and no bodies, so it would
        // export as a bundle of empty documents that looks like a successful backup and is not one.
        val cloudOnly = source.queryRows("SELECT cloudOnlyAt FROM notebooks WHERE id = ?", notebookId) {
            it.isNull(0)
        }.singleOrNull() == false
        if (cloudOnly) throw NotebookTransferException(
            Strings.this_notebook_s_contents_are_in_the_cloud_bring_it_back,
        )

        source.transaction {
            // The sync layer goes first, and the triggers go before the tables they read: a
            // snapshot of a connected database carries `installSyncTriggers`' triggers, which would
            // queue this bundle's rewrites into the exporting account's outbox, and the importer
            // refuses any bundle holding a trigger at all.
            source.queryRows(
                "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name NOT LIKE 'sqlite_%'",
            ) { it.text(0) }.forEach { trigger ->
                source.execSQL("DROP TRIGGER IF EXISTS `$trigger`")
            }
            // Per-device bookkeeping about one account; none of it describes the notebook, and a
            // cursor from someone else's account would make the importer skip deltas.
            source.execSQL("DROP TABLE IF EXISTS sync_outbox")
            source.execSQL("DROP TABLE IF EXISTS sync_entity_states")
            source.execSQL("DROP TABLE IF EXISTS sync_state")
            source.exec("DELETE FROM notebooks WHERE id <> ?", notebookId)
            // Which shelf a notebook sits on is the account's business, not the notebook's content.
            // Dropped rather than nulled so the bundle's columns stay the format's.
            source.execSQL("ALTER TABLE notebooks DROP COLUMN closedAt")
            source.execSQL("ALTER TABLE notebooks DROP COLUMN cloudOnlyAt")
            // Likewise per device, and dropped for the same reason. Whether this installation
            // has released a picture from its account says nothing about the notebook, and the
            // saved-version picture index is a cache the importing device rebuilds: an imported
            // picture arrives unreleased and an imported version unindexed, which is what a
            // picture and a version this device has never swept are.
            source.execSQL("ALTER TABLE attachments DROP COLUMN releasedAt")
            source.execSQL("ALTER TABLE page_revisions DROP COLUMN pictureIds")
            // Installation identity never travels; derived caches are rebuilt by the importer.
            source.execSQL("DROP TABLE IF EXISTS local_metadata")
            source.execSQL("DROP TABLE IF EXISTS attachment_text")
            source.execSQL("DROP TABLE IF EXISTS ink_text")
            source.execSQL("DROP TABLE IF EXISTS ink_text_generation")
            // Android's database framework adds this table to every database it opens, so every
            // Android bundle carries it and Android's importer requires it: its table allowlist is
            // exact. The desktop's SQLite never creates it, so the bundle is given the table and
            // the one row Android would have written.
            source.execSQL("CREATE TABLE IF NOT EXISTS android_metadata (locale TEXT)")
            if (source.scalarLong("SELECT COUNT(*) FROM android_metadata") == 0L) {
                source.exec("INSERT INTO android_metadata (locale) VALUES (?)", Locale.getDefault().toString())
            }
        }

        val currentDocs = source.queryRows("SELECT * FROM page_content", mapper = ::readContent)
        val revisions = source.queryRows("SELECT * FROM page_revisions", mapper = ::readRevision)
        val attachmentRefs = linkedMapOf<String, Int>()
        currentDocs.forEach { row ->
            decodeDocument(row.format, row.docJson.encodeToByteArray()).imageIds().forEach { id ->
                attachmentRefs[id] = attachmentRefs.getOrDefault(id, 0) + 1
            }
        }
        revisions.forEach { row ->
            DocumentRevisionPayload.unpack(row).imageIds().forEach { id ->
                attachmentRefs[id] = attachmentRefs.getOrDefault(id, 0) + 1
            }
        }

        val metadata = if (attachmentRefs.isEmpty()) emptyList() else source.queryRows(
            "SELECT * FROM attachments",
            mapper = ::readAttachment,
        ).filter { it.id in attachmentRefs }
        if (metadata.map { it.id }.toSet() != attachmentRefs.keys) {
            throw NotebookTransferException(Strings.the_notebook_references_an_attachment_whose_metadata_is_missing)
        }

        val stagedAttachments = metadata.sortedBy { it.id }.map { row ->
            requireValidAttachmentMetadata(row)
            val original = attachmentStore.fileFor(row.id)
            if (original == null || !original.isFile || original.length() != row.byteCount ||
                original.sha256() != row.id
            ) {
                throw NotebookTransferException(Strings.attachment_value_is_missing_or_damaged(row.id.take(12)))
            }
            val staged = File(root, "export-attachments/${row.id}").apply {
                parentFile?.mkdirs()
            }
            original.copyTo(staged)
            StagedAttachment(row.copy(refCount = attachmentRefs.getValue(row.id)), staged)
        }

        source.transaction {
            source.execSQL("DELETE FROM attachments")
            stagedAttachments.forEach { source.insertAttachment(it.metadata) }
            source.execSQL("CREATE TABLE vive_bundle (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            source.exec(
                "INSERT INTO vive_bundle VALUES ('format', ?), ('formatVersion', ?), " +
                    "('appSchemaVersion', ?), ('notebookId', ?)",
                FORMAT, FORMAT_VERSION.toString(), APP_SCHEMA_VERSION.toString(), notebookId,
            )
            source.execSQL("PRAGMA application_id = $APPLICATION_ID")
            source.execSQL("PRAGMA user_version = $FORMAT_VERSION")
        }
        source.execSQL("VACUUM")
        requireDatabaseOkay(source)
        PreparedExport(
            notebook = notebook,
            attachments = stagedAttachments,
            counts = BundleCounts(
                sections = source.scalarLong("SELECT COUNT(*) FROM sections").toInt(),
                pages = source.scalarLong("SELECT COUNT(*) FROM pages").toInt(),
                strokes = source.scalarLong("SELECT COUNT(*) FROM ink_strokes").toInt(),
                revisions = source.scalarLong("SELECT COUNT(*) FROM page_revisions").toInt(),
                attachments = stagedAttachments.size,
            ),
        )
    }

    private fun validateArchive(archive: File, root: File): ValidatedBundle {
        if (archive.length() !in 1..MAX_ARCHIVE_BYTES) fail(Strings.the_notebook_file_is_empty_or_too_large)
        val extractedRoot = File(root, "extracted").apply { mkdirs() }
        val names = linkedSetOf<String>()
        var expandedBytes = 0L
        ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!names.add(entry.name)) fail(Strings.the_archive_contains_a_duplicate_entry)
                if (names.size > MAX_ENTRIES) fail(Strings.the_archive_contains_too_many_entries)
                validateEntryName(entry.name)
                if (entry.isDirectory) fail(Strings.directory_entries_are_not_allowed_in_a_vive_file)
                val limit = entryLimit(entry.name)
                if (entry.size > limit) fail(Strings.archive_entry_value_is_too_large(entry.name))
                val target = safeTarget(extractedRoot, entry.name)
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output ->
                    expandedBytes += zip.copyBounded(
                        output,
                        limit,
                        Strings.archive_entry_value_expands_beyond_its_limit(entry.name),
                    )
                }
                if (expandedBytes > MAX_EXPANDED_BYTES) fail(Strings.the_archive_expands_beyond_its_limit)
                zip.closeEntry()
            }
        }
        if (MANIFEST_ENTRY !in names || DATABASE_ENTRY !in names || CHECKSUMS_ENTRY !in names) {
            fail(Strings.the_archive_is_missing_required_files)
        }

        val manifestFile = File(extractedRoot, MANIFEST_ENTRY)
        val manifest = runCatching {
            JSON.decodeFromString(NotebookBundleManifest.serializer(), manifestFile.readText())
        }.getOrElse { fail(Strings.manifest_not_valid_metadata, it) }
        validateManifest(manifest)

        val expectedNames = buildSet {
            add(MANIFEST_ENTRY)
            add(DATABASE_ENTRY)
            add(CHECKSUMS_ENTRY)
            manifest.attachments.forEach { add(it.path) }
        }
        if (names != expectedNames) fail(Strings.the_archive_contains_missing_or_unexpected_files)

        val checksums = parseChecksums(File(extractedRoot, CHECKSUMS_ENTRY))
        val checksummedNames = expectedNames - CHECKSUMS_ENTRY
        if (checksums.keys != checksummedNames) fail(Strings.the_checksum_list_does_not_exactly_match_the_archive)
        checksums.forEach { (path, expected) ->
            if (File(extractedRoot, path).sha256() != expected) fail(Strings.checksum_verification_failed_for_value(path))
        }
        if (manifest.database.sha256 != checksums[DATABASE_ENTRY] ||
            manifest.database.byteCount != File(extractedRoot, DATABASE_ENTRY).length()
        ) fail(Strings.the_database_metadata_does_not_match_its_file)
        manifest.attachments.forEach { attachment ->
            val file = File(extractedRoot, attachment.path)
            if (attachment.sha256 != checksums[attachment.path] || attachment.byteCount != file.length()) {
                fail(Strings.attachment_metadata_does_not_match_value(attachment.path))
            }
        }

        val data = validateBundleDatabase(File(extractedRoot, DATABASE_ENTRY), manifest)
        val stagedAttachments = manifest.attachments.associate { descriptor ->
            val metadata = data.attachments.singleOrNull { it.id == descriptor.id }
                ?: fail(Strings.attachment_metadata_is_missing_for_value(descriptor.id))
            if (
                descriptor.path != "$ATTACHMENTS_PREFIX${descriptor.id}" ||
                descriptor.sha256 != descriptor.id ||
                descriptor.mimeType != metadata.mimeType ||
                descriptor.pixelWidth != metadata.pixelWidth ||
                descriptor.pixelHeight != metadata.pixelHeight ||
                descriptor.byteCount != metadata.byteCount
            ) fail(Strings.attachment_metadata_is_inconsistent_for_value(descriptor.id))
            descriptor.id to File(extractedRoot, descriptor.path)
        }
        data.attachments.forEach { metadata ->
            requireValidAttachmentMetadata(metadata)
            val file = stagedAttachments[metadata.id] ?: fail(Strings.attachment_value_is_absent(metadata.id))
            if (file.sha256() != metadata.id) fail(Strings.attachment_value_is_damaged(metadata.id.take(12)))
            val bounds = boundsOf(file) ?: fail(Strings.attachment_value_is_not_a_readable_image(metadata.id.take(12)))
            if (bounds.width != metadata.pixelWidth || bounds.height != metadata.pixelHeight) {
                fail(Strings.attachment_value_has_incorrect_image_dimensions(metadata.id.take(12)))
            }
        }
        return ValidatedBundle(manifest, data, stagedAttachments)
    }

    private fun validateBundleDatabase(file: File, manifest: NotebookBundleManifest): BundleData {
        if (!file.isFile || file.length() !in 1..MAX_DATABASE_BYTES || !file.hasSQLiteHeader()) {
            fail(Strings.notebook_not_readable_database)
        }
        val source = runCatching { openBundle(file, SQLITE_OPEN_READONLY) }
            .getOrElse { fail(Strings.notebook_not_readable_database, it) }
        return source.use { database ->
            database.execSQL("PRAGMA query_only = ON")
            requireDatabaseOkay(database)
            if (database.scalarLong("PRAGMA application_id") != APPLICATION_ID.toLong() ||
                database.scalarLong("PRAGMA user_version") != FORMAT_VERSION.toLong()
            ) fail(Strings.the_notebook_database_has_an_unsupported_format)
            validateSchema(database)
            val bundleMetadata = database.queryRows(
                "SELECT key, value FROM vive_bundle",
            ) { it.text(0) to it.text(1) }.toMap()
            if (
                bundleMetadata["format"] != FORMAT ||
                bundleMetadata["formatVersion"] != FORMAT_VERSION.toString() ||
                bundleMetadata["appSchemaVersion"] != APP_SCHEMA_VERSION.toString() ||
                bundleMetadata["notebookId"] != manifest.sourceNotebookId
            ) fail(Strings.the_manifest_and_notebook_database_describe_different_bundles)

            validateTableCounts(database, manifest.counts)

            val notebooks = database.queryRows("SELECT * FROM notebooks", mapper = ::readNotebook)
            val sections = database.queryRows("SELECT * FROM sections", mapper = ::readSection)
            val pages = database.queryRows("SELECT * FROM pages", mapper = ::readPage)
            val contents = database.queryRows("SELECT * FROM page_content", mapper = ::readContent)
            val revisions = database.queryRows("SELECT * FROM page_revisions", mapper = ::readRevision)
            val strokes = database.queryRows("SELECT * FROM ink_strokes", mapper = ::readStroke)
            val erases = database.queryRows("SELECT * FROM ink_erases", mapper = ::readErase)
            val eraseTargets = database.queryRows(
                "SELECT eraseId, strokeId FROM ink_erase_targets",
            ) { InkEraseTargetEntity(it.text(0), it.text(1)) }
            val moves = database.queryRows("SELECT * FROM ink_moves", mapper = ::readMove)
            val moveTargets = database.queryRows(
                "SELECT moveId, strokeId FROM ink_move_targets",
            ) { InkMoveTargetEntity(it.text(0), it.text(1)) }
            val attachments = database.queryRows("SELECT * FROM attachments", mapper = ::readAttachment)

            validateCounts(manifest.counts, sections, pages, strokes, revisions, attachments)
            val notebook = notebooks.singleOrNull()
                ?: fail("A .vive file must contain exactly one notebook.")
            if (notebook.id != manifest.sourceNotebookId || notebook.name != manifest.notebookName ||
                notebook.deletedAt != null
            ) fail(Strings.the_manifest_notebook_does_not_match_notebook_sqlite)
            if (sections.any { it.notebookId != notebook.id }) {
                fail(Strings.the_notebook_contains_invalid_sections)
            }
            if (sections.any { !validId(it.id) || it.name.length > MAX_NAME_CHARS }) {
                fail(Strings.the_notebook_contains_invalid_section_metadata)
            }
            val sectionIds = sections.map { it.id }.toSet()
            if (pages.any { it.sectionId !in sectionIds }) {
                fail(Strings.the_notebook_contains_invalid_pages)
            }
            if (pages.any {
                    !validId(it.id) || it.title.length > MAX_NAME_CHARS ||
                        it.preview.length > MAX_PREVIEW_CHARS
                }
            ) fail(Strings.the_notebook_contains_invalid_page_metadata)
            val pageIds = pages.map { it.id }.toSet()
            if (contents.size != pages.size || contents.map { it.pageId }.toSet() != pageIds) {
                fail(Strings.every_imported_page_must_have_exactly_one_document)
            }
            if (strokes.any { it.pageId !in pageIds } || erases.any { it.pageId !in pageIds } ||
                moves.any { it.pageId !in pageIds } || revisions.any { it.pageId !in pageIds }
            ) fail(Strings.the_notebook_contains_records_outside_its_pages)

            val decodedCurrent = contents.associate { row ->
                if (row.docJson.encodeToByteArray().size > DocumentRevisionPayload.MAX_DOCUMENT_BYTES) {
                    fail("A page document exceeds the safe import limit.")
                }
                row.pageId to decodeDocument(row.format, row.docJson.encodeToByteArray())
            }
            strokes.forEach(::validateStroke)
            erases.forEach(::validateErase)
            moves.forEach(::validateMove)

            val strokesById = strokes.associateBy { it.id }
            val erasesById = erases.associateBy { it.id }
            val movesById = moves.associateBy { it.id }
            if (eraseTargets.any { target ->
                    !validId(target.eraseId) || !validId(target.strokeId) ||
                        target.eraseId !in erasesById
                }
            ) fail(Strings.the_notebook_contains_invalid_erase_targets)
            if (eraseTargets.any { target ->
                    val erase = erasesById[target.eraseId]
                    val stroke = strokesById[target.strokeId]
                    // A missing stroke is an inert, valid target: stroke tombstones are collected
                    // after seven days without rewriting the immutable erase that named them.
                    // Only a stored stroke can cross a page.
                    stroke != null && erase?.pageId != stroke.pageId
                }
            ) fail(Strings.an_erase_target_crosses_a_page_boundary)
            if (moveTargets.any { target ->
                    !validId(target.moveId) || !validId(target.strokeId) ||
                        target.moveId !in movesById
                }
            ) fail(Strings.the_notebook_contains_invalid_move_targets)
            if (moveTargets.any { target ->
                    val move = movesById[target.moveId]
                    val stroke = strokesById[target.strokeId]
                    // See the erase-target rule above: missing target strokes are valid and inert.
                    stroke != null && move?.pageId != stroke.pageId
                }
            ) fail("A move target crosses a page boundary.")

            val revisionSnapshots = revisions.associate { row ->
                if (!validId(row.id) || !SHA256.matches(row.sha256) ||
                    !SHA256.matches(row.inkSha256)
                ) fail("A saved page version has invalid metadata.")
                val doc = runCatching { DocumentRevisionPayload.unpack(row) }
                    .getOrElse { fail("A saved page version is damaged.", it) }
                val snapshot = runCatching { InkRevisionPayload.unpack(row) }
                    .getOrElse { fail("A saved ink version is damaged.", it) }
                if (snapshot.strokes.any { strokesById[it.id]?.pageId != row.pageId } ||
                    snapshot.eraseIds.any { erasesById[it]?.pageId != row.pageId } ||
                    snapshot.moveIds.any { movesById[it]?.pageId != row.pageId }
                ) fail("A saved version references data outside its page.")
                row.id to ValidatedRevision(row, doc.imageIds(), snapshot)
            }

            val referencedAttachments = buildSet {
                decodedCurrent.values.forEach { addAll(it.imageIds()) }
                revisionSnapshots.values.forEach { addAll(it.imageIds) }
            }
            if (attachments.map { it.id }.toSet() != referencedAttachments ||
                manifest.attachments.map { it.id }.toSet() != referencedAttachments
            ) fail(Strings.the_attachment_list_does_not_exactly_match_the_notebook_documents)

            BundleData(
                notebook,
                sections.sortedBy { it.sortIndex },
                pages.sortedWith(compareBy<PageEntity> { it.sectionId }.thenBy { it.sortIndex }),
                contents,
                revisions,
                revisionSnapshots,
                strokes,
                erases,
                eraseTargets,
                moves,
                moveTargets,
                attachments,
            )
        }
    }

    private suspend fun install(bundle: ValidatedBundle): NotebookImportResult {
        // Resolved before anything is read, so every collision below is asked about the notebook
        // this file is actually an update of.
        val data = bundle.data.installedUnder(installTarget(bundle.data))
        val notebookId = data.notebook.id
        requireContentsOnDevice(notebookId)
        val existingRows = auditLiveCollisions(data)
        val existingNotebook = existingRows.notebook
        val localSections = db.sectionDao().allInNotebook(notebookId)
        val localPages = db.pageDao().allInNotebook(notebookId)
        val localRevisions = loadChunked(
            data.pages.map { it.id },
            db.pageRevisionDao()::byPageIds,
        )
        // Import is an explicit restore boundary, not an implicit background sync. Every row the
        // chosen archive carries is authoritative for its stable id, even when the device has a
        // newer edit or tombstone. Local-only rows absent from the archive are tombstoned so the
        // visible notebook is the saved state, not a union with whatever happened after export.
        val restoringNotebook = existingNotebook?.deletedAt != null
        val restoringSectionIds = data.sections.mapNotNullTo(mutableSetOf()) { incoming ->
            incoming.id.takeIf {
                incoming.deletedAt == null && existingRows.sections[incoming.id]?.deletedAt != null
            }
        }
        val restoringPageIds = data.pages.mapNotNullTo(mutableSetOf()) { incoming ->
            incoming.id.takeIf {
                incoming.deletedAt == null && existingRows.pages[incoming.id]?.deletedAt != null
            }
        }
        val restored = restoringNotebook || restoringSectionIds.isNotEmpty() || restoringPageIds.isNotEmpty()
        val importedAt = clock()

        /** A restored archive state is a new mutation and must supersede the state it replaces. */
        fun importedTimestamp(incoming: Long, existing: Long): Long = maxOf(
            importedAt,
            incoming,
            if (existing == Long.MAX_VALUE) Long.MAX_VALUE else existing + 1L,
        )

        val installedFiles = mutableListOf<File>()
        try {
            bundle.attachmentFiles.forEach { (id, staged) ->
                val target = attachmentStore.fileFor(id) ?: fail(Strings.an_attachment_has_an_invalid_identity)
                if (target.exists()) {
                    if (!target.isFile || target.length() != staged.length() || target.sha256() != id) {
                        fail(Strings.an_existing_attachment_with_the_same_identity_is_damaged)
                    }
                } else {
                    target.parentFile?.mkdirs()
                    val pending = File(target.parentFile, "$id.${UUID.randomUUID()}.import")
                    staged.copyTo(pending)
                    if (pending.sha256() != id) {
                        pending.delete()
                        fail(Strings.an_attachment_changed_while_it_was_being_installed)
                    }
                    moveAtomically(pending, target)
                    installedFiles += target
                }
            }

            db.withTransaction {
                val attachmentDao = db.attachmentDao()
                data.attachments.forEach { metadata ->
                    if (metadata.id !in existingRows.attachments) {
                        attachmentDao.insert(metadata.copy(refCount = 0))
                    }
                }

                val notebookDao = db.notebookDao()
                if (existingNotebook == null) {
                    // Rechecked inside the write transaction so an edit which clears the marker can
                    // never race an import and lose a real notebook.
                    val replaceableStarter = replaceableStarterNotebook()
                    replaceableStarter?.let { notebookDao.retirePlaceholder(it.id, importedAt) }
                    notebookDao.upsert(
                        data.notebook.copy(
                            sortIndex = replaceableStarter?.sortIndex ?: notebookDao.nextSortIndex(),
                            expanded = true,
                        ),
                    )
                } else if (!existingNotebook.sameImportedStateAs(data.notebook)) {
                    // Expansion and top-level ordering are this device's navigation state.
                    notebookDao.upsert(
                        data.notebook.copy(
                            sortIndex = existingNotebook.sortIndex,
                            expanded = if (restoringNotebook) true else existingNotebook.expanded,
                            updatedAt = importedTimestamp(
                                data.notebook.updatedAt,
                                existingNotebook.updatedAt,
                            ),
                        ),
                    )
                }
                db.localMetadataDao().delete(NotesRepository.REPLACEABLE_STARTER_KEY)

                // Marks these bytes as an explicit restore of a notebook the server has never taken,
                // which sync reads to tell a retired id it must move the import off from one the
                // account erased. Not written when the server already holds this notebook, and a
                // stale marker is cleared: an id the account has stored is one Permanently delete
                // must still be able to reach.
                if (db.syncDao().entityState(SYNC_KIND_NOTEBOOK, notebookId) == null) {
                    db.localMetadataDao().put(
                        LocalMetadataEntity(NotesRepository.importedNotebookKey(notebookId), "$importedAt"),
                    )
                } else {
                    db.localMetadataDao().delete(NotesRepository.importedNotebookKey(notebookId))
                }

                val sectionDao = db.sectionDao()
                val importedSectionIds = data.sections.mapTo(hashSetOf()) { it.id }
                data.sections.forEach { row ->
                    val existing = existingRows.sections[row.id]
                    if (existing == null) {
                        sectionDao.upsert(row)
                    } else if (!existing.sameImportedStateAs(row)) {
                        sectionDao.upsert(
                            row.copy(updatedAt = importedTimestamp(row.updatedAt, existing.updatedAt)),
                        )
                    }
                }
                localSections.filter { it.id !in importedSectionIds && it.deletedAt == null }
                    .forEach { sectionDao.softDelete(it.id, importedAt) }

                val pageDao = db.pageDao()
                val importedPageIds = data.pages.mapTo(hashSetOf()) { it.id }
                data.pages.forEach { row ->
                    val existing = existingRows.pages[row.id]
                    if (existing == null) {
                        pageDao.upsert(row)
                    } else if (!existing.sameImportedStateAs(row)) {
                        pageDao.upsert(
                            row.copy(updatedAt = importedTimestamp(row.updatedAt, existing.updatedAt)),
                        )
                    }
                }
                localPages.filter { it.id !in importedPageIds && it.deletedAt == null }
                    .forEach { pageDao.softDelete(it.id, importedAt) }

                val attachmentDeltas = linkedMapOf<String, Int>()
                val contentDao = db.pageContentDao()
                data.contents.forEach { row ->
                    val existing = existingRows.contents[row.pageId]
                    if (existing == null || !existing.sameImportedStateAs(row)) {
                        existing?.safeImageIds().orEmpty().forEach { id ->
                            attachmentDeltas[id] = attachmentDeltas.getOrDefault(id, 0) - 1
                        }
                        row.safeImageIds().forEach { id ->
                            attachmentDeltas[id] = attachmentDeltas.getOrDefault(id, 0) + 1
                        }
                        contentDao.upsert(
                            if (existing == null) row else row.copy(
                                updatedAt = importedTimestamp(row.updatedAt, existing.updatedAt),
                            ),
                        )
                    }
                }

                // Reset every archived page's active ink first. Upserting every carried operation
                // below then reconstructs exactly the archive state; local-only operations stay as
                // tombstoned history rather than leaking onto the restored canvas.
                data.pages.forEach { page ->
                    db.inkTextDao().deleteForPage(page.id)
                    db.inkTextDao().bumpGeneration(page.id)
                    db.inkStrokeDao().softDeletePage(page.id, importedAt)
                    db.inkEraseDao().softDeletePage(page.id, importedAt)
                    db.inkMoveDao().softDeletePage(page.id, importedAt)
                }
                if (data.strokes.isNotEmpty()) db.inkStrokeDao().upsert(data.strokes)
                data.erases.forEach { db.inkEraseDao().upsert(it) }
                data.erases.map { it.id }.chunked(SQLITE_BIND_CHUNK).forEach { ids ->
                    db.inkEraseDao().deleteTargetsForErases(ids)
                }
                if (data.eraseTargets.isNotEmpty()) {
                    db.inkEraseDao().insertTargetsIfAbsent(data.eraseTargets)
                }
                data.moves.forEach { db.inkMoveDao().upsert(it) }
                data.moves.map { it.id }.chunked(SQLITE_BIND_CHUNK).forEach { ids ->
                    db.inkMoveDao().deleteTargetsForMoves(ids)
                }
                if (data.moveTargets.isNotEmpty()) {
                    db.inkMoveDao().insertTargetsIfAbsent(data.moveTargets)
                }

                val revisionDao = db.pageRevisionDao()
                val importedRevisionIds = data.revisions.mapTo(hashSetOf()) { it.id }
                localRevisions.map { it.id }.filterNot(importedRevisionIds::contains)
                    .chunked(SQLITE_BIND_CHUNK)
                    .forEach { revisionDao.deleteByIds(it) }
                val newRevisions = data.revisions.filter { it.id !in existingRows.revisions }
                newRevisions.forEach { revisionDao.insertIfAbsent(it) }
                newRevisions.map { it.pageId }.distinct().forEach { pageId ->
                    revisionDao.trimToNewest(pageId, NotesRepository.MAX_REVISIONS_PER_PAGE)
                }

                attachmentDeltas.forEach { (id, delta) ->
                    repeat(delta.coerceAtLeast(0)) { attachmentDao.retain(id) }
                    repeat((-delta).coerceAtLeast(0)) { attachmentDao.release(id) }
                }
            }
        } catch (failure: Throwable) {
            installedFiles.forEach(File::delete)
            throw failure
        }

        val firstSection = db.sectionDao().inNotebook(notebookId).firstOrNull()?.id
        val firstPage = firstSection?.let { sectionId ->
            db.pageDao().inNotebook(notebookId).firstOrNull { it.sectionId == sectionId }?.id
        }
        val finalNotebook = db.notebookDao().byId(notebookId) ?: error("synced notebook disappeared")
        return NotebookImportResult(
            notebookId,
            finalNotebook.name,
            firstSection,
            firstPage,
            created = existingNotebook == null,
            restored = restored,
        )
    }

    /**
     * A clean install creates one starter so the UI is usable before a restore. Its UUID is marked
     * locally after seeding and the marker is cleared on the first content mutation. Only that
     * exact, sole, still-live generated notebook can be replaced by a restored stable UUID.
     */
    /**
     * Where this device keeps the archive's notebook: the id the archive names, unless the account
     * permanently deleted it and `HierarchySync.remapPurgedImport` moved the import to a fresh one.
     *
     * The device that made the move records it, but `local_metadata` never syncs. Every other
     * device, and this one after a reinstall, receives the moved notebook with the archive's own
     * section ids under it and no record of the id it replaced, so those sections are what find it.
     * The move copies the notebook row under the new id, so the notebook holding them also carries
     * the archive's `createdAt`. Sections held anywhere else are left for [auditLiveCollisions] to
     * refuse.
     */
    private suspend fun installTarget(archive: BundleData): String {
        val archiveId = archive.notebook.id
        val recorded = db.localMetadataDao().value(NotesRepository.importRemapKey(archiveId)) ?: archiveId
        val holder = loadChunked(archive.sections.map { it.id }, db.sectionDao()::byIds)
            .mapTo(hashSetOf()) { it.notebookId }
            .singleOrNull()
            ?.takeIf { it != recorded }
            ?: return recorded
        if (db.notebookDao().byId(holder)?.createdAt != archive.notebook.createdAt) return recorded
        return holder
    }

    /**
     * Refuses a notebook this device holds without its contents: one moved to the cloud, or first
     * seen here already closed.
     *
     * A restore makes the notebook match the archive by tombstoning whatever this device holds
     * beyond it, and here that is only the index. Strokes added since the export were never
     * downloaded, so they would stay live on the server and every other device while this one
     * showed the archive, and a page added since would go to Deleted Items with no body to restore.
     */
    private suspend fun requireContentsOnDevice(notebookId: String) {
        val notebook = db.notebookDao().byId(notebookId) ?: return
        if (notebook.cloudOnlyAt == null &&
            db.localMetadataDao().value(deferredNotebookContentKey(notebookId)) == null
        ) return
        fail(Strings.value_is_in_the_cloud_bring_it_back_from_closed_notebooks(notebook.name))
    }

    private suspend fun replaceableStarterNotebook(): NotebookEntity? {
        val metadata = db.localMetadataDao()
        val starterId = metadata.value(NotesRepository.REPLACEABLE_STARTER_KEY) ?: return null
        if (db.notebookDao().count() != 1) return null
        return db.notebookDao().byId(starterId)?.takeIf { it.deletedAt == null }
    }

    /** Stable ids enable sync, so an id may only meet the same immutable object in the live DB. */
    private suspend fun auditLiveCollisions(data: BundleData): ExistingBundleRows {
        val notebookId = data.notebook.id
        val sections = loadChunked(data.sections.map { it.id }, db.sectionDao()::byIds)
            .associateBy { it.id }
        val pages = loadChunked(data.pages.map { it.id }, db.pageDao()::byIds)
            .associateBy { it.id }
        val contents = loadChunked(data.contents.map { it.pageId }, db.pageContentDao()::byIds)
            .associateBy { it.pageId }
        val strokes = loadChunked(data.strokes.map { it.id }, db.inkStrokeDao()::byIds)
            .associateBy { it.id }
        val erases = loadChunked(data.erases.map { it.id }, db.inkEraseDao()::byIds)
            .associateBy { it.id }
        val moves = loadChunked(data.moves.map { it.id }, db.inkMoveDao()::byIds)
            .associateBy { it.id }
        val revisions = loadChunked(data.revisions.map { it.id }, db.pageRevisionDao()::byGlobalIds)
            .associateBy { it.id }
        val attachments = loadChunked(data.attachments.map { it.id }, db.attachmentDao()::byIds)
            .associateBy { it.id }
        val eraseTargets = loadChunked(
            data.erases.map { it.id },
            db.inkEraseDao()::targetsForErases,
        ).toSet()
        val moveTargets = loadChunked(
            data.moves.map { it.id },
            db.inkMoveDao()::targetsForMoves,
        ).toSet()
        data.sections.forEach { incoming ->
            sections[incoming.id]?.let { existing ->
                if (existing.notebookId != notebookId) fail("A section id belongs to another notebook.")
            }
        }
        data.pages.forEach { incoming ->
            pages[incoming.id]?.let { existing ->
                if (existing.sectionId != incoming.sectionId) fail("A page id belongs to another section.")
            }
        }
        data.strokes.forEach { incoming ->
            strokes[incoming.id]?.let { existing ->
                if (!existing.sameImmutableInkAs(incoming)) fail(Strings.an_ink_id_has_conflicting_geometry)
            }
        }
        data.erases.forEach { incoming ->
            erases[incoming.id]?.let { existing ->
                if (!existing.sameImmutableInkAs(incoming)) fail(Strings.an_erase_id_has_conflicting_geometry)
            }
        }
        data.moves.forEach { incoming ->
            moves[incoming.id]?.let { existing ->
                if (!existing.sameImmutableInkAs(incoming)) fail("A move id has conflicting geometry.")
            }
        }
        data.revisions.forEach { incoming ->
            revisions[incoming.id]?.let { existing ->
                if (existing != incoming) fail("A version id has conflicting content.")
            }
        }
        data.attachments.forEach { incoming ->
            attachments[incoming.id]?.let { existing ->
                if (existing.mimeType != incoming.mimeType ||
                    existing.pixelWidth != incoming.pixelWidth ||
                    existing.pixelHeight != incoming.pixelHeight ||
                    existing.byteCount != incoming.byteCount
                ) fail(Strings.an_attachment_id_has_conflicting_metadata)
            }
        }
        return ExistingBundleRows(
            notebook = db.notebookDao().byId(notebookId),
            sections = sections,
            pages = pages,
            contents = contents,
            strokes = strokes,
            erases = erases,
            eraseTargets = eraseTargets,
            moves = moves,
            moveTargets = moveTargets,
            revisions = revisions,
            attachments = attachments,
        )
    }

    private suspend fun <T> loadChunked(
        ids: List<String>,
        load: suspend (List<String>) -> List<T>,
    ): List<T> = buildList {
        ids.chunked(SQLITE_BIND_CHUNK).forEach { addAll(load(it)) }
    }

    private fun validateSchema(database: SQLiteConnection) {
        val objects = database.queryRows(
            "SELECT type, name, sql FROM sqlite_master " +
                "WHERE name NOT LIKE 'sqlite_%' ORDER BY name",
        ) { Triple(it.text(0), it.text(1), it.nullableText(2)) }
        if (objects.any { it.first == "view" || it.first == "trigger" }) {
            fail(Strings.views_and_triggers_are_not_allowed_in_a_notebook_bundle)
        }
        val tables = objects.filter { it.first == "table" }.associate { it.second to it.third }
        if (tables.keys != EXPECTED_COLUMNS.keys || tables.values.any { sql ->
                sql == null || !sql.trimStart().startsWith("CREATE TABLE", ignoreCase = true)
            }
        ) fail(Strings.the_notebook_database_schema_is_not_recognized)
        EXPECTED_COLUMNS.forEach { (table, expected) ->
            val actual = database.queryRows("PRAGMA table_info(`$table`)") { it.text(1) }
            // SQLite preserves ALTER TABLE insertion order, while a fresh Room database puts
            // colorFollowsTheme alongside the entity field. Both are the same schema to callers.
            if (actual.size != expected.size || actual.toSet() != expected.toSet()) {
                fail(Strings.the_value_table_has_an_unexpected_schema(table))
            }
        }
    }

    /** Reject resource-exhaustion databases before their rows or blobs are materialized. */
    private fun validateTableCounts(database: SQLiteConnection, expected: BundleCounts) {
        fun count(table: String) = database.scalarLong("SELECT COUNT(*) FROM `$table`")
        val notebooks = count("notebooks")
        val sections = count("sections")
        val pages = count("pages")
        val contents = count("page_content")
        val revisions = count("page_revisions")
        val strokes = count("ink_strokes")
        val erases = count("ink_erases")
        val eraseTargets = count("ink_erase_targets")
        val moves = count("ink_moves")
        val moveTargets = count("ink_move_targets")
        val attachments = count("attachments")
        if (notebooks != 1L || sections != expected.sections.toLong() ||
            pages != expected.pages.toLong() || contents != pages ||
            revisions != expected.revisions.toLong() || strokes != expected.strokes.toLong() ||
            attachments != expected.attachments.toLong()
        ) fail(Strings.the_manifest_record_counts_do_not_match_notebook_sqlite)
        if (sections > MAX_SECTIONS || pages > MAX_PAGES || revisions > MAX_REVISIONS ||
            strokes > MAX_STROKES || erases > MAX_INK_OPERATIONS || moves > MAX_INK_OPERATIONS ||
            eraseTargets > MAX_INK_TARGETS || moveTargets > MAX_INK_TARGETS ||
            attachments > MAX_ATTACHMENTS
        ) fail(Strings.the_notebook_database_exceeds_safe_import_limits)
        val perPage = database.scalarLong(
            "SELECT COALESCE(MAX(revisionCount), 0) FROM (" +
                "SELECT COUNT(*) AS revisionCount FROM page_revisions GROUP BY pageId)",
        )
        if (perPage > NotesRepository.MAX_REVISIONS_PER_PAGE) {
            fail("A page contains too many saved versions.")
        }
    }

    private fun validateManifest(manifest: NotebookBundleManifest) {
        if (manifest.format != FORMAT || manifest.formatVersion != FORMAT_VERSION ||
            manifest.appSchemaVersion != APP_SCHEMA_VERSION
        ) fail(Strings.this_vive_format_is_not_supported_by_this_version_of_vivenotes)
        if (!UUIDISH.matches(manifest.bundleId) || !UUIDISH.matches(manifest.sourceNotebookId)) {
            fail(Strings.the_manifest_contains_invalid_identifiers)
        }
        if (manifest.notebookName.isBlank() || manifest.notebookName.length > MAX_NAME_CHARS) {
            fail(Strings.the_manifest_contains_an_invalid_notebook_name)
        }
        if (manifest.database.path != DATABASE_ENTRY ||
            manifest.database.byteCount !in 1..MAX_DATABASE_BYTES ||
            !SHA256.matches(manifest.database.sha256)
        ) fail(Strings.the_manifest_contains_invalid_database_metadata)
        if (manifest.attachments.size > MAX_ATTACHMENTS ||
            manifest.attachments.map { it.id }.toSet().size != manifest.attachments.size
        ) fail(Strings.the_manifest_contains_too_many_or_duplicate_attachments)
        manifest.attachments.forEach { attachment ->
            if (!SHA256.matches(attachment.id) || attachment.sha256 != attachment.id ||
                attachment.path != "$ATTACHMENTS_PREFIX${attachment.id}" ||
                attachment.byteCount !in 1..MAX_ATTACHMENT_BYTES ||
                attachment.pixelWidth !in 1..AttachmentStore.MAX_DIMENSION ||
                attachment.pixelHeight !in 1..AttachmentStore.MAX_DIMENSION ||
                attachment.mimeType !in ALLOWED_IMAGE_TYPES
            ) fail(Strings.the_manifest_contains_invalid_attachment_metadata)
        }
        val counts = manifest.counts
        if (counts.sections !in 0..MAX_SECTIONS || counts.pages !in 0..MAX_PAGES ||
            counts.strokes !in 0..MAX_STROKES || counts.revisions !in 0..MAX_REVISIONS ||
            counts.attachments != manifest.attachments.size
        ) fail(Strings.the_manifest_declares_unsafe_record_counts)
    }

    private fun validateCounts(
        expected: BundleCounts,
        sections: List<SectionEntity>,
        pages: List<PageEntity>,
        strokes: List<InkStrokeEntity>,
        revisions: List<PageRevisionEntity>,
        attachments: List<AttachmentEntity>,
    ) {
        if (
            sections.size != expected.sections || pages.size != expected.pages ||
            strokes.size != expected.strokes || revisions.size != expected.revisions ||
            attachments.size != expected.attachments
        ) fail(Strings.the_manifest_record_counts_do_not_match_notebook_sqlite)
        if (sections.size > MAX_SECTIONS || pages.size > MAX_PAGES || strokes.size > MAX_STROKES ||
            revisions.size > MAX_REVISIONS || attachments.size > MAX_ATTACHMENTS
        ) fail(Strings.the_notebook_database_exceeds_safe_import_limits)
    }

    private fun validateStroke(row: InkStrokeEntity) {
        if (!validId(row.id) || !validId(row.pageId) ||
            row.brushFamily.length > MAX_FORMAT_CHARS || (row.groupId?.length ?: 0) > MAX_ID_CHARS ||
            !row.sizeDp.isFinite() || row.sizeDp <= 0f || row.sizeDp > 1_000f ||
            !row.epsilon.isFinite() || row.epsilon <= 0f || row.epsilon > 100f ||
            row.stabilization !in 0..5 || row.points.isEmpty() || row.points.size > MAX_POINT_BYTES ||
            row.enc != InkCodec.ENCODING || !InkCodec.hasValidInputData(row.points) ||
            listOf(row.minX, row.minY, row.maxX, row.maxY).any { !it.isFinite() } ||
            row.minX > row.maxX || row.minY > row.maxY
        ) fail(Strings.the_notebook_contains_invalid_ink_data)
    }

    private fun validateErase(row: InkEraseEntity) {
        if (!validId(row.id) || !validId(row.pageId) ||
            !row.sizeDp.isFinite() || row.sizeDp <= 0f || row.sizeDp > 1_000f ||
            row.points.isEmpty() || row.points.size > MAX_POINT_BYTES ||
            row.enc != InkCodec.ENCODING || !InkCodec.hasValidInputData(row.points)
        ) fail(Strings.the_notebook_contains_invalid_erase_data)
    }

    private fun validateMove(row: InkMoveEntity) {
        if (!validId(row.id) || !validId(row.pageId) ||
            row.points.isEmpty() || row.points.size > MAX_POINT_BYTES ||
            listOf(row.dxDp, row.dyDp, row.scaleX, row.scaleY, row.anchorX, row.anchorY)
                .any { !it.isFinite() } ||
            InkCodec.decodeMove(row) == null
        ) fail(Strings.the_notebook_contains_invalid_move_data)
    }

    private fun requireValidAttachmentMetadata(row: AttachmentEntity) {
        if (!SHA256.matches(row.id) || row.mimeType !in ALLOWED_IMAGE_TYPES ||
            row.pixelWidth !in 1..AttachmentStore.MAX_DIMENSION ||
            row.pixelHeight !in 1..AttachmentStore.MAX_DIMENSION ||
            row.byteCount !in 1..MAX_ATTACHMENT_BYTES
        ) fail(Strings.the_notebook_contains_invalid_attachment_metadata)
    }

    private fun requireDatabaseOkay(database: SQLiteConnection) {
        val quick = database.prepare("PRAGMA quick_check(1)").use { statement ->
            statement.step() && statement.getText(0).equals("ok", ignoreCase = true)
        }
        if (!quick) fail("SQLite integrity verification failed.")
        val foreignKeyProblem = database.prepare("PRAGMA foreign_key_check").use { it.step() }
        if (foreignKeyProblem) fail(Strings.the_notebook_database_contains_broken_relationships)
    }

    private fun parseChecksums(file: File): Map<String, String> {
        if (!file.isFile || file.length() > MAX_CHECKSUM_BYTES) fail(Strings.the_checksum_list_is_invalid)
        val result = linkedMapOf<String, String>()
        file.readLines().filter { it.isNotBlank() }.forEach { line ->
            val match = CHECKSUM_LINE.matchEntire(line) ?: fail(Strings.the_checksum_list_is_malformed)
            val (hash, path) = match.destructured
            validateEntryName(path)
            if (path == CHECKSUMS_ENTRY || result.put(path, hash) != null) {
                fail(Strings.the_checksum_list_contains_a_duplicate_or_self_reference)
            }
        }
        return result
    }

    private fun validateEntryName(name: String) {
        if (name.isBlank() || name.length > MAX_ENTRY_NAME_CHARS || '\u0000' in name ||
            '\\' in name || name.startsWith('/') || name.split('/').any { it == ".." || it.isBlank() } ||
            (name != MANIFEST_ENTRY && name != DATABASE_ENTRY && name != CHECKSUMS_ENTRY &&
                !ATTACHMENT_PATH.matches(name))
        ) fail(Strings.the_archive_contains_an_unsafe_path)
    }

    private fun entryLimit(name: String): Long = when (name) {
        MANIFEST_ENTRY -> MAX_MANIFEST_BYTES.toLong()
        DATABASE_ENTRY -> MAX_DATABASE_BYTES
        CHECKSUMS_ENTRY -> MAX_CHECKSUM_BYTES
        else -> MAX_ATTACHMENT_BYTES
    }

    private fun safeTarget(root: File, name: String): File {
        val target = File(root, name)
        val prefix = root.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(prefix)) fail(Strings.the_archive_contains_an_unsafe_path)
        return target
    }

    /**
     * A new staging directory. Android's cache is emptied by the system when space runs low; a
     * desktop cache directory is not, so what a crash left here more than a day ago goes first.
     */
    private fun freshDirectory(kind: String): File {
        transferRoot.mkdirs()
        if (!transferRoot.isDirectory) fail(Strings.the_private_transfer_directory_is_unavailable)
        // File ages are wall-clock time, whatever [clock] says about the notes.
        val abandoned = System.currentTimeMillis() - STALE_STAGING_MILLIS
        transferRoot.listFiles { file -> file.isDirectory && file.lastModified() < abandoned }
            .orEmpty()
            .forEach(File::deleteRecursively)
        return File(transferRoot, "$kind-${UUID.randomUUID()}").also { directory ->
            if (!directory.mkdir()) fail("A private staging directory could not be created.")
        }
    }

    private fun decodeDocument(format: String, bytes: ByteArray) =
        format.takeIf { it.length <= MAX_FORMAT_CHARS }
            ?.let(DocumentCodecs::byId)
            ?.let { codec ->
                runCatching { codec.decode(bytes).migrated() }.getOrNull()
            } ?: fail(Strings.the_notebook_uses_an_unsupported_document_format)

    private fun PageDoc.imageIds(): List<String> =
        outlines.filterIsInstance<Outline.Image>().map { it.attachmentId }

    private fun PageContentEntity.safeImageIds(): List<String> = runCatching {
        decodeDocument(format, docJson.encodeToByteArray()).imageIds()
    }.getOrDefault(emptyList())

    private fun InkStrokeEntity.sameImmutableInkAs(other: InkStrokeEntity): Boolean =
        id == other.id && pageId == other.pageId && seq == other.seq &&
            brushFamily == other.brushFamily && brushVersion == other.brushVersion &&
            sizeDp == other.sizeDp && epsilon == other.epsilon &&
            stabilization == other.stabilization && minX == other.minX && minY == other.minY &&
            maxX == other.maxX && maxY == other.maxY && points.contentEquals(other.points) &&
            enc == other.enc && createdAt == other.createdAt

    private fun InkEraseEntity.sameImmutableInkAs(other: InkEraseEntity): Boolean =
        id == other.id && pageId == other.pageId && mode == other.mode && sizeDp == other.sizeDp &&
            points.contentEquals(other.points) && enc == other.enc && createdAt == other.createdAt

    private fun InkMoveEntity.sameImmutableInkAs(other: InkMoveEntity): Boolean =
        id == other.id && pageId == other.pageId && dxDp == other.dxDp && dyDp == other.dyDp &&
            scaleX == other.scaleX && scaleY == other.scaleY && anchorX == other.anchorX &&
            anchorY == other.anchorY && points.contentEquals(other.points) && enc == other.enc &&
            createdAt == other.createdAt

    private fun validId(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_ID_CHARS && '\u0000' !in value

    private fun moveAtomically(source: File, target: File) {
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fail(message: String, cause: Throwable? = null): Nothing =
        throw NotebookTransferException(message, cause)

    companion object {
        const val MIME_TYPE = "application/vnd.vivenotes.notebook+zip"
        const val EXTENSION = VIVE_EXTENSION
        const val FORMAT = "com.vivenotes.notebook"
        const val FORMAT_VERSION = 1
        const val APP_SCHEMA_VERSION = 13

        /** Where the staging directories live, inside the platform cache directory. */
        const val DIRECTORY = "notebook_transfers"

        /** `HierarchySync.SyncKind.Notebook.wire` on Android. */
        private const val SYNC_KIND_NOTEBOOK = "notebook"

        private const val MANIFEST_ENTRY = "manifest.json"
        private const val DATABASE_ENTRY = "notebook.sqlite"
        private const val CHECKSUMS_ENTRY = "checksums.sha256"
        private const val ATTACHMENTS_PREFIX = "attachments/"
        private const val APPLICATION_ID = 0x56495645
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_CHECKSUM_BYTES = 512 * 1024L
        private const val MAX_ARCHIVE_BYTES = 512 * 1024 * 1024L
        private const val MAX_EXPANDED_BYTES = 768 * 1024 * 1024L
        private const val MAX_DATABASE_BYTES = 384 * 1024 * 1024L
        private const val MAX_ATTACHMENT_BYTES = 32 * 1024 * 1024L
        private const val MAX_POINT_BYTES = 4 * 1024 * 1024
        private const val MAX_ATTACHMENTS = 2_048
        private const val MAX_ENTRIES = MAX_ATTACHMENTS + 3
        private const val MAX_SECTIONS = 2_048
        private const val MAX_PAGES = 20_000
        private const val MAX_STROKES = 500_000
        private const val MAX_INK_OPERATIONS = 500_000L
        private const val MAX_INK_TARGETS = 2_000_000L
        private const val MAX_REVISIONS = MAX_PAGES * NotesRepository.MAX_REVISIONS_PER_PAGE
        private const val MAX_NAME_CHARS = 512
        private const val MAX_PREVIEW_CHARS = 4_096
        private const val MAX_FORMAT_CHARS = 128
        private const val MAX_ID_CHARS = 512
        private const val SQLITE_BIND_CHUNK = 500
        private const val MAX_ENTRY_NAME_CHARS = 160
        internal const val STALE_STAGING_MILLIS = 24 * 60 * 60 * 1000L
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val UUIDISH = Regex("[0-9a-fA-F-]{20,64}")
        private val ATTACHMENT_PATH = Regex("attachments/[0-9a-f]{64}")
        private val CHECKSUM_LINE = Regex("([0-9a-f]{64})  ([^\\r\\n]+)")
        private val ALLOWED_IMAGE_TYPES = setOf("image/webp", "image/jpeg")

        private val EXPECTED_COLUMNS = linkedMapOf(
            "android_metadata" to listOf("locale"),
            "attachments" to listOf(
                "id", "mimeType", "pixelWidth", "pixelHeight", "byteCount", "refCount", "createdAt",
            ),
            "ink_erase_targets" to listOf("eraseId", "strokeId"),
            "ink_erases" to listOf(
                "id", "pageId", "mode", "sizeDp", "points", "enc", "createdAt", "deletedAt",
            ),
            "ink_move_targets" to listOf("moveId", "strokeId"),
            "ink_moves" to listOf(
                "id", "pageId", "dxDp", "dyDp", "scaleX", "scaleY", "anchorX", "anchorY",
                "points", "enc", "createdAt", "deletedAt",
            ),
            "ink_strokes" to listOf(
                "id", "pageId", "seq", "brushFamily", "brushVersion", "sizeDp", "colorArgb",
                "epsilon", "stabilization", "minX", "minY", "maxX", "maxY", "points", "enc",
                "createdAt", "groupId", "deletedAt", "colorFollowsTheme",
            ),
            "notebooks" to listOf(
                "id", "name", "colorArgb", "sortIndex", "expanded", "createdAt", "updatedAt", "deletedAt",
            ),
            "page_content" to listOf("pageId", "docJson", "updatedAt", "format"),
            "page_revisions" to listOf(
                "id", "pageId", "createdAt", "format", "encoding", "byteCount", "sha256", "payload",
                "inkFormat", "inkEncoding", "inkByteCount", "inkSha256", "inkPayload",
            ),
            "pages" to listOf(
                "id", "sectionId", "title", "sortIndex", "preview", "createdAt", "updatedAt", "deletedAt",
            ),
            "room_master_table" to listOf("id", "identity_hash"),
            "sections" to listOf(
                "id", "notebookId", "name", "colorArgb", "sortIndex", "createdAt", "updatedAt", "deletedAt",
            ),
            "vive_bundle" to listOf("key", "value"),
        )
    }
}

@Serializable
internal data class NotebookBundleManifest(
    val format: String = NotebookTransferManager.FORMAT,
    val formatVersion: Int = NotebookTransferManager.FORMAT_VERSION,
    val appSchemaVersion: Int = NotebookTransferManager.APP_SCHEMA_VERSION,
    val bundleId: String,
    val createdAt: Long,
    val sourceNotebookId: String,
    val notebookName: String,
    val database: BundleFile,
    val attachments: List<BundleAttachment>,
    val counts: BundleCounts,
)

@Serializable
internal data class BundleFile(val path: String, val byteCount: Long, val sha256: String)

@Serializable
internal data class BundleAttachment(
    val id: String,
    val path: String,
    val mimeType: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val byteCount: Long,
    val sha256: String,
)

@Serializable
internal data class BundleCounts(
    val sections: Int,
    val pages: Int,
    val strokes: Int,
    val revisions: Int,
    val attachments: Int,
)

private data class ManifestChecksum(val path: String, val sha256: String)
private data class StagedAttachment(val metadata: AttachmentEntity, val file: File)
private data class PreparedExport(
    val notebook: NotebookEntity,
    val attachments: List<StagedAttachment>,
    val counts: BundleCounts,
)

private data class ValidatedRevision(
    val row: PageRevisionEntity,
    val imageIds: List<String>,
    val ink: InkSnapshot,
)

private data class BundleData(
    val notebook: NotebookEntity,
    val sections: List<SectionEntity>,
    val pages: List<PageEntity>,
    val contents: List<PageContentEntity>,
    val revisions: List<PageRevisionEntity>,
    val revisionSnapshots: Map<String, ValidatedRevision>,
    val strokes: List<InkStrokeEntity>,
    val erases: List<InkEraseEntity>,
    val eraseTargets: List<InkEraseTargetEntity>,
    val moves: List<InkMoveEntity>,
    val moveTargets: List<InkMoveTargetEntity>,
    val attachments: List<AttachmentEntity>,
)

/**
 * The same bundle as it will be stored, when the archive's notebook id is not the one this device
 * keeps it under. Only the notebook id moves: `notebookId` is a column on `sections` alone, and
 * every id below a section is one the account never retired.
 */
private fun BundleData.installedUnder(notebookId: String): BundleData =
    if (notebookId == notebook.id) {
        this
    } else {
        copy(
            notebook = notebook.copy(id = notebookId),
            sections = sections.map { it.copy(notebookId = notebookId) },
        )
    }

private data class ValidatedBundle(
    val manifest: NotebookBundleManifest,
    val data: BundleData,
    val attachmentFiles: Map<String, File>,
)

private data class ExistingBundleRows(
    val notebook: NotebookEntity?,
    val sections: Map<String, SectionEntity>,
    val pages: Map<String, PageEntity>,
    val contents: Map<String, PageContentEntity>,
    val strokes: Map<String, InkStrokeEntity>,
    val erases: Map<String, InkEraseEntity>,
    val eraseTargets: Set<InkEraseTargetEntity>,
    val moves: Map<String, InkMoveEntity>,
    val moveTargets: Set<InkMoveTargetEntity>,
    val revisions: Map<String, PageRevisionEntity>,
    val attachments: Map<String, AttachmentEntity>,
)

/** Import owns archive content, while top-level order/expansion remain device navigation state. */
private fun NotebookEntity.sameImportedStateAs(other: NotebookEntity): Boolean =
    copy(sortIndex = other.sortIndex, expanded = other.expanded, updatedAt = other.updatedAt) == other

private fun SectionEntity.sameImportedStateAs(other: SectionEntity): Boolean =
    copy(updatedAt = other.updatedAt) == other

private fun PageEntity.sameImportedStateAs(other: PageEntity): Boolean =
    copy(updatedAt = other.updatedAt) == other

private fun PageContentEntity.sameImportedStateAs(other: PageContentEntity): Boolean =
    copy(updatedAt = other.updatedAt) == other

private val JSON = Json { encodeDefaults = true }

/** A picture file's pixel size from its header, as `BitmapFactory`'s bounds-only decode reports it. */
private class PixelSize(val width: Int, val height: Int)

/**
 * Skia's codec, like Android's `BitmapFactory`, reports the stored size and leaves an EXIF
 * orientation to whoever draws the picture, so both read the same dimensions for the check.
 */
private fun boundsOf(file: File): PixelSize? = runCatching {
    Data.makeFromBytes(file.readBytes()).use { data ->
        Codec.makeFromData(data).use { codec -> PixelSize(codec.width, codec.height) }
    }
}.getOrNull()?.takeIf { it.width > 0 }

private fun openBundle(file: File, flags: Int): SQLiteConnection =
    BundledSQLiteDriver().open(file.absolutePath, flags)

private fun InputStream.copyBounded(output: OutputStream, limit: Long, message: String): Long {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw NotebookTransferException(message)
        output.write(buffer, 0, read)
    }
    return total
}

private fun ZipOutputStream.putBytes(path: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(path))
    write(bytes)
    closeEntry()
}

private fun ZipOutputStream.putFile(path: String, file: File, stored: Boolean) {
    val entry = ZipEntry(path)
    if (stored) {
        entry.method = ZipEntry.STORED
        entry.size = file.length()
        entry.compressedSize = file.length()
        entry.crc = CRC32().also { crc ->
            FileInputStream(file).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read)
                }
            }
        }.value
    }
    putNextEntry(entry)
    FileInputStream(file).use { it.copyTo(this) }
    closeEntry()
}

private fun File.sha256(): String = FileInputStream(this).use(InputStream::sha256)
private fun File.hasSQLiteHeader(): Boolean = FileInputStream(this).use { input ->
    val header = ByteArray(SQLITE_HEADER.size)
    input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
}
private fun ByteArray.sha256(): String = ByteArrayInputStream(this).sha256()
private fun InputStream.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** One write transaction on a bundle connection, as Android's `beginTransaction` pair. */
private inline fun <T> SQLiteConnection.transaction(block: () -> T): T {
    execSQL("BEGIN IMMEDIATE")
    try {
        return block().also { execSQL("COMMIT") }
    } catch (failure: Throwable) {
        runCatching { execSQL("ROLLBACK") }
        throw failure
    }
}

/** Runs [sql] with [args] bound in order, stepping it to completion. */
private fun SQLiteConnection.exec(sql: String, vararg args: String) {
    prepare(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.bindText(index + 1, value) }
        while (statement.step()) Unit
    }
}

private inline fun <T> SQLiteConnection.queryRows(
    sql: String,
    vararg args: String,
    mapper: (Row) -> T,
): List<T> = prepare(sql).use { statement ->
    args.forEachIndexed { index, value -> statement.bindText(index + 1, value) }
    val row = Row(statement)
    buildList { while (statement.step()) add(mapper(row)) }
}

private fun SQLiteConnection.scalarLong(sql: String): Long = prepare(sql).use { statement ->
    check(statement.step())
    statement.getLong(0)
}

/**
 * One result row, read the way the Android version reads a `Cursor`: by column name, converting
 * as SQLite does, and failing on a NULL where the entity has no null — where Kotlin's null check on
 * `Cursor.getString` would have failed there.
 */
private class Row(private val statement: SQLiteStatement) {
    private val columns = statement.getColumnNames().withIndex().associate { (index, name) -> name to index }

    private fun index(name: String): Int = columns[name] ?: throw IllegalArgumentException("column '$name' does not exist")
    private fun present(index: Int): Int = index.also { require(!statement.isNull(it)) { "unexpected NULL" } }

    fun text(index: Int): String = statement.getText(present(index))
    fun nullableText(index: Int): String? = if (statement.isNull(index)) null else statement.getText(index)
    fun isNull(index: Int): Boolean = statement.isNull(index)

    fun string(name: String): String = text(index(name))
    fun long(name: String): Long = statement.getLong(present(index(name)))
    fun int(name: String): Int = long(name).toInt()
    fun float(name: String): Float = statement.getDouble(present(index(name))).toFloat()
    fun blob(name: String): ByteArray = statement.getBlob(present(index(name)))
    fun nullableLong(name: String): Long? = index(name).let { if (statement.isNull(it)) null else statement.getLong(it) }
    fun nullableString(name: String): String? = index(name).let { if (statement.isNull(it)) null else statement.getText(it) }
    fun nullableBoolean(name: String): Boolean? =
        index(name).let { if (statement.isNull(it)) null else statement.getLong(it) != 0L }
}

private fun readNotebook(it: Row) = NotebookEntity(
    id = it.string("id"), name = it.string("name"), colorArgb = it.int("colorArgb"),
    sortIndex = it.int("sortIndex"), expanded = it.int("expanded") != 0, createdAt = it.long("createdAt"),
    updatedAt = it.long("updatedAt"), deletedAt = it.nullableLong("deletedAt"),
)
private fun readSection(it: Row) = SectionEntity(
    it.string("id"), it.string("notebookId"), it.string("name"), it.int("colorArgb"),
    it.int("sortIndex"), it.long("createdAt"), it.long("updatedAt"), it.nullableLong("deletedAt"),
)
private fun readPage(it: Row) = PageEntity(
    it.string("id"), it.string("sectionId"), it.string("title"), it.int("sortIndex"),
    it.string("preview"), it.long("createdAt"), it.long("updatedAt"), it.nullableLong("deletedAt"),
)
private fun readContent(it: Row) = PageContentEntity(
    it.string("pageId"), it.string("docJson"), it.long("updatedAt"), it.string("format"),
)
private fun readRevision(it: Row) = PageRevisionEntity(
    it.string("id"), it.string("pageId"), it.long("createdAt"), it.string("format"),
    it.string("encoding"), it.int("byteCount"), it.string("sha256"), it.blob("payload"),
    it.string("inkFormat"), it.string("inkEncoding"), it.int("inkByteCount"),
    it.string("inkSha256"), it.blob("inkPayload"),
)
private fun readStroke(it: Row) = InkStrokeEntity(
    id = it.string("id"), pageId = it.string("pageId"), seq = it.int("seq"),
    brushFamily = it.string("brushFamily"), brushVersion = it.int("brushVersion"),
    sizeDp = it.float("sizeDp"), colorArgb = it.int("colorArgb"),
    colorFollowsTheme = it.nullableBoolean("colorFollowsTheme"), epsilon = it.float("epsilon"),
    stabilization = it.int("stabilization"), minX = it.float("minX"), minY = it.float("minY"),
    maxX = it.float("maxX"), maxY = it.float("maxY"), points = it.blob("points"),
    enc = it.string("enc"), createdAt = it.long("createdAt"), groupId = it.nullableString("groupId"),
    deletedAt = it.nullableLong("deletedAt"),
)
private fun readErase(it: Row) = InkEraseEntity(
    it.string("id"), it.string("pageId"), EraserMode.valueOf(it.string("mode")),
    it.float("sizeDp"), it.blob("points"), it.string("enc"), it.long("createdAt"),
    it.nullableLong("deletedAt"),
)
private fun readMove(it: Row) = InkMoveEntity(
    it.string("id"), it.string("pageId"), it.float("dxDp"), it.float("dyDp"),
    it.float("scaleX"), it.float("scaleY"), it.float("anchorX"), it.float("anchorY"),
    it.blob("points"), it.string("enc"), it.long("createdAt"), it.nullableLong("deletedAt"),
)
private fun readAttachment(it: Row) = AttachmentEntity(
    it.string("id"), it.string("mimeType"), it.int("pixelWidth"), it.int("pixelHeight"),
    it.long("byteCount"), it.int("refCount"), it.long("createdAt"),
)

private fun SQLiteConnection.insertAttachment(row: AttachmentEntity) {
    prepare(
        "INSERT INTO attachments (id, mimeType, pixelWidth, pixelHeight, byteCount, refCount, createdAt) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
    ).use { statement ->
        statement.bindText(1, row.id)
        statement.bindText(2, row.mimeType)
        statement.bindLong(3, row.pixelWidth.toLong())
        statement.bindLong(4, row.pixelHeight.toLong())
        statement.bindLong(5, row.byteCount)
        statement.bindLong(6, row.refCount.toLong())
        statement.bindLong(7, row.createdAt)
        statement.step()
    }
}

private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
