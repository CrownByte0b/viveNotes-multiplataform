package com.vivenotes.desktop

import java.util.Properties

/**
 * What the build wrote into `build-info.properties`: the desktop's counterpart of the Android app's
 * `BuildConfig`. See `desktopApp/build.gradle.kts` for where each value comes from.
 */
internal object DesktopBuildInfo {
    private val properties: Properties by lazy {
        val resource = requireNotNull(javaClass.getResourceAsStream("/build-info.properties")) {
            "Missing desktop build information"
        }
        resource.use { stream -> Properties().apply { load(stream) } }
    }

    val version: String by lazy { required("version") }

    /** Android's `BuildConfig.CLOUD_BASE_URL`, per profile: the development one is Android's debug build. */
    fun cloudBaseUrl(profile: DesktopProfile): String = cloudBaseUrlFor(profile, properties.getProperty("cloudBaseUrl"))

    /**
     * The development profile talks to the local Docker server, as Android's debug build talks to
     * the emulator's view of it; everything else to the managed deployment. [override] is the
     * build's `vive.cloudBaseUrl`, which replaces both.
     */
    internal fun cloudBaseUrlFor(profile: DesktopProfile, override: String?): String =
        override?.trim()?.takeIf { it.isNotEmpty() } ?: when (profile) {
            DesktopProfile.DEVELOPMENT -> DEVELOPMENT_SERVER
            DesktopProfile.PRODUCTION -> MANAGED_SERVER
        }

    /** `deploy/docker-compose.yml` publishes the sync service on this port. */
    const val DEVELOPMENT_SERVER = "http://localhost:5444"
    const val MANAGED_SERVER = "https://sync.vivenotes.net"

    /**
     * Whether Subscribe opens Stripe Checkout when the managed server sells by card. Off, plans are
     * bought in the Android app and the desktop never asks the server for its card plan.
     */
    const val CARD_CHECKOUT = true

    /** Empty when the build has no Google Desktop OAuth client, which leaves Google sign-in off. */
    val googleDesktopClientId: String by lazy { properties.getProperty("googleDesktopClientId").orEmpty().trim() }

    private fun required(key: String): String =
        properties.getProperty(key)?.takeIf(String::isNotBlank) ?: error("Missing desktop build value $key")
}
