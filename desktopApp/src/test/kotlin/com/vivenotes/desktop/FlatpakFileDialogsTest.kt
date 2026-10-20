package com.vivenotes.desktop

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlatpakFileDialogsTest {
    @Test
    fun productionWindowsMatchTheFlatpakDesktopFile() {
        assertEquals("net.vivenotes.ViveNotes", linuxWindowClass(DesktopProfile.PRODUCTION, "net.vivenotes.ViveNotes"))
        assertEquals("vivenotes-dev", linuxWindowClass(DesktopProfile.DEVELOPMENT, "net.vivenotes.ViveNotes"))
        assertEquals("vivenotes", linuxWindowClass(DesktopProfile.PRODUCTION, null))
        assertEquals("vivenotes", linuxWindowClass(DesktopProfile.PRODUCTION, ""))
    }

    @Test
    fun selectedPortalPathsPreserveSpacesUnicodeAndNewlines() {
        val chosen = "/run/user/1000/doc/abc/My notes λ\n.vive"
        val result = FlatpakFileDialogs.chooseWith("Import", listOf("vive"), null) { command ->
            assertEquals("/usr/bin/zenity", command.first())
            assertTrue("--file-filter=*.vive" in command)
            assertFalse("--save" in command)
            0 to "$chosen\n"
        }
        assertEquals(File(chosen), result)
    }

    @Test
    fun savesConfirmOverwriteAndPassTheSuggestedNameAsOneArgument() {
        val result = FlatpakFileDialogs.chooseWith("Export PDF", listOf("pdf"), "../Notes $(literal).pdf") { command ->
            assertTrue("--save" in command)
            assertTrue("--confirm-overwrite" in command)
            assertTrue("--filename=Notes $(literal).pdf" in command)
            assertTrue("--title=Export PDF" in command)
            0 to "/run/user/1000/doc/abc/Notes.pdf\n"
        }
        assertEquals("Notes.pdf", result!!.name)
    }

    @Test
    fun cancellationAndPortalFailureAreDifferent() {
        assertNull(FlatpakFileDialogs.chooseWith("Open", listOf("png", "jpg"), null) { command ->
            assertTrue("--file-filter=*.png *.jpg" in command)
            1 to ""
        })
        assertFailsWith<IOException> { FlatpakFileDialogs.chooseWith("Open", listOf("vive"), null) { 2 to "" } }
        assertFailsWith<IOException> { FlatpakFileDialogs.chooseWith("Open", listOf("vive"), null) { 0 to "\n" } }
    }

    @Test
    fun portalSavesKeepExactlyTheFilenameTheUserAuthorized() {
        val chosen = File("/run/user/1000/doc/abc/Custom name")
        assertEquals(chosen, withViveExtension(chosen, preserveName = true))
        assertEquals(chosen, withPdfExtension(chosen, preserveName = true))
    }
}
