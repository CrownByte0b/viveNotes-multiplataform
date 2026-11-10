package com.vivenotes.fileimport

import com.vivenotes.richtext.CanvasPasteItem
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertTrue

/**
 * Real documents through the whole importer, when a folder of them is named: `VIVE_IMPORT_FIXTURES`.
 * Users' documents stay outside the repository; without the variable this is skipped.
 *
 * Each one must open, draw every page it has and come in as objects. `VIVE_IMPORT_OUTPUT` keeps the
 * drawn pages and a summary of the objects, for looking at.
 */
class LocalDocumentsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun everyDocumentInTheFolderOpensDrawsAndComesInAsObjects() = runBlocking {
        val fixtures = System.getenv("VIVE_IMPORT_FIXTURES")?.let(::File)
        assumeTrue("VIVE_IMPORT_FIXTURES names no folder", fixtures?.isDirectory == true)
        val output = System.getenv("VIVE_IMPORT_OUTPUT")?.let(::File)?.apply { mkdirs() }
        val documents = fixtures!!.listFiles().orEmpty()
            .filter { it.extension.lowercase() in FileImportService.FILE_EXTENSIONS }
            .sortedBy { it.name }
        assumeTrue(documents.isNotEmpty())
        for (document in documents) {
            val importer = FileImporter(folder.newFolder())
            try {
                val started = System.nanoTime()
                val opened = importer.open(document.path)
                val openMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(opened.pages.isNotEmpty(), document.name)
                val summary = StringBuilder("${document.name}: ${opened.pages.size} pages, opened in $openMs ms\n")
                opened.pages.forEachIndexed { index, page ->
                    val bitmap = requireNotNull(importer.previewBitmap(opened, index, PREVIEW_PX)) { "${document.name} page $index" }
                    if (output != null && index < MAX_DRAWN) {
                        Image.makeFromBitmap(bitmap).use { image ->
                            File(output, "${document.nameWithoutExtension}-${index + 1}.png")
                                .writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
                        }
                    }
                    if (index == 0) summary.append("  page 1: ${page.widthDp} × ${page.heightDp} dp\n")
                }
                val chosen = opened.pages.indices.take(MAX_OBJECT_PAGES)
                val itemsStarted = System.nanoTime()
                val items = importer.objects(opened, chosen, emptySet())
                summary.append("  ${chosen.size} pages as ${items.size} objects in ${(System.nanoTime() - itemsStarted) / 1_000_000} ms\n")
                items.forEach { item ->
                    summary.append(
                        when (item) {
                            is CanvasPasteItem.Text -> "  text: " + item.blocks.joinToString(" ¶ ") { "${it.type}:${it.text.take(70)}" }.take(400)
                            is CanvasPasteItem.Table -> "  table ${item.outline.rowCount}×${item.outline.columnCount}: " +
                                item.outline.rows.take(2).joinToString(" | ") { row -> row.cells.joinToString(", ") { it.plainText.take(20) } }
                            is CanvasPasteItem.Image -> "  picture ${item.widthDp} × ${item.heightDp} dp, ${item.bytes?.size} bytes"
                        } + "\n",
                    )
                }
                assertTrue(items.isNotEmpty(), document.name)
                output?.let { File(it, "${document.nameWithoutExtension}.txt").writeText(summary.toString()) }
                println(summary)
            } finally {
                importer.release()
            }
        }
    }

    private companion object {
        const val PREVIEW_PX = 900
        const val MAX_DRAWN = 4
        const val MAX_OBJECT_PAGES = 6
    }
}
