package com.vivenotes.desktop

import com.vivenotes.localization.Strings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the server's device list will call this computer, and the identifier its installation id is
 * derived from — the desktop's answers to what Android reads from `Build` and `ANDROID_ID`.
 */
class DesktopSyncIdentityTest {

    private val linuxFiles = mapOf(
        "/etc/machine-id" to "0123456789abcdef0123456789abcdef\n",
        "/proc/sys/kernel/hostname" to "studio\n",
        "/etc/os-release" to "NAME=\"Fedora Linux\"\nPRETTY_NAME=\"Fedora Linux 44 (Workstation Edition)\"\n",
        "/sys/devices/virtual/dmi/id/product_name" to "XPS 13 9310\n",
    )
    private val linuxProperties = mapOf(
        "os.name" to "Linux",
        "os.version" to "7.2.10-200.fc44.x86_64",
        "os.arch" to "amd64",
        "user.name" to "owner",
    )

    private fun identity(
        profile: DesktopProfile = DesktopProfile.PRODUCTION,
        files: Map<String, String> = linuxFiles,
        properties: Map<String, String> = linuxProperties,
        environment: Map<String, String> = emptyMap(),
        machineGuid: String? = null,
    ) = DesktopSyncIdentity.current(
        profile = profile,
        appVersion = "0.1.0",
        read = { file: File -> files[file.invariantSeparatorsPath] },
        environment = { environment[it] },
        property = { properties[it] },
        windowsMachineGuid = { machineGuid },
    )

    /** Android's "<model> (<suffix>)": the host name, and four characters of a hash beside it. */
    @Test
    fun aLinuxComputerIsNamedByItsHostWithAndroidsSuffix() {
        val linux = identity()

        assertEquals("studio (${DesktopSyncIdentity.installationSuffix(linux.deviceSeed!!)})", linux.deviceName)
        assertTrue(Regex("studio \\([0-9a-f]{4}\\)").matches(linux.deviceName))
        assertEquals("Fedora Linux 44 (Workstation Edition)", linux.platform)
        assertEquals("0.1.0", linux.details.appVersion)
        assertEquals("XPS 13 9310", linux.details.model)
        assertEquals("amd64", linux.details.architecture)
        assertEquals("7.2.10-200.fc44.x86_64", linux.details.osVersion)
    }

    /**
     * The development profile is the desktop's debug build, a separate installation as Android's
     * `com.vivenotes.debug` is: another seed, so another installation id and suffix, and Android's
     * debug version suffix.
     */
    @Test
    fun theDevelopmentProfileIsAnInstallationOfItsOwn() {
        val production = identity(DesktopProfile.PRODUCTION)
        val development = identity(DesktopProfile.DEVELOPMENT)

        assertNotEquals(production.deviceSeed, development.deviceSeed)
        assertEquals("0.1.0-dev", development.details.appVersion)
        // The same machine, user and profile are the same installation every time.
        assertEquals(production, identity(DesktopProfile.PRODUCTION))
    }

    /** Without a machine id there is no seed, and so no suffix — never a fabricated one. */
    @Test
    fun aComputerWithoutAMachineIdHasNoSeedAndNoSuffix() {
        val bare = identity(files = linuxFiles - "/etc/machine-id")

        assertNull(bare.deviceSeed)
        assertEquals("studio", bare.deviceName)
    }

    @Test
    fun windowsUsesItsComputerNameAndMachineGuid() {
        val windows = identity(
            files = emptyMap(),
            properties = mapOf("os.name" to "Windows 11", "os.version" to "10.0", "user.name" to "owner"),
            environment = mapOf("COMPUTERNAME" to "DESKTOP-7Q2"),
            machineGuid = "6f1c2a3e-0b4d-4c5e-9f8a-1b2c3d4e5f60",
        )

        assertTrue(Regex("DESKTOP-7Q2 \\([0-9a-f]{4}\\)").matches(windows.deviceName))
        assertEquals("Windows 11 10.0", windows.platform)
    }

    /** A blank host name would be refused by the server; the fallback is the catalog's name. */
    @Test
    fun aComputerWithNoHostNameUsesTheDefaultName() {
        val unnamed = identity(files = linuxFiles - "/proc/sys/kernel/hostname")

        assertTrue(unnamed.deviceName.startsWith(Strings.desktop_device_name))
    }
}
