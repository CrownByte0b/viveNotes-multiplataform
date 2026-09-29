package com.vivenotes.desktop

import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.workspace.ShapeToolSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ShapeSettingsFileTest {
    @Test
    fun savesAndLoadsTheChosenShapeAndStyle() {
        val directory = Files.createTempDirectory("shape-settings").toFile()
        try {
            val store = ShapeSettingsFile(File(directory, "config/shape.properties"))
            assertEquals(ShapeToolSettings(), store.load())
            val chosen = ShapeToolSettings(ShapeKind.Cylinder, LineType.Dotted, 9,
                0xFF3584E4.toInt(), 0xFFE01B24.toInt())
            store.save(chosen)
            assertEquals(chosen, ShapeSettingsFile(File(directory, "config/shape.properties")).load())
            store.save(chosen.copy(fillArgb = null))
            assertEquals(null, store.load().fillArgb)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidSavedValuesFallBackAndClamp() {
        val file = Files.createTempFile("shape-settings-invalid", ".properties").toFile()
        try {
            file.writeText("kind=Unknown\nlineType=Unknown\nborderWidth=200\nborderArgb=broken\nfillArgb=broken\n")
            assertEquals(ShapeToolSettings(borderWidth = 12), ShapeSettingsFile(file).load())
        } finally {
            file.delete()
        }
    }
}
