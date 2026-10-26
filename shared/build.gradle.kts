import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.ExperimentalJsTestDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.xml.parsers.DocumentBuilderFactory

abstract class GenerateAppStrings : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringsFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(stringsFile.get().asFile)
        val elements = document.getElementsByTagName("string")
        val escapes = Regex("""\\(u[0-9a-fA-F]{4}|[nrt\\])""")
        fun decode(value: String): String = escapes.replace(value) { match ->
            when (val escape = match.groupValues[1]) {
                "n" -> "\n"
                "r" -> "\r"
                "t" -> "\t"
                "\\" -> "\\"
                else -> escape.drop(1).toInt(16).toChar().toString()
            }
        }
        fun literal(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                append(when (character) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '$' -> "\\$"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> character.toString()
                })
            }
            append('"')
        }
        val strings = (0 until elements.length).associate { index ->
            val element = elements.item(index) as org.w3c.dom.Element
            element.getAttribute("name") to decode(element.textContent)
        }
        val placeholder = Regex("""%(\d+)\$[sd]""")
        val target = outputDirectory.file("com/vivenotes/localization/Strings.kt").get().asFile
        target.parentFile.mkdirs()
        target.writeText(buildString {
            appendLine("// Generated from composeResources/values/strings.xml. Edit the XML, then build.")
            appendLine("package com.vivenotes.localization")
            appendLine()
            appendLine("internal val defaultStrings: Map<String, String> = buildMap {")
            strings.forEach { (name, value) -> appendLine("    put(${literal(name)}, ${literal(value)})") }
            appendLine("}")
            appendLine()
            appendLine("/** Shared UI text, also available to synchronous platform callbacks and state models. */")
            appendLine("object Strings {")
            strings.forEach { (name, value) ->
                val arity = placeholder.findAll(value).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
                if (arity == 0) appendLine("    val $name: String get() = Localization.text(${literal(name)})")
                else {
                    val parameters = (1..arity).joinToString { "arg$it: Any?" }
                    val arguments = (1..arity).joinToString { "arg$it" }
                    appendLine("    fun $name($parameters): String = Localization.text(${literal(name)}, $arguments)")
                }
            }
            appendLine("}")
        })
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val generateAppStrings = tasks.register<GenerateAppStrings>("generateAppStrings") {
    stringsFile.set(layout.projectDirectory.file("src/commonMain/composeResources/values/strings.xml"))
    outputDirectory.set(layout.buildDirectory.dir("generated/appStrings"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release = libs.versions.jvmTarget.get().toInt()
}

// Skiko and the bundled SQLite driver load native libraries in tests; JDK 24+ warns about that,
// and a future release will block it.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The SymPy engine is a uv project (`src/jvmMain/python`). The app carries its sources and lock as
// resources under `math-engine/` and has uv install the environment they describe on first use;
// a `.venv` or `__pycache__` left there by running the project by hand never reaches the jar.
val mathEngineResources = tasks.register<Sync>("mathEngineResources") {
    from("src/jvmMain/python") {
        include("*.py", "pyproject.toml", "uv.lock", ".python-version")
    }
    into(layout.buildDirectory.dir("generated/mathEngineResources/math-engine"))
}

// Committed like the Android app's `app/schemas/`: every schema change needs a reviewed export and
// an explicit migration. `SchemaCompatibilityTest` holds this export to the Android baseline.
room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())
            freeCompilerArgs.add("-Xjdk-release=${libs.versions.jvmTarget.get()}")
        }
    }

    @OptIn(ExperimentalJsTestDsl::class)
    js {
        browser {
            test {
                chromium()
            }
        }
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                // Kotlin 2.4's managed browser runner supports JS only; Karma reuses its Chromium.
                dependsOn("kotlinInstallPlaywrightBrowsers")
                environment("VIVE_PLAYWRIGHT_MODULE",
                    rootProject.layout.buildDirectory.dir("js/node_modules/playwright").get().asFile.absolutePath)
                providers.gradleProperty("kotlin.gradle.playwright.browsers.path").orNull?.let {
                    environment("PLAYWRIGHT_BROWSERS_PATH", it)
                }
                useKarma {
                    useChromeHeadless()
                    useConfigDirectory(rootProject.file("gradle/karma"))
                }
            }
        }
        binaries.executable()
    }


    sourceSets {
        commonMain {
            kotlin.srcDir(generateAppStrings.map { it.outputDirectory })
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.ratex)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.cbor)
            // Entities and DAOs are annotated in common code; only the JVM target builds a database.
            implementation(libs.androidx.room.common)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.compose.uiTest)
        }
        jvmMain.dependencies {
            // Skiko supplies the Android renderer's vector canvas; FOP writes those paths to PDF.
            implementation("org.apache.xmlgraphics:fop-core:2.11")
            val byteinkVersion = providers.gradleProperty("byteinkVersion").orElse("0.1.0-SNAPSHOT").get()
            implementation("com.vivenotes.byteink:byteink-compose:$byteinkVersion")
            implementation("com.vivenotes.byteink:byteink-kit:$byteinkVersion")
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            // The sync account record, in the same Preferences file format Android keeps it in.
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.onnxruntime)
            runtimeOnly("io.github.darriousliu:ratex-native-linux-x86-64:0.1.15")
            runtimeOnly("io.github.darriousliu:ratex-native-linux-aarch64:0.1.15")
            runtimeOnly("io.github.darriousliu:ratex-native-windows-x86-64:0.1.15")
        }
        jvmMain {
            resources.srcDir(files(layout.buildDirectory.dir("generated/mathEngineResources"))
                .builtBy(mathEngineResources))
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.apache.pdfbox:pdfbox:3.0.8")
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
        }
        jsTest.dependencies {
            implementation(npm("mocha", libs.versions.mocha.get()))
        }
    }
}

apply(from = rootProject.file("gradle/browser-test-page.gradle.kts"))

dependencies {
    add("kspJvm", libs.androidx.room.compiler)
}

tasks.named<Test>("jvmTest") {
    // The math engine's tests have uv install CPython and SymPy once and reuse them on every later
    // run. Under the root build directory, which `clean` leaves alone: the interpreter alone is a
    // 33 MB download.
    systemProperty("vivenotes.test.mathRuntime",
        rootProject.layout.buildDirectory.dir("math-engine-test").get().asFile.absolutePath)
    // Or a packaged interpreter with the locked packages (the Flatpak runtime's Python), so the same
    // tests check the engine on the Python a package ships.
    providers.environmentVariable("VIVE_TEST_MATH_PYTHON").orNull?.let {
        systemProperty("vivenotes.test.mathPython", it)
    }
    // The development profile's installed formula models. Tests that need a real formula graph read
    // it from there and skip when it has not been downloaded.
    systemProperty("vivenotes.test.formulaModels", rootProject.file("devdb/ai_models").absolutePath)
}

// The synthetic 40k-page interaction workload is opt-in and runs separately from normal tests.
tasks.register<Test>("inkPhase2Scenario") {
    group = "verification"
    description = "Records finished-page wet/finish/erase rendering at Fit and sparse zoom."
    val regular = tasks.named<Test>("jvmTest").get()
    testClassesDirs = regular.testClassesDirs
    classpath = regular.classpath
    dependsOn("jvmTestClasses")
    maxHeapSize = "4g"
    filter.includeTestsMatching("com.vivenotes.ui.canvas.InkLayerPerformanceScenario")
    systemProperty("vivenotes.ink.phase2Scenario", "true")
    systemProperty("vivenotes.ink.phase2Report", providers.gradleProperty("byteinkInkScenarioOutput")
        .orElse(layout.buildDirectory.file("reports/ink-phase2-scenario.json").map { it.asFile.absolutePath }).get())
    systemProperty("vivenotes.ink.phase2Optimized", providers.gradleProperty("byteinkInkScenarioRequireReuse")
        .orElse("false").get())
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("performanceBaseline") {
    group = "verification"
    description = "Capture opt-in desktop algorithm and file-backed SQLite performance samples."
    val regular = tasks.named<Test>("jvmTest").get()
    classpath = regular.classpath
    dependsOn("jvmTestClasses")
    mainClass.set("com.vivenotes.performance.PerformanceBaseline")
    workingDir = rootProject.projectDir
    minHeapSize = "512m"
    maxHeapSize = "2g"
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
    args(providers.gradleProperty("performanceBaselineOutput")
        .orElse(layout.buildDirectory.file("reports/performance-baseline.json").map { it.asFile.absolutePath }).get(),
        providers.gradleProperty("performanceBaselineFork").orElse("1").get())
    systemProperty("vivenotes.performance.scratch", layout.buildDirectory.dir("performance-tmp").get().asFile.absolutePath)
}
