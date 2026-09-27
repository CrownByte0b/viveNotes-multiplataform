package com.vivenotes.data

import java.io.File

/**
 * Where ViveNotes keeps its notes on this computer.
 *
 * The per-user application data directory, never the working directory. Linux follows the XDG
 * base directory specification, which is also how a Flatpak sandbox redirects it; Windows uses the
 * roaming application data folder. The database, its backups and, later, attachments live here.
 */
object AppDirectories {

    fun config(
        osName: String = System.getProperty("os.name"),
        environment: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home"),
    ): File = when {
        osName.startsWith("Windows", ignoreCase = true) -> File(
            environment("APPDATA")?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Roaming").path,
            "ViveNotes",
        )
        osName.startsWith("Mac", ignoreCase = true) -> File(home, "Library/Application Support/ViveNotes")
        else -> File(
            environment("XDG_CONFIG_HOME")?.takeIf { File(it).isAbsolute } ?: File(home, ".config").path,
            "vivenotes",
        )
    }

    /**
     * Scratch space nothing depends on keeping, such as the staging area of a `.vive` transfer:
     * the XDG cache directory on Linux, the local (never roaming) application data on Windows.
     */
    fun cache(
        osName: String = System.getProperty("os.name"),
        environment: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home"),
    ): File = when {
        osName.startsWith("Windows", ignoreCase = true) -> File(
            environment("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Local").path,
            "ViveNotes/Cache",
        )
        osName.startsWith("Mac", ignoreCase = true) -> File(home, "Library/Caches/ViveNotes")
        else -> File(
            environment("XDG_CACHE_HOME")?.takeIf { File(it).isAbsolute } ?: File(home, ".cache").path,
            "vivenotes",
        )
    }

    fun data(
        osName: String = System.getProperty("os.name"),
        environment: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home"),
    ): File = when {
        osName.startsWith("Windows", ignoreCase = true) -> File(
            environment("APPDATA")?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Roaming").path,
            "ViveNotes",
        )
        osName.startsWith("Mac", ignoreCase = true) -> File(home, "Library/Application Support/ViveNotes")
        // The specification makes a relative path invalid rather than relative to anything.
        else -> File(
            environment("XDG_DATA_HOME")?.takeIf { File(it).isAbsolute } ?: File(home, ".local/share").path,
            "vivenotes",
        )
    }
}
