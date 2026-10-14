package com.vivenotes.desktop

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
                InkHighlighterSettings(27.5f, 0x80654321.toInt()))
            store.save(chosen)
            assertEquals(chosen, InkSettingsFile(file).load())
            store.save(chosen.copy(pen = chosen.pen.copy(colorFollowsTheme = true)))
            assertTrue(store.load().pen.colorFollowsTheme)
            assertEquals(chosen.highlighter, store.load().highlighter)
            assertEquals(listOf("ink.properties"), file.parentFile.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun damagedPreferencesFallBackAndUnsafeValuesNormalize() {
        val file = Files.createTempFile("ink-settings-invalid", ".properties").toFile()
        try {
            file.writeText("pen.brush=Unknown\npen.sizeDp=NaN\npen.colorArgb=broken\npen.colorFollowsTheme=invalid\nhighlighter.sizeDp=Infinity\n")
            assertEquals(InkSettings(), InkSettingsFile(file).load())
            file.writeText("pen.sizeDp=-5\nhighlighter.sizeDp=200\nhighlighter.colorArgb=1193046\n")
            assertEquals(InkSettings(InkPenSettings(sizeDp = 0.5f),
                InkHighlighterSettings(96f, 0x80123456.toInt())), InkSettingsFile(file).load())
            InkSettingsFile(file).save(InkSettings(pen = InkPenSettings(sizeDp = Float.NaN)))
            assertEquals(InkSettings(), InkSettingsFile(file).load())
            file.writeText("pen.brush=\\uZZZZ")
            assertEquals(InkSettings(), InkSettingsFile(file).load())
        } finally { file.delete() }
    }
}
