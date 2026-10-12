package com.vivenotes.math

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The uv project the app carries under `math-engine/` in its resources: the Android app's
 * `math_engine.py`, `degrees.py` and `greeks.py` unchanged, the pipe that stands in for Chaquopy,
 * and the lock and version pin that say exactly what uv installs. Its sources are
 * `shared/src/jvmMain/python`.
 */
internal class MathEngineProject(
    /** A project file's bytes, or null when the app does not carry it. */
    private val read: (String) -> ByteArray? = { name ->
        MathEngineProject::class.java.getResourceAsStream("/$RESOURCES/$name")?.use { it.readBytes() }
    },
) {
    private fun bytes(name: String): ByteArray = read(name) ?: error("$name is missing from the app")

    /** The exact CPython the lock was made for, from `.python-version`. */
    val pythonVersion: String by lazy { bytes(PYTHON_VERSION).decodeToString().trim() }

    /**
     * Names the environment these files describe: a digest of the lock, the project and the
     * interpreter pin. An install records it, and a different one — an update that moved a pin —
     * means the environment on disk is not the one this app was built against.
     */
    val environmentDigest: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        ENVIRONMENT_FILES.forEach { name ->
            digest.update(name.encodeToByteArray())
            digest.update(0)
            digest.update(bytes(name))
            digest.update(0)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Writes every project file into [directory]. A file that already holds the same bytes is left
     * alone; any other is replaced whole, so a reader never sees half of one.
     */
    fun extract(directory: File) {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
        FILES.forEach { name ->
            val content = bytes(name)
            val target = File(directory, name)
            if (target.isFile && target.readBytes().contentEquals(content)) return@forEach
            val staging = File(directory, ".$name.part")
            staging.writeBytes(content)
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    companion object {
        const val RESOURCES = "math-engine"
        const val SERVER = "math_server.py"
        private const val PYTHON_VERSION = ".python-version"

        /** Everything in the project. `MathEngineProjectTest` holds this to the source directory. */
        val FILES = listOf(
            "pyproject.toml", "uv.lock", PYTHON_VERSION,
            "math_engine.py", "degrees.py", "greeks.py", SERVER,
        )

        /** The files that decide what uv installs; see [environmentDigest]. */
        val ENVIRONMENT_FILES = listOf("pyproject.toml", "uv.lock", PYTHON_VERSION)
    }
}
