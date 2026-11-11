package com.vivenotes.fileimport

import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileImporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun document(): File = folder.newFile("picked.docx").also {
        DocxTestFile.write(it, DocxTestFile.paragraph("A document that can be inserted."))
    }

    @Test
    fun closingTheWindowWhileOpeningClosesTheDocumentAndRemovesItsCopy() = runBlocking {
        val cache = folder.newFolder("cache")
        lateinit var importer: FileImporter
        importer = FileImporter(cache, log = DebugLog(true) { line ->
            if ("opened " in line) importer.release()
        })
        try {
            assertFailsWith<CancellationException> { importer.open(document().path) }
            assertNoStaging(cache)
        } finally {
            importer.release()
        }
    }

    @Test
    fun cancellationAfterReadingClosesTheDocumentAndRemovesItsCopy() = runTest {
        val cache = folder.newFolder("cache")
        var cancelOpen: () -> Unit = {}
        val importer = FileImporter(cache, dispatcher = StandardTestDispatcher(testScheduler), log = DebugLog(true) { line ->
            if ("opened " in line) cancelOpen()
        })
        try {
            val source = document()
            val opening = async { importer.open(source.path) }
            cancelOpen = { opening.cancel() }

            assertFailsWith<CancellationException> { opening.await() }

            assertNoStaging(cache)
        } finally {
            importer.release()
        }
    }

    @Test
    fun aReadFailureClosesEveryKindOfZipAndRemovesItsCopy() = runBlocking {
        val cache = folder.newFolder("cache")
        val importer = FileImporter(cache)
        val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        try {
            for (format in listOf("docx", "odt", "pptx")) {
                val file = folder.newFile("broken.$format")
                val part = if (format == "docx") "word/document.xml" else "ppt/presentation.xml"
                val type = if (format == "docx") {
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                } else {
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
                }
                val entries = if (format == "odt") {
                    mapOf("mimetype" to "application/vnd.oasis.opendocument.text", "content.xml" to "<broken")
                } else {
                    mapOf(
                        "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/$part" ContentType="$type"/></Types>""",
                        "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="main" Type="$office/officeDocument" Target="$part"/></Relationships>""",
                        part to "<broken",
                    )
                }
                ZipOutputStream(file.outputStream()).use { zip ->
                    entries.forEach { (name, text) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(text.toByteArray())
                        zip.closeEntry()
                    }
                }

                assertEquals(ImportFailure.Unreadable, assertFailsWith<ImportException> { importer.open(file.path) }.failure)
                assertNoStaging(cache)
            }
            // A failed open leaves the same importer available for the next file.
            assertEquals(1, importer.open(document().path).pages.size)
        } finally {
            importer.release()
        }
    }

    @Test
    fun failuresDuringAnOpenDocumentJobAreTranslatedAndTheDocumentCanStillBeUsed() = runBlocking {
        val importer = FileImporter(folder.newFolder("cache"))
        try {
            val opened = importer.open(document().path)
            for ((error, kind) in listOf(
                OutOfMemoryError("page") to ImportFailure.TooLarge,
                StackOverflowError("page") to ImportFailure.Unreadable,
                IllegalArgumentException("page") to ImportFailure.Unreadable,
            )) {
                val failure = assertFailsWith<ImportException> { importer.withPages(opened) { throw error } }
                assertEquals(kind, failure.failure)
                assertSame(error, failure.cause)
            }
            val cancelled = CancellationException("closed")
            assertEquals(cancelled.message, assertFailsWith<CancellationException> { importer.withPages(opened) { throw cancelled } }.message)
            assertTrue(importer.objects(opened, listOf(0), emptySet()).isNotEmpty())
        } finally {
            importer.release()
        }
    }

    @Test
    fun wordTemplatesAndMacroFormsOfferedByTheChooserAreReadable() = runBlocking {
        val importer = FileImporter(folder.newFolder("cache"))
        val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val forms = mapOf(
            "dotx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml",
            "docm" to "application/vnd.ms-word.document.macroEnabled.main+xml",
            "dotm" to "application/vnd.ms-word.template.macroEnabledTemplate.main+xml",
        )
        try {
            for ((extension, type) in forms) {
                assertTrue(extension in FileImportService.FILE_EXTENSIONS)
                val file = folder.newFile("template.$extension")
                val entries = mapOf(
                    "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/word/document.xml" ContentType="$type"/></Types>""",
                    "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="main" Type="$office/officeDocument" Target="word/document.xml"/></Relationships>""",
                    "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${DocxTestFile.paragraph("Template body")}</w:body></w:document>""",
                )
                ZipOutputStream(file.outputStream()).use { zip ->
                    entries.forEach { (name, text) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(text.toByteArray())
                        zip.closeEntry()
                    }
                }
                val opened = importer.open(file.path)
                val text = importer.objects(opened, listOf(0), emptySet()).single() as com.vivenotes.richtext.CanvasPasteItem.Text
                assertEquals("Template body", text.blocks.single().text)
            }
        } finally {
            importer.release()
        }
    }

    private fun assertNoStaging(cache: File) {
        val staging = File(cache, "file-import")
        assertTrue(staging.listFiles().orEmpty().isEmpty(), "The staged file must be removed")
        // Linux permits deleting an open ZIP; check the descriptor too. Windows' delete already
        // proves the file was closed because that platform refuses to delete an open file.
        val descriptors = Path.of("/proc/self/fd")
        if (Files.isDirectory(descriptors)) Files.list(descriptors).use { files ->
            val retained = files.map { runCatching { Files.readSymbolicLink(it).toString() }.getOrDefault("") }
                .filter { it.startsWith(staging.absolutePath + File.separator) }.toList()
            assertTrue(retained.isEmpty(), "An import still holds a staged file open: $retained")
        }
    }
}
