package com.vivenotes.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebBuildConfigurationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun rootBuildAssemblesWebAndRunsPortableTestsWithManagedBrowsersAndEnoughCompilerMemory() {
        val project = File(System.getProperty("vivenotes.test.projectRoot"))
        val directory = temp.newFolder("app")
        // Gradle canonicalizes project directories. Exercise an alias locally too; Windows
        // CI supplies a similar alias through its RUNNER~1 temporary-directory path.
        val fixture = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) directory
            else Files.createSymbolicLink(File(temp.root, "app alias").toPath(), directory.toPath()).toFile()
        for (path in listOf(
            "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
            "shared/build.gradle.kts", "webApp/build.gradle.kts", "desktopApp/build.gradle.kts",
            "gradle/karma/managed-chromium.js", "gradle/browser-test-page.gradle.kts",
            "gradle/tests/ratex-webpack.cjs", "webApp/webpack.config.d/ratex.js",
        )) {
            File(project, path).copyTo(File(fixture, path).apply { parentFile.mkdirs() })
        }
        val init = File(temp.root, "probe.gradle").apply {
            writeText("""
                gradle.taskGraph.whenReady { graph ->
                    def app = gradle.rootProject
                    def report = graph.allTasks.collect { "task|" + it.path + "|" + it.enabled }
                    report.add('workers|' + gradle.startParameter.maxWorkerCount)
                    ['shared', 'webApp'].each { name ->
                        def module = app.project(':' + name)
                        ['compileKotlinWasmJs', 'compileProductionExecutableKotlinWasmJs'].each { nameOfTask ->
                            def compiler = module.tasks.getByName(nameOfTask)
                            report.add('heap|' + compiler.path + '|' + compiler.kotlinDaemonJvmArguments.get().join(' '))
                        }
                        ['js', 'wasmJs'].each { target ->
                            def test = module.tasks.getByName(target + 'BrowserTest')
                            report.add('runner|' + test.path + '|' + test.testFramework.class.simpleName)
                        }
                        def wasm = module.tasks.getByName('wasmJsBrowserTest')
                        report.add('browserInstall|' + name + '|' + wasm.dependsOn.contains('kotlinInstallPlaywrightBrowsers'))
                        report.add('browserModule|' + name + '|' + wasm.environment['VIVE_PLAYWRIGHT_MODULE'])
                    }
                    app.file('web-build-report.txt').text = report.join('\n')
                }
            """.trimIndent())
        }
        // Gradle resolves web/tool inputs while building this graph, even in a dry run.
        // A fresh desktop CI cache has none of them; use the same resolution as a real build.
        runGradle(fixture, "build", "--dry-run", "--init-script", init.path, offline = false)
        val report = File(fixture, "web-build-report.txt").readLines()
        for (module in listOf("shared", "webApp")) {
            for (target in listOf("js", "wasmJs")) {
                assertTrue("task|:$module:${target}BrowserDistribution|true" in report, report.toString())
                assertTrue("task|:$module:${target}BrowserTest|true" in report, report.toString())
            }
            assertTrue("browserInstall|$module|true" in report, report.toString())
            assertTrue("task|:$module:kotlinInstallPlaywrightBrowsers|true" in report, report.toString())
            val browserModule = report.single { it.startsWith("browserModule|$module|") }.substringAfterLast('|')
            assertEquals(File(fixture, "build/js/node_modules/playwright").canonicalFile,
                File(browserModule).canonicalFile, report.toString())
            assertTrue("runner|:$module:jsBrowserTest|KotlinPlaywrightJsTestFramework" in report, report.toString())
            assertTrue("runner|:$module:wasmJsBrowserTest|KotlinKarma" in report, report.toString())
        }
        assertTrue("task|:shared:compileTestKotlinJs|true" in report)
        assertTrue("task|:shared:compileTestKotlinWasmJs|true" in report)
        assertTrue("task|:webApp:webBundlingTest|true" in report)
        val heaps = report.filter { it.startsWith("heap|") }
        assertEquals(4, heaps.size)
        for (heap in heaps) {
            val match = Regex("-Xmx(\\d+)([mMgG])").find(heap) ?: error("Missing compiler heap: $heap")
            val megabytes = match.groupValues[1].toInt() * if (match.groupValues[2].equals("g", true)) 1024 else 1
            assertTrue(megabytes >= 6144, heap)
        }
        assertTrue(report.single { it.startsWith("workers|") }.substringAfter('|').toInt() <= 2)
    }

    @Test
    fun browserTestPageUsesLocalMochaAndRefreshesAssetsWithConfigurationCache() {
        val fixture = temp.newFolder("browser-page")
        val project = File(System.getProperty("vivenotes.test.projectRoot"))
        File(project, "gradle/browser-test-page.gradle.kts").copyTo(File(fixture, "browser-page.gradle.kts"))
        File(fixture, "build.gradle.kts").writeText("""
            tasks.register("prepareWebpackBundleForKotlinJsTests") {
                val upstream = layout.projectDirectory.file("upstream.html")
                val bundle = layout.buildDirectory.dir("kotlinJsTest/dist")
                inputs.file(upstream)
                outputs.dir(bundle)
                doLast {
                    val directory = bundle.get().asFile.apply { mkdirs() }
                    upstream.asFile.copyTo(directory.resolve("test.html"), overwrite = true)
                }
            }
            apply(from = "browser-page.gradle.kts")
        """.trimIndent())
        File(fixture, "upstream.html").writeText(
            """<link href="https://unpkg.com/mocha/mocha.css"><script src="https://unpkg.com/mocha/mocha.js"></script>""",
        )
        val mocha = File(fixture, "build/js/node_modules/mocha").apply { mkdirs() }
        File(mocha, "mocha.js").writeText("window.Mocha = {};")
        File(mocha, "mocha.css").writeText("#mocha { color: black; }")
        runGradle(fixture, "prepareWebpackBundleForKotlinJsTests", configurationCache = true)
        val bundle = File(fixture, "build/kotlinJsTest/dist")
        assertEquals("""<link href="mocha.css"><script src="mocha.js"></script>""", File(bundle, "test.html").readText())
        assertEquals(File(mocha, "mocha.js").readText(), File(bundle, "mocha.js").readText())
        assertEquals(File(mocha, "mocha.css").readText(), File(bundle, "mocha.css").readText())

        File(mocha, "mocha.js").writeText("window.Mocha = { updated: true };")
        runGradle(fixture, "prepareWebpackBundleForKotlinJsTests", configurationCache = true)
        assertEquals("window.Mocha = { updated: true };", File(bundle, "mocha.js").readText())
        assertEquals("""<link href="mocha.css"><script src="mocha.js"></script>""", File(bundle, "test.html").readText())
    }

    private fun runGradle(fixture: File, vararg arguments: String, configurationCache: Boolean = false, offline: Boolean = true) {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val gradle = File(System.getProperty("vivenotes.test.gradleHome"), "bin/gradle${if (windows) ".bat" else ""}")
        val command = (if (windows) listOf("cmd", "/c", gradle.path) else listOf(gradle.path)) + arguments +
            (if (offline) listOf("--offline") else emptyList()) + listOf(
            "--console=plain", if (configurationCache) "--configuration-cache" else "--no-configuration-cache",
            "--gradle-user-home", System.getProperty("vivenotes.test.gradleUserHome"),
        )
        val log = File(temp.root, "gradle.log")
        val process = ProcessBuilder(command).directory(fixture).redirectErrorStream(true).redirectOutput(log).apply {
            environment()["JAVA_HOME"] = System.getProperty("java.home")
        }.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("Web build configuration probe timed out:\n${log.readText()}")
        }
        assertEquals(0, process.exitValue(), log.readText())
    }
}
