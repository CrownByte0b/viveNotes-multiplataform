package com.vivenotes.desktop.update

import java.io.File

/** The installed app an update replaces: its launcher, and the processes that must end first. */
internal data class InstalledApp(val launcher: File, val processIds: List<Long>) {
    val directory: File get() = launcher.absoluteFile.parentFile

    companion object {
        /**
         * The running app, when its jpackage launcher started it: the launcher names itself in
         * `jpackage.app-path`. Null under Gradle, which is never an installation to replace.
         */
        fun current(appPath: String? = System.getProperty("jpackage.app-path")): InstalledApp? {
            val launcher = appPath?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile) ?: return null
            val self = ProcessHandle.current()
            val command = self.info().command().orElse(null)
            // The Windows launcher starts a second copy of itself to host the JVM and waits for it
            // (jpackage's WinLauncher.cpp); that first copy holds the executable open too.
            val launcherProcess = self.parent().orElse(null)
                ?.takeIf { command != null && it.info().command().orElse(null) == command }
            return InstalledApp(launcher, listOfNotNull(self.pid(), launcherProcess?.pid()))
        }
    }
}

/** Runs a verified installer once the app has gone, so it can replace the app's files. */
internal interface UpdateInstaller {
    /** Whether this computer has what [launch] runs. */
    fun available(): Boolean

    /** Starts the hand-over to [msi]; the app must exit next. */
    fun launch(msi: File)
}

/**
 * Hands the MSI to Windows Installer through a small PowerShell script in the download directory: it
 * waits for [app]'s processes to end, installs over the same folder with only a progress bar, and
 * opens the app again. The script outlives the app because jpackage's launcher lets its job's
 * children break away (`JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK`).
 */
internal class WindowsMsiInstaller(
    private val app: InstalledApp,
    // Full paths, never a PATH lookup: the launcher puts its own app directory first on PATH.
    private val powershell: File = File(systemRoot(), "System32\\WindowsPowerShell\\v1.0\\powershell.exe"),
    private val msiexec: File = File(systemRoot(), "System32\\msiexec.exe"),
    private val start: (List<String>, File) -> Unit = ::startDetached,
) : UpdateInstaller {
    override fun available(): Boolean = powershell.isFile && msiexec.isFile

    override fun launch(msi: File) {
        val directory = msi.absoluteFile.parentFile
        val script = File(directory, SCRIPT_NAME)
        // Windows PowerShell reads a script without a byte order mark in the ANSI code page, which
        // would garble a user name outside it.
        script.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            installScript(msi, File(directory, INSTALL_LOG), app, msiexec).toByteArray(Charsets.UTF_8))
        start(listOf(powershell.path, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden", "-File", script.absolutePath), File(directory, SCRIPT_LOG))
    }

    companion object {
        const val SCRIPT_NAME = "install-update.ps1"
        const val SCRIPT_LOG = "install-update.log"
        const val INSTALL_LOG = "install-msi.log"
        /** Longer than any flush the app could need; past it the update waits for another launch. */
        const val EXIT_WAIT_SECONDS = 300
    }
}

/**
 * The hand-over script: PowerShell 5.1, which every supported Windows has. Each value is a
 * single-quoted literal, so a path is never parsed as code. Windows Installer keeps the per-user
 * install where it is through `INSTALLDIR`, the property the jpackage MSI installs into. The app opens
 * again whatever the installer's result: a failed or cancelled upgrade rolls back to this version.
 */
internal fun installScript(msi: File, log: File, app: InstalledApp, msiexec: File): String {
    val ids = app.processIds.joinToString(", ")
    // Windows Installer reads `\"` as an escaped quote, so a directory must not end in a backslash.
    val directory = app.directory.absolutePath.trimEnd('\\', '/')
    val arguments = "/i \"${msi.absolutePath}\" /passive /norestart /l*v \"${log.absolutePath}\" INSTALLDIR=\"$directory\""
    return """
        |${'$'}ErrorActionPreference = 'Continue'
        |${'$'}app = @($ids)
        |foreach (${'$'}id in ${'$'}app) {
        |    Wait-Process -Id ${'$'}id -Timeout ${WindowsMsiInstaller.EXIT_WAIT_SECONDS} -ErrorAction SilentlyContinue
        |}
        |if (Get-Process -Id ${'$'}app -ErrorAction SilentlyContinue) {
        |    'Vive Notes did not close; the update was not installed.'
        |    exit 1
        |}
        |${'$'}installer = Start-Process -FilePath ${quote(msiexec.path)} -ArgumentList ${quote(arguments)} -Wait -PassThru
        |"Windows Installer exit code: ${'$'}(${'$'}installer.ExitCode)"
        |if (Test-Path -LiteralPath ${quote(app.launcher.absolutePath)}) {
        |    Start-Process -FilePath ${quote(app.launcher.absolutePath)}
        |}
        |
    """.trimMargin().replace("\n", "\r\n")
}

/** A PowerShell single-quoted literal; PowerShell also closes one at the typographic single quotes. */
internal fun quote(value: String): String =
    "'" + value.replace(Regex("['\u2018\u2019\u201A\u201B]")) { it.value + it.value } + "'"

private fun systemRoot(): String = System.getenv("SystemRoot")?.takeIf(String::isNotBlank) ?: "C:\\Windows"

/**
 * Starts [command] with no window and no tie to this process: its input closed and its output in
 * [log], so nothing breaks when the app exits under it.
 */
private fun startDetached(command: List<String>, log: File) {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.to(log))
        .start()
    process.outputStream.close()
}
