package com.vivenotes.desktop

import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.RuleLines
import com.vivenotes.workspace.EditorDefaults
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorDefaultsFileTest {
    @Test
    fun choicesSurviveReopeningAndInvalidValuesFallBack() {
        val directory = createTempDirectory("editor-defaults").toFile()
        try {
            val file = File(directory, "config/editor.properties")
            val store = EditorDefaultsFile(file)
            assertEquals(EditorDefaults(), store.load())
            val chosen = EditorDefaults("lora", 24, PaperSize.Custom, PaperDimensions(7f, 9f), RuleLines.Wide)
            store.save(chosen)
            assertEquals(chosen, EditorDefaultsFile(file).load())

            file.writeText("fontFamily=unknown\nfontSize=999\npaper=Unknown\nruleLines=Unknown\ncustomWidth=-2\ncustomHeight=9\n")
            assertEquals(EditorDefaults(), store.load())
        } finally {
            directory.deleteRecursively()
        }
    }
}
