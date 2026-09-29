package com.vivenotes.desktop

import java.util.Properties

internal object DesktopBuildInfo {
    val version: String by lazy {
        val resource = requireNotNull(javaClass.getResourceAsStream("/build-info.properties")) {
            "Missing desktop build information"
        }
        resource.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")
                ?.takeIf(String::isNotBlank)
                ?: error("Missing desktop build version")
        }
    }
}
