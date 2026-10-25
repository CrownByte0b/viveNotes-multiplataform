package com.vivenotes.desktop.update

import com.vivenotes.desktop.readProperties
import com.vivenotes.desktop.writePropertiesAtomically
import java.io.File
import java.util.Properties

/** The release the user chose to skip, so the app stops offering it until a newer one comes out. */
internal class UpdatePreferencesFile(private val file: File) {
    fun skippedVersion(): ReleaseVersion? = file.readProperties()?.getProperty("skippedVersion")?.let(ReleaseVersion::parse)

    fun skip(version: ReleaseVersion) {
        file.writePropertiesAtomically(Properties().apply { setProperty("skippedVersion", version.toString()) },
            "ViveNotes update preferences")
    }
}
