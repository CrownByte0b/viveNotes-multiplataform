package com.vivenotes.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceLintTest {
    private val project = File(System.getProperty("vivenotes.test.projectRoot"))
    private val linter = File(project, "tools/lint/ResourceLint.java")

    private fun lint(defaults: String, source: String = "", translation: String? = null): Pair<Int, String> {
        val fixture = Files.createTempDirectory("vive-resource-lint-").toFile()
        fun write(path: String, text: String) {
            File(fixture, path).apply { parentFile.mkdirs(); writeText(text) }
        }
        try {
            write("shared/src/commonMain/composeResources/values/strings.xml", defaults)
            write("shared/src/commonMain/kotlin/com/vivenotes/ui/Example.kt", source)
            translation?.let { write("shared/src/commonMain/composeResources/values-fr/strings.xml", it) }
            val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
                linter.absolutePath, fixture.absolutePath).redirectErrorStream(true).start()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Resource lint timed out")
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally {
            fixture.deleteRecursively()
        }
    }

    @Test
    fun acceptsPartialTranslationsReorderedPlaceholdersAndTechnicalIdentifiers() {
        val (status, output) = lint(
            """<resources><string name="greeting">Hello %1${'$'}s, %2${'$'}s</string><string name="close">Close</string></resources>""",
            """
                // Text("This comment is not UI")
                /* Nested /* "comment" */ ignored. */
                object ExampleTags { const val Field = "Size" }
                val id = "example-item"
                toolkit.createCustomCursor(image, point,
                    "Canvas auto-scroll North")
                Text(Strings.close)
                log.event("fixture") { "A diagnostic message" }
            """.trimIndent(),
            """<resources><string name="greeting">%2${'$'}s, bonjour %1${'$'}s</string></resources>""",
        )
        assertEquals(0, status, output)
        assertTrue(output.contains("Resource lint passed"), output)
    }

    @Test
    fun rejectsHardcodedTextAndAccessibilityDescriptionsIncludingTemplates() {
        val (status, output) = lint("<resources><string name=\"close\">Close</string></resources>",
            """
                Text("hello")
                Text("OK")
                val label = "Untranslated choice"
                Modifier.semantics { contentDescription = "Button description" }
                Text("Hello ${'$'}{name.ifBlank { "friend" }}")
            """.trimIndent())
        assertEquals(1, status)
        assertTrue(output.contains("Hardcoded UI text: hello"), output)
        assertTrue(output.contains("Hardcoded UI text: OK"), output)
        assertTrue(output.contains("Untranslated choice"), output)
        assertTrue(output.contains("Button description"), output)
        assertTrue(output.contains("Hardcoded UI text: Hello"), output)
    }

    @Test
    fun rejectsReferencesWithoutResourcesButIgnoresReferencesInComments() {
        val (status, output) = lint("<resources><string name=\"close\">Close</string></resources>",
            "Text(Strings.missing)\nText(\"\${Strings.missing_template}\")\n// Text(Strings.ignored)")
        assertEquals(1, status)
        assertTrue(output.contains("Missing resource missing"), output)
        assertTrue(output.contains("Missing resource missing_template"), output)
        assertTrue(!output.contains("Missing resource ignored"), output)
    }

    @Test
    fun rejectsLostPlaceholderPositionsAndUnknownTranslatedKeys() {
        val (status, output) = lint(
            """<resources><string name="value">%1${'$'}s and %2${'$'}s</string></resources>""",
            translation = """<resources><string name="value">%1${'$'}s</string><string name="unknown">Unknown</string></resources>""",
        )
        assertEquals(1, status)
        assertTrue(output.contains("Placeholder or resource-type mismatch for value"), output)
        assertTrue(output.contains("Unknown translation key unknown"), output)
    }

    @Test
    fun rejectsDuplicatesUnnumberedFormattingAndInvalidPlurals() {
        val (status, output) = lint("""
            <resources>
                <string name="close">Close</string>
                <string name="close">Another close</string>
                <string name="unnumbered">Hello %s</string>
                <plurals name="pages"><item quantity="one">One page</item></plurals>
            </resources>
        """.trimIndent())
        assertEquals(1, status)
        assertTrue(output.contains("Duplicate resource close"), output)
        assertTrue(output.contains("Use numbered %1${'$'}s or %1${'$'}d"), output)
        assertTrue(output.contains("needs an other quantity"), output)
    }
}
