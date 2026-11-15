package com.vivenotes.desktop.update

import com.vivenotes.localization.Strings
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * The AppImage this process runs from: the file the AppImage runtime names in `APPIMAGE`, when this
 * JVM is the one inside the mounted image (`APPDIR`), not a Gradle run that inherited the variables.
 */
internal data class RunningAppImage(val image: File, val processId: Long) {
    companion object {
        fun current(
            environment: (String) -> String? = System::getenv,
            javaHome: String? = System.getProperty("java.home"),
        ): RunningAppImage? {
            val image = environment("APPIMAGE")?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)
                ?: return null
            val mount = environment("APPDIR")?.takeIf(String::isNotBlank)?.let { File(it).absoluteFile } ?: return null
            val runtime = javaHome?.let { File(it).absoluteFile } ?: return null
            if (!runtime.toPath().normalize().startsWith(mount.toPath().normalize())) return null
            return RunningAppImage(image.absoluteFile, ProcessHandle.current().pid())
        }
    }
}

/**
 * Replaces the running AppImage with a verified newer one. The download waits beside the image as a
 * hidden file, so the swap is a rename within one folder; the running copy keeps reading its old file,
 * which stays on disk until the app exits. An image that still has its release name
 * (`ViveNotes-<version>-x86_64.AppImage`) takes the new release's name and the old file goes; one the
 * user renamed, as AppImage managers do, is replaced at its own path. A small shell script then waits
 * for this process to end and opens the new image with the same options.
 */
internal class AppImageInstaller(
    private val app: RunningAppImage,
    /** `--x11` and `--debug` as this launch had them. */
    private val arguments: List<String>,
    /** Where the reopening script writes what the new launch prints. */
    private val logDirectory: File,
    private val start: (command: List<String>, directory: File, log: File) -> Unit = ::startDetachedProcess,
    private val homeDirectory: File = File(System.getProperty("user.home")),
    private val environment: (String) -> String? = System::getenv,
) : UpdateInstaller {
    private val folder: File get() = app.image.absoluteFile.parentFile

    override fun problem(): String? = if (Files.isWritable(folder.toPath())) null else Strings.update_appimage_read_only(folder.path)

    override fun staged(release: Release): File = File(folder, ".${release.installer.name}$STAGED_SUFFIX")

    override fun seed(): File = app.image

    /** Only this updater's own hidden files: the folder is the user's. */
    override fun clearLeftovers() {
        folder.listFiles()?.filter { LEFTOVER.matches(it.name) }?.forEach(File::delete)
    }

    /** Where [release] ends up: under its own name when the running image still has a release's name. */
    fun destination(release: Release): File =
        if (RELEASE_NAME.matches(app.image.name)) File(folder, release.installer.name) else app.image

    override fun launch(file: File, release: Release) {
        val destination = destination(release)
        try {
            val permissions = runCatching { Files.getPosixFilePermissions(app.image.toPath()) }.getOrNull().orEmpty()
            Files.setPosixFilePermissions(file.toPath(), permissions + EXECUTABLE)
            try {
                Files.move(file.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            // The notes are closed already: open this version again rather than leave the user with nothing.
            reopen(app.image)
            throw error
        }
        if (destination != app.image) app.image.delete()
        reopen(destination)
    }

    private fun reopen(image: File) {
        logDirectory.mkdirs()
        // The image's working directory, where the runtime keeps it, so relative paths mean the same.
        val directory = environment("OWD")?.let(::File)?.takeIf(File::isDirectory) ?: homeDirectory
        start(listOf("/bin/sh", "-c", REOPEN_SCRIPT, "vivenotes-update", app.processId.toString(), image.path) +
            arguments, directory, File(logDirectory, REOPEN_LOG))
    }

    companion object {
        const val STAGED_SUFFIX = ".update"
        const val REOPEN_LOG = "reopen-appimage.log"
        /** As long as the Windows hand-over waits; past it the new version opens at the next launch. */
        const val EXIT_WAIT_SECONDS = WindowsMsiInstaller.EXIT_WAIT_SECONDS

        private val RELEASE_NAME = Regex("""ViveNotes-\d[^/]*-x86_64\.AppImage""")
        private val LEFTOVER = Regex("""\.ViveNotes-[^/]+-x86_64\.AppImage\.update(-\d+\.part)?""")
        private val EXECUTABLE = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)

        /** What the AppImage runtime sets for the image it runs; the new image's runtime sets its own. */
        val RUNTIME_VARIABLES = listOf("APPIMAGE", "APPDIR", "ARGV0", "OWD")

        /**
         * Waits for process `$1` to end, then runs the rest. A process that has ended but is not yet
         * reaped is a zombie, which `kill -0` still finds, so `/proc` decides.
         */
        val REOPEN_SCRIPT = """
            |pid=${'$'}1
            |shift
            |tries=0
            |while [ -e "/proc/${'$'}pid" ] && ! grep -q '^State:[[:space:]]*Z' "/proc/${'$'}pid/status" 2>/dev/null; do
            |    tries=${'$'}((tries + 1))
            |    if [ "${'$'}tries" -gt ${EXIT_WAIT_SECONDS * 5} ]; then
            |        echo 'Vive Notes did not close; the new version opens at the next launch.'
            |        exit 1
            |    fi
            |    sleep 0.2
            |done
            |exec "${'$'}@"
        """.trimMargin()
    }
}

/**
 * Starts [command] in [directory] with nothing tied to this process: input closed, output appended
 * to [log], and without the AppImage runtime's variables for the image that is going away.
 */
private fun startDetachedProcess(command: List<String>, directory: File, log: File) {
    val builder = ProcessBuilder(command).directory(directory).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
    removeRuntimeVariables(builder.environment())
    builder.start().outputStream.close()
}

/** Drops the variables the AppImage runtime set for the image that is going away; its successor's sets its own. */
internal fun removeRuntimeVariables(environment: MutableMap<String, String>) {
    AppImageInstaller.RUNTIME_VARIABLES.forEach(environment::remove)
}
