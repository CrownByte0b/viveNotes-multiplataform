package com.vivenotes.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposeDependencyCompatibilityTest {
    @Test
    fun resolvedUiAndMaterialLibrariesMatchThePluginCatalog() {
        val catalog = File(System.getProperty("vivenotes.test.projectRoot"), "gradle/libs.versions.toml").readText()
        fun version(name: String) = Regex("(?m)^$name = \"([^\"]+)\"$")
            .find(catalog)!!.groupValues[1]

        for ((className, artifact, expected) in listOf(
            Triple("androidx.compose.ui.Modifier", "ui-desktop", version("composeMultiplatform")),
            Triple("androidx.compose.foundation.BackgroundKt", "foundation-desktop", version("composeMultiplatform")),
            Triple("androidx.compose.material3.MaterialTheme", "material3-desktop", version("material3")),
        )) {
            val jar = File(Class.forName(className).protectionDomain.codeSource.location.toURI())
            assertEquals("$artifact-$expected.jar", jar.name, "Resolved $className must match the catalog/plugin")
        }
    }
}
