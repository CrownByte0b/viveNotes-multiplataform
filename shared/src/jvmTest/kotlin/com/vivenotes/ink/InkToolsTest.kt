package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.data.InkEdit
import com.vivenotes.data.NotesLibrary
import com.vivenotes.workspace.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class InkToolsTest {
    @Test
    fun automaticPenAndFixedHighlighterUseTheCatalogRules() {
        val defaults = InkSettings()
        for (automatic in listOf(0xff000000.toInt(), 0xffffffff.toInt())) {
            val pen = defaults.byteInkTool(InkTool.Pen, automatic)
            assertEquals(automatic, pen.colorArgb)
            assertEquals(true, pen.colorFollowsTheme)
            assertEquals(3f, pen.sizeDp)
            val highlighter = defaults.byteInkTool(InkTool.Highlighter, automatic)
            assertEquals(ViveBrushes.HIGHLIGHTER, highlighter.familyId)
            assertEquals(false, highlighter.colorFollowsTheme)
            assertEquals(0x80ffe000.toInt(), highlighter.colorArgb)
            assertEquals(0, highlighter.stabilization)
        }
    }

    @Test
    fun everyConfiguredBrushAndHighlighterRetainTheirStyleThroughDatabaseReopen() = runBlocking {
        val directory = Files.createTempDirectory("ink-tools").toFile()
        try {
            val expectedFamilies = mapOf(InkBrush.Marker to ViveBrushes.MARKER,
                InkBrush.PressurePen to ViveBrushes.PRESSURE_PEN,
                InkBrush.Calligraphy to ViveBrushes.calligraphy(ViveBrushes.DEFAULT_CALLIGRAPHY_PRESSURE),
                InkBrush.DashedLine to ViveBrushes.DASHED_LINE)
            val settings = expectedFamilies.keys.map { brush ->
                InkSettings(pen = InkPenSettings(brush, 7.5f, 0xff123456.toInt(), false)) to InkTool.Pen
            } + (InkSettings(highlighter = InkHighlighterSettings(27.5f, 0x80654321.toInt())) to InkTool.Highlighter)
            val inputs = MutableStrokeInputBatch().apply {
                add(InputToolType.MOUSE, 10f, 50f, 0L)
                add(InputToolType.MOUSE, 90f, 50f, 80L)
            }
            val originals = mutableListOf<com.vivenotes.data.db.InkStrokeEntity>()
            val pageId = NotesLibrary.open(directory).use { library ->
                val repository = library.repository
                val notebook = repository.createNotebook("Brush contract")
                val section = repository.createSection(notebook, "Ink")
                val page = repository.createPage(section)
                settings.forEachIndexed { index, (preference, tool) ->
                    val configured = preference.byteInkTool(tool, 0xffffffff.toInt())
                    val authored = configured.complete(Stroke(configured.brush, inputs), "stroke-$index", page, index, index + 1L)
                    val row = authored.row.toEntity()
                    originals += row
                    repository.applyInkEdit(page, InkEdit.AddStroke(row))
                    assertEquals(if (tool == InkTool.Pen) expectedFamilies[preference.pen.brush] else ViveBrushes.HIGHLIGHTER,
                        row.brushFamily)
                    assertEquals(if (tool == InkTool.Pen) 7.5f else 27.5f, row.sizeDp)
                    assertEquals(if (tool == InkTool.Pen) 0xff123456.toInt() else 0x80654321.toInt(), row.colorArgb)
                    assertEquals(false, row.colorFollowsTheme)
                }
                page
            }
            NotesLibrary.open(directory).use { library ->
                val rows = library.repository.inkFor(pageId)
                assertEquals(5, rows.size)
                val loaded = library.repository.loadInk(pageId).desktopGeometry()
                assertEquals(5, loaded.projections.size)
                originals.forEach { original ->
                    val row = rows.single { it.id == original.id }
                    assertEquals(original.brushFamily, row.brushFamily)
                    assertEquals(original.colorArgb, row.colorArgb)
                    assertEquals(original.sizeDp, row.sizeDp)
                    assertContentEquals(original.points, row.points)
                    val decoded = assertNotNull(ViveInkCodec.decode(row.toByteInk()))
                    assertEquals(original.colorArgb, decoded.brush.colorIntArgb)
                    assertEquals(original.sizeDp, decoded.brush.size)
                    assertEquals(decoded.shape.computeBoundingBox(), loaded.projections.single { it.id == row.id }.stroke.shape.computeBoundingBox())
                }
            }
        } finally { directory.deleteRecursively() }
    }
}
