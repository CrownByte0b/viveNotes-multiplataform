package com.vivenotes.desktop

import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.KeyChord
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.ShortcutKey
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyBindingsFileTest {
    private val directory: File = createTempDirectory("key-bindings").toFile()

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun changedAndDisabledShortcutsSurviveReopening() {
        val file = File(directory, "config/keyboard.properties")
        assertEquals(KeyBindings.Default, KeyBindingsFile(file).load())

        val changed = KeyBindings.Default
            .rebind(ShortcutAction.ZoomIn, KeyChord(ShortcutKey.Equals, ctrl = true, alt = true))
            .rebind(ShortcutAction.Bold, null)
            // Takes Ctrl+R from Redo, which keeps only Ctrl+Shift+Z: a change of its own to store.
            .rebind(ShortcutAction.Undo, KeyChord(ShortcutKey.R, ctrl = true))
        KeyBindingsFile(file).save(changed)
        assertEquals(changed, KeyBindingsFile(file).load())

        val text = file.readText()
        assertTrue("ZoomIn=Ctrl+Alt+Equals" in text, text)
        assertTrue(Regex("(?m)^Bold=$").containsMatchIn(text), text)
        assertTrue("Redo=Ctrl+Shift+Z" in text, text)
        assertFalse("NewPage" in text, "an unchanged shortcut was written: $text")
    }

    @Test
    fun unreadableLinesKeepTheirDefaults() {
        val file = File(directory, "keyboard.properties")
        file.writeText("""
            Undo=Ctrl+Nope
            Teleport=Ctrl+T
            Italic=I
            Copy=Ctrl+Shift+C
            Paste=Ctrl+Shift+C
        """.trimIndent())
        val loaded = KeyBindingsFile(file).load()
        assertEquals(KeyChord(ShortcutKey.Z, ctrl = true), loaded.primary(ShortcutAction.Undo))
        assertEquals(KeyChord(ShortcutKey.I, ctrl = true), loaded.primary(ShortcutAction.Italic))
        // One chord, one command: the later line in the table's order keeps it.
        assertEquals(KeyChord(ShortcutKey.C, ctrl = true, shift = true), loaded.primary(ShortcutAction.Paste))
        assertEquals(null, loaded.primary(ShortcutAction.Copy))

        file.writeText("not a properties file \u0000\\u12")
        assertEquals(KeyBindings.Default, KeyBindingsFile(file).load())
    }

    @Test
    fun resettingEverythingLeavesAnEmptyFile() {
        val file = File(directory, "keyboard.properties")
        KeyBindingsFile(file).save(KeyBindings.Default.rebind(ShortcutAction.Copy, null))
        KeyBindingsFile(file).save(KeyBindings.Default)
        assertEquals(KeyBindings.Default, KeyBindingsFile(file).load())
        assertTrue(file.readLines().all { it.startsWith("#") }, file.readText())
    }
}
