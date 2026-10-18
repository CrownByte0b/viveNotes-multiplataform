package com.vivenotes.desktop

import com.vivenotes.data.sync.InstallationDetails
import com.vivenotes.localization.Strings
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * What the Android app's `SyncAccounts` reads from Android about this installation, read from the
 * desktop instead: the name and platform the server's device list shows, the optional details the
 * Google routes send, and the identifier its installation id is derived from.
 */
internal data class DesktopSyncIdentity(
    /** Android's `"<Build.MODEL> (<suffix>)"`: the host name, and the same four-character suffix. */
    val deviceName: String,
    /** Android's `"Android <release>"`: the operating system's own name for its release. */
    val platform: String,
    val details: InstallationDetails,
    /**
     * Stands in for Android's `ANDROID_ID`, which is scoped to the app's signing key, the user and the
     * device: the machine id, the user and the profile — the development profile being the desktop's
     * debug build, a separate installation as `com.vivenotes.debug` is. Null where the machine has
     * no id, which falls back to a random installation id, as Android does without one.
     */
    val deviceSeed: String?,
) {
    companion object {
        fun current(
            profile: DesktopProfile,
            appVersion: String,
            read: (File) -> String? = ::readSmallFile,
            environment: (String) -> String? = System::getenv,
            property: (String) -> String? = System::getProperty,
            windowsMachineGuid: () -> String? = ::queryWindowsMachineGuid,
        ): DesktopSyncIdentity {
            val osName = property("os.name").orEmpty()
            val windows = osName.startsWith("Windows", ignoreCase = true)
            val machineId = if (windows) {
                windowsMachineGuid()
            } else {
                read(File("/etc/machine-id")) ?: read(File("/var/lib/dbus/machine-id"))
            }?.trim()?.takeIf { it.isNotEmpty() }
            val seed = machineId?.let { "$it\u0000${property("user.name").orEmpty()}\u0000${profile.name}" }

            val host = (if (windows) environment("COMPUTERNAME") else null)
                ?: read(File("/proc/sys/kernel/hostname"))
                ?: read(File("/etc/hostname"))
                ?: environment("HOSTNAME")
            val model = host?.trim()?.takeIf { it.isNotEmpty() } ?: Strings.desktop_device_name
            val deviceName = seed?.let { "$model (${installationSuffix(it)})" } ?: model

            val release = if (windows) null else osRelease(read(File("/etc/os-release")))
            val platform = (release ?: listOf(osName, property("os.version").orEmpty()).joinToString(" "))
                .trim().ifEmpty { Strings.desktop_device_name }

            return DesktopSyncIdentity(
                deviceName = deviceName,
                platform = platform,
                details = InstallationDetails(
                    // Android's debug build reports `<version>-debug`; the development profile is
                    // the desktop's debug build.
                    appVersion = if (profile == DesktopProfile.DEVELOPMENT) "$appVersion-dev" else appVersion,
                    osVersion = property("os.version")?.takeIf { it.isNotBlank() },
                    model = read(File("/sys/devices/virtual/dmi/id/product_name"))?.trim()?.takeIf { it.isNotEmpty() },
                    architecture = property("os.arch")?.takeIf { it.isNotBlank() },
                ),
                deviceSeed = seed,
            )
        }

        /**
         * Android's suffix: the leading two bytes of a SHA-256, so two computers with one host name are
         * two rows somebody can tell apart. Hashed and truncated because the server needs a label and
         * not an identifier.
         */
        internal fun installationSuffix(seed: String): String =
            MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
                .take(2)
                .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

        /** `PRETTY_NAME` from `/etc/os-release`, e.g. "Fedora Linux 44 (Workstation Edition)". */
        internal fun osRelease(text: String?): String? = text?.lineSequence()
            ?.firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter('=')
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() }
    }
}

private fun readSmallFile(file: File): String? = runCatching {
    if (file.isFile && file.length() <= 64 * 1024) file.readText() else null
}.getOrNull()

/** `HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid`, Windows' per-installation id. */
private fun queryWindowsMachineGuid(): String? = runCatching {
    val process = ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    output.lineSequence().firstOrNull { it.contains("MachineGuid") }?.trim()?.split(Regex("\\s+"))?.lastOrNull()
}.getOrNull()
