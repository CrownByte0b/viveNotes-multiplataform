package com.vivenotes.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which sync server each profile talks to — Android's `CLOUD_BASE_URL` per build type: the
 * development profile, the desktop's debug build, the local Docker server; installed builds the
 * managed deployment; and `vive.cloudBaseUrl` both.
 */
class DesktopBuildInfoTest {
    @Test
    fun packagedBuildVersionIsAvailableToTheApp() {
        assertEquals("1.0.0", DesktopBuildInfo.version)
    }

    @Test
    fun theDevelopmentProfileTalksToTheLocalDockerServerAndProductionToTheManagedOne() {
        assertEquals("http://localhost:5444", DesktopBuildInfo.cloudBaseUrlFor(DesktopProfile.DEVELOPMENT, null))
        assertEquals("https://sync.vivenotes.net", DesktopBuildInfo.cloudBaseUrlFor(DesktopProfile.PRODUCTION, null))
        assertEquals("http://localhost:5444", DesktopBuildInfo.cloudBaseUrlFor(DesktopProfile.DEVELOPMENT, " "))
    }

    @Test
    fun aConfiguredServerReplacesBoth() {
        val lan = "http://192.168.1.20:5444"

        assertEquals(lan, DesktopBuildInfo.cloudBaseUrlFor(DesktopProfile.DEVELOPMENT, lan))
        assertEquals(lan, DesktopBuildInfo.cloudBaseUrlFor(DesktopProfile.PRODUCTION, " $lan "))
    }

    /** The resource the build writes carries every value this reads, expanded rather than left as templates. */
    @Test
    fun theBuildResourceIsExpanded() {
        val text = checkNotNull(javaClass.getResourceAsStream("/build-info.properties")).use { it.readBytes().decodeToString() }

        assertTrue("\${" !in text, text)
        listOf("version=", "cloudBaseUrl=", "googleDesktopClientId=").forEach { key ->
            assertTrue(text.lineSequence().any { it.startsWith(key) }, "missing $key")
        }
        // The managed server exchanges the sign-in code; no build carries a Google client secret.
        assertTrue("Secret" !in text, text)
        DesktopBuildInfo.cloudBaseUrl(DesktopProfile.DEVELOPMENT)
        DesktopBuildInfo.cloudBaseUrl(DesktopProfile.PRODUCTION)
    }
}
