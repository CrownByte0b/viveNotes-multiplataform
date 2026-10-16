package com.vivenotes.desktop

import com.vivenotes.data.EraserMode
import com.vivenotes.model.ink.LineType
import com.vivenotes.workspace.*
import com.vivenotes.workspace.InkBrush
import com.vivenotes.workspace.InkHighlighterSettings
import com.vivenotes.workspace.InkPenSettings
import com.vivenotes.workspace.InkSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class InkSettingsFileTest {
    @Test
    fun missingFileDefaultsAndIndependentChoicesRoundTrip() {
        val directory = Files.createTempDirectory("ink-settings").toFile()
        try {
            val file = File(directory, "config/ink.properties")
            val store = InkSettingsFile(file)
            assertEquals(InkSettings(), store.load())
            val chosen = InkSettings(InkPenSettings(InkBrush.Calligraphy, 7.5f, 0xff123456.toInt(), false),
                InkHighlighterSettings(27.5f, 0x66654321))
            store.save(chosen)
            assertEquals(chosen, InkSettingsFile(file).load())
            store.save(chosen.copy(pen = chosen.pen.copy(colorFollowsTheme = true)))
            assertTrue(store.load().pen.colorFollowsTheme)
            assertEquals(chosen.highlighter, store.load().highlighter)
            assertEquals(listOf("ink.properties"), file.parentFile.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun allAndroidKitsAndTheRollingPaletteSurviveRestartWhileLegacyFilesKeepTheirFirstPen() {
        val file = Files.createTempFile("draw-kits", ".properties").toFile()
        try {
            val settings = InkSettings(pen2 = InkPenSettings(InkBrush.Calligraphy, 6f, 0xff123456.toInt(), false,
                lineType = LineType.Dotted, pressure = 5, stabilization = 4, holdForStraightLine = false),
                pen3 = InkPenSettings(sizeDp = 7f), eraser = InkEraserSettings(EraserMode.Object, 42f),
                ruler = InkRulerSettings(RulerKind.Protractor, 800), palette = PenColors.withColorInFront(0xff123456.toInt()),
                table = TableToolSettings(inkOnly = false, columns = 5, rows = 4, headerColumn = true,
                    borderWidth = 3, fillArgb = 0xffeeeeee.toInt()))
            InkSettingsFile(file).save(settings)
            assertEquals(settings, InkSettingsFile(file).load())
            file.writeText("pen.brush=Calligraphy\npen.sizeDp=7.5\npen.colorArgb=-15584170\npen.colorFollowsTheme=false\n")
            val legacy = InkSettingsFile(file).load()
            assertEquals(InkPenSettings(InkBrush.Calligraphy, 7.5f, 0xff123456.toInt(), false), legacy.pen)
            assertEquals(InkSettings().pen2, legacy.pen2)
            assertEquals(InkSettings().pen3, legacy.pen3)
        } finally { file.delete() }
    }

    @Test
    fun displacedLegacyPalettePinsWhiteThenBlackOnLoadAndSaveWithoutChangingPenChoices() {
        val file = Files.createTempFile("ink-palette-legacy", ".properties").toFile()
        try {
            val custom = List(9) { 0xff102030.toInt() + it }
            file.writeText("palette=${custom.joinToString(",")}\npen.colorArgb=-1\npen.colorFollowsTheme=false\n")
            val loaded = InkSettingsFile(file).load()
            assertEquals(PenColors.take(2) + custom.take(7), loaded.palette)
            assertEquals(-1, loaded.pen.colorArgb)
            assertFalse(loaded.pen.colorFollowsTheme)
            val changed = loaded.copy(palette = custom.reversed())
            InkSettingsFile(file).save(changed)
            val reopened = InkSettingsFile(file).load()
            assertEquals(PenColors.take(2) + custom.reversed().take(7), reopened.palette)
            assertEquals(loaded.pen, reopened.pen)
            assertEquals(loaded.pen2, reopened.pen2)
            assertEquals(loaded.pen3, reopened.pen3)
        } finally { file.delete() }
    }

    @Test
    fun damagedPreferencesFallBackAndUnsafeValuesNormalize() {
        val file = Files.createTempFile("ink-settings-invalid", ".properties").toFile()
        try {
            file.writeText("pen.brush=Unknown\npen.sizeDp=NaN\npen.colorArgb=broken\npen.colorFollowsTheme=invalid\nhighlighter.sizeDp=Infinity\n")
            assertEquals(InkSettings(), InkSettingsFile(file).load())
            file.writeText("pen.sizeDp=-5\nhighlighter.sizeDp=200\nhighlighter.colorArgb=1193046\n")
            assertEquals(InkSettings(InkPenSettings(sizeDp = 1f),
                InkHighlighterSettings(40f, 0x66123456)), InkSettingsFile(file).load())
            InkSettingsFile(file).save(InkSettings(pen = InkPenSettings(sizeDp = Float.NaN)))
            assertEquals(InkSettings(), InkSettingsFile(file).load())
            file.writeText("pen.brush=\\uZZZZ")
            assertEquals(InkSettings(), InkSettingsFile(file).load())
        } finally { file.delete() }
    }
}
