package com.vivenotes.desktop

import com.vivenotes.data.AppDirectories
import java.io.File

internal data class ProfileDirectories(val data: File, val config: File, val cache: File)

/** Separate identities and storage for development runs and the installed app. */
internal enum class DesktopProfile(val windowClass: String, val windowTitle: String) {
    PRODUCTION("vivenotes", "Vive Notes"),
    DEVELOPMENT("vivenotes-dev", "Vive Notes Dev");

    fun directories(workingDirectory: File = File(System.getProperty("user.dir"))): ProfileDirectories =
        when (this) {
            PRODUCTION -> ProfileDirectories(AppDirectories.data(), AppDirectories.config(), AppDirectories.cache())
            DEVELOPMENT -> File(workingDirectory.absoluteFile, "devdb").let { root ->
                ProfileDirectories(root, File(root, "config"), File(root, "cache"))
            }
        }

    companion object {
        fun fromProperty(value: String? = System.getProperty("vivenotes.profile")): DesktopProfile =
            when (value) {
                null, "production" -> PRODUCTION
                "dev" -> DEVELOPMENT
                else -> error("Unknown vivenotes.profile '$value'; use 'dev' or 'production'")
            }
    }
}
