package com.vivenotes.desktop.update

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hand-over from the closing app to Windows Installer. */
class WindowsInstallerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun theScriptWaitsForBothLauncherProcessesInstallsInPlaceAndReopensTheApp() {
        val app = InstalledApp(File(temp.newFolder("Vive Notes"), "Vive Notes.exe"), listOf(4321L, 4300L))
        val msi = File(temp.newFolder("updates"), "ViveNotes-x64.msi")
        val log = File(msi.parentFile, WindowsMsiInstaller.INSTALL_LOG)

        val script = installScript(msi, log, app, File("C:\\Windows\\System32\\msiexec.exe"))

        assertTrue(script.contains("\r\n") && !script.replace("\r\n", "").contains('\n'), "Windows line endings throughout")
        val lines = script.lines().map(String::trimEnd)
        assertTrue("\$app = @(4321, 4300)" in lines, script)
        assertTrue("    Wait-Process -Id \$id -Timeout 300 -ErrorAction SilentlyContinue" in lines, script)
        assertTrue("if (Get-Process -Id \$app -ErrorAction SilentlyContinue) {" in lines, script)
        val install = lines.single { it.startsWith("\$installer = Start-Process") }
        assertEquals("\$installer = Start-Process -FilePath 'C:\\Windows\\System32\\msiexec.exe' -ArgumentList " +
            "'/i \"${msi.absolutePath}\" /passive /norestart /l*v \"${log.absolutePath}\" " +
            "INSTALLDIR=\"${app.directory.absolutePath}\"' -Wait -PassThru", install)
        assertTrue(lines.indexOf(install) > lines.indexOf("    exit 1"), "Nothing installs while the app is still running")
        assertTrue("    Start-Process -FilePath ${quote(app.launcher.absolutePath)}" in lines, script)
    }

    @Test
    fun quotesKeepEveryPathALiteral() {
        assertEquals("'C:\\Users\\Ada\\Vive Notes'", quote("C:\\Users\\Ada\\Vive Notes"))
        assertEquals("'C:\\Users\\O''Brien'", quote("C:\\Users\\O'Brien"))
        // PowerShell also ends a single-quoted string at the typographic quotes.
        assertEquals("'C:\\Users\\O\u2019\u2019Brien \u2018\u2018x\u2018\u2018'", quote("C:\\Users\\O\u2019Brien \u2018x\u2018"))
        assertEquals("'\$env:TEMP; Remove-Item C:\\'", quote("\$env:TEMP; Remove-Item C:\\"))
    }

    @Test
    fun anInstallDirectoryNeverEndsInABackslashBeforeItsClosingQuote() {
        // Only Linux keeps a backslash as part of a name; Windows normalises it away by itself.
        val app = InstalledApp(File(File(temp.root, "Vive Notes\\"), "Vive Notes.exe"), listOf(1L))
        val script = installScript(File(temp.root, "a.msi"), File(temp.root, "a.log"), app, File("msiexec.exe"))

        assertTrue(script.contains("INSTALLDIR=\"${File(temp.root, "Vive Notes").absolutePath}\"' -Wait"), script)
    }

    @Test
    fun launchingWritesTheScriptWithAByteOrderMarkAndRunsItWithWindowsPowerShell() {
        val app = InstalledApp(File(temp.newFolder("Vive Notes"), "Vive Notes.exe"), listOf(10L))
        val updates = temp.newFolder("updates")
        val msi = File(updates, "ViveNotes-x64.msi").apply { writeText("msi") }
        val powershell = temp.newFile("powershell.exe")
        val msiexec = temp.newFile("msiexec.exe")
        val started = mutableListOf<Pair<List<String>, File>>()
        val installer = WindowsMsiInstaller(app, updates, powershell, msiexec) { command, log -> started += command to log }

        assertNull(installer.problem())
        installer.launch(msi, release)

        val script = File(updates, WindowsMsiInstaller.SCRIPT_NAME)
        assertContentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), script.readBytes().copyOf(3))
        assertEquals(installScript(msi, File(updates, WindowsMsiInstaller.INSTALL_LOG), app, msiexec),
            script.readBytes().copyOfRange(3, script.length().toInt()).decodeToString())
        assertEquals(listOf(listOf(powershell.path, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden", "-File", script.absolutePath) to File(updates, WindowsMsiInstaller.SCRIPT_LOG)), started)
    }

    @Test
    fun withoutPowerShellOrWindowsInstallerItIsUnavailable() {
        val app = InstalledApp(temp.newFile("Vive Notes.exe"), listOf(1L))
        val present = temp.newFile("present.exe")
        val missing = File(temp.root, "missing.exe")

        val reason = "Windows Installer could not be started on this computer."
        assertEquals(reason, WindowsMsiInstaller(app, temp.root, missing, present).problem())
        assertEquals(reason, WindowsMsiInstaller(app, temp.root, present, missing).problem())
    }

    /**
     * The installed app has no copy of its own MSI, so the last installer stays as the next update's
     * seed; unfinished downloads and installers under the old versioned names go.
     */
    @Test
    fun theLastInstallerStaysAsTheNextUpdatesSeed() {
        val app = InstalledApp(temp.newFile("Vive Notes.exe"), listOf(1L))
        val updates = temp.newFolder("updates")
        val installer = WindowsMsiInstaller(app, updates, temp.newFile("powershell.exe"), temp.newFile("msiexec.exe"))
        assertNull(installer.seed(), "Before the first update there is nothing to start from")
        assertEquals(File(updates, INSTALLER_NAME), installer.staged(release))

        File(updates, INSTALLER_NAME).writeText("1.2.0's installer")
        File(updates, "ViveNotes-1.0.2-x64.msi").writeText("older, from when the name carried a version")
        File(updates, "$INSTALLER_NAME-123.part").writeText("unfinished")
        File(updates, WindowsMsiInstaller.INSTALL_LOG).writeText("last install")
        installer.clearLeftovers()

        assertEquals(listOf(INSTALLER_NAME, WindowsMsiInstaller.INSTALL_LOG), updates.list()!!.sorted())
        assertEquals(File(updates, INSTALLER_NAME), installer.seed())
    }

    /**
     * The real script under Windows PowerShell, in a folder whose name needs quoting: Windows
     * Installer, here a stand-in that records its arguments, starts only after the app's process has
     * ended, and the app is opened again afterwards.
     */
    @Test
    fun onWindowsTheScriptRunsTheInstallerOnlyAfterTheAppHasExitedThenReopensIt() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val root = temp.newFolder("Vive Notes O'Brien")
        val record = File(root, "msiexec-arguments.txt")
        val reopened = File(root, "reopened.txt")
        val msiexec = File(root, "msiexec.cmd").apply {
            writeText("@echo off\r\necho %*> \"${record.absolutePath}\"\r\nexit /b 0\r\n")
        }
        val launcher = File(File(root, "Vive Notes").apply { mkdirs() }, "Vive Notes.cmd").apply {
            writeText("@echo off\r\necho reopened> \"${reopened.absolutePath}\"\r\n")
        }
        val msi = File(File(root, "updates").apply { mkdirs() }, "ViveNotes-x64.msi").apply { writeText("msi") }
        // Stands in for the closing app: a process that lives a few seconds.
        val closingApp = ProcessBuilder("cmd.exe", "/c", "ping -n 5 127.0.0.1 > NUL").start()
        val powershell = File(System.getenv("SystemRoot"), "System32\\WindowsPowerShell\\v1.0\\powershell.exe")

        WindowsMsiInstaller(InstalledApp(launcher, listOf(closingApp.pid())), msi.parentFile, powershell, msiexec).launch(msi, release)

        Thread.sleep(1_500)
        assertTrue(closingApp.isAlive, "The stand-in app must outlive the first check")
        assertFalse(record.exists(), "Windows Installer started while the app was still running")
        assertTrue(closingApp.waitFor(30, TimeUnit.SECONDS))
        val exitedAt = System.currentTimeMillis()
        val log = File(msi.parentFile, WindowsMsiInstaller.SCRIPT_LOG)
        awaitFile(reopened) { "The app was not reopened. Script output:\n${log.takeIf(File::exists)?.readText()}" }
        assertEquals("/i \"${msi.absolutePath}\" /passive /norestart /l*v " +
            "\"${File(msi.parentFile, WindowsMsiInstaller.INSTALL_LOG).absolutePath}\" " +
            "INSTALLDIR=\"${launcher.parentFile.absolutePath}\"", record.readText().trim())
        assertTrue(record.lastModified() >= exitedAt - 1_000, "Windows Installer ran before the app exited")
        assertTrue(log.readText().contains("Windows Installer exit code: 0"), log.readText())
    }

    private val release = Release(ReleaseVersion.parse("0.2.0")!!, "",
        ReleaseAsset(INSTALLER_NAME, URI("https://github.com/x/releases/download/v0.2.0/$INSTALLER_NAME"), 3),
        ReleaseAsset("$INSTALLER_NAME.sha256", URI("https://github.com/x/releases/download/v0.2.0/$INSTALLER_NAME.sha256"), 85))

    private fun awaitFile(file: File, message: () -> String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!file.exists()) {
            if (System.nanoTime() > deadline) throw AssertionError(message())
            Thread.sleep(200)
        }
    }
}
