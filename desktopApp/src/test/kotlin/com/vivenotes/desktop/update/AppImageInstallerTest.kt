package com.vivenotes.desktop.update

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the AppImage swaps itself for a newer one and opens it again. */
class AppImageInstallerTest {
    @get:Rule val temp = TemporaryFolder()

    private val release = Release(ReleaseVersion.parse("0.2.0")!!, "",
        ReleaseAsset(appImageName("0.2.0"), URI("https://github.com/x/releases/download/v0.2.0/${appImageName("0.2.0")}"), 3),
        ReleaseAsset("${appImageName("0.2.0")}.sha256", URI("https://github.com/x/${appImageName("0.2.0")}.sha256"), 98))

    private val started = mutableListOf<Triple<List<String>, File, File>>()

    private fun installer(image: File, environment: Map<String, String> = emptyMap(), processId: Long = 4242) =
        AppImageInstaller(RunningAppImage(image, processId), listOf("--x11"), temp.root.resolve("cache/updates"),
            start = { command, directory, log -> started += Triple(command, directory, log) },
            homeDirectory = temp.root, environment = environment::get)

    private fun image(name: String): File = File(temp.newFolder("Applications"), name).apply {
        writeText("old image")
        if (isLinux()) Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rwxr-x---"))
    }

    @Test
    fun anImageWithItsReleaseNameTakesTheNewReleasesNameAndTheOldFileGoes() {
        assumeTrue(isLinux())
        val image = image("ViveNotes-0.1.0-x86_64.AppImage")
        val installer = installer(image, mapOf("OWD" to temp.root.path))
        val staged = installer.staged(release)
        assertEquals(File(image.parentFile, ".ViveNotes-0.2.0-x86_64.AppImage.update"), staged)
        assertEquals(image, installer.seed())
        staged.writeText("new image")

        installer.launch(staged, release)

        val updated = File(image.parentFile, "ViveNotes-0.2.0-x86_64.AppImage")
        assertEquals("new image", updated.readText())
        assertFalse(image.exists())
        assertFalse(staged.exists())
        assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(updated.toPath()))
        val (command, directory, log) = started.single()
        assertEquals(listOf("/bin/sh", "-c", AppImageInstaller.REOPEN_SCRIPT, "vivenotes-update", "4242", updated.path, "--x11"),
            command)
        assertEquals(temp.root, directory, "the image's own working directory")
        assertEquals(temp.root.resolve("cache/updates/${AppImageInstaller.REOPEN_LOG}"), log)
    }

    /** AppImage managers rename images (Gear Lever to a fixed name), and their menu entries point there. */
    @Test
    fun anImageTheUserRenamedIsReplacedAtItsOwnPath() {
        assumeTrue(isLinux())
        val image = image("vive_notes.appimage")
        Files.setPosixFilePermissions(image.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        val installer = installer(image)
        val staged = installer.staged(release).apply { writeText("new image") }

        installer.launch(staged, release)

        assertEquals("new image", image.readText())
        assertEquals(listOf(image.name), image.parentFile.list()!!.toList())
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(image.toPath()), "it must run")
        assertEquals(image.path, started.single().first[5])
        assertEquals(temp.root, started.single().second, "the home folder without the runtime's OWD")
    }

    @Test
    fun aFailedSwapOpensTheOldVersionAgain() {
        assumeTrue(isLinux())
        val image = image("ViveNotes-0.1.0-x86_64.AppImage")
        val installer = installer(image)

        val missing = installer.staged(release)
        runCatching { installer.launch(missing, release) }.also { assertTrue(it.isFailure) }

        assertEquals("old image", image.readText())
        assertEquals(image.path, started.single().first[5])
    }

    @Test
    fun onlyTheUpdatersOwnLeftoversAreCleared() {
        val image = image("ViveNotes-0.1.0-x86_64.AppImage")
        val folder = image.parentFile
        val ours = listOf(".ViveNotes-0.2.0-x86_64.AppImage.update", ".ViveNotes-0.2.0-x86_64.AppImage.update-123456.part")
        val theirs = listOf("ViveNotes-0.0.9-x86_64.AppImage", ".hidden", "notes.part", "Other-1.0-x86_64.AppImage.update")
        (ours + theirs).forEach { File(folder, it).writeText("x") }

        installer(image).clearLeftovers()

        assertEquals((theirs + image.name).sorted(), folder.list()!!.sorted())
    }

    @Test
    fun aFolderThatCannotBeChangedIsExplained() {
        assumeTrue(isLinux() && System.getProperty("user.name") != "root")
        val image = image("ViveNotes-0.1.0-x86_64.AppImage")
        assertNull(installer(image).problem())
        image.parentFile.setWritable(false)
        try {
            assertEquals("Vive Notes cannot replace its AppImage in ${image.parentFile.path}. Move the AppImage to a folder " +
                "you can change, then try again.", installer(image).problem())
        } finally {
            image.parentFile.setWritable(true)
        }
    }

    @Test
    fun theNewLaunchDoesNotInheritTheOldImagesRuntimeVariables() {
        val environment = mutableMapOf("APPIMAGE" to "/old.AppImage", "APPDIR" to "/tmp/.mount_x", "ARGV0" to "x",
            "OWD" to "/home/ada", "HOME" to "/home/ada", "WAYLAND_DISPLAY" to "wayland-0")
        removeRuntimeVariables(environment)
        assertEquals(mapOf("HOME" to "/home/ada", "WAYLAND_DISPLAY" to "wayland-0"), environment)
    }

    /** The real script and process start: the new image opens only once the old process has ended. */
    @Test
    fun theReopenScriptWaitsForTheAppToExitThenRunsTheNewImage() {
        assumeTrue(isLinux())
        val image = image("ViveNotes-0.1.0-x86_64.AppImage")
        val marker = File(temp.root, "reopened.txt")
        val closingApp = ProcessBuilder("sleep", "2").start()
        val installer = AppImageInstaller(RunningAppImage(image, closingApp.pid()), listOf("--x11", "--debug"),
            temp.root.resolve("cache/updates"), homeDirectory = temp.root, environment = { null })
        val staged = installer.staged(release).apply {
            writeText("#!/bin/sh\necho \"$@\" > '${marker.path}'\n")
        }

        installer.launch(staged, release)

        Thread.sleep(1_000)
        assertTrue(closingApp.isAlive, "The stand-in app must outlive the first check")
        assertFalse(marker.exists(), "The new image opened while the old one was still running")
        assertTrue(closingApp.waitFor(30, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(100)
        assertEquals("--x11 --debug", marker.readText().trim(),
            temp.root.resolve("cache/updates/${AppImageInstaller.REOPEN_LOG}").takeIf(File::exists)?.readText())
    }

    private fun isLinux() = System.getProperty("os.name").startsWith("Linux")
}
