package com.vivenotes.desktop

import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.RuleLines
import com.vivenotes.workspace.EditorDefaults
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorDefaultsFileTest {
    @Test
    fun choicesSurviveReopeningAndInvalidValuesFallBack() {
        val directory = createTempDirectory("editor-defaults").toFile()
        try {
            val file = File(directory, "config/editor.properties")
            val store = EditorDefaultsFile(file)
            assertEquals(EditorDefaults(), store.load())
            val chosen = EditorDefaults("lora", 24, PaperSize.Custom, PaperDimensions(7f, 9f), RuleLines.Wide,
                highlight = 0x66FF4081)
            store.save(chosen)
            assertEquals(chosen, EditorDefaultsFile(file).load())
            assertTrue("textHighlight=66FF4081" in file.readLines())

            file.writeText("fontFamily=unknown\nfontSize=999\npaper=Unknown\nruleLines=Unknown\ncustomWidth=-2\ncustomHeight=9\n" +
                "textHighlight=FF000000\n")
            assertEquals(EditorDefaults(), store.load())
            file.writeText("textHighlight=yellow\n")
            assertEquals(EditorDefaults(), store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** A file saved before the highlight ink was a preference keeps the yellow the button used. */
    @Test
    fun anEarlierFileStartsTheHighlighterYellow() {
        val directory = createTempDirectory("editor-defaults").toFile()
        try {
            val file = File(directory, "editor.properties")
            file.writeText("fontFamily=lora\nfontSize=24\npaper=Auto\nruleLines=Wide\n")
            val loaded = EditorDefaultsFile(file).load()
            assertEquals(EditorDefaults("lora", 24, ruleLines = RuleLines.Wide), loaded)
            assertEquals(0x66FFEB3B, loaded.highlight)
        } finally {
            directory.deleteRecursively()
        }
    }
}
