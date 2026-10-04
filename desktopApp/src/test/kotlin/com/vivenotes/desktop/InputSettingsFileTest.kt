package com.vivenotes.desktop

import com.vivenotes.workspace.InputSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class InputSettingsFileTest {
    @Test
    fun aFingerDoesNotDrawUntilChosenAndTheChoiceIsKept() {
        val directory = Files.createTempDirectory("input-settings").toFile()
        try {
            val store = InputSettingsFile(File(directory, "config/input.properties"))
            assertEquals(InputSettings(drawWithFinger = false), store.load())
            store.save(InputSettings(drawWithFinger = true))
            assertEquals(InputSettings(drawWithFinger = true),
                InputSettingsFile(File(directory, "config/input.properties")).load())
            store.save(InputSettings(drawWithFinger = false))
            assertEquals(InputSettings(drawWithFinger = false), store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun anUnreadableValueFallsBackToPanning() {
        val file = Files.createTempFile("input-settings-invalid", ".properties").toFile()
        try {
            file.writeText("drawWithFinger=sometimes\n")
            assertEquals(InputSettings(), InputSettingsFile(file).load())
        } finally {
            file.delete()
        }
    }
}
