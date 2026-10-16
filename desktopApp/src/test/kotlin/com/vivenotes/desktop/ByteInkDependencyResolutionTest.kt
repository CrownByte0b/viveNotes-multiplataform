package com.vivenotes.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.jar.JarOutputStream
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ByteInkDependencyResolutionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun defaultUsesLocalMavenEvenWhenSiblingCheckoutExists() {
        assertPublished(probe())
    }

    @Test
    fun disablingCompositeStillUsesLocalMaven() {
        assertPublished(probe("-PbyteinkComposite=false"))
    }

    @Test
    fun sourceBuildRequiresExplicitOptIn() {
        assertSource(probe("-PbyteinkComposite=true"))
    }

    @Test
    fun explicitSourcePathCanUseAnotherCheckout() {
        assertSource(probe("-PbyteinkCompositePath=../custom-byteink", customSource = true), "custom-byteink")
    }

    private fun assertPublished(report: List<String>) {
        assertEquals("included=", report.first())
        val artifacts = report.drop(1)
        assertEquals(2, artifacts.size)
        assertTrue(artifacts.all { it.startsWith("module=com.vivenotes.byteink:byteink-") }, report.toString())
        assertTrue(artifacts.all { it.substringAfter('|').startsWith(File(temp.root, "maven").canonicalPath) })
    }

    private fun assertSource(report: List<String>, buildName: String = "byteink") {
        assertEquals("included=$buildName", report.first())
        assertEquals(2, report.drop(1).size)
        assertTrue(report.drop(1).all { it.startsWith("project=") }, report.toString())
    }

    private fun probe(vararg properties: String, customSource: Boolean = false): List<String> {
        val app = temp.newFolder("app")
        File(System.getProperty("vivenotes.test.settingsFile")).copyTo(File(app, "settings.gradle.kts"))
        for (module in listOf("desktopApp", "shared", "webApp")) File(app, module).mkdirs()
        val source = temp.newFolder(if (customSource) "custom-byteink" else "byteink")
        File(source, "settings.gradle").writeText(
            "rootProject.name = 'byteink'\ninclude 'byteink-compose', 'byteink-kit'\n",
        )
        File(source, "build.gradle").writeText(
            """
            subprojects {
                apply plugin: 'java-library'
                group = 'com.vivenotes.byteink'
                version = '0.1.0-SNAPSHOT'
            }
            """.trimIndent(),
        )
        val maven = temp.newFolder("maven")
        for (module in listOf("byteink-compose", "byteink-kit")) {
            File(source, module).mkdirs()
            val publication = File(maven, "com/vivenotes/byteink/$module/0.1.0-SNAPSHOT").apply { mkdirs() }
            JarOutputStream(File(publication, "$module-0.1.0-SNAPSHOT.jar").outputStream()).close()
            File(publication, "$module-0.1.0-SNAPSHOT.pom").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.vivenotes.byteink</groupId>
                    <artifactId>$module</artifactId>
                    <version>0.1.0-SNAPSHOT</version>
                </project>
                """.trimIndent(),
            )
        }
        File(app, "build.gradle").writeText(
            """
            import org.gradle.api.artifacts.component.ModuleComponentIdentifier
            configurations { inkProbe }
            dependencies {
                inkProbe 'com.vivenotes.byteink:byteink-compose:0.1.0-SNAPSHOT'
                inkProbe 'com.vivenotes.byteink:byteink-kit:0.1.0-SNAPSHOT'
            }
            tasks.register('probeByteink') {
                dependsOn configurations.inkProbe
                doLast {
                    def lines = ['included=' + gradle.includedBuilds.collect { it.name }.sort().join(',')]
                    lines.addAll(configurations.inkProbe.incoming.artifacts.artifacts.collect {
                        def id = it.id.componentIdentifier
                        (id instanceof ModuleComponentIdentifier ? 'module=' : 'project=') +
                            id.displayName + '|' + it.file.canonicalPath
                    }.sort())
                    file('resolution.txt').text = lines.join('\n')
                }
            }
            """.trimIndent(),
        )

        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val gradle = File(System.getProperty("vivenotes.test.gradleHome"), "bin/gradle${if (windows) ".bat" else ""}")
        val command = (if (windows) listOf("cmd", "/c", gradle.path) else listOf(gradle.path)) + listOf(
            "probeByteink", "--offline", "--console=plain", "--max-workers=1", "--no-configuration-cache",
            "--gradle-user-home", System.getProperty("vivenotes.test.gradleUserHome"),
            "-Dmaven.repo.local=${maven.absolutePath}",
        ) + properties
        val log = File(temp.root, "gradle.log")
        val process = ProcessBuilder(command).directory(app).redirectErrorStream(true).redirectOutput(log).apply {
            environment()["JAVA_HOME"] = System.getProperty("java.home")
        }.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("ByteInk resolution probe timed out:\n${log.readText()}")
        }
        assertEquals(0, process.exitValue(), log.readText())
        return File(app, "resolution.txt").readLines()
    }
}
